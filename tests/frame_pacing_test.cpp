// Presentation pacing for every FPS limit (runtime/pacing.h), the arithmetic
// the Vulkan presenter, frame_rate::PaceSwap and the guest's VBlankWorker read.
//
// The run is deterministic: a fixed origin, a display period of 60 Hz (and
// 59.94 for the panels that report it) and the exact frame deadline function.
// For each of the seven settings -- 30, 35, 40, 45, 50, 55 and the console's 60
// -- it checks that:
//
//   * a frame's deadline is n/limit seconds from the origin, with no rounded
//     33.333 or 28.571 ms accumulating over an hour of frames;
//   * the boundary chosen for it is never earlier than its deadline and never
//     more than one refresh later, so nothing is presented early and the error
//     cannot grow;
//   * the average presented interval is the selected rate for the limits that
//     divide the display (30, 40, 50) and alternates two adjacent gaps for the
//     ones that do not (35, 45, 55), which is the closest a 60 Hz panel can be;
//   * the bounded present queue and the mode/strategy choices behave the same
//     at every rate, and 60 / no limit keep the console's own presentation.
//
// Header-only: no Vulkan, Android or environment.
#include "pacing.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <vector>

namespace
{
    int g_checks = 0;
    int g_failures = 0;

    void Check(bool ok, const char* what)
    {
        g_checks++;
        if (ok) return;
        g_failures++;
        std::fprintf(stdout, "FAIL: %s\n", what);
    }

    constexpr double k60 = 60.0;
    constexpr double k5994 = 59.94;
    constexpr int64_t kMs = 1'000'000;
    constexpr int64_t kOrigin = 1'000'000'000;      // 1 s of uptime
    constexpr int kFrames = 3600;                   // a minute at 60 fps

    // Every limit the app offers: 30 to 60 in steps of 5.
    const uint32_t kLimits[] = { 30, 35, 40, 45, 50, 55, 60 };
    const uint32_t kPaced[] = { 30, 35, 40, 45, 50, 55 };

    // The presenter's queue policy played out: `serials` arrive one at a time,
    // each present pops the oldest that survives the drop rule. Returns the
    // serials the window actually showed, in order.
    std::vector<uint64_t> Present(const std::vector<uint64_t>& serials,
                                  pacing::Mode mode, bool limited)
    {
        std::deque<uint64_t> queue;
        std::vector<uint64_t> shown;
        for (uint64_t serial : serials)
        {
            // ShowImage's backpressure applies only to the ordered queue: the
            // producer waits rather than growing past the ceiling, which is
            // what draining first models here.
            if (pacing::PresentInOrder(mode, limited))
                while (!pacing::HasRoom(queue.size(), mode, limited)) queue.pop_front();
            queue.push_back(serial);
            // DrawOneFrame: drop what the policy says is excess, then present
            // the oldest survivor. The original rule is newest-wins.
            if (pacing::PresentInOrder(mode, limited))
            {
                const size_t drop = pacing::ExcessToDrop(queue.size(), mode, limited);
                for (size_t i = 0; i < drop; ++i) queue.pop_front();
            }
            else
            {
                while (queue.size() > 1) queue.pop_front();
            }
            if (!queue.empty())
            {
                shown.push_back(queue.front());
                queue.pop_front();
            }
        }
        return shown;
    }

    bool Near(double a, double b, double tolerance) { return std::fabs(a - b) <= tolerance; }

    // The boundary the pacer picks for frame n: the frame's deadline placed on
    // the next display refresh, exactly as frame_rate::PaceSwap does it.
    int64_t Boundary(uint32_t fps, uint64_t frame, double hz, int64_t phase)
    {
        const double period = pacing::PeriodNs(hz);
        const int64_t target = pacing::FrameDeadline(kOrigin, fps, frame);
        return pacing::NextBoundary(target, period, phase, 1);
    }
}

int main()
{
    // ---- the frame timeline: exact for every limit --------------------------
    for (uint32_t fps : kLimits)
    {
        char label[160];
        for (uint64_t n : { uint64_t(0), uint64_t(1), uint64_t(2), uint64_t(10),
                            uint64_t(100), uint64_t(1000), uint64_t(kFrames) })
        {
            const int64_t at = pacing::FrameDeadline(kOrigin, fps, n) - kOrigin;
            const double wanted = double(n) * 1e9 / double(fps);
            std::snprintf(label, sizeof label,
                          "%u FPS frame %llu is n/limit seconds, not a rounded period",
                          fps, (unsigned long long)n);
            Check(std::llabs(double(at) - wanted) <= 1.0, label);
        }
        // An hour at 60 fps: a rounded 16.666/28.571 ms period would be seconds
        // off by here; the rational deadline is still exact to the nanosecond.
        const uint64_t hour = uint64_t(fps) * 3600;
        const int64_t at = pacing::FrameDeadline(kOrigin, fps, hour) - kOrigin;
        std::snprintf(label, sizeof label, "%u FPS does not drift over an hour", fps);
        Check(std::llabs(double(at) - 3600.0 * 1e9) <= 1.0, label);
        std::snprintf(label, sizeof label, "%u FPS has a frame period", fps);
        Check(pacing::FramePeriodNs(fps) > 0, label);
    }

    // ---- deadlines placed on the display's boundaries -----------------------
    for (uint32_t fps : kPaced)
    {
        char label[200];
        for (double hz : { k60, k5994, 120.0, 90.0 })
        {
            const double period = pacing::PeriodNs(hz);
            const int64_t phase = kOrigin + 3'000'000;      // an arbitrary measured phase
            int64_t previous = 0;
            bool neverEarly = true, alwaysWithinOneRefresh = true, strictlyLater = true;
            double first = 0, last = 0;
            for (uint64_t n = 0; n < kFrames; ++n)
            {
                const int64_t target = pacing::FrameDeadline(kOrigin, fps, n);
                const int64_t at = pacing::NextBoundary(target, period, phase, 1);
                if (at < target) neverEarly = false;
                if (at - target >= int64_t(std::llround(period)) + 2) alwaysWithinOneRefresh = false;
                if (previous && at <= previous) strictlyLater = false;
                if (n == 0) first = double(at);
                last = double(at);
                previous = at;
            }
            const double mean = (last - first) / double(kFrames - 1);
            const double wanted = 1e9 / double(fps);
            std::snprintf(label, sizeof label,
                          "%u FPS on %.2f Hz is never presented early", fps, hz);
            Check(neverEarly, label);
            std::snprintf(label, sizeof label,
                          "%u FPS on %.2f Hz waits at most one refresh", fps, hz);
            Check(alwaysWithinOneRefresh, label);
            std::snprintf(label, sizeof label,
                          "%u FPS on %.2f Hz asks for a later boundary every frame", fps, hz);
            Check(strictlyLater, label);
            std::snprintf(label, sizeof label,
                          "%u FPS on %.2f Hz keeps the average frame interval", fps, hz);
            // A 60 Hz panel cannot show 55 frames a second exactly; its mean is
            // still within a tenth of a percent over a minute, and it does not
            // wander further the longer it runs.
            Check(Near(mean, wanted, wanted * 0.001), label);
        }
    }

    // ---- the even-divisor limits land on one grid ---------------------------
    {
        struct Even { uint32_t fps; double hz; };
        for (Even pair : { Even{30, 60.0}, Even{30, 59.94}, Even{30, 120.0},
                           Even{40, 120.0}, Even{45, 90.0}, Even{50, 100.0},
                           Even{50, 60.0} /* 1.2: not even, see below */ })
        {
            const uint32_t divisor = pacing::GridDivisor(
                uint64_t(std::llround(pacing::PeriodNs(pair.hz))), pair.fps);
            const bool even = pacing::EvenDivisor(pair.hz, pair.fps);
            char label[200];
            if (!even)
            {
                std::snprintf(label, sizeof label,
                              "%u on %.2f Hz has no whole-refresh grid", pair.fps, pair.hz);
                Check(divisor == 1, label);
                continue;
            }
            int64_t previous = 0;
            bool constantGap = true;
            const int64_t gridStep =
                int64_t(std::llround(pacing::PeriodNs(pair.hz) * double(divisor)));
            for (uint64_t n = 0; n < 240; ++n)
            {
                const int64_t at = Boundary(pair.fps, n, pair.hz, kOrigin);
                if (previous && std::llabs((at - previous) - gridStep) > 2) constantGap = false;
                previous = at;
            }
            std::snprintf(label, sizeof label,
                          "%u FPS on %.2f Hz keeps one even frame interval", pair.fps, pair.hz);
            Check(constantGap, label);
        }
    }

    // ---- 45 and 55 on a 60 Hz panel alternate adjacent gaps -----------------
    {
        for (uint32_t fps : { 35, 45, 55 })
        {
            const double period = pacing::PeriodNs(k60);
            const int64_t step = int64_t(std::llround(period));
            int64_t previous = 0;
            int one = 0, two = 0, other = 0;
            for (uint64_t n = 0; n < kFrames; ++n)
            {
                const int64_t at = Boundary(fps, n, k60, kOrigin);
                if (previous)
                {
                    const int64_t gap = at - previous;
                    if (std::llabs(gap - step) <= 2) one++;
                    else if (std::llabs(gap - step * 2) <= 2) two++;
                    else other++;
                }
                previous = at;
            }
            char label[160];
            std::snprintf(label, sizeof label,
                          "%u FPS on 60 Hz only ever skips zero or one refresh", fps);
            Check(other == 0, label);
            std::snprintf(label, sizeof label,
                          "%u FPS on 60 Hz uses both gaps", fps);
            Check(one > 0 && two > 0, label);
        }
    }

    // ---- FramesElapsed: a stalled pacer resumes, it does not catch up -------
    for (uint32_t fps : kLimits)
    {
        const int64_t period = pacing::FramePeriodNs(fps);
        for (uint64_t n : { uint64_t(0), uint64_t(1), uint64_t(5), uint64_t(60) })
        {
            const int64_t now = kOrigin + int64_t(n) * period;
            char label[160];
            std::snprintf(label, sizeof label, "%u FPS has elapsed %llu frames", fps,
                          (unsigned long long)n);
            Check(std::llabs(double(pacing::FramesElapsed(kOrigin, fps, now)) - double(n)) <= 1.0,
                  label);
        }
    }

    // ---- the queue: ordered for every limited rate --------------------------
    {
        const std::vector<uint64_t> serials = { 1, 2, 3, 4, 5, 6 };
        const std::vector<uint64_t> ordered = Present(serials, pacing::Mode::Auto, true);
        Check(ordered.size() == serials.size(), "an ordered queue shows every frame");
        Check(ordered == serials, "in the order the renderer finished them");
        // A backlog the presenter finds when it wakes: compatibility keeps the
        // newest, the ordered mode keeps all three up to its ceiling.
        std::deque<uint64_t> backlog(serials.begin(), serials.end());
        while (backlog.size() > 1) backlog.pop_front();
        Check(backlog.size() == 1 && backlog.front() == serials.back(),
              "compatibility still drops the older frames and shows the newest");
        Check(pacing::QueueCapacity(pacing::Mode::Smooth, true) == 3, "the ordered queue holds three");
        Check(pacing::QueueCapacity(pacing::Mode::Smooth, false) == 1, "the console's queue is one");
        Check(pacing::ExcessToDrop(3, pacing::Mode::Auto, true) == 0,
              "the ceiling is not the normal path");
        Check(pacing::ExcessToDrop(4, pacing::Mode::Auto, true) == 1,
              "only a presenter that stopped lets the queue pass its ceiling");
        Check(pacing::ExcessToDrop(2, pacing::Mode::Compatibility, true) == 1,
              "compatibility's queue keeps one survivor");
    }

    // ---- modes and strategies, at every rate --------------------------------
    for (uint32_t fps : kLimits)
    {
        char label[200];
        const bool paced = pacing::Schedulable(fps);
        std::snprintf(label, sizeof label, "%u FPS is schedulable: %d", fps, paced ? 1 : 0);
        Check(paced == (fps < 60), label);
        std::snprintf(label, sizeof label, "%u FPS keeps the console's presentation", fps);
        Check(pacing::PresentInOrder(pacing::Mode::Auto, paced) == paced, label);
        Check(pacing::PresentInOrder(pacing::Mode::Smooth, paced) == paced, label);
        Check(!pacing::PresentInOrder(pacing::Mode::Compatibility, paced), "compatibility never orders");
        if (!paced)
        {
            Check(pacing::Choose(pacing::Mode::Auto, fps, { true, true }) == pacing::Strategy::Original,
                  "60 and no limit keep the display's own blank");
            continue;
        }
        Check(pacing::Choose(pacing::Mode::Auto, fps, { true, true }) == pacing::Strategy::DisplayTiming,
              "Auto uses display timing for a paced limit");
        Check(pacing::Choose(pacing::Mode::Smooth, fps, { false, true }) == pacing::Strategy::PhaseLockedFifo,
              "Smooth falls back to phase-locked FIFO");
        Check(pacing::Choose(pacing::Mode::Smooth, fps, { false, false }) == pacing::Strategy::Original,
              "with no display clock at all the timeline stands alone");
        Check(pacing::Choose(pacing::Mode::Compatibility, fps, { true, true }) == pacing::Strategy::Original,
              "Compatibility restores the original presentation");
    }

    // ---- grid divisor and phase error ---------------------------------------
    {
        Check(pacing::DivisorForPeriod(uint64_t(std::llround(pacing::PeriodNs(60.0))), 30) == 2,
              "30 FPS on 60 Hz is every second refresh");
        Check(pacing::DivisorForPeriod(uint64_t(std::llround(pacing::PeriodNs(59.94))), 30) == 2,
              "and on 59.94 Hz");
        Check(pacing::DivisorForPeriod(uint64_t(std::llround(pacing::PeriodNs(30.0))), 30) == 1,
              "30 FPS on a 30 Hz mode is every refresh");
        Check(pacing::DivisorForPeriod(uint64_t(std::llround(pacing::PeriodNs(120.0))), 30) == 4,
              "30 FPS on 120 Hz is every fourth refresh");
        Check(pacing::EvenDivisor(59.94, 30), "59.94 is an even 30 FPS divisor");
        Check(pacing::EvenDivisor(60.0, 30), "60 is too");
        Check(pacing::EvenDivisor(120.0, 30), "120 is too");
        Check(!pacing::EvenDivisor(60.0, 45), "45 on 60 is not an even divisor");
        Check(!pacing::EvenDivisor(90.0, 40), "40 on 90 is not an even divisor");
        Check(pacing::GridDivisor(uint64_t(std::llround(pacing::PeriodNs(60.0))), 45) == 1,
              "45 on 60 measures phase against the refresh itself");
        Check(pacing::GridDivisor(uint64_t(std::llround(pacing::PeriodNs(120.0))), 40) == 3,
              "40 on 120 is every third refresh");

        const double period = pacing::PeriodNs(59.94);
        const int64_t phase = 5 * kMs;
        Check(pacing::PhaseError(phase, period, phase, 1) == 0, "a present on the grid has no error");
        Check(pacing::PhaseError(phase + int64_t(period / 3), period, phase, 1) > 0,
              "a late present is a positive error");
        Check(pacing::PhaseError(phase - int64_t(period / 3), period, phase, 1) < 0,
              "an early one is negative");
        Check(std::llabs(pacing::PhaseError(phase + int64_t(period), period, phase, 1)) == 0,
              "one whole period away folds back to zero");
    }

    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

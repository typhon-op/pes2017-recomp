// 30 FPS presentation pacing (runtime/pacing.h). The arithmetic the Vulkan
// presenter, frame_rate::PaceSwap and the guest's VBlankWorker all read:
//
//   * the strategy each mode and device allows, and that Compatibility is the
//     original behaviour;
//   * where the next even refresh boundary is at 60 Hz and at 59.94 Hz;
//   * that a present on the grid has no phase error and a late one is folded
//     into [-step/2, +step/2];
//   * the bounded present queue's order, ceiling and backpressure, including
//     the original newest-wins drop that Compatibility keeps;
//   * the refresh divisor for 30 on 30/59.94/60/120 Hz.
//
// Header-only: no Vulkan, Android or environment.
#include "pacing.h"

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

    // The presenter's queue policy played out: `serials` arrive one at a time,
    // each present pops the oldest that survives the drop rule. Returns the
    // serials the window actually showed, in order.
    std::vector<uint64_t> Present(const std::vector<uint64_t>& serials,
                                  pacing::Mode mode, bool thirty)
    {
        std::deque<uint64_t> queue;
        std::vector<uint64_t> shown;
        for (uint64_t serial : serials)
        {
            // ShowImage's backpressure: the producer waits rather than growing
            // past the ceiling. In this deterministic simulation the queue is
            // drained below the ceiling first, which is what the wait does.
            while (!pacing::HasRoom(queue.size(), mode, thirty)) queue.pop_front();
            queue.push_back(serial);
            // DrawOneFrame: drop what the policy says is excess, then present
            // the oldest survivor.
            const size_t drop = pacing::ExcessToDrop(queue.size(), mode, thirty);
            for (size_t i = 0; i < drop; ++i) queue.pop_front();
            if (!queue.empty())
            {
                shown.push_back(queue.front());
                queue.pop_front();
            }
        }
        while (!queue.empty())
        {
            shown.push_back(queue.front());
            queue.pop_front();
        }
        return shown;
    }
}

int main()
{
    // ---- mode parsing ------------------------------------------------------
    {
        Check(pacing::ParseMode(nullptr) == pacing::Mode::Auto, "unset pacing is Auto");
        Check(pacing::ParseMode("") == pacing::Mode::Auto, "empty pacing is Auto");
        Check(pacing::ParseMode("auto") == pacing::Mode::Auto, "auto reads as Auto");
        Check(pacing::ParseMode("smooth") == pacing::Mode::Smooth, "smooth reads as Smooth");
        Check(pacing::ParseMode("Smooth 30 FPS") == pacing::Mode::Smooth, "a label still reads as Smooth");
        Check(pacing::ParseMode("compatibility") == pacing::Mode::Compatibility,
              "compatibility reads as Compatibility");
        Check(pacing::ParseMode("compat") == pacing::Mode::Compatibility, "the short name too");
        Check(pacing::ParseMode("nonsense") == pacing::Mode::Auto, "an unknown value is the default");
        Check(pacing::ParseRefresh(nullptr) == pacing::Refresh::System, "unset refresh is System");
        Check(pacing::ParseRefresh("prefer60") == pacing::Refresh::Prefer60, "prefer60 reads as Prefer60");
    }

    // ---- strategy and fallback ---------------------------------------------
    {
        using pacing::Mode;
        using pacing::Strategy;
        const pacing::Capabilities none{ false, false };
        const pacing::Capabilities waitOnly{ false, true };
        const pacing::Capabilities timing{ true, true };

        Check(pacing::Choose(Mode::Auto, true, timing) == Strategy::DisplayTiming,
              "Auto uses display timing when the driver offers it");
        Check(pacing::Choose(Mode::Smooth, true, timing) == Strategy::DisplayTiming,
              "Smooth uses display timing too");
        Check(pacing::Choose(Mode::Smooth, true, waitOnly) == Strategy::PhaseLockedFifo,
              "Smooth falls back to the phase-locked FIFO pacer");
        Check(pacing::Choose(Mode::Auto, true, none) == Strategy::Original,
              "with no display clock the original pacer is all there is");
        Check(pacing::Choose(Mode::Compatibility, true, timing) == Strategy::Original,
              "Compatibility restores the original presentation even with timing available");
        Check(pacing::Choose(Mode::Smooth, false, timing) == Strategy::Original,
              "a non-30 limit keeps the original pacing");
    }

    // ---- frame ordering and the bounded queue ------------------------------
    {
        using pacing::Mode;
        const std::vector<uint64_t> serials{ 1, 2, 3, 4, 5 };
        {
            const auto shown = Present(serials, Mode::Smooth, true);
            Check(shown == serials, "Smooth 30 shows every valid frame once, in order");
        }
        {
            const auto shown = Present(serials, Mode::Auto, true);
            Check(shown == serials, "Auto 30 shows every valid frame once, in order");
        }
        {
            // The original loop drops all but the newest when a present is
            // already waiting. Two finished frames with one on screen: the
            // older is let go unshown, so the newest is the survivor.
            std::deque<uint64_t> backlog{ 1, 2 };
            for (size_t i = 0; i < pacing::ExcessToDrop(backlog.size(), Mode::Compatibility, true); ++i)
                backlog.pop_front();
            Check(backlog.front() == 2, "Compatibility keeps the original newest-wins drops");
            // The ordered mode keeps the older one: it is the next frame due.
            std::deque<uint64_t> ordered{ 1, 2 };
            for (size_t i = 0; i < pacing::ExcessToDrop(ordered.size(), Mode::Smooth, true); ++i)
                ordered.pop_front();
            Check(ordered.front() == 1, "Smooth shows the older frame first, in order");
        }
        {
            Check(pacing::QueueCapacity(Mode::Smooth, true) == 3, "the ordered queue holds three frames");
            Check(pacing::QueueCapacity(Mode::Compatibility, true) == 1,
                  "Compatibility's queue is the one frame it will show");
            Check(pacing::HasRoom(2, Mode::Smooth, true), "two frames leave room for a third");
            Check(!pacing::HasRoom(3, Mode::Smooth, true), "a full ordered queue applies backpressure");
            Check(pacing::ExcessToDrop(3, Mode::Smooth, true) == 0, "a full ordered queue drops nothing");
            Check(pacing::ExcessToDrop(4, Mode::Smooth, true) == 1,
                  "past the ceiling one old frame is let go");
            Check(pacing::ExcessToDrop(2, Mode::Compatibility, true) == 1,
                  "Compatibility drops the older of two waiting frames");
        }
    }

    // ---- 60 Hz deadlines ---------------------------------------------------
    {
        const double period = pacing::PeriodNs(k60);   // 16.666.. ms
        const int64_t phase = 1'000'000;
        Check(std::llabs(period - 16'666'666.67) < 1.0, "60 Hz is a 16.667 ms refresh");

        const int64_t step2 = int64_t(std::llround(period * 2.0));
        Check(pacing::NextBoundary(phase + kMs, period, phase, 2) == phase + step2,
              "half a refresh after the grid, 30 FPS targets the second boundary");
        Check(pacing::NextBoundary(phase, period, phase, 2) == phase,
              "exactly on a boundary, that boundary is the target");
        Check(pacing::NextBoundary(phase + step2 + kMs, period, phase, 2) ==
                  phase + int64_t(std::llround(period * 4.0)),
              "past one boundary, the next even one is two steps on");
        Check(pacing::NextBoundary(phase + step2 - 1, period, phase, 2) == phase + step2,
              "one nanosecond before a boundary still targets it");

        const int64_t late = phase + step2 + 3 * kMs;
        Check(pacing::PhaseError(late, period, phase, 2) == 3 * kMs,
              "a present 3 ms late has +3 ms of phase error");
        const int64_t early = phase + step2 - 3 * kMs;
        Check(pacing::PhaseError(early, period, phase, 2) == -3 * kMs,
              "a present 3 ms early has -3 ms of phase error");
        Check(pacing::PhaseError(phase, period, phase, 2) == 0, "a present on the grid has no error");
    }

    // ---- 59.94 Hz deadlines and drift -------------------------------------
    {
        const double period = pacing::PeriodNs(k5994);
        Check(std::llabs(period - 16'683'350.02) < 1.0, "59.94 Hz is a 16.683 ms refresh");
        const int64_t phase = 5'000'000;
        const int64_t step2 = int64_t(std::llround(period * 2.0));
        Check(std::llabs(step2 - 33'366'700) <= 1, "two 59.94 Hz refreshes are 33.367 ms");

        // Thirty frames at 59.94 are 30 * 2 refreshes = 1.00098 s, not the
        // 1.00000 s an independent 33.333 ms pacer would keep.
        const int64_t gridAt30 = phase + 30 * step2;
        const int64_t naive = phase + 30 * 33'333'333;
        Check(gridAt30 - naive > 900'000 && gridAt30 - naive < 1'100'000,
              "an independent 33.333 ms pacer is about a millisecond off after one second");

        int64_t at = phase;
        int64_t previous = phase;
        bool allOnGrid = true;
        bool monotonic = true;
        for (int i = 0; i < 120; ++i)
        {
            at = pacing::NextBoundary(previous + 1, period, phase, 2);
            allOnGrid = allOnGrid && std::llabs(pacing::PhaseError(at, period, phase, 2)) <= 1;
            monotonic = monotonic && at > previous;
            previous = at;
        }
        Check(allOnGrid, "120 frames scheduled at 59.94 stay on the grid");
        Check(monotonic, "and each deadline is later than the one before");
        Check(std::llabs((previous - phase) - int64_t(std::llround(120 * period * 2.0))) <= 1,
              "the grid accumulates the panel's period, not a rounded 33.333 ms");
    }

    // ---- divisor -----------------------------------------------------------
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
    }

    // ---- mode switching ----------------------------------------------------
    {
        using pacing::Mode;
        Check(pacing::PresentInOrder(Mode::Auto, true), "Auto presents 30 FPS frames in order");
        Check(pacing::PresentInOrder(Mode::Smooth, true), "Smooth presents them in order");
        Check(!pacing::PresentInOrder(Mode::Compatibility, true),
              "Compatibility restores the original drop rule");
        Check(!pacing::PresentInOrder(Mode::Auto, false), "a non-30 limit keeps the original rule");
        Check(pacing::Choose(Mode::Compatibility, true, { true, true }) == pacing::Strategy::Original,
              "switching to Compatibility restores the original strategy");
        Check(pacing::Choose(Mode::Smooth, true, { false, true }) == pacing::Strategy::PhaseLockedFifo,
              "switching back to Smooth restores phase-locked pacing");
    }

    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

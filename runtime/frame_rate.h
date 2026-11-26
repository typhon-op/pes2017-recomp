#pragma once
// PES17_FPS_LIMIT: how many frames a second the title draws at most.
//
// Unset or 60 is the console's pacing: D3D retires a frame at the display's
// blank and the title's own limiter (com_maxfps) is at its 60. Any other
// number takes the title off the blank the way the console's D3D offers it
// (d3d_hooks.cpp), with that number as com_maxfps; 0 is no limit. The title's
// limiter counts whole milliseconds a frame, so the rate it keeps is 1000
// over a whole number: 120 gives 125.
//
// PES17_PACING (Settings > Graphics & performance, both menus): "auto" (the
// default), "smooth" or "compatibility". Every limited run -- 30, 35, 40, 45,
// 50, 55 -- is paced by the same arithmetic in runtime/pacing.h: an exact frame
// timeline (no 33.333 or 28.571 rounding that drifts) placed on the display's
// measured boundary. A limit that divides the panel puts every frame on the
// same phase; a limit that does not (45 on 60 Hz) alternates the one- and
// two-refresh gaps whose average is that rate, which is the best a 60 Hz panel
// can show. Compatibility is exactly what this file did before tfix5: an
// independent clock, for comparing against.
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <thread>
#include "display_clock.h"
#include "env.h"
#include "pacing.h"

namespace frame_rate
{
    constexpr uint32_t kConsole = 60;

    // com_maxfps: 0 is no limit, and 1000 is the most the title takes.
    inline uint32_t Limit()
    {
        static const uint32_t limit = uint32_t(std::min<uint64_t>(env::Number("PES17_FPS_LIMIT", kConsole), 1000));
        return limit;
    }

    // Whether this run is paced by software at all: a limit below the console's
    // 60, and not "no limit". 60 and 0 stay on the display's own blank.
    inline bool Limited()
    {
        const uint32_t limit = Limit();
        return limit != 0 && limit < kConsole;
    }

    // The saved pacing mode. A run reads it once, like every other PES17_*
    // switch: changing it needs the next match, which the Settings pages say.
    inline pacing::Mode PacingMode()
    {
        static const pacing::Mode mode = pacing::ParseMode(env::Text("PES17_PACING"));
        return mode;
    }

    // Whether every finished frame may be shown in order. Presenter
    // (vulkan/presenter.cpp) and this pacer must agree, which is why the rule
    // lives in one place. It is on for every limited rate, not 30 alone.
    inline bool PresentInOrder()
    {
        return pacing::PresentInOrder(PacingMode(), Limited());
    }

    inline int64_t NowNs()
    {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
                   std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    // How early the guest submits for a blank: enough for the presenter's copy
    // to be queued, not so early that the previous frame's work is not done.
    constexpr int64_t kLeadNs = 1'500'000;

    // Under the console's 60 there is no present hook to take the title off the
    // blank (T_D3D_Present is not located for this title), so the limit is kept
    // where the title submits a frame: VdSwap waits until the frame's display
    // boundary has come. Called from the thread that swaps.
    //
    // Auto and Smooth use one origin and an exact frame index, so a run of
    // 59.94 Hz panel boundaries cannot gradually pull the rate away from 45 or
    // 55: the target is always `origin + n / limit` seconds, and the sleep is
    // to the next display boundary at or after it. A frame that ran long does
    // not owe the frames it missed; the index skips to the present so nothing
    // fires back to back afterwards.
    inline void PaceSwap()
    {
        if (!Limited()) return;

        // Compatibility: the original independent clock, unchanged so the mode
        // restores it exactly.
        if (PacingMode() == pacing::Mode::Compatibility)
        {
            using clock = std::chrono::steady_clock;
            static clock::time_point next{};
            const auto now = clock::now();
            const auto period = std::chrono::microseconds(1000000 / Limit());
            if (now < next) std::this_thread::sleep_until(next);
            // A frame that ran long does not owe the ones it missed.
            next = (now > next + period) ? now + period : next + period;
            return;
        }

        const int64_t now = NowNs();
        static int64_t origin = 0;
        static uint64_t frame = 0;
        if (origin == 0) origin = now;
        ++frame;

        int64_t target = pacing::FrameDeadline(origin, Limit(), frame);
        // Skip straight to the frame that is due now after a stall, so a slow
        // second does not end in a burst of frames the display cannot take.
        if (target + pacing::FramePeriodNs(Limit()) < now)
        {
            frame = pacing::FramesElapsed(origin, Limit(), now);
            target = pacing::FrameDeadline(origin, Limit(), frame);
            if (target < now) target = pacing::FrameDeadline(origin, Limit(), ++frame);
        }

        // Put the deadline on the display's own boundary. The grid step is the
        // panel's period: a frame deadline inside one refresh lands on that
        // refresh, so 30 FPS keeps every second boundary and 35/45/55 alternate
        // the gaps whose average is the selected rate.
        uint64_t period = 0;
        int64_t phase = 0;
        if (display_clock::Read(period, phase, now))
            target = pacing::NextBoundary(target, double(period), phase, 1);

        // Inside the lead already: submit now rather than skipping a whole
        // frame to the boundary after the next one.
        if (target - kLeadNs > now)
            std::this_thread::sleep_until(std::chrono::steady_clock::time_point(
                std::chrono::nanoseconds(target - kLeadNs)));
    }

    // Frames are retired at the blank, one a blank, as on the console.
    inline bool Console() { return Limit() == kConsole; }
}

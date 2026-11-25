#pragma once
// PES17_FPS_LIMIT: how many frames a second the title draws at most.
//
// Unset or 60 is the console's pacing: D3D retires a frame at the display's
// blank and the title's own limiter (com_maxfps) is at its 60. Any other
// number takes the title off the blank the way the console's D3D offers it
// (d3d_hooks.cpp), with that number as com_maxfps; 0 is no limit. The title's
// limiter counts whole milliseconds a frame, so the rate it keeps is 1000
// over a whole number: 120 gives 125, 144 gives 166.
//
// PES17_PACING (Settings > Graphics & performance, both menus): "auto" (the
// default), "smooth" or "compatibility". At the 30 FPS limit Auto and Smooth
// take every finished frame to the screen in order and follow the display's
// measured blank -- every second refresh on a 60 Hz screen, whether that is
// 60.000 or 59.94 -- rather than a free-running 33.333 ms timer.
// Compatibility is exactly what this file did before tfix5: newest-wins
// presents and the independent clock. Every other limit is untouched.
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

    // The saved pacing mode. A run reads it once, like every other PES17_*
    // switch: changing it needs the next match, which the Settings pages say.
    inline pacing::Mode PacingMode()
    {
        static const pacing::Mode mode = pacing::ParseMode(env::Text("PES17_PACING"));
        return mode;
    }

    // Whether the 30 FPS run may show every finished frame in order. Presenter
    // (vulkan/presenter.cpp) and this pacer must agree, which is why the rule
    // lives in one place.
    inline bool PresentInOrder()
    {
        return pacing::PresentInOrder(PacingMode(), Limit() == 30);
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
    inline void PaceSwap()
    {
        const uint32_t limit = Limit();
        if (limit == 0 || limit >= kConsole) return;

        // Smooth 30: wait for the display's own next even boundary, measured
        // from presents that actually reached the panel. This is the fallback
        // when the driver has no display-timing scheduling, and it is also what
        // keeps the two limiters on one clock when it does.
        if (PresentInOrder())
        {
            const int64_t now = NowNs();
            uint64_t period = 0;
            int64_t phase = 0;
            if (display_clock::Read(period, phase, now))
            {
                const uint32_t divisor = pacing::DivisorForPeriod(period, limit);
                const int64_t at = pacing::NextBoundary(now, double(period), phase, divisor);
                // Inside the lead already: submit now rather than skipping a
                // whole frame to the boundary after the next one.
                if (at - kLeadNs > now)
                    std::this_thread::sleep_until(std::chrono::steady_clock::time_point(
                        std::chrono::nanoseconds(at - kLeadNs)));
                return;
            }
        }

        // Compatibility (or no display clock yet): the original independent
        // clock, unchanged so the compatibility mode restores it exactly.
        using clock = std::chrono::steady_clock;
        static clock::time_point next{};
        const auto now = clock::now();
        const auto period = std::chrono::microseconds(1000000 / limit);
        if (now < next) std::this_thread::sleep_until(next);
        // A frame that ran long does not owe the ones it missed.
        next = (now > next + period) ? now + period : next + period;
    }

    // Frames are retired at the blank, one a blank, as on the console.
    inline bool Console() { return Limit() == kConsole; }
}

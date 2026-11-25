#pragma once
// Presentation pacing for the title's 30 FPS limit (PES17_PACING).
//
// At 60 the guest's D3D swap retires at the display's blank and the title keeps
// its own 60. Any lower limit takes the title off the blank (d3d_hooks.cpp) and
// frame_rate::PaceSwap is what keeps it to the chosen rate. On a phone a 30 FPS
// frame belongs on every second refresh of a 60 Hz screen -- and a "60 Hz"
// panel is usually 59.94, so an independent 33.333 ms timer drifts against it
// and every few seconds shows a frame twice or skips one. That is the judder
// this header exists to remove.
//
// This is the arithmetic only: which strategy a mode and a device allow, where
// the next even boundary is, and how the bounded present queue behaves. No
// Vulkan, Android or environment access, so tests/frame_pacing_test.cpp can
// pin it on the host.

#include <cmath>
#include <cstddef>
#include <cstdint>

namespace pacing
{
    // Settings > Graphics & performance, in both menus.
    enum class Mode { Auto, Smooth, Compatibility };

    // Display refresh preference for a 30 FPS match.
    enum class Refresh { System, Prefer60 };

    inline Mode ParseMode(const char* text)
    {
        if (!text || !*text) return Mode::Auto;
        switch (text[0])
        {
            case 's': case 'S': return Mode::Smooth;
            case 'c': case 'C': return Mode::Compatibility;
            default: return Mode::Auto;
        }
    }

    inline const char* ModeName(Mode mode)
    {
        switch (mode)
        {
            case Mode::Smooth: return "smooth";
            case Mode::Compatibility: return "compatibility";
            default: return "auto";
        }
    }

    inline Refresh ParseRefresh(const char* text)
    {
        return (text && (text[0] == 'p' || text[0] == 'P')) ? Refresh::Prefer60 : Refresh::System;
    }

    // What this run can actually do. The driver extension is optional, and
    // present_wait is what gives a measured display phase without it.
    struct Capabilities
    {
        bool displayTiming = false;
        bool presentWait = false;
    };

    enum class Strategy
    {
        // Whatever this runtime did before tfix5: newest-wins presents and an
        // independent clock. Compatibility asks for exactly this.
        Original,
        // VK_GOOGLE_display_timing: every 30 FPS present asks the driver for
        // the next even refresh boundary and the driver reports when it landed.
        DisplayTiming,
        // FIFO with the software pacer following the display phase measured
        // from vkWaitForPresentKHR.
        PhaseLockedFifo,
    };

    inline const char* StrategyName(Strategy strategy)
    {
        switch (strategy)
        {
            case Strategy::DisplayTiming: return "display-timing";
            case Strategy::PhaseLockedFifo: return "phase-locked-fifo";
            default: return "original";
        }
    }

    // Auto and Smooth mean the same thing for the 30 FPS cap; the difference is
    // that Auto may fall back and Smooth is the player asking for the best
    // available pacing. Both keep the original presentation for every other
    // limit, which is not an even multiple of the refresh and has no grid.
    inline Strategy Choose(Mode mode, bool thirtyFps, Capabilities capabilities)
    {
        if (mode == Mode::Compatibility || !thirtyFps) return Strategy::Original;
        if (capabilities.displayTiming) return Strategy::DisplayTiming;
        if (capabilities.presentWait) return Strategy::PhaseLockedFifo;
        // No measured display clock at all: the independent pacer is all this
        // device can offer, with FIFO still ordering the presents.
        return Strategy::Original;
    }

    // Whether every finished frame goes to the screen in order. Compatibility
    // keeps the original newest-wins behaviour; so does every limit that is not
    // 30, because those are not an even multiple of any phone refresh rate.
    inline bool PresentInOrder(Mode mode, bool thirtyFps)
    {
        return mode != Mode::Compatibility && thirtyFps;
    }

    constexpr double kSecondNs = 1e9;

    inline double PeriodNs(double hz)
    {
        return hz > 0.0 ? kSecondNs / hz : kSecondNs / 60.0;
    }

    // How many display refreshes one frame of `fps` occupies: 1 at 30 on a
    // 30 Hz mode, 2 at 30 on 59.94/60, 4 at 30 on 120. Rounded, so a measured
    // 59.94 Hz period still gives 2.
    inline uint32_t DivisorForPeriod(uint64_t periodNs, uint32_t fps)
    {
        if (!periodNs || !fps) return 1;
        const double ratio = kSecondNs / (double(periodNs) * double(fps));
        const long rounded = std::lround(ratio);
        return rounded > 0 ? uint32_t(rounded) : 1;
    }

    // Whether a refresh rate is a whole multiple of `fps`: 30 on 59.94, 60 and
    // 120; not 35 or 45 on 60. The tolerance is a few ppm, enough for a mode
    // that reports 59.94 as 59.94006.
    inline bool EvenDivisor(double hz, uint32_t fps)
    {
        if (hz <= 0.0 || !fps) return false;
        const double ratio = hz / double(fps);
        const double nearest = std::round(ratio);
        return nearest >= 1.0 && std::fabs(ratio - nearest) < 0.01;
    }

    // The display's present grid: boundary n is `phaseNs + n * period * divisor`
    // where `phaseNs` is one present that actually reached the panel. Returns
    // the first boundary at or after `nowNs`; callers subtract their own lead.
    inline int64_t NextBoundary(int64_t nowNs, double periodNs, int64_t phaseNs, uint32_t divisor)
    {
        if (!(periodNs > 0.0) || divisor == 0) return nowNs;
        const double step = periodNs * double(divisor);
        const double delta = double(nowNs) - double(phaseNs);
        const double n = std::ceil(delta / step);
        int64_t at = phaseNs + int64_t(std::llround(n * step));
        // Floating point can land a nanosecond on the wrong side of `now`.
        if (at < nowNs) at += int64_t(std::llround(step));
        return at;
    }

    // Signed distance of an observed present from the grid, folded into
    // [-step/2, +step/2]: what PES17_TRACE_PACING reports as the phase error.
    inline int64_t PhaseError(int64_t atNs, double periodNs, int64_t phaseNs, uint32_t divisor)
    {
        if (!(periodNs > 0.0) || divisor == 0) return 0;
        const double step = periodNs * double(divisor);
        double error = std::fmod(double(atNs - phaseNs), step);
        if (error < 0.0) error += step;
        if (error > step / 2.0) error -= step;
        return int64_t(std::llround(error));
    }

    // The bounded present queue. Three gives the presenter the frame on screen,
    // the one waiting for the next blank and one the renderer has just
    // finished; the producer already waits on WaitUntilTaken before drawing the
    // next, so this is a ceiling rather than the usual depth.
    constexpr size_t kOrderedQueueFrames = 3;

    inline size_t QueueCapacity(Mode mode, bool thirtyFps)
    {
        return PresentInOrder(mode, thirtyFps) ? kOrderedQueueFrames : 1;
    }

    inline bool HasRoom(size_t queued, Mode mode, bool thirtyFps)
    {
        return queued < QueueCapacity(mode, thirtyFps);
    }

    // How many of the oldest queued frames must be let go before a new one can
    // be shown. Compatibility's answer is the original newest-wins drop; the
    // ordered mode's is only ever non-zero if the queue passed its ceiling.
    inline size_t ExcessToDrop(size_t queued, Mode mode, bool thirtyFps)
    {
        const size_t capacity = QueueCapacity(mode, thirtyFps);
        return queued > capacity ? queued - capacity : 0;
    }
}

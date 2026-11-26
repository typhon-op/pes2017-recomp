#pragma once
// Presentation pacing for every FPS limit below the console's 60 (PES17_PACING).
//
// At 60 the guest's D3D swap retires at the display's blank and the title keeps
// its own 60. A lower limit takes the title off the blank (d3d_hooks.cpp) and
// this header plus frame_rate::PaceSwap are what keep it to the chosen rate.
//
// A phone can only show a frame at a refresh boundary. A limit that divides the
// panel evenly -- 30 on 60 Hz -- belongs on every second boundary, and a limit
// that does not (35, 45 and 55 on 60 Hz, 45 on 59.94) has no exact grid at all:
// the closest a 60 Hz panel can come to 35 is a repeating 2,1,2,2,1-blank
// pattern whose average is 35. Scheduling each frame from a timeline that
// advances by the *exact* frame period, and then taking the next display
// boundary at or after that target, gives that pattern for every limit and
// never accumulates drift: the error stays under one refresh forever instead of
// growing a millisecond larger every few seconds.
//
// This is the arithmetic only: which strategy a mode and a device allow, where
// the next display boundary is, the drift-free frame timeline, and how the
// bounded present queue behaves. No Vulkan, Android or environment access, so
// tests/frame_pacing_test.cpp can pin it on the host for all seven limits.

#include <cmath>
#include <cstddef>
#include <cstdint>

namespace pacing
{
    // Settings > Graphics & performance, in both menus.
    enum class Mode { Auto, Smooth, Compatibility };

    // Display refresh preference for a paced match.
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
        // VK_GOOGLE_display_timing: every paced present asks the driver for the
        // next display boundary and the driver reports when it landed.
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

    // Every rate the title is paced at in this build: 30, 35, 40, 45, 50, 55.
    // 60 (and no limit) is the console's own blank pacing, which is not a
    // software schedule at all.
    inline bool Schedulable(uint32_t fps) { return fps > 0 && fps < 60; }

    // Auto and Smooth mean the same thing for a paced limit; the difference is
    // that Auto may fall back and Smooth is the player asking for the best
    // available pacing. Both keep the original presentation for 60 and for no
    // limit, where the display already owns the boundary.
    inline Strategy Choose(Mode mode, uint32_t fps, Capabilities capabilities)
    {
        if (mode == Mode::Compatibility || !Schedulable(fps)) return Strategy::Original;
        if (capabilities.displayTiming) return Strategy::DisplayTiming;
        if (capabilities.presentWait) return Strategy::PhaseLockedFifo;
        // No measured display clock at all: the independent timeline is all
        // this device can offer, with FIFO still ordering the presents.
        return Strategy::Original;
    }

    // Whether every finished frame goes to the screen in order. Compatibility
    // keeps the original newest-wins behaviour, and so does the console's 60
    // (or no limit), where the display's blank already paces the title.
    inline bool PresentInOrder(Mode mode, bool limited)
    {
        return mode != Mode::Compatibility && limited;
    }

    constexpr double kSecondNs = 1e9;
    constexpr int64_t kSecond = 1'000'000'000;

    inline double PeriodNs(double hz)
    {
        return hz > 0.0 ? kSecondNs / hz : kSecondNs / 60.0;
    }

    // One frame at `fps`, in nanoseconds. Rounded only for use as a timeout or
    // a lead; the schedule itself never accumulates this rounding (below).
    inline int64_t FramePeriodNs(uint32_t fps)
    {
        if (!fps) return 0;
        return int64_t(std::llround(kSecondNs / double(fps)));
    }

    // The exact deadline of frame `index` counted from `originNs`, to the
    // nanosecond and with no accumulated error: frame n at 35 FPS is n/35 s,
    // not n times a rounded 28.571 ms. Written as whole seconds plus the
    // remainder so the product cannot overflow for any run length that fits in
    // a phone's uptime.
    inline int64_t FrameDeadline(int64_t originNs, uint32_t fps, uint64_t index)
    {
        if (!fps) return originNs;
        const uint64_t seconds = index / fps;
        const uint64_t remainder = index % fps;
        return originNs + int64_t(seconds) * kSecond +
               int64_t((remainder * uint64_t(kSecond)) / fps);
    }

    // How many whole frame periods fit between `originNs` and `nowNs`, which is
    // the first frame index that is still in the future. Used to skip the frames
    // a stall made late rather than firing them back to back.
    inline uint64_t FramesElapsed(int64_t originNs, uint32_t fps, int64_t nowNs)
    {
        if (!fps || nowNs <= originNs) return 0;
        const uint64_t elapsed = uint64_t(nowNs - originNs);
        return (elapsed / uint64_t(kSecond)) * fps +
               ((elapsed % uint64_t(kSecond)) * fps) / uint64_t(kSecond);
    }

    // How many display refreshes one frame of `fps` occupies when the refresh
    // rate is a whole multiple of it: 1 at 30 on a 30 Hz mode, 2 at 30 on
    // 59.94/60, 4 at 30 on 120. Rounded, so a measured 59.94 Hz period still
    // gives 2.
    inline uint32_t DivisorForPeriod(uint64_t periodNs, uint32_t fps)
    {
        if (!periodNs || !fps) return 1;
        const double ratio = kSecondNs / (double(periodNs) * double(fps));
        const long rounded = std::lround(ratio);
        return rounded > 0 ? uint32_t(rounded) : 1;
    }

    // Whether a refresh rate is a whole multiple of `fps`: 30 on 59.94, 60 and
    // 120; not 35, 45 or 55 on 60. The tolerance is a few ppm, enough for a mode
    // that reports 59.94 as 59.94006.
    inline bool EvenDivisor(double hz, uint32_t fps)
    {
        if (hz <= 0.0 || !fps) return false;
        const double ratio = hz / double(fps);
        const double nearest = std::round(ratio);
        return nearest >= 1.0 && std::fabs(ratio - nearest) < 0.01;
    }

    // The display grid step for a limit: whole refreshes when the panel divides
    // evenly, otherwise one refresh (the frame deadline is then placed on the
    // next boundary rather than on a grid that does not exist).
    inline uint32_t GridDivisor(uint64_t periodNs, uint32_t fps)
    {
        const double hz = periodNs ? kSecondNs / double(periodNs) : 0.0;
        return EvenDivisor(hz, fps) ? DivisorForPeriod(periodNs, fps) : 1;
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

    inline size_t QueueCapacity(Mode mode, bool limited)
    {
        return PresentInOrder(mode, limited) ? kOrderedQueueFrames : 1;
    }

    inline bool HasRoom(size_t queued, Mode mode, bool limited)
    {
        return queued < QueueCapacity(mode, limited);
    }

    // How many of the oldest queued frames must be let go before a new one can
    // be shown. Compatibility's answer is the original newest-wins drop; the
    // ordered mode's is only ever non-zero if the queue passed its ceiling.
    inline size_t ExcessToDrop(size_t queued, Mode mode, bool limited)
    {
        const size_t capacity = QueueCapacity(mode, limited);
        return queued > capacity ? queued - capacity : 0;
    }
}

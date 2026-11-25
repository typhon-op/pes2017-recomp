#pragma once
// The display's own blank, as the presenter measured it.
//
// VK_GOOGLE_display_timing and VK_KHR_present_wait both report when a frame
// actually reached the panel. frame_rate::PaceSwap and the guest's VBlankWorker
// need that phase -- not another free-running timer -- when the 30 FPS pacer
// follows the display. The presenter writes here, the guest thread reads; the
// storage is a few atomics so neither side includes the Vulkan presenter.
//
// Timestamps are on std::chrono::steady_clock's clock (CLOCK_MONOTONIC on
// Android), which is the same clock frame_rate.h reads. A reading older than
// half a second is ignored: a paused window has no display clock to follow.

#include <atomic>
#include <cstdint>

namespace display_clock
{
    inline std::atomic<uint64_t>& PeriodNsSlot()
    {
        static std::atomic<uint64_t> value{ 0 };
        return value;
    }

    inline std::atomic<int64_t>& PhaseNsSlot()
    {
        static std::atomic<int64_t> value{ 0 };
        return value;
    }

    inline std::atomic<int64_t>& PublishedAtSlot()
    {
        static std::atomic<int64_t> value{ 0 };
        return value;
    }

    // `presentNs` is when a frame reached the panel and `nowNs` is when it was
    // recorded. Anything outside 10 Hz..1 kHz is a misread mode, not a panel.
    inline void Publish(uint64_t periodNs, int64_t presentNs, int64_t nowNs)
    {
        if (periodNs < 1'000'000 || periodNs > 100'000'000) return;
        PeriodNsSlot().store(periodNs, std::memory_order_relaxed);
        PhaseNsSlot().store(presentNs, std::memory_order_relaxed);
        PublishedAtSlot().store(nowNs, std::memory_order_relaxed);
    }

    inline bool Read(uint64_t& periodNs, int64_t& phaseNs, int64_t nowNs)
    {
        const int64_t at = PublishedAtSlot().load(std::memory_order_relaxed);
        if (!at) return false;
        if (nowNs - at > 500'000'000 || nowNs < at - 100'000'000) return false;
        periodNs = PeriodNsSlot().load(std::memory_order_relaxed);
        phaseNs = PhaseNsSlot().load(std::memory_order_relaxed);
        return periodNs != 0;
    }

    // For a test or a fresh run.
    inline void Forget()
    {
        PeriodNsSlot().store(0, std::memory_order_relaxed);
        PhaseNsSlot().store(0, std::memory_order_relaxed);
        PublishedAtSlot().store(0, std::memory_order_relaxed);
    }
}

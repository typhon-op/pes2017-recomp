#pragma once
// Presses that have not been delivered to the title yet.
//
// The app sends absolute pad state, and the title samples it once a frame. A
// button that goes down and up between two samples therefore never exists as
// far as the title is concerned -- which is what a crisp double-tap on a phone
// looks like. The bits of every press are left here instead, and the next poll
// carries them once: absolute state for what is held, a pulse for what was
// tapped.
//
// Adding is a fetch_or and taking is an exchange, so a press from the UI thread
// cannot be lost against a poll on the guest thread. Nothing here touches the
// platform, the guest or the GPU, so tests/ can check it on a tree with none of
// them.
#include <atomic>
#include <cstdint>

namespace input
{
    class PressPulses
    {
    public:
        void Add(std::uint16_t bits)
        {
            if (bits) pending.fetch_or(bits, std::memory_order_acq_rel);
        }

        // The bits waiting, cleared. A poll calls this once and sends what it
        // gets; a press that never repeats is one poll's worth of state.
        std::uint16_t Take()
        {
            return pending.exchange(0, std::memory_order_acq_rel);
        }

        void Clear() { pending.store(0, std::memory_order_release); }

        bool Pending() const { return pending.load(std::memory_order_acquire) != 0; }

    private:
        std::atomic<std::uint16_t> pending{ 0 };
    };
}

#pragma once
#include "../guest.h"

// XMP -- the console's system-music player, of which this runtime has none.
// The title still asks it whether something is playing before it starts its own
// soundtrack, and the answer is two big-endian output words in guest memory, not
// a value in the import's result register. They are the whole of what this file
// implements: no playlist, no player, no decoding.
namespace kernel::xmp
{
    inline constexpr uint32_t kApp = 0xFA;
    inline constexpr uint32_t kGetPlaybackController = 0x0007001B;
    inline constexpr uint32_t kGetStatus = 0x00070009;

    inline bool Handled(uint32_t message)
    {
        return message == kGetPlaybackController || message == kGetStatus;
    }

    // XMP's synchronous queries return HRESULTs. A positive Win32 error would be
    // mistaken for success by the title's HRESULT-to-Win32 SDK wrapper, so the
    // rejection is the HRESULT form of E_INVALIDARG.
    inline uint32_t Query(uint32_t message, uint32_t packet, uint32_t length)
    {
        constexpr uint32_t invalid = 0x80070057u; // E_INVALIDARG
        if (!Handled(message)) return invalid;
        const uint32_t bytes = message == kGetStatus ? 8 : 12;
        // The SDK passes length 0 for these in-process requests. An explicit
        // length still has to cover the packet, and a guest address must not
        // wrap around the end of the address space.
        if (!guest::Base() || !packet || (packet & 3) ||
            uint64_t(packet) + bytes > 0x100000000ull || (length && length < bytes))
            return invalid;

        const be32* words = GuestPtr<be32>(packet);
        if (uint32_t(words[0]) != 2) return invalid; // the SDK's supported client
        const uint32_t first = words[1];
        // GetStatus has only two words in its packet: do not read a third.
        const uint32_t second = message == kGetPlaybackController ? uint32_t(words[2]) : 0;
        if (!first || (first & 3) ||
            (message == kGetPlaybackController && (!second || (second & 3))))
            return invalid;

        // Nothing owns playback and nothing is locked: controller 0, locked 0,
        // status idle (0). Both pointers are validated before either store, so a
        // rejected request cannot leave one of its outputs changed.
        *GuestPtr<be32>(first) = 0;
        if (message == kGetPlaybackController) *GuestPtr<be32>(second) = 0;
        return 0;
    }
}

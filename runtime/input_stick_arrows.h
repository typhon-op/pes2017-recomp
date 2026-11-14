#pragma once
// The left stick standing in for the d-pad, which is how a screen that only
// accepts the d-pad is reached with a thumb.
//
// The desktop build has always done this; the Android guest path did not, and
// the on-screen pad has no d-pad control of its own, so a menu or a prompt that
// ignores the stick could not be driven at all. Both backends now use this one
// rule, so they cannot drift apart again.
//
// Press at 0.60 of full deflection, release at 0.42. A thumb resting exactly at
// the edge of a threshold would otherwise chatter the bit every frame, and a
// worn stick dithers around its own flat zone; the gap between the two points
// is what stops both.
#include <cstdint>
#include <cstdlib>

namespace input
{
    // XINPUT_GAMEPAD_DPAD_*, the bits the guest reads. kernel/input.cpp checks
    // these against the platform layer's names where both are in the build.
    inline constexpr std::uint16_t kDpadUp = 0x0001;
    inline constexpr std::uint16_t kDpadDown = 0x0002;
    inline constexpr std::uint16_t kDpadLeft = 0x0004;
    inline constexpr std::uint16_t kDpadRight = 0x0008;

    // 0.60 and 0.42 of a full 16-bit axis.
    inline constexpr int kArrowPress = 19660;
    inline constexpr int kArrowRelease = 13760;

    struct ArrowLatch
    {
        bool up = false, down = false, left = false, right = false;

        bool Any() const { return up || down || left || right; }
        void Clear() { up = down = left = right = false; }
    };

    namespace detail
    {
        inline int Sign(int value) { return value > 0 ? 1 : value < 0 ? -1 : 0; }

        // One direction of one axis: set at the press point, kept until the
        // stick comes back inside the release ring or swings the other way.
        inline bool Edge(int value, int direction, bool& held)
        {
            const int magnitude = std::abs(value);
            if (held)
            {
                if (magnitude <= kArrowRelease || Sign(value) != direction) held = false;
            }
            else if (magnitude >= kArrowPress && Sign(value) == direction)
            {
                held = true;
            }
            return held;
        }
    }

    // The d-pad bits the stick is standing in for. [latch] carries the
    // hysteresis between calls: one per player, owned by the caller.
    inline std::uint16_t StickArrows(std::int16_t stickX, std::int16_t stickY, ArrowLatch& latch)
    {
        detail::Edge(stickX, +1, latch.right);
        detail::Edge(stickX, -1, latch.left);
        detail::Edge(stickY, +1, latch.up);
        detail::Edge(stickY, -1, latch.down);

        std::uint16_t bits = 0;
        if (latch.up)    bits |= kDpadUp;
        if (latch.down)  bits |= kDpadDown;
        if (latch.left)  bits |= kDpadLeft;
        if (latch.right) bits |= kDpadRight;
        return bits;
    }
}

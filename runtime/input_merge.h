#pragma once
// One stick, one hand: which source a frame's stick value comes from when the
// on-screen controls and a physical pad are both driving player 1.
//
// Taking the stronger of each axis independently lets two sources add up to a
// diagonal neither of them is pushing -- a thumb at full right and a pad at
// full down and the player runs down-right -- and makes where a stick "is"
// depend on which hand moved last. So the pair is chosen whole: the vector with
// the larger magnitude wins, and a tie goes to the pad, the source that has
// just been picked up.
//
// Buttons and triggers are not like this: a press is a press whoever made it,
// so they are still added (buttons OR'd, triggers taken at the larger value).
// Nothing here touches the platform, the guest or the GPU, so tests/ can check
// it on a tree with none of them.
#include <cstdint>
#include <cstdlib>

namespace input
{
    inline std::int64_t Magnitude(std::int16_t x, std::int16_t y)
    {
        const std::int64_t a = std::abs(std::int64_t(x));
        const std::int64_t b = std::abs(std::int64_t(y));
        return a * a + b * b;
    }

    // `into` is what has been gathered so far (the touch controls for player 1)
    // and `from` what is being merged in (the physical pad). The pair of one of
    // them comes out whole; a tie goes to `from`.
    inline void MergeStickPair(std::int16_t intoX, std::int16_t intoY,
                               std::int16_t fromX, std::int16_t fromY,
                               std::int16_t& outX, std::int16_t& outY)
    {
        if (Magnitude(fromX, fromY) >= Magnitude(intoX, intoY))
        {
            outX = fromX;
            outY = fromY;
        }
        else
        {
            outX = intoX;
            outY = intoY;
        }
    }
}

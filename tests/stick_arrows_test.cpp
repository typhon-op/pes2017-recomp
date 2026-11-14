// The left stick standing in for the d-pad (runtime/input_stick_arrows.h): the
// press thresholds, the hysteresis that keeps a resting thumb from chattering a
// bit, diagonals, and that swinging the stick the other way lets the old
// direction go. Both backends use this rule, so a screen that only accepts the
// d-pad answers to a stick on the phone as well as on the desktop.
//
// It asserts rather than reporting, and includes nothing that needs the guest,
// the disc, a GPU or an Android device.
#include "input_stick_arrows.h"

#include <cstdio>

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

    using input::ArrowLatch;
    using input::kArrowPress;
    using input::kArrowRelease;
    using input::kDpadDown;
    using input::kDpadLeft;
    using input::kDpadRight;
    using input::kDpadUp;
    using input::StickArrows;
}

int main()
{
    {
        ArrowLatch latch;
        Check(StickArrows(0, 0, latch) == 0, "a centred stick presses nothing");
    }

    {
        ArrowLatch latch;
        Check(StickArrows(0, kArrowPress - 1, latch) == 0, "just under the press point is still nothing");
        Check(StickArrows(0, kArrowPress, latch) == kDpadUp, "the press point presses up");
        Check(StickArrows(0, kArrowRelease + 1, latch) == kDpadUp,
              "the hysteresis holds it between the two points");
        Check(StickArrows(0, kArrowRelease, latch) == 0, "the release point lets it go");
    }

    {
        ArrowLatch latch;
        Check(StickArrows(0, -kArrowPress, latch) == kDpadDown, "down is the negative side");
        Check(StickArrows(0, -kArrowRelease, latch) == 0, "and it lets go at the release point");
        Check(StickArrows(kArrowPress, 0, latch) == kDpadRight, "right is the positive X side");
        Check(StickArrows(-kArrowPress, 0, latch) == kDpadLeft, "left is the negative X side");
    }

    {
        ArrowLatch latch;
        const unsigned bits = StickArrows(kArrowPress, kArrowPress, latch);
        Check(bits == (kDpadUp | kDpadRight), "a diagonal presses two bits");
    }

    {
        ArrowLatch latch;
        Check(StickArrows(0, kArrowPress, latch) == kDpadUp, "up");
        Check(StickArrows(0, -kArrowPress, latch) == kDpadDown,
              "swinging straight through to the other side does not keep up pressed");
    }

    {
        ArrowLatch latch;
        Check(StickArrows(0, kArrowPress, latch) == kDpadUp, "up");
        latch.Clear();
        Check(!latch.Any(), "Clear lets go of everything");
        Check(StickArrows(0, kArrowRelease + 1, latch) == 0,
              "and the old press is not remembered through it");
    }

    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

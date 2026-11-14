// Mixing the on-screen controls with a physical pad (runtime/input_merge.h):
// a stick's frame comes from one source whole, never one axis from each, and a
// dead heat goes to the pad. A thumb at full right and a pad at full down must
// not turn into a diagonal; a thumb on the glass must not fight a pad that is
// resting.
//
// It asserts rather than reporting, and includes nothing that needs the guest,
// the disc, a GPU or an Android device.
#include "input_merge.h"

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

    using input::Magnitude;
    using input::MergeStickPair;

    constexpr std::int16_t Full = 32767;
    constexpr std::int16_t FullNegative = -32768;
}

int main()
{
    std::int16_t x = 1, y = 1;

    {
        MergeStickPair(1000, 0, 0, 0, x, y);
        Check(x == 1000 && y == 0, "the touch stick keeps the frame while the pad rests");
    }

    {
        MergeStickPair(0, 0, 0, FullNegative, x, y);
        Check(x == 0 && y == FullNegative, "the pad keeps the frame while no thumb is on the glass");
    }

    {
        MergeStickPair(Full, 0, 0, FullNegative, x, y);
        Check(!(x == Full && y == FullNegative), "two sources never add up to a diagonal neither is pushing");
        Check(x == 0 && y == FullNegative, "the stronger vector wins whole");
    }

    {
        MergeStickPair(Full, 0, 0, Full, x, y);
        Check(x == 0 && y == Full, "a dead heat goes to the pad");
    }

    {
        MergeStickPair(2 * Full / 3, Full / 3, -Full, 0, x, y);
        Check(x == -Full && y == 0, "a stronger pad vector replaces the touch vector, axis signs included");
    }

    {
        MergeStickPair(0, 0, 0, 0, x, y);
        Check(x == 0 && y == 0, "both at rest is still at rest");
    }

    Check(Magnitude(Full, 0) > Magnitude(Full / 2, Full / 2), "a full axis is longer than a half diagonal");
    Check(Magnitude(0, 0) == 0, "a centred stick has no length");

    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

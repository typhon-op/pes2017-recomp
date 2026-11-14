// The look area's saturation (runtime/look_scale.h): the app's slider is a feel
// multiplier while the input layer integrates pixels a second, and 1.0 has to
// stay the value the touch pad was tuned with. Also: the conversion is
// monotonic, it clamps what it is handed, and the figure a caller on the old
// pixels-a-second contract would pass does not slip through as the default.
//
// It asserts rather than reporting, and includes nothing that needs the guest,
// the disc, a GPU or an Android device.
#include "look_scale.h"

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

    bool Near(float a, float b)
    {
        const float difference = a > b ? a - b : b - a;
        return difference < 0.5f;
    }
}

int main()
{
    using look::kBasePixelsPerSecond;
    using look::kMaxScale;
    using look::kMinScale;
    using look::SaturationPixelsPerSecond;

    Check(Near(SaturationPixelsPerSecond(1.0f), kBasePixelsPerSecond), "1.0 is the default speed");
    Check(SaturationPixelsPerSecond(2.0f) < SaturationPixelsPerSecond(1.0f), "a bigger scale turns faster");
    Check(SaturationPixelsPerSecond(0.5f) > SaturationPixelsPerSecond(1.0f), "a smaller scale turns slower");
    Check(Near(SaturationPixelsPerSecond(0.0f), SaturationPixelsPerSecond(kMinScale)), "below the range clamps");
    Check(Near(SaturationPixelsPerSecond(1.0e6f), SaturationPixelsPerSecond(kMaxScale)), "above the range clamps");
    Check(SaturationPixelsPerSecond(kMinScale) >= 100.0f, "the slowest setting still moves the stick");
    Check(!Near(SaturationPixelsPerSecond(2200.0f), kBasePixelsPerSecond),
          "the old pixels-a-second figure is not silently taken as the default");

    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

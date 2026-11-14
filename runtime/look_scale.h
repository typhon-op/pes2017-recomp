#pragma once
// The look area's saturation setting, as the app means it.
//
// The app's slider (Prefs.lookSaturation) is a feel multiplier: 1.0 is the
// default, below it turns slower and above it reaches the stick's edge on a
// shorter flick. What the input layer integrates is the other quantity -- the
// speed, in pixels a second, at which the look stick reaches full deflection --
// so the conversion lives in one place instead of being guessed at the call
// sites. 1.0 keeps the value the touch pad was tuned with.
//
// Nothing here touches the platform, the guest or the GPU, so tests/ can check
// it on a tree with none of them.
#include <algorithm>

namespace look
{
    // 2200 px/s is a brisk flick across a 1080p phone held in landscape.
    inline constexpr float kBasePixelsPerSecond = 2200.0f;

    // Low enough that the slider at its end still turns, and a ceiling that
    // keeps a caller passing the old pixels-a-second figure from dividing the
    // stick into a dead one.
    inline constexpr float kMinScale = 0.30f;
    inline constexpr float kMaxScale = 10.0f;

    inline float SaturationPixelsPerSecond(float scale)
    {
        return kBasePixelsPerSecond / std::clamp(scale, kMinScale, kMaxScale);
    }
}

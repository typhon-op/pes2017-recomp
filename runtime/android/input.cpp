// The pad the title reads, fed by the touch controls and by a physical pad.
//
// The overlay sends an absolute state on every touch event -- a button mask,
// two triggers and two sticks -- so a dropped event cannot leave a button
// held down. The look area is different: it sends movement, and movement has
// to become a stick deflection the title can integrate.
//
// Look: the deflection is the finger's *speed*, not its displacement. The
// title turns the view by the stick's value once a frame, so a stick set from
// speed turns the view by the distance the finger travelled, whatever rate
// either side runs at -- 60 Hz polling, 120 Hz touch reports, a frame that
// took 40 ms. Displacement alone would turn faster on a device that reports
// touches more often, which is how touch aiming usually ends up feeling
// different on every phone.
#ifdef PES17_ANDROID

#include "android.h"
#include "../log.h"
#include "../input_merge.h"
#include "../input_pulses.h"
#include "../look_scale.h"
#include "../stick_curve.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>

namespace
{
    using Clock = std::chrono::steady_clock;

    struct Source
    {
        std::mutex lock;
        android::input::Pad pad;
        bool connected = false;
    };

    Source g_touch;                     // the on-screen controls
    // Presses from the on-screen controls that no poll has carried yet.
    ::input::PressPulses g_touchPulses;
    Source g_hardware[4];               // physical pads, as the app numbers them
    std::atomic<bool> g_hardwareEnabled{ true };

    // ---- the look area ----------------------------------------------------
    std::mutex g_lookLock;
    double g_lookAccumX = 0, g_lookAccumY = 0;      // pixels since the last poll
    Clock::time_point g_lookLast{};                 // when that poll was
    float g_lookValueX = 0, g_lookValueY = 0;       // the stick, -1..1
    bool g_lookActive = false;

    float g_sensitivityX = 1.0f, g_sensitivityY = 1.0f;
    float g_smoothing = 0.35f;                      // 0 raw, 0.9 very smooth
    bool g_invertY = false;
    // The speed, in pixels a second, at which the stick reaches full tilt.
    // The base is a brisk flick across a 1080p phone held in landscape; the
    // app's slider scales it (look_scale.h).
    float g_saturationPixelsPerSecond = look::kBasePixelsPerSecond;

    // ---- the sticks -------------------------------------------------------
    // Movement used to be linear from the first pixel, so a 75 dp stick jumped
    // from standing to a jog. The shape is radial: a diagonal stays one, and the
    // deadzone is a circle like the socket it is drawn as.
    std::mutex g_stickLock;
    // The right stick is passed through untouched until a player asks for a
    // shape -- tricks are muscle memory, and this must not quietly retune them.
    stick::Shape g_leftStick{ 0.08f, 1.45f, 1.0f };
    stick::Shape g_rightStick{ 0.0f, 1.0f, 1.0f };

    int16_t ToAxis(float value)
    {
        value = std::clamp(value, -1.0f, 1.0f);
        constexpr float kMinThreshold = 0.015f;
        const float absVal = std::abs(value);
        if (absVal < kMinThreshold) return 0;

        // The console title has an internal thumbstick deadzone of ~14% (0.14f).
        // Touch look should respond immediately to fine movements without requiring
        // the finger to exceed a stick deadzone barrier.
        // Remap from [kMinThreshold, 1.0f] to [0.0f, 1.0f] so near-zero residuals do
        // not snap to full minimum deflection.
        constexpr float kDeadzone = 0.14f;
        const float sign = value < 0.0f ? -1.0f : 1.0f;
        const float normalized = (absVal - kMinThreshold) / (1.0f - kMinThreshold);
        const float boosted = sign * (kDeadzone + normalized * (1.0f - kDeadzone));
        return int16_t(std::lround(boosted * (boosted < 0 ? 32768.0f : 32767.0f)));
    }

    // The pad carries the sticks as int16, as the console does. The curve works
    // in -1..1 and comes back to the same scale.
    float ToUnit(int16_t value)
    {
        return value < 0 ? float(value) / 32768.0f : float(value) / 32767.0f;
    }

    int16_t ToStick(float value)
    {
        value = std::clamp(value, -1.0f, 1.0f);
        return int16_t(std::lround(value * (value < 0 ? 32768.0f : 32767.0f)));
    }

    // Converts what the finger did since the last call into a stick value.
    // Called from the title's poll, which is where the title's own clock is.
    void UpdateLook(android::input::Pad& out)
    {
        std::lock_guard lock(g_lookLock);
        const auto now = Clock::now();
        if (g_lookLast.time_since_epoch().count() == 0) g_lookLast = now;
        const double elapsed = std::chrono::duration<double>(now - g_lookLast).count();

        // Two polls in the same frame: the title asks for every user's state
        // one after another, and the second must not see an empty accumulator
        // and read as "the finger stopped". Below a millisecond the last value
        // stands and nothing is consumed.
        if (elapsed >= 0.001)
        {
            g_lookLast = now;
            const double speedX = g_lookAccumX / elapsed;
            const double speedY = g_lookAccumY / elapsed;
            g_lookAccumX = g_lookAccumY = 0;

            if (!g_lookActive)
            {
                // A finger that has been lifted centres the stick immediately
                // rather than decaying through smoothing.
                g_lookValueX = 0.0f;
                g_lookValueY = 0.0f;
            }
            else
            {
                const float saturation = std::max(g_saturationPixelsPerSecond, 100.0f);
                float x = float(speedX) * g_sensitivityX / saturation;
                float y = float(speedY) * g_sensitivityY / saturation;
                // Smoothing takes the stairs out of a finger reported at 120 Hz
                // against a title polling at 60; too much of it feels like ice.
                const float keep = std::clamp(g_smoothing, 0.0f, 0.9f);
                g_lookValueX = g_lookValueX * keep + std::clamp(x, -1.0f, 1.0f) * (1.0f - keep);
                g_lookValueY = g_lookValueY * keep + std::clamp(y, -1.0f, 1.0f) * (1.0f - keep);
                if (std::fabs(g_lookValueX) < 0.002f) g_lookValueX = 0;
                if (std::fabs(g_lookValueY) < 0.002f) g_lookValueY = 0;
            }
        }

        if (g_lookValueX == 0 && g_lookValueY == 0) return;
        // The guest's Y axis points up; the screen's points down.
        const float y = g_invertY ? g_lookValueY : -g_lookValueY;
        // The look area adds to the right stick rather than replacing it, so a
        // player using both the stick and the look area is not fighting them.
        const int32_t mergedX = int32_t(out.rightX) + ToAxis(g_lookValueX);
        const int32_t mergedY = int32_t(out.rightY) + ToAxis(y);
        out.rightX = int16_t(std::clamp<int32_t>(mergedX, -32768, 32767));
        out.rightY = int16_t(std::clamp<int32_t>(mergedY, -32768, 32767));
    }

    // The touch controls and a physical pad can both drive player 1. A press is
    // a press whoever makes it (buttons OR'd, triggers at the larger value); a
    // stick belongs to one hand at a time, so the whole vector of whichever
    // source is pushing harder wins. Taking each axis separately would let a
    // thumb at full right and a pad at full down add up to a diagonal neither
    // of them is pushing.
    void Merge(android::input::Pad& into, const android::input::Pad& from)
    {
        into.buttons |= from.buttons;
        into.leftTrigger = std::max(into.leftTrigger, from.leftTrigger);
        into.rightTrigger = std::max(into.rightTrigger, from.rightTrigger);

        static bool toldBothHands = false;
        if (!toldBothHands && ::input::Magnitude(from.leftX, from.leftY) > 0 &&
            ::input::Magnitude(into.leftX, into.leftY) > 0)
        {
            toldBothHands = true;
            LOGI("input: a pad and the touch stick are both driving player 1; the stronger one wins");
        }

        int16_t x = 0, y = 0;
        ::input::MergeStickPair(into.leftX, into.leftY, from.leftX, from.leftY, x, y);
        into.leftX = x; into.leftY = y;
        ::input::MergeStickPair(into.rightX, into.rightY, from.rightX, from.rightY, x, y);
        into.rightX = x; into.rightY = y;
    }

    // Set by jni.cpp so a motor can reach the app.
    void (*g_rumble)(uint32_t user, uint16_t low, uint16_t high) = nullptr;
}

namespace android::input::detail
{
    void SetRumbleSink(void (*sink)(uint32_t, uint16_t, uint16_t)) { g_rumble = sink; }
}

void android::input::SetTouchPad(const Pad& pad)
{
    // Shaped here, at the single point every touch state arrives by, so a
    // recorded macro replays exactly the deflection it was recorded with.
    Pad shaped = pad;
    {
        std::lock_guard lock(g_stickLock);
        float x = ToUnit(shaped.leftX), y = ToUnit(shaped.leftY);
        if (stick::Apply(x, y, g_leftStick)) { shaped.leftX = ToStick(x); shaped.leftY = ToStick(y); }
        else { shaped.leftX = shaped.leftY = 0; }
        x = ToUnit(shaped.rightX); y = ToUnit(shaped.rightY);
        if (stick::Apply(x, y, g_rightStick)) { shaped.rightX = ToStick(x); shaped.rightY = ToStick(y); }
        else { shaped.rightX = shaped.rightY = 0; }
    }
    std::lock_guard lock(g_touch.lock);
    g_touch.pad = shaped;
    g_touch.connected = true;
}

void android::input::AddTouchPulse(uint16_t bits)
{
    g_touchPulses.Add(bits);
}

void android::input::SetStickSettings(float leftDeadzone, float leftCurve,
                                      float rightDeadzone, float rightCurve)
{
    // Sanitised once, so the log below and the stick above are the same shape.
    const stick::Shape left = stick::Sanitize({ leftDeadzone, leftCurve, 1.0f });
    const stick::Shape right = stick::Sanitize({ rightDeadzone, rightCurve, 1.0f });
    {
        std::lock_guard lock(g_stickLock);
        g_leftStick = left;
        g_rightStick = right;
    }
    LOGI("touch sticks: left deadzone %.2f curve %.2f, right deadzone %.2f curve %.2f",
         left.deadzone, left.exponent, right.deadzone, right.exponent);
}

void android::input::AddLook(float dx, float dy)
{
    std::lock_guard lock(g_lookLock);
    g_lookAccumX += dx;
    g_lookAccumY += dy;
    g_lookActive = true;
    // The first movement of a gesture starts the clock here, so the time the
    // finger spent resting on the screen does not count as slow movement.
    if (g_lookLast.time_since_epoch().count() == 0) g_lookLast = Clock::now();
}

void android::input::ClearLook()
{
    std::lock_guard lock(g_lookLock);
    g_lookAccumX = g_lookAccumY = 0;
    g_lookValueX = g_lookValueY = 0;
    g_lookActive = false;
    g_lookLast = Clock::now();
}

void android::input::SetLookSettings(float sensitivityX, float sensitivityY, float smoothing,
                                     bool invertY, float saturationScale)
{
    std::lock_guard lock(g_lookLock);
    g_sensitivityX = std::clamp(sensitivityX, 0.05f, 10.0f);
    g_sensitivityY = std::clamp(sensitivityY, 0.05f, 10.0f);
    g_smoothing = std::clamp(smoothing, 0.0f, 0.9f);
    g_invertY = invertY;
    // The app's slider is a multiplier. It used to arrive here as if it were a
    // speed in pixels a second and was dropped below 100, so the setting never
    // did anything; look_scale.h is the one place that converts it now.
    g_saturationPixelsPerSecond = look::SaturationPixelsPerSecond(saturationScale);
    if (saturationScale > look::kMaxScale)
        LOGW("input: look saturation %.1f looks like pixels/second, not a %.1f..%.1f multiplier",
             saturationScale, look::kMinScale, look::kMaxScale);
    LOGI("input: look saturation %.2f -> %.0f px/s", saturationScale, g_saturationPixelsPerSecond);
}

void android::input::SetHardwarePad(uint32_t user, const Pad& pad)
{
    if (user >= 4) return;
    std::lock_guard lock(g_hardware[user].lock);
    // State only. Only SetHardwarePadConnected says a pad is there; letting a
    // push imply it made a slot the app had just disconnected come straight
    // back with the all-zero state it was let go with, and the title could then
    // see four controllers where there was one.
    g_hardware[user].pad = pad;
}

void android::input::SetHardwarePadConnected(uint32_t user, bool connected)
{
    if (user >= 4) return;
    std::lock_guard lock(g_hardware[user].lock);
    g_hardware[user].connected = connected;
    if (!connected) g_hardware[user].pad = Pad{};
    LOGI("input: physical pad %u %s", user, connected ? "connected" : "gone");
}

void android::input::SetHardwarePadEnabled(bool enabled)
{
    g_hardwareEnabled.store(enabled, std::memory_order_relaxed);
    LOGI("input: physical pads %s", enabled ? "on" : "off");
}

bool android::input::HardwarePadEnabled() { return g_hardwareEnabled.load(std::memory_order_relaxed); }

bool android::input::Connected(uint32_t user)
{
    if (user >= 4) return false;
    if (user == 0)
    {
        if (HardwarePadEnabled())
        {
            std::lock_guard lock(g_hardware[0].lock);
            if (g_hardware[0].connected) return true;
        }
        return true;   // touch controls stand in if no hardware pad
    }
    if (!HardwarePadEnabled()) return false;
    std::lock_guard lock(g_hardware[user].lock);
    return g_hardware[user].connected;
}

bool android::input::Poll(uint32_t user, Pad& out)
{
    out = Pad{};
    if (user >= 4) return false;

    bool any = false;
    if (user == 0)
    {
        std::lock_guard lock(g_touch.lock);
        if (g_touch.connected) { out = g_touch.pad; any = true; }
    }
    if (HardwarePadEnabled())
    {
        std::lock_guard lock(g_hardware[user].lock);
        if (g_hardware[user].connected)
        {
            if (any) Merge(out, g_hardware[user].pad);
            else     out = g_hardware[user].pad;
            any = true;
        }
    }
    // User 0 always answers: the touch controls stand in for a pad, and a
    // title told "no controller" on user 0 stops at its "reconnect the
    // controller" screen.
    if (user == 0)
    {
        UpdateLook(out);
        // A tap that began and ended between two polls rides on this state,
        // once: what is held is the state, what was tapped is the pulse.
        out.buttons = uint16_t(out.buttons | g_touchPulses.Take());
        any = true;
    }
    return any;
}

void android::input::Rumble(uint32_t user, uint16_t low, uint16_t high)
{
    if (g_rumble) g_rumble(user, low, high);
}

#endif  // PES17_ANDROID

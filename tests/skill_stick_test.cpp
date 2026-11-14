// The skill stick read as a flick (runtime/skill_stick.h): directions taken
// from the travel from where the thumb landed, eight of them, with a real
// neutral between.
//
// It asserts rather than reporting, and includes nothing that needs the guest,
// the disc, a GPU or an Android device. The last section drives the classifier
// and the sequencer together, because the thing that has to be true is a
// property of the pair: "left, then right" has to reach the title as two
// directions with nothing in between, however fast the thumb went.
#include "skill_stick.h"
#include "stick_pulses.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <utility>
#include <vector>

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

    bool Near(float a, float b, float tolerance = 1e-4f)
    {
        return std::fabs(a - b) <= tolerance;
    }

    // A direction's travel, in throws, at a given angle and distance.
    void At(float degrees, float distance, float& dx, float& dy)
    {
        const float radians = degrees / skill::kDegreesPerRadian;
        dx = std::cos(radians) * distance;
        dy = std::sin(radians) * distance;
    }

    std::int16_t Axis(float value)
    {
        value = std::fmax(-1.0f, std::fmin(value, 1.0f));
        return std::int16_t(std::lround(value * (value < 0 ? 32768.0f : 32767.0f)));
    }
}

int main()
{
    const skill::Shape shape{};

    // ---- nothing engaged ----------------------------------------------------

    {
        skill::Flick flick;
        flick.Begin();
        float x = 1.0f, y = 1.0f;
        Check(!flick.Feed(0.0f, 0.0f, x, y, shape), "a thumb that has not moved has no direction");
        Check(x == 0.0f && y == 0.0f, "and says so with an exact zero, not a small one");
        Check(flick.Current() < 0, "nothing is engaged");
    }

    {
        // A resting thumb drifts. Inside the engage radius it is still nothing.
        skill::Flick flick;
        flick.Begin();
        float x = 0.0f, y = 0.0f;
        Check(!flick.Feed(0.2f, 0.0f, x, y, shape), "a wobble short of the engage radius is not a flick");
        Check(flick.Current() < 0, "and engages nothing");
    }

    // ---- taking a direction -------------------------------------------------

    {
        skill::Flick flick;
        flick.Begin();
        float x = 0.0f, y = 0.0f;
        Check(flick.Feed(shape.engage, 0.0f, x, y, shape), "at the engage radius a direction is taken");
        Check(flick.Current() == 0, "travel to the right is direction 0");
        Check(Near(x, shape.magnitude) && Near(y, 0.0f), "and sends it at the configured magnitude");
    }

    {
        skill::Flick flick;
        flick.Begin();
        float x = 0.0f, y = 0.0f;
        flick.Feed(0.0f, 0.5f, x, y, shape);
        Check(flick.Current() == 2, "travel up is direction 2");
        Check(Near(x, 0.0f) && Near(y, shape.magnitude), "and points up exactly");
    }

    {
        skill::Flick flick;
        flick.Begin();
        float x = 0.0f, y = 0.0f;
        flick.Feed(0.5f, 0.5f, x, y, shape);
        Check(flick.Current() == 1, "an even diagonal is direction 1");
        const float expected = shape.magnitude * 0.70710678f;
        Check(Near(x, expected, 1e-3f) && Near(y, expected, 1e-3f),
              "and is a true 45 degrees at the configured magnitude");
    }

    {
        // Held a long way from the socket's centre, a direction is still one
        // direction: the gesture is the travel, not the position.
        skill::Flick flick;
        flick.Begin();
        float x = 0.0f, y = 0.0f;
        flick.Feed(0.0f, 3.0f, x, y, shape);
        Check(flick.Current() == 2, "a long flick is the same direction");
        Check(Near(x, 0.0f) && Near(y, shape.magnitude), "and the same magnitude");
    }

    // ---- the neutral between two flicks -------------------------------------

    {
        // Engaged, then back inside the release radius: exactly zero, which is
        // the state a title needs to see between "left" and "right".
        skill::Flick flick;
        flick.Begin();
        float x = 0.0f, y = 0.0f;
        flick.Feed(-0.5f, 0.0f, x, y, shape);
        Check(flick.Current() == 4, "travel to the left is direction 4");

        float rx = 1.0f, ry = 1.0f;
        Check(flick.Feed(-0.10f, 0.0f, rx, ry, shape), "coming back inside release lets go");
        Check(flick.Current() < 0, "and nothing is engaged");
        Check(rx == 0.0f && ry == 0.0f, "the release is an exact zero");
    }

    {
        // Between engage and release the last answer stands: a thumb resting on
        // the boundary must not chatter between a direction and nothing.
        skill::Flick flick;
        flick.Begin();
        float x = 0.0f, y = 0.0f;
        flick.Feed(0.30f, 0.0f, x, y, shape);
        Check(flick.Current() == 0, "engaged");
        flick.Feed(0.18f, 0.0f, x, y, shape);
        Check(flick.Current() == 0, "drifting inside the engage radius keeps the direction");
        Check(Near(x, shape.magnitude), "and keeps sending it, not a faded one");
        flick.Feed(0.15f, 0.0f, x, y, shape);
        Check(flick.Current() < 0, "crossing the release radius lets go");
    }

    // ---- not re-firing while it is held ------------------------------------

    {
        // A thumb that wobbles over a sector boundary is one direction, not two.
        skill::Flick flick;
        flick.Begin();
        float x = 0.0f, y = 0.0f;
        flick.Feed(0.30f, 0.0f, x, y, shape);                 // east
        bool changed = flick.Feed(0.30f * std::cos(0.35f), 0.30f * std::sin(0.35f), x, y, shape);
        Check(!changed && flick.Current() == 0, "twenty degrees off east is still east");
        changed = flick.Feed(0.30f * std::cos(0.70f), 0.30f * std::sin(0.70f), x, y, shape);
        Check(changed && flick.Current() == 1, "forty degrees off east is the next direction");
    }

    {
        // And having changed, it does not fall back at the halfway point.
        skill::Flick flick;
        flick.Begin();
        float x = 0.0f, y = 0.0f;
        flick.Feed(0.30f, 0.0f, x, y, shape);
        flick.Feed(0.30f * std::cos(0.70f), 0.30f * std::sin(0.70f), x, y, shape);
        Check(flick.Current() == 1, "moved to direction 1");
        flick.Feed(0.30f * std::cos(0.40f), 0.30f * std::sin(0.40f), x, y, shape);
        Check(flick.Current() == 1, "and stays there rather than flickering back");
    }

    // ---- the lift ------------------------------------------------------------

    {
        skill::Flick flick;
        flick.Begin();
        float x = 0.0f, y = 0.0f;
        flick.Feed(0.5f, 0.0f, x, y, shape);
        flick.End();
        Check(!flick.Engaged(), "lifting the thumb engages nothing");
        float ex = 9.0f, ey = 9.0f;
        flick.Feed(0.0f, 0.0f, ex, ey, shape);
        Check(ex == 0.0f && ey == 0.0f, "and the next gesture starts from nothing");
    }

    // ---- the whole circle ----------------------------------------------------

    {
        // Every angle lands in a direction, keeps its magnitude, and no angle
        // produces a NaN -- which would poison the pad.
        bool sane = true;
        bool complete = true;
        bool seen[skill::kDirections] = {};
        for (int i = 0; i < 720; i++)
        {
            const float degrees = float(i) * 0.5f;
            float dx = 0.0f, dy = 0.0f;
            At(degrees, 0.6f, dx, dy);

            skill::Flick flick;
            flick.Begin();
            float x = 0.0f, y = 0.0f;
            flick.Feed(dx, dy, x, y, shape);

            const int sector = flick.Current();
            if (sector < 0 || sector >= skill::kDirections) { complete = false; continue; }
            seen[sector] = true;
            if (std::isnan(x) || std::isnan(y) || std::isinf(x) || std::isinf(y)) sane = false;
            if (!Near(std::sqrt(x * x + y * y), shape.magnitude, 2e-3f)) sane = false;

            // And the direction it answered with is the one nearest the travel.
            float cx = 0.0f, cy = 0.0f;
            skill::Direction(sector, cx, cy);
            const float cosine = std::clamp((cx * dx + cy * dy) / 0.6f, -1.0f, 1.0f);
            if (std::acos(cosine) * skill::kDegreesPerRadian > 22.51f) complete = false;
        }
        Check(sane, "every angle sends the configured magnitude, and no NaN");
        Check(complete, "every angle lands in the direction nearest it, within half a sector");
        bool all = true;
        for (bool s : seen) all = all && s;
        Check(all, "all eight directions are reachable");
    }

    {
        // A tie sits exactly on a boundary; it has to answer the same way on
        // every run rather than depending on rounding noise.
        const bool right = skill::SectorOf(0.0f, 0.0f) == 0;
        Check(right, "a zero vector answers a direction rather than trapping");
        const int up = skill::SectorOf(0.0f, 1.0f);
        const int left = skill::SectorOf(-1.0f, 0.0f);
        const int down = skill::SectorOf(0.0f, -1.0f);
        Check(up == 2 && left == 4 && down == 6, "the four axes are 2, 4 and 6");
    }

    // ---- what it refuses to be asked for -----------------------------------

    {
        const skill::Shape absurd = skill::Sanitize({ 5.0f, 5.0f, -3.0f, 9.0f });
        Check(absurd.engage <= 0.90f, "an absurd engage radius is pulled in");
        Check(absurd.release < absurd.engage, "and release stays below engage");
        Check(absurd.relock >= 5.0f, "an absurd relock is pulled in");
        Check(absurd.magnitude <= 1.0f, "and the magnitude cannot exceed full deflection");
    }

    {
        // A shape whose release is at or above its engage would chatter: every
        // flick would be taken and dropped on the same frame.
        const skill::Shape tight = skill::Sanitize({ 0.30f, 0.30f, 30.0f, 0.85f });
        Check(tight.release < tight.engage, "release is forced below engage");
    }

    // ---- the classifier and the sequencer together --------------------------

    {
        // Double Touch, done the way a thumb can do it on glass: flick left,
        // lift, flick right. What the title must see is left, a frame of
        // nothing, then right -- never the sweep in between.
        input::StickPulses pulses;
        skill::Flick flick;

        std::vector<std::pair<int, int>> frames;   // as int16, as the pad carries them
        auto sample = [&] {
            std::int16_t x = 0, y = 0;
            const bool active = pulses.Sample(x, y);
            frames.emplace_back(active ? int(x) : 0, active ? int(y) : 0);
        };

        flick.Begin();
        float x = 0.0f, y = 0.0f;
        flick.Feed(-0.5f, 0.0f, x, y, shape);          // left
        // Taken from what the classifier answered, so the expectation is the
        // pad's own integer scale rather than a second rounding of the same
        // number.
        const int west = Axis(x);
        pulses.Set(Axis(x), Axis(y));
        flick.End();
        pulses.Set(0, 0);
        for (int i = 0; i < 5; i++) sample();
        const size_t firstEnd = frames.size();

        flick.Begin();
        flick.Feed(0.5f, 0.0f, x, y, shape);           // right
        const int east = Axis(x);
        pulses.Set(Axis(x), Axis(y));
        flick.End();
        pulses.Set(0, 0);
        for (int i = 0; i < 5; i++) sample();

        bool leftSeen = false, gapSeen = false, rightSeen = false, ordered = true;
        for (size_t i = 0; i < frames.size(); i++)
        {
            const bool isLeft = frames[i].first == west && frames[i].second == 0;
            const bool isNothing = frames[i].first == 0 && frames[i].second == 0;
            const bool isRight = frames[i].first == east && frames[i].second == 0;
            if (isLeft) { leftSeen = true; if (rightSeen) ordered = false; }
            if (isNothing && leftSeen && !rightSeen) gapSeen = true;
            if (isRight) { rightSeen = true; }
        }
        Check(leftSeen, "the first flick reaches the title");
        Check(ordered, "left comes before right");
        Check(gapSeen, "and there is a frame of nothing between them");
        Check(rightSeen, "the second flick reaches the title");
        Check(firstEnd >= 2, "the first direction was held for its dwell");

        // And the sequence lets go of the stick once it has drained.
        bool still = true;
        for (int i = 0; i < 5; i++)
        {
            std::int16_t sx = 1, sy = 1;
            if (pulses.Sample(sx, sy)) still = false;
        }
        Check(still, "and the sequencer goes quiet rather than holding the last direction");
    }

    {
        // The guard runtime/android/input.cpp puts in front of the classifier:
        // while a played-back move is running, travel is not read at all, so a
        // thumb landing on the glass cannot cancel the move.
        input::StickPulses pulses;
        skill::Flick flick;
        const std::int16_t up = Axis(shape.magnitude);
        const input::StickPulses::Step move[] = { { 0, up, 4 } };
        pulses.Play(move, 1);

        auto liveTravel = [&](float dx, float dy) {
            if (pulses.Playing()) { flick.End(); return; }
            float x = 0.0f, y = 0.0f;
            if (flick.Feed(dx, dy, x, y, shape)) pulses.Set(Axis(x), Axis(y));
        };

        std::vector<std::int16_t> sent;
        flick.Begin();
        for (int i = 0; i < 6; i++)
        {
            // A thumb lands mid-move and drags hard to the left.
            liveTravel(-0.9f, 0.0f);
            std::int16_t sx = 0, sy = 0;
            pulses.Sample(sx, sy);
            sent.push_back(sy);
        }
        Check(sent[0] == up && sent[1] == up && sent[2] == up && sent[3] == up,
              "a drag across the stick does not cancel a playing move");
        Check(!pulses.Playing(), "and the move ran to its end");
    }

    {
        // A direction held by the thumb keeps being sent: "hold the stick back".
        input::StickPulses pulses;
        const std::int16_t back = -20000;
        pulses.Set(0, back);
        bool held = true;
        for (int i = 0; i < 120; i++)
        {
            std::int16_t x = 0, y = 0;
            if (!pulses.Sample(x, y) || x != 0 || y != back) held = false;
        }
        Check(held, "a held direction is still being sent two seconds later");
    }

    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

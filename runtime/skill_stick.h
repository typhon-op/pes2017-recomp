#pragma once
// Reading the skill stick as a flick rather than as a position.
//
// A physical right stick springs back to the middle, so "neutral" is a place
// the thumb can always return to, and a move described as "left, then right" is
// two deflections with a rest between them. A touchscreen stick has no spring:
// the finger's offset from the socket's centre is the deflection, the centre is
// a point on a path rather than a state, and a thumb that tries to come back to
// it traces every direction in between.
//
// The title recognises moves from those edges. Fed a sweep, "left then right"
// becomes one continuous rotation -- a Double Touch resolves as a Roulette, or
// as nothing at all. That is the whole of why multi-part feints feel broken on
// glass, and it cannot be fixed by tuning a deadzone.
//
// So on touch the right stick is measured differently. The origin is where the
// thumb *landed*, not the socket's centre: the gesture is the travel from
// there. "Neutral" then means "has not moved yet", which is a place a thumb can
// actually be, and letting go is the only way to stop a direction.
//
// Three rules:
//
//   * engage and release are separate radii -- it takes a clear push to start a
//     direction and a clear return to stop it, so a thumb resting on the glass
//     cannot chatter between them;
//   * a direction is one of eight, 45 degrees apart, which is what the moves
//     are written in and what a thumb can aim at;
//   * the direction only changes when the travel is more than `relock` degrees
//     away from the one being shown, so the boundary is crossed once and a
//     wobbling thumb does not re-fire it.
//
// Nothing below touches the platform, the guest or the GPU: it is arithmetic on
// a displacement, and tests/ can check all of it.
#include <algorithm>
#include <cmath>

namespace skill
{
    /** Eight directions, 45 degrees apart. */
    inline constexpr int kDirections = 8;

    inline constexpr float kDegreesPerRadian = 57.29577951308232f;
    inline constexpr float kRadiansPerDirection = 0.78539816339744831f;   // pi / 4

    /** The unit vector of a direction: 0 is +X (right), rising anticlockwise. */
    inline void Direction(int sector, float& x, float& y)
    {
        const float angle = float(sector) * kRadiansPerDirection;
        x = std::cos(angle);
        y = std::sin(angle);
    }

    /**
     * The direction a displacement points in, 0..kDirections-1. Exact ties go
     * to the higher index, so the answer is the same on every run.
     */
    inline int SectorOf(float x, float y)
    {
        float degrees = std::atan2(y, x) * kDegreesPerRadian;
        if (degrees < 0.0f) degrees += 360.0f;
        const int sector = int(std::lround(degrees / 45.0f)) % kDirections;
        return sector < 0 ? sector + kDirections : sector;
    }

    struct Shape
    {
        /** How far the thumb travels before a direction is taken, in throws. */
        float engage = 0.28f;
        /** How far it has to come back before the direction is let go. */
        float release = 0.16f;
        /** How far past a boundary before the direction shown changes. */
        float relock = 30.0f;
        /** What an engaged direction sends, as a fraction of full deflection. */
        float magnitude = 0.85f;
    };

    /** What Apply will accept, so a setting cannot ask for a broken stick. */
    inline Shape Sanitize(Shape shape)
    {
        shape.engage = std::clamp(shape.engage, 0.05f, 0.90f);
        const float mostRelease = std::max(shape.engage - 0.01f, 0.0f);
        shape.release = std::clamp(shape.release, 0.0f, mostRelease);
        shape.relock = std::clamp(shape.relock, 5.0f, 89.0f);
        shape.magnitude = std::clamp(shape.magnitude, 0.20f, 1.0f);
        return shape;
    }

    /**
     * One thumb's flick. Begin on touch-down, Feed the travel as it moves, End
     * on the lift; there is no state to keep between gestures.
     *
     * Free of the platform and of the pad's integer scale, so the same code
     * answers for a finger, a macro or a test.
     */
    class Flick
    {
    public:
        /** A thumb landed: the next Feed is measured from the origin. */
        void Begin() { current = -1; }

        /** The thumb lifted: nothing is shown, and the next gesture starts over. */
        void End() { current = -1; }

        /** The direction being shown, or -1 for none. */
        int Current() const { return current; }

        bool Engaged() const { return current >= 0; }

        /**
         * The travel from where the thumb landed, in throws (1.0 is the edge of
         * the socket), with Y pointing up as the guest's does. Writes the stick
         * to send -- a direction at `magnitude`, or exactly zero -- and returns
         * whether the direction changed, which is what a caller has to pass on.
         */
        bool Feed(float dx, float dy, float& x, float& y, const Shape& shape)
        {
            const Shape s = Sanitize(shape);
            const float travel = std::sqrt(dx * dx + dy * dy);
            int next = current;

            if (current < 0)
            {
                // Nothing engaged: it takes a clear push to start.
                if (travel >= s.engage) next = SectorOf(dx, dy);
            }
            else if (!(travel >= s.release))
            {
                // Back inside the release radius: the direction is let go, and
                // exactly, so the title sees a real neutral.
                next = -1;
            }
            else
            {
                // Engaged: only a deliberate move to another direction changes
                // it, so a thumb on a boundary does not re-fire it.
                const int wanted = SectorOf(dx, dy);
                if (wanted != current)
                {
                    float cx = 0.0f, cy = 0.0f;
                    Direction(current, cx, cy);
                    const float cosine =
                        std::clamp((cx * dx + cy * dy) / std::max(travel, 1e-6f), -1.0f, 1.0f);
                    if (std::acos(cosine) * kDegreesPerRadian >= s.relock) next = wanted;
                }
            }

            const bool changed = next != current;
            current = next;
            if (current < 0)
            {
                x = y = 0.0f;
            }
            else
            {
                Direction(current, x, y);
                x *= s.magnitude;
                y *= s.magnitude;
            }
            return changed;
        }

    private:
        int current = -1;
    };
}

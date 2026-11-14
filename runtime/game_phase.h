#pragma once
// What the title is doing, as far as the app can tell: in its menus, or in a
// match.
//
// The app uses it to show the controls that belong to each -- a d-pad over the
// menus, and nothing extra over the pitch. The only signal this build has is
// whether the player entity exists yet (player::NowRunning), and it is a read
// from the guest, so it is sampled rather than asked for every frame.
//
// Set pieces and replays are not distinguishable this way: telling those apart
// needs a hook into the title's own state machine, which is not in this
// repository. They read as a match, and the player keeps the manual control over
// the pad's visibility.
//
// Header-only and free of the guest, so tests/ checks the transitions on a tree
// with no game image.
#include <cstdint>

namespace phase
{
    enum class Phase : std::uint8_t
    {
        Unknown = 0,   // nothing to read: the app keeps its own layout rules
        Menus = 1,
        Match = 2,
    };

    class Tracker
    {
    public:
        // Once every half second: a menu reached from a match takes seconds to
        // arrive, and this read is a dereference of the guest's memory.
        static constexpr double kSampleSeconds = 0.5;

        // Whether a read is due. Call this first, so the guest is only read when
        // the answer can change.
        bool Due(double nowSeconds) const
        {
            return !sampled_ || nowSeconds - sampledAt_ >= kSampleSeconds;
        }

        // `known` is false when the build has no addresses to read, which is not
        // the same as "no player": one keeps the app's own layout, the other
        // says the title is in its menus.
        Phase Observe(bool known, bool playerExists, double nowSeconds)
        {
            if (!Due(nowSeconds)) return current_;
            sampled_ = true;
            sampledAt_ = nowSeconds;
            current_ = !known ? Phase::Unknown
                     : playerExists ? Phase::Match
                     : Phase::Menus;
            return current_;
        }

        Phase Current() const { return current_; }

    private:
        Phase current_ = Phase::Unknown;   // until the first read
        double sampledAt_ = 0.0;
        bool sampled_ = false;
    };
}

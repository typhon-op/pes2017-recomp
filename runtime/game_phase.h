#pragma once
// What the title is doing, as far as the app can tell: in its menus, or in a
// match.
//
// The app uses it to show the controls that belong to each -- a d-pad over the
// menus, and nothing extra over the pitch.
//
// There are two sources, and the first one is the title's own:
//
//   * The procedure switch (runtime/pes_hooks.cpp, sub_82F00CE8) is handed the
//     name of the flow it enters -- "TopMenu/TopMenu", "Match/MatchSetup",
//     "Match/MatchIdle". A name beginning "Match/" is the match flow, and
//     anything else is a menu, which is a direct answer with no address of the
//     title's needed. See NoteProcedure.
//   * Whether the player entity exists (player::NowRunning), a read from the
//     guest. Only reached when the switch has said nothing yet, because the
//     title's client-state chain is still a placeholder in title.h; that read is
//     sampled rather than asked for every frame.
//
// Set pieces and replays are not distinguishable by either: telling those apart
// needs deeper state than the flow name gives. They read as a match, and the
// player keeps the manual control over the pad's visibility.
//
// Header-only and free of the guest, so tests/ checks the transitions on a tree
// with no game image.
#include <atomic>
#include <cstdint>
#include <cstring>

namespace phase
{
    enum class Phase : std::uint8_t
    {
        Unknown = 0,   // nothing to read: the app keeps its own layout rules
        Menus = 1,
        Match = 2,
    };

    namespace detail
    {
        // What the procedure switch last named: 0 nothing yet, 1 menus, 2 match.
        // One word, written by the thread that enters a procedure and read by
        // the input hook's sampler.
        inline std::atomic<int>& FlowWord()
        {
            static std::atomic<int> word{ 0 };
            return word;
        }
    }

    // What the procedure switch last said, or Unknown before it has run.
    inline Phase Observed()
    {
        switch (detail::FlowWord().load(std::memory_order_relaxed))
        {
        case 1: return Phase::Menus;
        case 2: return Phase::Match;
        default: return Phase::Unknown;
        }
    }

    // A procedure the title entered, as its own switch names it. Keeps the
    // answer for Observed() and returns it. An unreadable name (an empty guest
    // string) is not an observation and leaves the answer as it was.
    inline Phase NoteProcedure(const char* name, unsigned length)
    {
        if (!name || !length) return Observed();
        const bool match = length >= 6 && std::memcmp(name, "Match/", 6) == 0;
        detail::FlowWord().store(match ? 2 : 1, std::memory_order_relaxed);
        return match ? Phase::Match : Phase::Menus;
    }

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

#pragma once
// What the game is doing, from the title's own flow names.
//
// The title's procedure switch (runtime/pes_hooks.cpp, sub_82F00CE8) is handed
// the name of each flow it enters. That name is the only game-state source this
// build has that is not a guess: the client-state chain in title.h is still a
// placeholder (T_DATA_ClientStates = 0), and nothing is read out of the guest
// for this. What the names support, and nothing more:
//
//   * "Match/MatchSetup" -- the match flow before the ball is in play: the
//                           teams come out and the match loads.
//   * any other "Match/.." -- the match flow proper: MatchIdle in play, and
//                           MatchPostPause after the kick-off pause.
//   * anything else        -- a menu, including the walk back from a match.
//
// Not visible here, and deliberately not reported: possession (the player's,
// the opponent's or a loose ball), half-time, the final whistle, the
// post-match screens, and the next match's load. Those live in the title's
// match state machine, which this build has no address for; guessing them from
// the flow name or from touch input would be worse than the engine's silence.
// What the app gets is a stable enum, one event per change, and the lifecycle
// pause/resume the app itself causes.
//
// State lives in one word, because the flow is named on the guest thread and
// the pause comes from the app's thread: a reader must never see a state from
// before a change beside an event from after it.
//
// Header-only and free of the guest, so tests/match_state_test.cpp pins the
// transitions on a tree with no game image.
#include <atomic>
#include <cstdint>
#include <cstring>

namespace match
{
    enum class State : std::uint8_t
    {
        Unknown = 0,   // the switch has not named a flow yet
        Menus = 1,
        Loading = 2,   // Match/MatchSetup
        InPlay = 3,    // Match/MatchIdle, Match/MatchPostPause, the rest of Match/
    };

    // What changed. Sent once per change; the app decides what a pause of a
    // loading match, or a menu reached from a match, means for its own UI.
    enum class Event : std::uint8_t
    {
        None = 0,
        EnteredMenus = 1,     // a menu is up, from unknown or from another menu
        LeftMatch = 2,        // a menu is up after Loading or InPlay
        EnteredLoading = 3,
        EnteredInPlay = 4,
        Paused = 5,           // the app paused the title (lifecycle)
        Resumed = 6,
    };

    inline const char* StateName(State state)
    {
        switch (state)
        {
            case State::Menus: return "menus";
            case State::Loading: return "loading";
            case State::InPlay: return "in play";
            default: return "unknown";
        }
    }

    inline const char* EventName(Event event)
    {
        switch (event)
        {
            case Event::EnteredMenus: return "entered menus";
            case Event::LeftMatch: return "left the match";
            case Event::EnteredLoading: return "entered match loading";
            case Event::EnteredInPlay: return "entered play";
            case Event::Paused: return "paused";
            case Event::Resumed: return "resumed";
            default: return "none";
        }
    }

    // What one flow name says, on its own. An empty name is not an observation
    // and answers Unknown, which leaves the state as it was.
    inline State Classify(const char* name, unsigned length)
    {
        if (!name || !length) return State::Unknown;
        constexpr unsigned kFlow = 6;   // "Match/"
        if (length < kFlow || std::memcmp(name, "Match/", kFlow) != 0) return State::Menus;
        constexpr char kSetup[] = "MatchSetup";
        constexpr unsigned kSetupLength = sizeof kSetup - 1;
        if (length >= kFlow + kSetupLength && std::memcmp(name + kFlow, kSetup, kSetupLength) == 0)
            return State::Loading;
        return State::InPlay;
    }

    namespace detail
    {
        // One word: state (2), previous (2), paused (1), last event (3).
        inline std::atomic<std::uint32_t>& Word()
        {
            static std::atomic<std::uint32_t> word{ 0 };
            return word;
        }

        constexpr std::uint32_t kStateShift = 0, kStateMask = 0x3u;
        constexpr std::uint32_t kPrevShift = 2, kPrevMask = 0x3u;
        constexpr std::uint32_t kPausedBit = 1u << 4;
        constexpr std::uint32_t kEventShift = 5, kEventMask = 0x7u;

        inline State StateOf(std::uint32_t word)
        {
            return State((word >> kStateShift) & kStateMask);
        }
        inline State PrevOf(std::uint32_t word)
        {
            return State((word >> kPrevShift) & kPrevMask);
        }
        inline bool PausedOf(std::uint32_t word) { return (word & kPausedBit) != 0; }
        inline Event EventOf(std::uint32_t word)
        {
            return Event((word >> kEventShift) & kEventMask);
        }
        inline std::uint32_t Pack(State state, State previous, bool paused, Event event)
        {
            return (std::uint32_t(std::uint8_t(state)) & kStateMask) << kStateShift |
                   (std::uint32_t(std::uint8_t(previous)) & kPrevMask) << kPrevShift |
                   (paused ? kPausedBit : 0u) |
                   (std::uint32_t(std::uint8_t(event)) & kEventMask) << kEventShift;
        }
    }

    inline State Current() { return detail::StateOf(detail::Word().load(std::memory_order_relaxed)); }
    inline State Previous() { return detail::PrevOf(detail::Word().load(std::memory_order_relaxed)); }
    inline bool Paused() { return detail::PausedOf(detail::Word().load(std::memory_order_relaxed)); }
    inline Event LastEvent() { return detail::EventOf(detail::Word().load(std::memory_order_relaxed)); }

    // Between matches, or when a run starts: nothing has been observed yet, and
    // a stale pause is not carried into the next one.
    inline void Reset()
    {
        detail::Word().store(detail::Pack(State::Unknown, State::Unknown, false, Event::None),
                             std::memory_order_relaxed);
    }

    // A procedure the title entered, as its own switch names it. Returns the
    // change, or None when the name says the same thing as last time or says
    // nothing at all.
    inline Event NoteFlow(const char* name, unsigned length)
    {
        const State said = Classify(name, length);
        if (said == State::Unknown) return Event::None;
        std::atomic<std::uint32_t>& word = detail::Word();
        std::uint32_t old = word.load(std::memory_order_relaxed);
        for (;;)
        {
            const State now = detail::StateOf(old);
            if (now == said) return Event::None;
            const bool paused = detail::PausedOf(old);
            Event event = Event::None;
            switch (said)
            {
                case State::Menus:
                    event = (now == State::Loading || now == State::InPlay) ? Event::LeftMatch
                                                                           : Event::EnteredMenus;
                    break;
                case State::Loading: event = Event::EnteredLoading; break;
                case State::InPlay: event = Event::EnteredInPlay; break;
                default: return Event::None;
            }
            if (word.compare_exchange_weak(old, detail::Pack(said, now, paused, event),
                                           std::memory_order_relaxed))
                return event;
        }
    }

    // The app paused or resumed the title. A paused match stays the match it
    // was: only the flag moves, so a resume puts the app back where it was.
    inline Event NotePaused(bool paused)
    {
        std::atomic<std::uint32_t>& word = detail::Word();
        std::uint32_t old = word.load(std::memory_order_relaxed);
        for (;;)
        {
            if (detail::PausedOf(old) == paused) return Event::None;
            const Event event = paused ? Event::Paused : Event::Resumed;
            const std::uint32_t next = detail::Pack(detail::StateOf(old), detail::PrevOf(old),
                                                    paused, event);
            if (word.compare_exchange_weak(old, next, std::memory_order_relaxed)) return event;
        }
    }
}

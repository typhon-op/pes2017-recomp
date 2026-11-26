// The match state the app is told about (runtime/match_state.h): what one flow
// name says, the change event for each, that a repetition is not a change, that
// a pause keeps the state it paused, and that a reset leaves nothing behind.
#include "match_state.h"

#include <cstdio>
#include <cstring>
#include <string>

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

    using match::Event;
    using match::State;

    match::Event Flow(const char* name)
    {
        return match::NoteFlow(name, unsigned(std::char_traits<char>::length(name)));
    }
}

int main()
{
    // ---- what a name says on its own ------------------------------------
    Check(match::Classify("Match/MatchSetup", 16) == State::Loading, "MatchSetup is the match loading");
    Check(match::Classify("Match/MatchIdle", 15) == State::InPlay, "MatchIdle is in play");
    Check(match::Classify("Match/MatchPostPause", 20) == State::InPlay, "the kick-off pause is in play");
    Check(match::Classify("TopMenu/TopMenu", 15) == State::Menus, "the top menu is the menus");
    Check(match::Classify("Intro/ProcInstall", 17) == State::Menus, "the intro is not a match");
    Check(match::Classify("", 0) == State::Unknown, "no name is not an observation");
    Check(match::Classify(nullptr, 4) == State::Unknown, "a null name is not an observation");
    // The switch hands over a length, not a terminator: the prefix alone decides.
    Check(match::Classify("Match/", 6) == State::InPlay, "the bare flow prefix is a match");
    Check(match::Classify("Match", 5) == State::Menus, "a truncated prefix is not a match");
    Check(match::Classify("Match/MatchSetupX", 17) == State::Loading,
          "MatchSetup is matched by prefix, as the title names its own flows");

    // ---- transitions -----------------------------------------------------
    {
        match::Reset();
        Check(match::Current() == State::Unknown, "nothing is known before the first flow");
        Check(Flow("TopMenu/TopMenu") == Event::EnteredMenus, "the first menu is entered, not left");
        Check(match::Current() == State::Menus, "and it is current");
        Check(Flow("TopMenu/TopMenu") == Event::None, "the same flow again is not a change");
        Check(Flow("Match/MatchSetup") == Event::EnteredLoading, "MatchSetup enters loading");
        Check(match::Previous() == State::Menus, "the state it left is kept");
        Check(Flow("Match/MatchIdle") == Event::EnteredInPlay, "MatchIdle enters play");
        Check(match::Current() == State::InPlay, "in play is current");
        Check(Flow("Match/MatchPostPause") == Event::None, "another in-play flow is no change");
        Check(Flow("TopMenu/TopMenu") == Event::LeftMatch, "a menu after a match is leaving it");
        Check(match::Previous() == State::InPlay, "and the match state is the one left behind");
    }

    // ---- an empty name leaves the answer alone ---------------------------
    {
        match::Reset();
        Flow("Match/MatchIdle");
        Check(match::NoteFlow("", 0) == Event::None, "an empty name is no event");
        Check(match::Current() == State::InPlay, "and does not disturb the state");
        Check(match::NoteFlow(nullptr, 10) == Event::None, "a null name is no event");
        Check(match::Current() == State::InPlay, "and does not disturb the state either");
    }

    // ---- pause and resume ------------------------------------------------
    {
        match::Reset();
        Flow("Match/MatchIdle");
        Check(!match::Paused(), "a fresh run is not paused");
        Check(match::NotePaused(true) == Event::Paused, "pausing is an event");
        Check(match::Paused(), "and the flag is up");
        Check(match::Current() == State::InPlay, "a paused match is still the match");
        Check(match::Previous() == State::Unknown, "the previous flow is untouched by a pause");
        Check(match::NotePaused(true) == Event::None, "pausing twice is not a change");
        Check(match::NotePaused(false) == Event::Resumed, "resuming is an event");
        Check(!match::Paused(), "and the flag is down");
        Check(match::NotePaused(false) == Event::None, "resuming twice is not a change");
        Check(match::Current() == State::InPlay, "the match resumes where it was");
    }

    // ---- a flow change while paused keeps the pause ----------------------
    {
        match::Reset();
        Flow("TopMenu/TopMenu");
        match::NotePaused(true);
        Check(Flow("Match/MatchSetup") == Event::EnteredLoading, "the flow still changes while paused");
        Check(match::Paused(), "without losing the pause");
        Check(match::NotePaused(false) == Event::Resumed, "the resume is still an event");
    }

    // ---- reset -----------------------------------------------------------
    {
        match::Reset();
        Flow("Match/MatchIdle");
        match::NotePaused(true);
        match::Reset();
        Check(match::Current() == State::Unknown, "a reset forgets the state");
        Check(match::Previous() == State::Unknown, "and the state before it");
        Check(!match::Paused(), "and the pause, so the next match does not start paused");
        Check(match::LastEvent() == Event::None, "and the last event");
        Check(Flow("TopMenu/TopMenu") == Event::EnteredMenus, "after a reset a menu is entered again");
    }

    std::fprintf(stdout, "match-state: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures ? 1 : 0;
}

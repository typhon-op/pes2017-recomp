# Game-state signals, native to Android

What the engine can tell the app about the game's state, what it deliberately
does not, and why.

## The source: the title's own flow names

The title enters its screens by procedure, and its own switch (`sub_82F00CE8`,
`runtime/pes_hooks.cpp`) is handed the name of each flow as it runs:

| Name | What it is |
|---|---|
| `Match/MatchSetup` | The match flow before the ball is in play: teams out, match loading. |
| `Match/MatchIdle` | The match, in play. |
| `Match/MatchPostPause` | The match after the kick-off pause: still in play. |
| `TopMenu/TopMenu`, `Intro/ProcInstall`, … | Menus, and the flows around them. |

`runtime/match_state.h` turns those names into a small state machine:

| State | Meaning |
|---|---|
| `Unknown` | The switch has not named a flow yet (a fresh run). |
| `Menus` | A menu is up, including the walk back from a match. |
| `Loading` | `Match/MatchSetup`. |
| `InPlay` | Any other `Match/…` flow. |

Every change raises one event: `EnteredMenus`, `LeftMatch` (a menu reached from
`Loading` or `InPlay`), `EnteredLoading`, `EnteredInPlay`, `Paused`, `Resumed`.
State, the state before it, and the pause flag live in one atomic word, because
the flow arrives on the guest thread and the pause comes from the app's.

Between matches the machine needs no reset -- a new match names `MatchSetup`
again, and the flow never repeats without a change. A new *run* resets it
(`nativeStart`), so nothing stale survives a restart.

## What the app gets

* `NativeListener.onMatchState(state, event)` -- pushed once per change.
* `NativeBridge.nativeMatchState()` and `nativeMatchPaused()` -- the state
  itself, for a listener that missed a push or an activity rebuilt under a
  running game. Both are polled beside the phase in `GameActivity.phasePoll`.
* Kotlin mirrors: `MatchState`, `MatchEvent` (`ControlLayout.kt`).

The older, coarser signal is unchanged: `onGamePhase` / `nativeGamePhase` still
answer menus or a match, and the pad's visibility still follows it. Nothing on
the pad reads `MatchState` yet; it is a signal, not a layout rule.

## Lifecycle

A pause is an event, not a state: `Paused` and `Resumed` leave the match state
where it was, so an interruption does not read as leaving the match and a
resume puts the app back into the match it paused. The app's own pause
(`nativeSetPaused`) is the one source, so the signal cannot disagree with the
engine being stopped.

## What is not signalled, and why

The flow name does not carry these, and the build has no other source for them:

* **Possession** -- the player's, the opponent's, a change of it, or a loose
  ball. That is match state inside the title, not the flow it is running.
* **Half-time, the final whistle, the post-match screens, the next match's
  load.** All of them stay inside the `Match/…` flow.

`runtime/title.h` still holds placeholders for the title's client-state chain
(`T_DATA_ClientStates = 0`), which is where those live. Until that address is
derived from the title itself, guessing them -- from the flow name, from the
clock, or from what the player touched -- would put a wrong answer where the
app currently gets a correct silence. The enumeration has room to grow: adding
a state and an event is a change to one header and one enum on each side.

## Checks

`tests/match_state_test.cpp` (ctest `match-state`) pins the classification, the
event for each transition, that a repeated flow name is not a change, that an
empty name leaves the state alone, that a pause keeps the match it paused, and
that a reset leaves nothing behind. The JVM suite covers the app side's
compilation only; the push itself needs a phone.

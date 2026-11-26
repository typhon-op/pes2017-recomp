# PES 2017 Android — Project Handoff

Last updated: 2026-11-15 (touch-controls overhaul)

## Repository and branch

- Repository: `typhon-op/pes2017-recomp`
- Continuation branch: `freebuff/938b1b2d294d57d7251b2bb3`
- Previous baseline: `29d4c391a6c1348325ea1ca248bab9b2c73198a2`
- Implementation checkpoint: `0ac04d5` (present locally and on the remote branch)
- Later commits on this branch: the fake-shot / stick / sprint corrections and
  the touch-controls documentation (see `git log` on the branch)
- `docs/touch-controls.md` is the reference for every control and all sixteen
  swipe slots.

Nothing was reset, reverted or discarded. The signing key (`PES17_KEYSTORE`,
external, never in the repository) was not touched and no key was generated.
ISO importing and the Bluetooth/USB gamepad path were not modified.

## Implementation status

| Requirement | Status |
|---|---|
| Move-mode floating joystick (socket under the thumb, fixed base, knob relative to it) | Implemented; arithmetic unit-tested; not device-tested |
| Fixed-mode joystick (layout socket, knob inside it, centres on release) | Implemented; same shared arithmetic; not device-tested |
| Sprint from the rim, press 0.92 / release 0.82, R1/RB, rim glow, reset on cancel | Implemented; latch unit-tested; mapping RB = sprint matches the PES 2017 manual; not device-tested |
| Four-direction swipes on all four action buttons (16 independent slots) | Slots and classification unit-tested; every slot now ships with a PES 2017 default (modifier + button or double tap); editor binds all forms; not device-tested |
| Compound swipes: modifier + button, double tap, plain bit | Implemented (`SwipeAction`, `SwipeSequence`, `ComboHold`); timing unit-tested; every cancellation clears them; not device-tested |
| Fake shot, Shoot → drag → Pass, cancelling the shot | Corrected to PES 2017's actual cancel rule (pass pressed while shoot is held); arming unit-tested; **not device-verified** |
| Visible arrows and live direction feedback | Implemented; not device-tested |
| Camera / LOOK region with sensitivity, smoothing, saturation, invert | Input path traced to the right stick; not device-tested; no separate user deadzone (see docs) |
| Layout editor: move, resize, opacity, visibility, toggle, snap, keep-apart, reset, arrangement, per-button swipes | Implemented, plus binding picker, display-name editing, per-stance defending copy, add/remove from the factory palette, 20-step undo/redo and named profiles |
| Visual theme: glass surfaces, accent rims, short press glow/scale, draw-time safeguard, effects off switch | Implemented; pure timing helpers; **not device-profiled** |
| Swipe corridors and collision-free spacing | 64 dp retune + nearest-centre hit testing + corridor checker and editor warning; geometrically unit-tested |
| Failsafe: schema v7 migration, backup, repair-on-load, Restore defaults / Restore backup | Implemented; migration and reset unit-tested |
| Control guide (launcher and pause menu, live bindings) | Implemented; not device-viewed |
| D-Pad auto-hide: four idle seconds in a match, 280 ms fade, instant wake, Settings switch | Implemented; timing unit-tested (`DpadAutoHideTest`); not device-tested |
| Manual attacking/defending pads | Implemented (editor arrangement, Settings and pause-menu switch); automatic switching is **not** implemented and must not be claimed |
| Skill palette and flick skill stick with native capability probes | Previously implemented; palette kept, hidden by default |
| Skill wheel (hold, 8 sectors, native playback) | Implemented; pure geometry unit-tested; not device-tested |
| Stance chip, dual defensive labels, near-thumb switch | Implemented; defaults and clearance unit-tested; not device-tested |
| Dribbling: close control, dummy, 2x RB speed burst, stop and face | Mapped onto existing controls; no new buttons; only the tap cadence is new code |
| Multitouch and cancellation safety | Counted claims, per-pointer gestures, `ACTION_CANCEL`/pause clearing; unchanged and extended for the fake shot |
| Bluetooth/USB controller | Previously implemented; unchanged |

## Corrections made in the verification pass

1. **Fake shot.** The checkpoint sent shoot, released it (a real shot), then a
   pass pulse. PES 2017's own manual lists cancel pass/shot as **B/X/Y then A**:
   pass must go down while the shot's gauge is showing. The gesture now arms
   during the drag, presses and holds pass while shoot is still down, sends
   nothing at the lift, and suppresses the button's swipe bit for that gesture.
   Pure rule in `PadGestures.kt` (`FakeShotGesture`).
2. **Floating stick.** The checkpoint dragged the socket along with the thumb.
   The spec is a fixed base at the touch-down point with the knob moving
   relative to it; the base no longer follows. Shared vector in `TouchMath.kt`
   (`StickVector`).
3. **Sprint.** Was a single 0.92 threshold with the glow from a shaped value.
   Now a latch (`SprintLatch`): press 0.92, release 0.82, on the raw throw, glow
   only while latched, reset from every cancellation path.
4. **Compound swipes and defaults.** A slot can now be a modifier + button
   (LB/LT/RB/RT held before the bit) or a double tap, and all sixteen slots
   ship with the requested PES 2017 default. The modifier leads by 30 ms, the
   pair is held 120 ms, a double tap is 90 ms apart, and every sequence ends
   clear. `releaseEverything` (lift of the next swipe, ACTION_CANCEL, pause,
   window focus loss, editor) clears the combo, so no modifier or trigger can
   stick. The fake shot keeps strict priority over the Shoot swipe towards
   Pass (`SwipePriority`).
5. **Skill wheel, eight moves and the real rainbow.** The palette was five
   hidden buttons; the default is now one wheel control: hold 120 ms, slide to
   one of eight sectors, lift to play, with a quick tap doing nothing. The
   native table grew from five moves to eight (step over, Ronaldo chop,
   elastico appended at 5-7), indices 0 and 4 were made distinct scripts
   (double touch flicks up, body feint down), and the rainbow is the sheet's
   **2x R3** via a new frame-based `skill::ButtonSeq` rather than a stick flick.
   The wheel only chooses an index: the runtime still owns the stick, and the
   left stick is always the player's exit.

## Defending without possession detection

The title does not report possession, so the defending pad
is the player's call. The match phase itself is known now: the title's procedure
switch names each flow it enters, and `Match/...` is a match (see
`runtime/game_phase.h`), which is what the app's `onGamePhase` follows. Possession
is still absent. It is now one tap away: a **stance chip** above the action
diamond (`ControlKind.STANCE`, no bit of its own) flips the pad, writes
`Prefs.defendingPad`, and toasts which pad is out. The four action buttons carry
their defensive names in `sub2` (PRESS / TEAM / SLIDE / GK), and the defending pad
has its own near-thumb LB switch while the far top-left LB belongs to attack.
The labels are presentation only -- the title reads the same bits by possession --
and the slide-tackle button (X per the official Xbox manual, B per one 2017
guide) still needs an in-title check. See `docs/touch-controls.md`.

## Touch-controls overhaul (2026-11-15)

- **Look.** `ControlTheme` gives the pad one palette: near-black glass, a rim
  in each family's accent, a specular arc on the face buttons, and a short
  press light (4 % settle over 90 ms, 140 ms fade). SUBTLE is the default;
  FULL adds an expanding ring; OFF is flat. Frames are only requested inside
  the press window, and the overlay turns its own effects off for the session
  if its draw averages over 6 ms for three samples.
- **Spacing.** Actions are 64 dp and pushed 4 dp outward: diagonals now clear
  by 7.5 dp (was 1-2 dp). Switch moved to `-212, -45`. `PROFILE_CLASSIC`
  restores the old pad exactly. Hit testing is nearest-centre, not list order.
  `GestureCorridor` checks every bound direction for edge room and neighbours;
  the editor reports it.
- **Safety.** Stick and sprint latch still write only their own axes/bits;
  swipes are local to the button; the fake shot now keys off `ControlRole`
  (`SHOOT`) rather than the printed `sub`, so renaming cannot move it.
- **Customization.** Editor "More…": change binding, rename, defending-pad
  copy, add/remove control, undo/redo, profiles (Modern, Classic, named).
- **Failsafe.** `controls.json` is v7; older files are migrated instead of
  deleted; every save rotates `controls.backup.json`; unreadable files are
  copied to `controls.broken.json` and the session starts on defaults;
  Settings has Restore defaults and Restore last backup.
- **Guide.** `ControlGuideActivity` from the launcher and the pause menu, with
  cards built from the player's current layout (wheel sectors, defending
  labels, bound swipes).
- **D-pad auto-hide.** The d-pad now also ships on the match pad at `200, 120`
  and clears the stick by 37 dp and the near LB by 5.2 dp. In a match it fades
  after four idle seconds over 280 ms, wakes instantly on a tap within a
  generous ring (that tap is consumed), refuses to fade while an arm is held,
  and is skipped by hit testing once hidden. Menus never fade it, the editor
  always shows it, and Settings → Controls → Auto-hide D-Pad (default on)
  disables the behaviour. Timing lives in the pure `DpadAutoHide`.

## Tests actually executed

Host C++ checks, compiled directly with `g++ -std=c++20 -I runtime` (the repo's
checks are standalone `main()` programs, so no CMake is needed here):

| Check | Result |
|---|---|
| `dynamic_scale_test` | 50 checks, 0 failures |
| `game_phase_test` | 12 checks, 0 failed |
| `input_merge_test` | 9 checks, 0 failed |
| `input_pulses_test` | 9 checks, 0 failed |
| `log_repeat_test` | 19 checks, 0 failures |
| `look_scale_test` | 7 checks, 0 failed |
| `skill_moves_test` | 79 checks, 0 failed (fifteen moves, distinct feints, R3 rainbow, button sequencer, exit hints and ability gates) |
| `skill_stick_test` | 47 checks, 0 failed |
| `stick_arrows_test` | 15 checks, 0 failed |
| `stick_curve_test` | 25 checks, 0 failed |
| `stick_pulses_test` | 62 checks, 0 failed |
| `texture_volume_test` | 196 checks, 0 failures |
| `texture_store_test` | all checks passed |
| `version_test` | 97 checks passed |

That is the previous 281 checks plus 196 + 97 and the texture store, with
`skill_moves_test` re-run at 40 checks after the wheel work. Not run:
`bc_decoder_test` (needs Vulkan headers, absent here), `durable_test` (pulls in
`ppc_recomp_shared.h`, which only the recompiled guest build generates), and
every Kotlin/JUnit test, including the new `PadGesturesTest`,
`StickVectorTest`, `SwipeBindingsTest`, `SkillWheelTest`, `DefensiveLayoutTest`,
`TouchGeometryTest`, `LayoutSerializationTest` and `DpadAutoHideTest`, and the existing
`TouchClaimsTest`, `SwipeClassifierTest`, `TouchMathTest`,
`ControlLayoutPhaseTest` and `GamepadInputTest` -- this workspace has no JDK 17
and no Android SDK, and large downloads were deliberately not started. The two
new test classes compile only by inspection here and have **not been run**.

Static checks done: every modified Kotlin file balances its delimiters; every
`res/**/*.xml` parses; `R.string`/`R.id` references resolve.

## Not verified — needs a device or CI

1. `android/gradlew :app:assembleRelease` (JDK 17 + Android SDK) and
   `android/gradlew :app:testDebugUnitTest`.
2. Fake shot on a real match: shot swing cancels, no shot and no pass leave the
   player, ordinary shoot and pass taps unchanged.
3. Both stick modes on 16:9 and 20:9 screens; base does not move during a push;
   simultaneous stick and buttons; lift/cancel resets.
4. Sprint at 0.92 on, 0.85 held on, 0.82 off; no stuck R1 after pause or focus
   loss; physical pad sprint unaffected.
5. Swipes: confirm each of the sixteen default combinations (modifier lead,
   trigger axis, low cross double tap), each arrow, and that a plain tap is
   unchanged.
6. The compound timing on a real title poll: 30 ms modifier lead, 120 ms hold,
   90 ms double-tap gap; adjust `SwipeSequence` from what the match does.
7. The wheel on a real thumb: 120 ms hold opens it reliably without opening on
   ordinary presses, 25% deadzone is comfortable, and each of the eight moves
   is recognised by the title. Adjust `TrickWheel` and `skill_moves.h` from
   what the match does, not from the sheet.
8. Close control with RT held while steering, the RB dummy with the stick
   centred, and the 2x RB speed burst (two taps must read as two presses).
9. Camera region turns the view and does not steal touches.
10. The defending pad appears from Settings and from the pause menu.

## Suggested next steps

1. Run the two Gradle commands on CI (it has the SDK and the signing secret) and
   fix any compile findings; the Kotlin here was written without a compiler.
2. Device-test items 2-10 above and record the results here.
3. Tune the compound and wheel timings if the title misses a modifier, a double
   tap or a move; they live in two places (`SwipeSequence`/`TrickWheel` in
   `PadGestures.kt`, and the frame counts in `runtime/skill_moves.h`).

## tfix4 (2026-11-24)

### Engine

`libpes17.so` was taken from the engine APK at
`https://files.catbox.moe/wg3gha.apk` and unpacked into
`libpes17_prebuilt/arm64-v8a/` (git-ignored, `-PprebuiltNative` layout). Its
SHA-256 is `1133fa0b...8eb69`, exactly `ENGINE_SO_SHA256` in
`tools/engine_pin.sh`, and the APK's own hash is `f06290eb...66c02`, exactly
`ENGINE_APK_SHA256`: the catbox file and the pinned `V0.2.4` `pes17native.apk`
are the same build, so no pin change was needed. The planned
`pes2017native.apk` release will need the five values in `engine_pin.sh`
updated when it is published.

### Auto MATCH/MENU switch in the title's in-match menu

The engine keeps naming the flow `Match/...` while Game Plan, Substitutions or
the START pause sheet is open, so the phase cannot tell that a menu is up.
`TouchOverlayView.noteStartPressed()` now follows the START key: a press in a
running match latches the title's in-match menu and puts the switch on MENU; the
next START (the title's own resume) releases it. The latch is cleared on a
phase change and is kept across the app's own pause menu, because carrying on
from it returns to the title's menu. Both the on-screen START and a pad's Start
(`GameActivity.dispatchKeyEvent`) feed it; a START bound to a swipe or sent by a
macro is not followed. `StickSwitch.stage`/`key` take the new source, and
`StickSwitchTest` covers it. Edge case: resuming the title's menu with the
"Resume Match" row or a Back/Circle press instead of START leaves the switch on
MENU until the next START or phase change; the app cannot see that resume from
the engine.

### Dark Mode for the wrapper

`Prefs.darkMode` (SharedPreferences, default off) is read by `WrapperTheme`,
which sets `Theme.PES17` or `Theme.PES17.Dark` before a wrapper activity inflates
its layout. The neutral palette moved to theme attributes (`attrs.xml`:
`pesPage`, `pesPanel`, `pesPanelSurface`, `pesCard`, `pesCardBorder`,
`pesDivider`, `pesRowTop`, `pesRowBottom`, `pesText`, `pesMuted`,
`pesAccentText`, `pesInfoText`, `pesTicker`, `pesTickerText`, `pesScrimStart`),
used by layouts, styles and the four shared drawables, so both modes share one
layout. The launcher entry sits under PATCHES; pressing it saves and recreates
the launcher. Settings, Graphics & performance, Controls & sound, Advanced, Game
Files, Patches, Graphics driver, Captures, Install, Install patch, Game files
install, the file browser and their dialogs all take the same theme; the
in-game pause overlay (`PesMenu`) and the pad editor keep their own dark match
surfaces and were not changed. The launcher artwork is faded in Dark Mode
because it is drawn on a white sheet, and the footer strip becomes a dark band
with silver text instead of the light blue one. Light Mode's values are the
originals.

### Verification

Built with the extracted engine (`:app:assembleDebug -PprebuiltNative=...`) and
`StickSwitchTest` run on the JVM. No device was available: the title's menu
transition, the dark/light look on a phone and dialogs at large font sizes were
not viewed on a device.

## tfix5 (2026-11-24): the 53 skill mappings

### Engine in the linked APK

The APK at `https://files.catbox.moe/pwdg20.apk` (58,671,890 bytes) was
downloaded and unpacked. Its `lib/arm64-v8a/libpes17.so` is SHA-256
`1133fa0b9309b802fbaa8e074b1decde8f5a7912599be409937807ac6478eb69`, byte-identical
to `ENGINE_SO_SHA256` in `tools/engine_pin.sh`. The container's own hash
(`05930d89...d8140`) is not `ENGINE_APK_SHA256`, but the engine inside it is the
pinned one, so the pin was left alone: it names the GitHub release that
`tools/fetch_engine.sh` downloads, and this branch already packages exactly that
engine. The supplied `default.xex` is 32,493,568 bytes, SHA-256
`b914ec9a...779e6`.

### What was verified

The 53 proposed mappings were checked against PES 2017's own command list (the
skill tutorials transcribed in the Steam guide *Tutorial - All skills*), the
official Xbox 360 manual's control scheme, and the PES 2016 skill list where the
2017 list is silent. `docs/skill-moves.md` is the result: a 53-row verification
table with a verdict per row, the timings as macro timelines, the ability and
context gates, the corrections, and the removed names with reasons.

Result after the executable pass: 24 correct, 11 partly, 6 duplicates
(identical right-stick half), 8 wrong (input, step count, control name or
category), 4 not in 2017 / not in this build. The main
errors in the proposal were systematic: *Scissors Feint* and *Cross Over Turn*
are abilities/later-game names, not 2017 commands; *Hold RS 360* is a rotation,
not a hold; the sombrero/juggling family is **R3** (stick click), not a stick
direction; the Ronaldo Chop is one RS input, not two; and several names (In and
Out, Out and In, Elastico to Diagonal, McGeady Spin, the sombrero variants, the
stationary fake shot, Riquelme's nutmeg) are the same press as another name with
a different left stick.

### Primary source: the executable itself

The first pass leaned on the 2017 tutorial-list transcript for both names and
inputs. That is now backed by the title's own binary: the supplied
`default.xex` was decompressed (XEX2, retail AES, LZX normal compression) to a
34,471,936-byte image with the vendored XenonRecomp LZX path and searched for
its internal identifiers. That recovered the `FEINT_KIND_*` enumeration and the
player-skill entry points, and it changed seven verdicts:

- **Real, not absent:** High Speed Scissors (`SCISSORS_OUT_HIGHSPEED` /
  `_LOOP_HIGHSPEED`), Scissors/Reverse Scissors (the `SCISSORS_OUT/IN` family
  that the English list calls Step Over / Reverse Step Over), and Draw and Open
  (`PLAY_DATA_DRAW_OPEN`, `FEINT_KIND_DRAWOPENCLOSE`).
- **Absent from this build:** Cross Over Turn (no `CROSSOVER` kind; a 2019
  skill), Flick Up (`FLICK_UP`), Drag Back Dummy (no `DUMMY`/`DRAG_BACK` kind),
  and Scoop Turn (only the goalkeeper `ANIME_KP_SCOOP_OUT`).
- **Reclassified:** "Dummy Through the Legs / Dummy Burst" is a conflation —
  `FEINT_KIND_BURST` is the RB ×2 speed burst, and "through the legs" is the
  pre-reception RB hold. "Dummy Fake Shot" is the shot cancel
  (`FEINT_KIND_KICKCANCEL`), i.e. the Fancy Fake Shot.

The executable proves existence, not input order or timing; those still come
from the command list and remain provisional. The full internal-name-to-row
table is in `docs/skill-moves.md`.

### Metadata in the log and the editor

`Move::exitHint` and `Move::ability` are now used rather than inert:
`PlaySkill` writes both to the runtime log (the left-stick half the player owes
and the ability gate, on the `touch:` line), and
`ControlEditPanel.pickSkillMove` shows them beside the move name. `SkillMoves.kt` mirrors the two arrays for the picker; the C++
table stays authoritative and the host test pins its values.

### What changed in code

- `runtime/skill_moves.h`: the catalogue grew from 8 to 15 entries (appended,
  never reordered: a saved layout stores indices). New: inside feint (RS left,
  covering inside bounce / drag and close / draw and close / L-feint / Scotch),
  hocus pocus, reverse elastico, reverse step over, multiple step overs,
  sombrero (R3 hold) and nutmeg (RB twice). Each entry gained `exitHint` and
  `ability`. The drag back was corrected from a 10-frame hold with no exit to a
  flick with the LS left that the title actually asks for.
- `SkillMoves.kt`: COUNT 15, the names/short labels and the exit flags.
- `tests/skill_moves_test.cpp`: 79 checks, 0 failed, run on the host
  (`g++ -std=c++20 -I runtime`), covering each verified direction, continuity
  of the circles, the sombrero hold, the nutmeg double press, the exit hints and
  ability gates, and the lockout.
- `SkillWheelTest`: updated to fifteen named moves and the new exit flags (JVM
  not available here, so it was not executed).
- `docs/touch-controls.md`: the wheel section points at the verification table
  and the new counts.

### Not verified

No macro was played inside the title: this workspace has no Android device and
no Vulkan runtime, so the timings are provisional. The device checklist at the
end of `docs/skill-moves.md` is what turns each entry into "working"; nothing
here should be described as fully functional before that run.

## tfix5 (2026-11-26): the analogue RS, a derived wheel, the preview page, pacing at every limit

The RS d-pad this branch carried is **removed**: the control, its `RsDpad`
class, its test, its default placement and its editor/serialisation hooks are
gone. A layout saved while it existed still loads -- the reader skips
`"id": "rs_dpad"` and any d-pad marked `"axes": true` -- so nobody's saved pad
is reset by the removal.

### The RS stick itself

The right stick had been routed only through `nativeSkillPulses` whenever
`flickSkillStick` was on (the default): `pushState` skipped its analogue axes
while a flick was being classified (`flicked`), so the title never saw a held
right-stick deflection, only short pulses. The stick is now always analogue --
`stickPositions[id]` is written to `rightX`/`rightY` for every right-stick
element, every frame, while the flick classifier rides along and still plays
its pulses. Left-stick arithmetic is untouched (`LeftStickFeel`, the sprint
latch and the two-stage throw all still run exactly as before), and nothing
presses a button on the RS path. Per-pointer claims already made LS and RS
independent; releasing one stick writes only that stick's axes, and
`ACTION_CANCEL`, pause, focus loss and the editor clear both.

### The wheel that vanished while rolling

The ring used a stored `open` flag set by a posted runnable. Anything that
removed that callback -- a second thumb landing, a redraw, a cancelled
message -- could leave the ring gone while the thumb was still moving through
sectors. Visibility is now **derived** by `WheelGesture.isOpen(now)`: the owner
pointer plus the hold clock, with no timer able to close it. The posted tick is
only a redraw; a second finger cannot steal an open wheel, a non-owner lift
closes nothing, and a cancel clears everything.

### Left stick preview: its own fixed page

The settings page keeps its ScrollView; the embedded 220 dp preview is gone.
A **Preview** button opens `LeftStickPreviewActivity`, a page that does not
scroll, with the stick centred at the largest radius that fits the window. The
drawing is the pad's own: `TouchOverlayView.drawStick`'s body was moved into
`StickArt` and both call it, so socket, notches, cap, dish and grip dots are the
same code at a larger radius. A thumb there goes through `StickVector` and the
selected `LeftStickFeel` profile (deadzone, curve, sensitivity, smoothing) and
the sprint latch, so the live percentage and the walk/run state, and the ring's
on/off points, are the numbers a match would receive. The page never calls
NativeBridge; Back returns to the settings page.

### The repository link

Under "Kick off straight away when the app opens" (`direct_launch`) both main
layouts gained a row with a small GitHub mark and a link to the repository the
Git remote names, `https://github.com/typhon-op/pes2017-recomp`. The whole row
is tappable; a device with no browser gets a toast.

### Frame pacing at all seven limits

`runtime/pacing.h` now schedules every limited rate (30, 35, 40, 45, 50, 55,
and 60 stays on the display's blank), not 30 alone: an exact frame timeline
(`origin + n / fps`, no 33.333 or 28.571 ms rounding), placed on the display's
measured boundary, with the one-/two-refresh alternation for limits that do not
divide the panel. `frame_rate.h` and the Vulkan presenter use that timeline;
the queue and in-order rules key off `Limited()` rather than a 30 comparison.
The whole frame-rate list stays available in both menus, the setting lives in
`Prefs` across restarts, and the in-game Apply & restart re-reads it. The
Settings text no longer says 35-55 judder; Smooth is no longer "Smooth 30 FPS".

### Engine provenance, and what is honestly in the APK

- Source APK (supplied):
  `https://www.dropbox.com/scl/fi/7ltef4cuuppbfvoe8nbjzn/pes2017-recomp-tfix5-adrenotools.apk?rlkey=bafohx1mfjn33u3f18ghdosfu&st=9xqauz92&dl=0`
  -- downloaded via `dl.dropboxusercontent.com`, 52,153,211 bytes, SHA-256
  `2d2a005690a26a39726066f9ed7d18b07a9cc3933cb85edde00157c5155c92c6`.
- ABI: `arm64-v8a` only.
- Extracted engine: `/tmp/srcapk/lib/arm64-v8a/libpes17.so`, 139,430,000
  bytes, SHA-256 `480d50af78bb3228ee8c02e10e27c45f496e63610c42f81f3e6a63a4e6913327`.
  It is **not** the `1133fa0b...` engine `tools/engine_pin.sh` names: this APK
  was built from a newer tree, and its strings already carry
  `pacing: mode %s, strategy %s, limit %u...`.
- Rebuild: **not possible in this workspace**. `libpes17.so` is the recompiled
  guest (`ppc/`, `ppc_recomp_shared.h`, ~1,600 generated units, `build.sh`),
  and neither the generated tree nor the NDK exists here. The engine packaged
  is therefore byte-identical to the extracted one; the native pacing sources
  in this tree were compiled and exercised on the host (below), not into this
  `.so`. No claim is made that the APK's engine contains the new pacing code.
- `default.xex` (supplied): 32,493,568 bytes, SHA-256
  `b914ec9a1abb3a9b42a64255b6aa8ff27312da126f7c18f8c880106b832779e6`, matching
  `BuildConfig.PES_XEX_SHA256`. It is the game data the installer expects; the
  APK does not embed it and this build does not need it.

### Tests actually run

- `g++ -std=c++20 -O1 -I runtime tests/frame_pacing_test.cpp`: **276 checks, 0
  failed**. Covers 30/35/40/45/50/55/60 on 60/59.94/90/120 Hz panels, no drift,
  the one-refresh bound, the alternating gaps for 35/45/55, queue policy,
  strategies and grid divisors.
- `stick_curve_test`: 30 checks, 0 failed. `stick_pulses_test`: 62 checks, 0
  failed -- its one intermittent failure was a single-core scheduling race (the
  reader could finish 20,000 samples before the writer thread first ran); the
  test now waits (bounded) for the writer's first write, and three consecutive
  runs are clean.
- `:app:testDebugUnitTest` (JDK 17, Android SDK 35): **371 tests, 0 failures**.
  Includes the new `WheelGestureTest` (hold boundary, two laps of continuous
  rolling asserted at every sample, direction changes, deadzone, a second
  thumb, owner lift, tap, cancel, re-press, long roll) and the existing suite.
  `RsDpadTest` was deleted with its subject.

### Build

    ./gradlew :app:assembleRelease -PprebuiltNative=/tmp/pes17-engine \
      --no-daemon --max-workers=2 \
      -Dorg.gradle.jvmargs="-Xmx2560m -XX:+UseSerialGC"

Exit 0, `BUILD SUCCESSFUL in 4m 53s`.
`android/app/build/outputs/apk/release/app-release.apk`: **50,181,941 bytes**,
SHA-256 `40364bc2b1dbf49a27f0bcfa73bc0fa27be1fe01ccd5e06a3e6afdceb61fedab`,
debug key, `native-code: 'arm64-v8a'` only.

Verified inside the APK: `lib/arm64-v8a/libpes17.so` hashes to the extracted
engine above (unmodified); `LeftStickPreviewActivity` is in the manifest;
`resources.arsc` carries the new strings (the all-limits pacing hint, "Smooth
(every limit)", "GitHub · typhon-op/pes2017-recomp", the preview readout and
ring strings); `rs_dpad` is no longer referenced.

### Remaining issues and the device gap

- **The packaged engine predates the native pacing work**, for the reason
  above. A full rebuild needs the generated guest tree and the NDK; only then
  can `PES17_TRACE_PACING` be checked in a match. The JVM/UI changes (RS
  path, wheel, preview, GitHub link, all seven limits selectable) are in this
  APK.
- **No Android device or emulator was available.** The RS fix, the wheel under
  continuous rolling, the preview on real screen sizes, and pacing smoothness
  were not run in-game. The APK was not installed. What is verified is source,
  unit tests, host timing tests, the build, and the APK's contents.

### This pass

Commit `dcddc23` ("Fix the analogue RS, the rolling skill wheel, the stick
preview and pace every FPS limit") on `tfix5`; working tree clean after it. The
built APK was uploaded to GoFile: <https://gofile.io/d/sKnLytFB> (50,181,941
bytes, md5 `d280909ff62409980ede33fe1d44a983`, the same file named
`app-release.apk` in `android/app/build/outputs/apk/release/`).

### Engine check: does the packaged `libpes17.so` already pace every limit?

**No.** The supplied engine has the 30-only pacing from `e309841`, not this
tree's all-limits schedule.

How it was checked: the packaged library has no `.symtab`, but it does keep a
725,819-entry `.dynsym`, which exports `frame_rate::PresentInOrder()`
(`0x38506b8`, 252 bytes) and `frame_rate::PaceSwap()` (`0x38f0ff4`, 560 bytes).
Those two functions are the whole decision, so they were decoded from the
`.so` in place (Capstone, ARM64):

- `PresentInOrder`: `cmp w19, #2` / `ccmp w8, #0x1e, #0, ne` / `cset w0, eq`
  -- i.e. `mode != Compatibility && Limit() == 30`. That is exactly the
  pre-`dcddc23` `pacing::PresentInOrder(PacingMode(), Limit() == 30)`. The
  all-limits code asks `Limited()` (`limit != 0 && limit < 60`) instead, so a
  build of this tree contains no `#0x1e` there.
- `PaceSwap`: `sub w8, w19, #0x3c` / `cmn w8, #0x3b` / `b.lo` (return for 0 or
  >= 60), `bl PresentInOrder`, then the in-order branch computing
  `DivisorForPeriod` (float divide, `fcvtas`, `fcmul` of period and limit) and
  `NextBoundary`; every other limit falls through to the independent clock
  `udiv 0x000f4240, limit` x 1000 ns. A build of this tree's `PaceSwap` has
  `FrameDeadline`/`FramesElapsed` (integer division of the frame index by the
  limit) and calls `NextBoundary(..., 1)`.

Consequence: 30 FPS is tied to the display's boundary in the shipped engine;
35/40/45/50/55 keep the free-running `1000/limit` clock and drift against the
panel. The Settings pages and the app-side wiring offer all seven, and the host
test pins the new arithmetic, but the `.so` in the APK is the old one -- a
rebuilt engine is needed for all-limits pacing in a match.

### RS regression test, and a small extraction

The right-stick arithmetic `pushState` and `sendSkill` already did (the sum of
the fingers on the stick, the clamp to -1..1, the flick's capped travel and its
Y sign) now lives in `RightStick.kt`, and `RightStickTest` (6 tests) pins it:
with flick-skill on, a press plus a flick still sends a throw on RS in the
flick's direction; a resting stick sends nothing; two fingers clamp; travel
past the rim keeps the knob on it while the runtime gets the full travel; a new
push starts from zero. Suite: **377 tests, 0 failures** (was 371). This is JVM
coverage of the arithmetic; the View itself still needs a phone.

The APK on GoFile predates this extraction -- behaviour is unchanged, only
moved code -- so that binary is not a build of this exact commit.


### Native engine rebuilt from the resume kit (second pass, 2026-11-26)

The engine in the APK from the previous pass was the one extracted from the
supplied package. This pass rebuilt `libpes17.so` from source and packaged
**that**, so the .so in the APK below is not the supplied binary.

**What was restored.** `pes17-resume-kit-tfix5-adrenotools.tar.zst`
(377,034,221 bytes, SHA-256 `c415832ce00eb054fcc4c0069f19d7949f69064510e2389f4fd59625818610af`,
zstd -t clean; 2,291,824,640 bytes of tar, 15,087 entries) into `_kit/resume-kit`.
It carries the generated `ppc/` tree (1,420 files, 478 MB), the configured
`build-android/` with 1,290 cached guest objects, `pes/default.xex`, the
`third_party/libadrenotools` submodule content, the supplied engine as
`payload/engine/` (SHA-256 `480d50af…`, byte-identical to the .so in the
supplied APK), and `patches/uncommitted.patch`. Nothing tracked was overwritten:
`ppc/`, `build-android/`, `pes/` and `third_party/libadrenotools` were absent
from this checkout and were copied in; the payload copies of `third_party/`
are byte-identical to the tracked trees (`diff -rq`, no output).

**The resume point.** Objects cached from the kit build of 2026-11-26 06:10;
`libpes17.so` itself was not in the kit, so the link and everything after it
was the work of this pass. The kit's `build.ninja` is keyed to
`/workspace/chats/81946bcbd8e89e75f282d199` and `/workspace/homes/81946bcbd8e89e75f282d199`;
`_kit/nsbuild.sh` bind-mounts this checkout at the first path inside a private
user namespace, so every cached command line stays byte-identical.

**Making Ninja trust the cache.** A fresh checkout stamps every file with the
checkout time, which is newer than the cached objects and rebuilds all 1,415
guest units (the first two attempts did exactly that, 1,676 edges). Two
corrections, both mechanical and both verified by `ninja -d explain`:

* every tracked file unchanged between `e18265c` (the last commit before the
  kit was made) and `HEAD` was given the mtime `2026-11-25 18:00Z`, older than
  both object sets;
* the NDK copied to the baked path `/tmp/tc/android-ndk-r28c` (r28c,
  28.2.13676358, clang 19.0.1) was given the same mtime, because its sysroot
  headers are inputs of every guest unit.

Without the second one 1,251 guest units still rebuilt. With both, the build
was 153 edges.

**Two source fixes the checkout was missing**, both from the kit's
`patches/uncommitted.patch` (the kit's engine was built with them applied, so
they are part of the supplied engine's behaviour):

* `runtime/gpu/vulkan/presenter.cpp` used `g.spaceWake` in four places but never
  declared it -- the file did not compile at all on this branch;
* `VkPresentTimesInfoGOOGLE` has `pTimes`, not `pPresentTimes`, so the
  display-timing path did not compile either.

**Builds.** `cmake` 4.4.5 and `ninja` 1.13.2 (pip `--user`), NDK r28c, JDK 17,
SDK 35. `ninja -j3 pes17` on the 4-core/8 GiB container: peak RSS 2,355 MiB,
link 1.5 GB. The engine builds clean.

**What is in the rebuilt engine.** `frame_rate::PresentInOrder()` disassembles
to `cmp w19,#2` / `mov w9,#0x3b` / `sub w8,w8,#1` / `ccmp w8,w9,#2,ne` /
`cset w0,lo` -- `mode != Compatibility && 1 <= limit <= 59`, i.e. the
all-limits schedule. The supplied engine's copy of the same function tests
`ccmp w8,#0x1e` (`limit == 30`) in the same place, so the APK's pacing is new
code, not the package's.

| | bytes | SHA-256 |
|---|---|---|
| rebuilt, unstripped `build-android/libpes17.so` | 459,006,976 | `c38fa6e2b0ae88e9d365bc0d7640240c92ae12c714c7b54ac253137147948dd3` |
| built, stripped `engine-tfix5/arm64-v8a/libpes17.so` | 139,432,888 | `cb1ba5639438d0c98e34780cf41bae2520407534731cf26c97698b0895ba4289` |
| supplied engine (previous APK, kept at `_kit/engine-previous/`) | 139,430,000 | `480d50af78bb3228ee8c02e10e27c45f496e63610c42f81f3e6a63a4e6913327` |

**Game-state signals.** `runtime/match_state.h` turns the title's own flow
names (`Match/MatchSetup`, `Match/MatchIdle`, `Match/MatchPostPause`, anything
else = menus) into `Unknown/Menus/Loading/InPlay` plus one event per change and
a lifecycle pause flag, in one atomic word. Pushed as
`NativeListener.onMatchState(state, event)`, readable as
`nativeMatchState()` / `nativeMatchPaused()`, reset in `nativeStart`, paused
from `nativeSetPaused`. Not signalled, deliberately: possession, loose ball,
half-time, full time, post-match, next match -- none of them are in the flow
name and this build has no address for the title's match state machine
(`T_DATA_ClientStates` is still 0). See `docs/game-state-signals.md`.

**This pass's checks.** `tests/match_state_test.cpp` 43 checks, 0 failures;
`tests/game_phase_test.cpp` 19, 0; `:app:testDebugUnitTest` 377 tests, 0
failures; release APK built; the packaged
`lib/arm64-v8a/libpes17.so` hashes to the stripped engine above and contains
`nativeMatchState`/`nativeMatchPaused` and the new pacing code.

**APK this pass.** `android/app/build/outputs/apk/release/app-release.apk`,
**50,225,395 bytes**, SHA-256
`2380b0aac406ca5fca885ee72a454f1dd07d8cf05341ba80ca80dec12f519397`, arm64-v8a
only, debug key. The previous APK (50,181,941 bytes, `40364bc2…`) is unchanged
in the repository's history and on GoFile; this one supersedes it.

**Still not verified.** No phone or emulator here: the pacing schedule, the
guest-unique parts, the flow names in a real match, and the new callback in
Java were not run. `PES17_TRACE_PACING` has not been seen on a device, and the
game-state enumeration was not confirmed against a live title. The `.so` builds
and its code is present and correct by disassembly, which is not the same as a
match running at 35 FPS.

**Not done.** The APK was not re-uploaded to GoFile (not asked for this pass);
`_kit/` holds the downloads and the extracted kit and is not tracked.

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

## tfix5 (2026-11-25): the configurable RS d-pad

`rs_dpad` is a second d-pad in the touch layout that states the right stick's
axes instead of the four d-pad bits, so the title's right-stick commands are a
thumb away while the left thumb keeps the analogue movement. It is
`ControlKind.DPAD` with `rightStickAxes`, so the geometry, the hit test, the
editor, the pointer claims and `controls.json` are the four-way d-pad's; the
file gains one optional key, `"axes": true`. The factory place is `480, 290` at
84 dp on the 800 x 360 design pad, clear of every control on screen by default.

Eight equal 45-degree wedges with a 30 % resting zone, read from the thumb's
offset in the socket and tracked per Android pointer id, so a slide changes
direction without lifting. The axes are written after the app's stick shaping,
so a deadzone or curve cannot bend them. **The Y sign is the guest's** (up is
+1, per `StickVector`/`StickAxesTest`); the table the branch was specified with
was screen-signed, and that single flip lives in `RsDpad.Direction`, with
`RsDpadTest.upIsUpAndDownIsDown` pinning it. Every release path -- the owner's
lift, `ACTION_CANCEL`, a phase or stance change, the editor, the pause menu,
focus loss, `releaseEverything` -- returns the axes to (0, 0), and a hidden or
off control is skipped by `pushState`; no other source is reset.

### Tests actually run

The cloud workspace was given JDK 17 (Temurin) and Android SDK 35 (platform
35, build-tools 35.0.0) for this pass, so the Kotlin tests ran for the first
time on this branch. `RsDpadTest` (new), `TouchGeometryTest` -- which caught the
first default position overlapping the d-pad --, `ControlLayoutPhaseTest` and
`LayoutSerializationTest` pass. `:app:testDebugUnitTest` is 355 tests, **all
passing**. The suite's one pre-existing failure
(`SkillWheelTest.anUnboundSectorPlaysNothingAndABadIndexIsRefused`) was a real
bug rather than a stale test: `ControlElement.trickMove`/`setTrickMove` bounded
a sector by the move array's length (`SkillMoves.COUNT`) instead of the wheel's
eight sectors, so an off-wheel index read back a bound move. The bound is now
`TrickWheel.SECTORS`, and the test passes unchanged.

### Build

`:app:assembleRelease -PprebuiltNative=...` with
`-Dorg.gradle.jvmargs="-Xmx1024m -XX:+UseSerialGC" --max-workers=1 --no-daemon`
and `-x lint -x lintVitalRelease -x test`, exit 0;
`android/app/build/outputs/apk/release/app-release.apk` (about 48 MiB, debug
key). The engine packaged is the pinned `libpes17.so`
(`1133fa0b...8eb69`), taken from the tfix5 engine APK at
`https://files.catbox.moe/pwdg20.apk`; the pinned GitHub V0.2.4 asset now
returns 404 and the gofile link needs its site token, so neither could be used
here. The APK's dex carries `rs_dpad` and `axes`.

### On the phone: what to try

1. **Placement.** Edit controls: `RS` draws eight spokes and an `RS` middle.
   Move it, resize it, switch it off and on, then restart the app: it comes
   back where it was left.
2. **Both thumbs.** Hold the left stick at a diagonal and press `RS` up with a
   second finger. The player keeps moving and the right-stick command plays;
   lifting `RS` leaves the movement untouched, and lifting the stick never
   leaves the right stick deflected.
3. **The sign, first.** In a match, press `RS` up: the title must do the
   *up* right-stick command. If it plays the down one, the guest axis is the
   other way round on that build and `RsDpad.Direction`'s `y` needs its sign
   back.
4. **All eight.** Up, down, left, right and the four diagonals in a skill
   context (e.g. the knock-on, the roulette, the chop). A diagonal must be the
   diagonal, not one of its sides.
5. **Sliding.** Hold `RS` and slide up → up-right → right without lifting. Each
   change is immediate, nothing has to be re-pressed, and no old direction
   stays behind.
6. **Release.** Lift the finger: the stick returns to neutral with no spin, no
   flick left running. Do it while the left thumb is still moving.
7. **Cancellation.** Hold a direction, then open the pause menu, drop the
   notification shade, or switch apps. Coming back, nothing is stuck and the
   left stick still steers.
8. **Hidden.** In the editor, switch `RS` off while it is being held: the right
   stick releases rather than staying deflected.

### Not verified

No device or emulator was available: the control was not drawn on a screen, no
right-stick command was played in the title, and position/size/visibility
persistence was checked by the layout tests and the editor's shared path, not
by restarting the app on a phone. The Y flip is reasoned from the port's own
axis convention and pinned by a test, not observed on hardware.

## tfix5 (2026-11-25): 30 FPS frame pacing and both Settings menus

All seven audit findings were re-checked in this tree and still applied. The
change is presentation timing only: no draw, pass, upscaler, shader, texture,
resolution, audio or lifecycle path was changed.

### Implementation

- `runtime/pacing.h` (new, header-only): Auto/Smooth/Compatibility strategy,
  display-timing vs phase-locked-FIFO choice, even-boundary deadline, divisor
  and phase-error arithmetic, bounded-queue policy. No Vulkan/Android/env, so
  it is host-testable.
- `runtime/display_clock.h` (new): the measured display phase written by the
  presenter and read by `PaceSwap` and the guest vblank.
- `presenter.cpp`: enables optional `VK_GOOGLE_display_timing`, puts the next
  even boundary in `VkPresentTimesInfoGOOGLE` for 30 FPS presents, and reads
  `vkGetPastPresentationTimingGOOGLE` for actual timestamps/phase. The
  swapchain stays FIFO. Without the extension, Auto/Smooth use
  `VK_KHR_present_wait` timings. Compatibility and every non-30 limit add no
  timing chain and keep the original newest-wins drop.
- `DrawOneFrame`: Auto/Smooth at 30 present every valid frame in order from a
  queue capped at 3; `ShowImage` applies bounded backpressure instead of
  letting a backlog grow.
- `frame_rate.h::PaceSwap`: waits for the display's measured next even boundary
  with a 1.5 ms lead, so 59.94 Hz accumulates the panel's period rather than
  33.333 ms. Compatibility keeps the independent clock.
- `gpu.cpp::VBlankWorker`: period already followed the display; the next blank
  is now moved onto the measured phase once, and only in the smooth modes.
- Both limiters remain: the title's own limiter and `PaceSwap`. Neither was
  removed; no measurement justified a change.
- `GameActivity`: `Surface.setFrameRate(30f, FIXED_SOURCE)` on API 30+ for a
  30 FPS/frame-generation run, cleared with `0/DEFAULT` in `onPause` and
  `onDestroy`, reapplied on resume and surface changes.
- `PES17_TRACE_PACING` (any value but 0) logs mode/strategy and, every 120 presents,
  interval mean/min/max, 1/2/3/4-blank counts and phase error.

### Settings, in both menus

`FramePacingOptions` (new) holds the option lists and the shared keys; `Prefs`
stores `framePacing` and `framePacingRefresh`. Neither is a `DeviceProfile`
preset field and no pacing listener calls `presetTouched()`, so a pacing change
cannot flip the preset to Custom or rewrite a preset setting.

- Main menu: Settings -> GRAPHICS & PERFORMANCE -> Frame pacing (under Frame
  rate): Frame pacing mode (Auto / Smooth 30 FPS / Compatibility) and Display
  refresh preference (System default / Prefer 60 Hz for 30 FPS gameplay).
- In-game: pause menu -> Graphics & performance -> the same two rows, value in
  the right-hand column. Both pages read/write the same `Prefs` keys, and the
  in-game relaunch intent carries both values as well.
- Saved immediately. The runtime reads `PES17_PACING` at match start; both
  pages say so (in-game Apply & restart does it at once). System default keeps
  the existing "match the screen" / 60 Hz-lock choices; Prefer 60 Hz asks for
  60 Hz at a 30 FPS limit. The refresh choice is disabled and dimmed while
  frame generation is on, which requires 60 Hz.

### Tests and build actually completed

- `tests/frame_pacing_test.cpp`: **56 checks, 0 failed** with
  `g++ -std=c++20 -I runtime`; also registered in `tests/CMakeLists.txt` as
  `frame-pacing`. Covers strategies/fallbacks, Compatibility restoration,
  ordering and newest-wins, queue ceiling/backpressure, 60/59.94 Hz deadlines
  and drift, phase error and divisors.
- `:app:testDebugUnitTest`: **365 tests in 34 classes, 0 failures, 0 errors**
  (JDK 17, Android SDK 35). `FramePacingOptionsTest` checks the shared-key
  round trip both ways, preset isolation, cycling, mode switching and API
  helpers. The first run had one failure in that new test (a wrong backward
  step expectation); it was corrected and the full suite rerun clean.
- `:app:assembleRelease -PprebuiltNative=... -x lint -x lintVitalRelease`:
  **succeeded** (1m30s standalone; the final run with the unit tests took
  1m55s; the first attempt was SIGKILLed by the host). APK:
  `android/app/build/outputs/apk/release/app-release.apk`, 50,180,677 bytes,
  debug key. New pacing resources are in `resources.arsc`.
- The packaged `libpes17.so` is the pinned engine from
  `https://files.catbox.moe/pwdg20.apk` (`1133fa0b...`). **That engine predates
  this change**, so the APK carries the new Settings but not the new presenter,
  pacer or vblank code. A native build needs the recompiled guest tree
  (`ppc_recomp_shared.h`) and the NDK, neither of which is in this workspace;
  the native sources were reviewed and the header arithmetic tested, not
  compiled into an APK.

### Remaining issues and device gap

No Android device or Vulkan runtime was available, so present intervals at
59.94/60 Hz, extension presence, `Surface.setFrameRate` acceptance, input
latency and battery/heat were **not measured**. `PES17_TRACE_PACING` is the
check: in a 30 FPS match the 2-blank count should dominate and phase error stay
within a few milliseconds; Perfetto or `dumpsys SurfaceFlinger --latency` can
cross-check. Without `VK_GOOGLE_display_timing`, a phase needs
`VK_KHR_present_wait`; with neither, the original independent clock remains.
The guest vblank is aligned once, not every blank. A 30 Hz panel uses divisor
1, and 30 on 90/120 Hz uses 3/4. No CPU/GPU/render workload was added: one
driver timing query per 50 ms only on the display-timing path, three queue
slots, and logging only under `PES17_TRACE_PACING`.

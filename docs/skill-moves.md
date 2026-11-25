# PES 2017 skill moves: verification against the Xbox 360 game

This is the verification reference for the 53 proposed skill mappings and for
the catalogue in `runtime/skill_moves.h`. It records what the **Xbox 360 title**
actually asks for, what this port can send, what was corrected, and what was
removed and why.

Nothing here has been executed inside the title. This workspace has no Android
device, no Vulkan runtime and no way to run the recompiled guest, so every
"in-game test" cell below reads **not run**. A mapping is therefore at best
**source-verified** (it matches the title's own command list or the official
manual) and its timing is **provisional** until someone plays it on a phone.
Only such a run can move an entry to "fully functional", and none has.

## Sources

| Source | What it establishes |
|---|---|
| PES 2017 in-game **Command List** / skill tutorials, transcribed by the Steam guide *Tutorial - All skills* (Fatal $MK, Sep 2016, `steamcommunity.com/sharedfiles/filedetails/?id=770849570`) | The 2017 move names, the exact stick/button orders, and the ability gates ("Skill required: …"). Every "the game says" row below comes from this list. |
| **This build's own executable** — the supplied `default.xex`, decompressed to a 34,471,936-byte image with the repository's XEX/AES/LZX path (`third_party/XenonRecomp`), then searched with `strings` | The primary source for **which commands exist** in PES 2017: its internal `FEINT_KIND_*` enumeration, its player-skill entry points (`ml_get_skill_scissors`, `_elastico`, `_marseille`, `_chapeau`, `_rabona`, `_heel_trick`), and command data (`PLAY_DATA_DRAW_OPEN`, `SOMBRERO`, `FLIP_FLAP_SKILLS`, `NEYMAR_STEPKICK`). It proves existence and names; it does not spell out input sequences. |
| Official PES **Xbox 360** manual control pages (Xbox Live game-manual assets; the 2016 edition is readable and uses the same scheme) | Pad identities: LS move, RS skill controls, RB dash, RT close control/special controls, LB switch, A low pass, B lofted pass/cross, X shot, Y through ball. Confirms RS is the skill control and that RT is the special-controls trigger. |
| PES 2016 complete skills list (PSU, Oct 2015; same engine family) | The older names that the proposals borrow: *Cross Over Turn*, *Cut Behind*, *Kick Feint*, *Double Touch*, *Marseille Turn*. Used only where the 2017 list is silent, and flagged as such. |
| PES 2018/2019 player-skill lists (Neoseeker) | Cross-checks the late names. *Cross Over Turn* is introduced in 2019, which is why its absence from the 2017 executable is expected; the executable also shows *Scissors Feint* was already an ability in 2017 (`ml_get_skill_scissors`), so the 2018 list is not used as a cut-off for that name. |
| This repository (`runtime/skill_moves.h`, `runtime/stick_pulses.h`, `docs/touch-controls.md`) | What the port can physically send: RS deflection, face/shoulder/stick-click bits, and the player's own LS. |

The game's tutorial list is the authority for the **input order**; the
executable is the authority for **which commands exist**. A row that the
tutorial list has is not guessed at even when the executable has no matching
string (display names live in the language archives, not the `.xex`), and a row
is only marked **not in PES 2017** when neither the tutorial list nor the
executable has any trace of it. The "Primary-source evidence" section records
the internal names behind the reconciled rows.

## What the port can and cannot send

The title reads the console pad once per guest frame. This port sends:

- the **right stick** as eight directions at 0.85 deflection (well past the
  title's ~14 % deadzone), held for whole frames by `input::StickPulses`
  (minimum 2 frames per direction, 1 frame of true neutral between separate
  flicks unless a move marks its legs continuous);
- **buttons** (A/B/X/Y, LB/RB, L3/R3, d-pad) through `skill::ButtonSeq`;
- **the player's left stick**, untouched.

That last line is the central constraint. Nearly every PES 2017 skill is
"right-stick gesture **then** left-stick direction". The wheel plays the
right-stick half and stops; the player's left thumb supplies the exit, exactly
as it does on a pad. Consequently:

- two move names whose right-stick halves are identical are **one macro** here,
  because the only difference is the LS direction the player supplies. They are
  aliases in one catalogue entry, not duplicate entries;
- moves that are a **hold before the ball arrives** (through the legs, flick)
  or that need the **left stick while the hold is live** (sombrero) can only be
  half-automated; the catalogue entry says so;
- moves that need a **trigger held before a stick input** (knock the ball:
  RT then RS →) are not in the wheel; the pad already has a live RT control for
  that, and a wheel macro cannot sequence an analogue trigger with a stick leg
  through `ButtonSeq`;
- kick feints (X/B then A) are the pad's existing fake-shot gesture, not wheel
  moves.

Conditions that apply to **every** right-stick move:

1. **Auto-Feint must be OFF**
   (Extras → Options → Personal Data Settings → Select Personal Data → Auto
   Feint). With it on, the title chooses feints itself and the right-stick
   command is not guaranteed to produce the named move.
2. The player must be **in possession and dribbling** (a few moves require
   *stationary*, noted per row).
3. The listed **ability** must be on the player; the command otherwise does
   nothing or produces a different animation.
4. Inputs are written **relative to the player facing forward** (left to right
   on screen); mirror them when running the other way.

## The pad, in the units the macros use

| Control | Pad bit | Notes |
|---|---|---|
| A | `0x1000` | Low pass; the **cancel** button |
| B | `0x2000` | Lofted pass / cross |
| X | `0x4000` | Shot |
| Y | `0x8000` | Through ball |
| LB | `0x0100` | Switch / modifier |
| RB | `0x0200` | Dash; also the nutmeg double-tap |
| L3 | `0x0040` | Left-stick click |
| R3 | `0x0080` | Right-stick click — the sombrero/juggling/rainbow input |
| LT / RT | analogue axes | Not available to `ButtonSeq` (see above) |

RS directions in this document use the console's axes: ↑ is away from the
player (guest +Y), ↘ is right-and-down, and the eight sectors are 45° apart.

## Verification table

Legend for **Verdict**:

- **Correct** — the proposed mapping matches the 2017 command list as written.
- **Partly** — a real move, but the proposal is vague, incomplete or adds a
  step the game does not ask for.
- **Wrong** — the proposed input is not what the game reads.
- **Duplicate** — a real move whose right-stick half is identical to another
  row's, so it is one macro with the player's LS choosing the animation.
- **Not in 2017 / not in this build** — no such command in the title's command
  list or anywhere in the executable (later game, an ability, or a conflation
  of two names). **No macro is created for it.**

"Status" is the implementation state: *catalogue* = a macro exists (source-
verified mapping, provisional timing, **not played in the title**); *player* =
performed by the player with the pad, no macro; *none* = no macro by design.

### A. Body feints and scissors

| # | Proposed | Verdict | What the Xbox 360 title reads | Conditions | Status |
|---|---|---|---|---|---|
| 1 | Body Feint / Gambetta — RS ↑ or ↓ → release → LS exit | Partly | RS ↓ **or** ↑, a flick. No LS exit is required; "Gambetta" is not a PES 2017 name. | On the ball; stationary or moving. | Catalogue (4) |
| 2 | Scissors Feint — RS ↗ ↑ | Partly | Real command, wrong name: the executable's step-over family is `FEINT_KIND_SCISSORS_OUT` / `_LOOP` (ability `ml_get_skill_scissors`), and the 2017 command list calls the move **Step Over Feint**: RS **→ ↑**, then LS ↘. "Scissors Feint" is the ability label, not a second command. | On the ball. | Catalogue (5) |
| 3 | Reverse Scissors Feint — RS ↘ ↓ | Partly | `FEINT_KIND_SCISSORS_IN` is the **Reverse Step Over** (#5): RS **↓ →** (or **↑ →**), no LS exit. The proposed ↘ ↓ is not the sequence. | On the ball. | Catalogue (11) |
| 4 | Step Over Feint — RS quarter-circle + LS exit | Partly | RS **→ ↑** (a quarter circle through ↗), then LS **↘**; the reverse direction is allowed. | On the ball. | Catalogue (5) |
| 5 | Reverse Step Over — RS reverse quarter-circle | Partly | RS **↓ →** (quarter circle) **or** RS **↑ →**; no LS exit. | On the ball. | Catalogue (11) |
| 6 | Multiple Scissors — repeated sequence | Wrong | **Multiple Step Overs**: RS **→ ↓** (quarter circle), then RS **↓ → ↑** (half circle), continuous. | On the ball. | Catalogue (12) |
| 7 | High Speed Scissors — while sprinting | Partly | Real: `FEINT_KIND_SCISSORS_OUT_HIGHSPEED` and `..._LOOP_HIGHSPEED` are in the executable, gated by `ml_get_skill_scissors`. It is the **same step-over input at speed**, not a separate stick sequence. | Scissors Feint; sprinting. | Catalogue (5) |

### B. Step-on and drag skills

| # | Proposed | Verdict | What the Xbox 360 title reads | Conditions | Status |
|---|---|---|---|---|---|
| 8 | Draw and Open — hold RS ← + LS ↓/↘/→ | Partly | Real: the executable has `PLAY_DATA_DRAW_OPEN` and one `FEINT_KIND_DRAWOPENCLOSE`, so open and close are the same command. RS **←**, then the LS picks: →/↘ opens, ↑ closes. Same right-stick half as #9. | On the ball. | Catalogue (8) |
| 9 | Draw and Close — RS ← → LS ↑ | Correct | RS **←**, then LS **↑**. | On the ball. | Catalogue (8) |
| 10 | L-Feint — RS ← → LS ↓ | Correct | RS **←**, then LS **↓**. | On the ball. | Catalogue (8) |
| 11 | Scotch Move — RS ← → LS ↓ | Correct | RS **←**, then LS **↓**; requires **Scotch Move**. Same input as L-Feint; the ability picks the animation. | Scotch Move. | Catalogue (8) |
| 12 | Inside Bounce — RS ← → LS → | Correct | RS **←**, then LS **→**; requires **Scotch Move**. | Scotch Move. | Catalogue (8) |
| 13 | Drag Back — RS ↑/↓ → LS ← | Partly | RS **↑** or **↓**, then LS **←**. The old macro held RS ↓ for 10 frames and claimed no exit; corrected to a flick with the exit to the player. | On the ball. | Catalogue (3) |
| 14 | Drag Back Dummy | Not in 2017 | No `DUMMY`/`DRAG_BACK` feint kind in the executable and no entry in the 2017 command list. | — | None |
| 15 | Drag and Close — RS ← → LS → | Correct | RS **←**, then LS **→**; identical to Inside Bounce (#12). | On the ball. | Catalogue (8) |
| 16 | Inside Double Touch — RS ↓ → LS ↑ | Correct | RS **↓**, then LS **↑** (reverse allowed). | On the ball. | Catalogue (0) |
| 17 | Inside Double Touch Behind — RS ↓ → LS ↖ | Correct | RS **↓**, then LS **↖** (reverse allowed). | On the ball. | Catalogue (0) |

### C. Flip flap and advanced variations

| # | Proposed | Verdict | What the Xbox 360 title reads | Conditions | Status |
|---|---|---|---|---|---|
| 18 | Flip Flap / Elastico — RS ↘ → LS ↗ | Correct | RS **↘**, then LS **↗**; requires **Flip Flap** (the ability; the move is the Elastico). | Flip Flap. | Catalogue (7) |
| 19 | Reverse Flip Flap — RS ↗ → LS ↘ | Correct | RS **↗**, then LS **↘**; requires **Flip Flap**. | Flip Flap. | Catalogue (10) |
| 20 | Elastico to Diagonal Take — RS ↘ → LS ↘ | Duplicate | Real, but its RS half is exactly #18's; only the player's LS differs (↘ instead of ↗). No separate macro. | Flip Flap. | Catalogue (7) |
| 21 | Hocus Pocus — RS ↖ → LS ↘ | Correct | RS **↖**, then LS **↘**; requires **Flip Flap**. | Flip Flap. | Catalogue (9) |
| 22 | In and Out — RS ↘ → LS ↗ | Duplicate | Real name, identical input to the Elastico (#18). The 2017 list gives both the same command. No separate macro. | Flip Flap. | Catalogue (7) |
| 23 | Out and In — RS ↗ → LS ↘ | Duplicate | Real name, identical input to Reverse Elastico (#19). | Flip Flap. | Catalogue (10) |
| 24 | Neymar Signature Move — RS ↑ → LS ↓ | Correct | RS **↑**, then LS **↓**; **Neymar only**. Same RS half as the double-touch family; the LS ↓ picks it. The executable has `FEINT_KIND_NEYMAR_STEPKICK_L/R`, so it is a real, player-specific command. | Neymar. | Catalogue (0) |

### D. Roulette, turns and directional skills

| # | Proposed | Verdict | What the Xbox 360 title reads | Conditions | Status |
|---|---|---|---|---|---|
| 25 | Marseille Turn — RS 360° → LS exit | Correct | RS a full **360°** (either direction), then LS **↘**, **→** or **↗**. | On the ball. | Catalogue (1) |
| 26 | One-Foot Roulette — RS 360° → LS → | Correct | Same 360°, then LS **→**; requires **Weak Foot Use 1**. Same macro as #25; `FEINT_KIND_ROULETTE_SINGLE_L/R` confirms the single-foot variant. | Weak Foot Use 1. | Catalogue (1) |
| 27 | Spin — RS 360° → LS ↑ or ↓ | Correct | Same 360°, then LS **↑** or **↓**. Same macro. | On the ball. | Catalogue (1) |
| 28 | McGeady Spin — verify sequence | Duplicate | Same 360° then LS **↑/↓** as Spin (#27); requires **Marseille Turn**. No separate macro. | Marseille Turn. | Catalogue (1) |
| 29 | Double Touch — RS ↑/↓ → LS exit | Partly | RS **↑** or **↓**, then LS **→**. The exit is specifically →, not "any". | On the ball. | Catalogue (0) |
| 30 | Ronaldo Chop — RS → → LS ↓/↘/↗/↑ | Wrong | **One** RS **→** (held a moment), then LS **↓**, **↘**, **↗** or **↑**. The proposal's second → is not in the game. | On the ball. | Catalogue (6) |
| 31 | Cross Over Turn — RS → → LS ↑/↗/↖ | Not in this build | No `CROSSOVER`/`CROSS_OVER` string or feint kind in the executable, and the 2017 command list does not list it (it appears in the PES 2019 skill list). The 2016 command did not survive into 2017; the RS → input exists only as the Ronaldo Chop here. | — | None |

### E. Flick and sombrero skills

| # | Proposed | Verdict | What the Xbox 360 title reads | Conditions | Status |
|---|---|---|---|---|---|
| 32 | Juggling — hold RS while moving | Wrong | Hold **R3** (right-stick click) while dribbling. A stick *direction* is not the input. Continuous hold. | On the ball. | Player (R3 control) |
| 33 | Flick — hold RS before receiving | Wrong | Hold **R3** before the ball arrives. Pre-reception hold. | Ball incoming. | Player (R3 control) |
| 34 | Rainbow Flick — click RS twice | Correct | **R3 ×2**, with a release between the clicks. | On the ball. | Catalogue (2) |
| 35 | Sombrero — hold RS → LS towards opponent | Wrong | **Stationary:** hold **R3**, then LS toward the opponent; requires **Sombrero**. R3 is a click, not a deflection. | Sombrero; stationary. | Catalogue (13) |
| 36 | Sombrero Heel Flick — verify sequence | Duplicate | Same command as Sombrero: hold **R3** then LS toward the opponent; requires **Sombrero**. | Sombrero; stationary. | Catalogue (13) |
| 37 | Advanced Sombrero — simultaneous RS/LS | Partly | Hold **R3** + LS toward the opponent **while dribbling** (not stationary); requires **Sombrero**. The catalogue's R3 hold covers it; movement is the player's. | Sombrero. | Catalogue (13) |
| 38 | Bergkamp Flick — hold RS click + LS towards opponent | Wrong | **Stationary:** hold **R3** + LS toward the opponent; requires **Sombrero**. Same input as #35. | Sombrero. | Catalogue (13) |
| 39 | Flick Up — verify RS sequence | Not in 2017 | No `FLICK_UP` string or feint kind in the executable, and no entry in the 2017 command list; not established from any source. | — | None |

### F. Kick feints and special moves

| # | Proposed | Verdict | What the Xbox 360 title reads | Conditions | Status |
|---|---|---|---|---|---|
| 40 | Fancy Fake Shot — X → A before the kick | Correct | **X then A** while standing (shoot, cancelled with pass before contact). Already the pad's fake-shot gesture (shoot held → pass down). | Stationary. | Player |
| 41 | Kick Feint — X or B → A + LS ↗/↘ | Correct | **X or B**, then **A** as the kick starts, + LS **↗** or **↘**. | On the ball. | Player |
| 42 | Fake Rabona — X → A | Correct | **X**, then **A**; requires **Rabona**. Same input as #40; the ability picks the animation. | Rabona. | Player |
| 43 | Cruyff Turn — X → A + LS opposite diagonal | Correct | **X**, then **A** + LS toward the **opposite diagonal** of the opponent; requires **Cut Behind & Turn**. | Cut Behind & Turn. | Player |
| 44 | Cut Behind — X or B → A + LS exit | Correct | **X or B**, then **A** + LS **↑/↗/↖** (relative); requires **Cut Behind & Turn**. Same family as the Cruyff; the LS picks it. | Cut Behind & Turn. | Player |
| 45 | Stationary Fake Shot — verify sequence | Duplicate | Standing **X then A** is the Fancy Fake Shot (#40). No separate command. | Stationary. | Player |

### G. Nutmeg and other special moves

| # | Proposed | Verdict | What the Xbox 360 title reads | Conditions | Status |
|---|---|---|---|---|---|
| 46 | Nutmeg / Run Around — RB twice → LS towards opponent | Correct | **RB ×2**, then LS toward the opponent. `FEINT_KIND_NUTMEG` is in the executable. | On the ball, defender ahead. | Catalogue (14) |
| 47 | Riquelme's Nutmeg — RB twice → LS ↖ | Correct | **RB ×2**, then LS **↖**. Same buttons; the LS picks it. `FEINT_KIND_NUTMEG_RIQUELME_L/R` confirms it. | On the ball. | Catalogue (14) |
| 48 | Through the Legs — hold RB before receiving | Correct | Hold **RB** before the ball arrives. Pre-reception hold; no short macro can span the pass. The executable's `FEINT_KIND_KICK_NUTMEG` is the kick nutmeg — related, but it does not prove the RB hold. | Ball incoming. | Player (RB control) |
| 49 | Knock the Ball — hold RT → RS → | Correct | Hold **RT**, then RS **→**. RT is an analogue trigger, so it is not in the wheel table; the pad's RT control plus the skill stick does it. | On the ball. | Player |
| 50 | Dive — LB + LT → LS click + RS click | Wrong | Hold **LB + LT**, then **L3 + R3**. This is a goalkeeper/context command, not an on-ball dribble; it is not a wheel skill. | Goalkeeper context. | None |
| 51 | Knock Forward for Shot — verify sequence | Wrong | The manual's **Knock-on**: stationary, hold **RT**, then push **LS** in the chosen direction (not RS). Improves the next shot/pass. | Stationary; on the ball. | Player |
| 52 | Dummy Fake Shot — verify stationary sequence | Not in 2017 | No such command. The shot cancel is `FEINT_KIND_KICKCANCEL`, which is the Fancy Fake Shot (#40); the "dummy" is **hold RB with the stick centred** (let the ball run). | On the ball. | Player |
| 53 | Dummy Through the Legs / Dummy Burst | Partly | `FEINT_KIND_BURST` exists but is the **speed burst** (RB ×2), not a dribble; *through the legs* is a separate hold (#48) and the executable has `FEINT_KIND_KICK_NUTMEG` for the kick nutmeg. "Dummy Burst" itself is not a command. | — | Player (burst = RB ×2) |

### Tally

| Verdict | Count |
|---|---|
| Correct | 24 |
| Partly (real move, vague or mis-macroed proposal) | 11 |
| Duplicate (same right-stick half as another row) | 6 |
| Wrong (input, step count, control name, or category) | 8 |
| Not in PES 2017 / not in this build | 4 |
| **Total** | **53** |

(Duplicate rows are counted once each; several "Correct" rows share a catalogue
entry because their right-stick halves are identical.)

## Primary-source evidence from the executable

The supplied `default.xex` was decompressed — XEX2, AES-CBC with the retail key,
LZX normal compression — to a 34,471,936-byte image with the vendored
XenonRecomp LZX path, then searched with `strings`. These are the title's own
identifiers, and each one settles a row of the table above. They prove which
commands exist in this build; they do not spell out input order, which still
comes from the command-list transcript.

| Internal name(s) in the image | Family | Rows settled |
|---|---|---|
| `FEINT_KIND_SCISSORS_OUT`, `_LOOP`, `_OUT_HIGHSPEED`, `_LOOP_HIGHSPEED` | step over, including the fast variant | 2, 4, 6, 7 |
| `FEINT_KIND_SCISSORS_IN` | reverse step over | 3, 5 |
| `FEINT_KIND_BODYFAKE`, `_L/_R` | body feint | 1 |
| `FEINT_KIND_DOUBLETOUCH`, `_L/_R` | double touch | 16, 17, 29 |
| `FEINT_KIND_DRAWOPENCLOSE`, `_L/_R`; `PLAY_DATA_DRAW_OPEN` | draw open / draw close | 8, 9 |
| `FEINT_KIND_L_FEINT`, `_L/_R` | L-feint (its RS← family) | 10 |
| `FEINT_KIND_INOUT`, `FEINT_KIND_OUTIN` | in and out / out and in | 22, 23 |
| `FEINT_KIND_ELASTICO`, `_REVERSE`; `FEINT_KIND_ELASTICOTURN`; `FLIP_FLAP_SKILLS` | elastico family | 18, 19, 20, 22, 23 |
| `FEINT_KIND_ROULETTE`, `_SINGLE`, `_RIBERY`, `_RIQUELME` | roulette family | 25, 26, 27, 28 |
| `FEINT_KIND_NUTMEG`, `_RIQUELME`, `FEINT_KIND_KICK_NUTMEG` | nutmeg family | 46, 47, 48, 53 |
| `FEINT_KIND_NEYMAR_STEPKICK_L/R` | Neymar's move | 24 |
| `FEINT_KIND_KICKCANCEL`, `FEINT_KIND_KICKFEINT_*` | shot/pass cancel and the kick feints | 40–45, 52 |
| `FEINT_KIND_BURST` | speed burst | 53 |
| `SOMBRERO`; `ml_get_skill_chapeau` (likely the Sombrero gate) | sombrero family | 35–38 |
| `FEINT_KIND_HEELLIFT`, `FEINT_KIND_HEEL_ROLL_TURN`; `HEEL_TRICK` | heel commands — related to, but not proof of, the guide's "sombrero heel flick" | 36 |
| `ml_get_skill_scissors`, `_elastico`, `_marseille`, `_rabona`, `_heel_trick` | ability gates the command list names | 2, 7, 18–21, 42 |

Not found anywhere in the image: `CROSSOVER`/`CROSS_OVER` (row 31), `SCOOP_TURN`
(the only `SCOOP` is the goalkeeper animation `ANIME_KP_SCOOP_OUT`, row 39's
neighbour), `FLICK_UP` (39), and any `DUMMY` or `DRAG_BACK` feint kind (14, 53).
`HOCUS`, `RAINBOW` and `JUGGLE` also have no string in the image, but those moves
are in the tutorial list and their display names live in the language archives,
so their absence from the `.xex` is not evidence against them. That is the rule
used for every row: the executable can only **add** a command that the tutorial
list omits, never remove one it lists.

## Macro timelines

Every timeline below is what `runtime/skill_moves.h` plays, expressed in guest
frames (authoritative) and milliseconds at **60 fps** (the title's poll rate
when the app is not limiting it; at 30 fps each frame is 33.3 ms and the
sequence means the same thing because it is frame-counted). "Player LS" is the
thumb, not the macro. All timings are **provisional**: they are the sequencer's
documented minimums (2-frame hold, 1-frame gap) plus the leg counts, not values
read off a running title.

### 0 — Double Touch (also Inside Double Touch, Inside Double Touch Behind, Neymar)

`RS ↑` (2 frames) → neutral (3 frames) → complete; player pushes LS **→**
(inside: **↑**; behind: **↖**; Neymar: **↓**).

Entries 0, 3 and 4 send the same two flicks the title accepts for this family
(up or down). The left stick the player pushes is what selects Double Touch,
Drag Back or Body Feint. They stay three wheel entries because their indices
predate this pass and a saved layout stores them; the entry's label is the
intent, and the exit hint names the LS direction.

| Time | Event |
|---|---|
| 0 ms | RS ↑ at 0.85 deflection (frame 0) |
| 33 ms | RS released; neutral (frames 2-4) |
| 83 ms | Macro complete; the player's LS chooses the named move |

### 1 — Roulette / Marseille Turn / One-Foot Roulette / Spin / McGeady Spin

`RS` 360° anticlockwise in nine 45° legs, no gap between legs; player pushes LS
to the exit.

| Time | Event |
|---|---|
| 0 ms | RS → |
| 33 ms | RS ↗ |
| 67 ms | RS ↑ |
| 100 ms | RS ↖ |
| 133 ms | RS ← |
| 167 ms | RS ↙ |
| 200 ms | RS ↓ |
| 233 ms | RS ↘ |
| 267 ms | RS → (closes the circle) |
| 300 ms | Released; macro complete; player's LS picks ↘/→/↗ (or ↑/↓ for Spin) |

### 2 — Rainbow Flick

`R3` click twice with a release between them (button sequencer).

| Time | Event |
|---|---|
| 0 ms | R3 down (1 frame) |
| 17 ms | R3 up (1 frame) |
| 33 ms | R3 down (1 frame) |
| 50 ms | R3 up (3 frames) |
| 83 ms | Complete (the sequencer adds a final all-clear frame) |

### 3 — Drag Back

`RS ↓` flick; player pushes LS **←** (the title accepts ↑ or ↓ as the first
input).

| Time | Event |
|---|---|
| 0 ms | RS ↓ |
| 33 ms | Released; neutral |
| 67 ms | Complete; player's LS ← |

*(The old entry held RS ↓ for ten frames and advertised no exit. Both were
wrong: the title wants a flick, and the LS is what finishes the move.)*

### 4 — Body Feint

`RS ↓` flick, no exit required (the player may still steer).

| Time | Event |
|---|---|
| 0 ms | RS ↓ |
| 33 ms | Released; neutral |
| 67 ms | Complete |

### 5 — Step Over Feint

`RS → ↗ ↑`, continuous (no neutral between legs); player pushes LS **↘**.

| Time | Event |
|---|---|
| 0 ms | RS → |
| 33 ms | RS ↗ |
| 67 ms | RS ↑ |
| 100 ms | Released; player's LS ↘ |

### 6 — Ronaldo Chop (alias: Cross Over Turn, LS ↑/↗/↖)

`RS →` held three frames; player pushes LS to the exit.

| Time | Event |
|---|---|
| 0 ms | RS → |
| 50 ms | Released; player's LS ↓/↘/↗/↑ |

### 7 — Elastico (aliases: Flip Flap, Elastico to Diagonal Take, In and Out)

`RS ↘` held three frames; player pushes LS **↗** (to-diagonal: ↘).

| Time | Event |
|---|---|
| 0 ms | RS ↘ |
| 50 ms | Released; player's LS ↗ |

### 8 — Inside Feint (aliases: Inside Bounce, Drag and Close, Draw and Close, L-Feint, Scotch Move)

`RS ←` flick; player pushes LS **→** (inside bounce / drag and close), **↑**
(draw and close) or **↓** (L-feint / Scotch Move).

| Time | Event |
|---|---|
| 0 ms | RS ← |
| 33 ms | Released; neutral |
| 67 ms | Complete; player's LS chooses the move |

### 9 — Hocus Pocus

`RS ↖` flick; player pushes LS **↘**. Requires Flip Flap.

| Time | Event |
|---|---|
| 0 ms | RS ↖ |
| 33 ms | Released; neutral |
| 67 ms | Complete; player's LS ↘ |

### 10 — Reverse Elastico (aliases: Reverse Flip Flap, Out and In)

`RS ↗` flick; player pushes LS **↘**. Requires Flip Flap.

| Time | Event |
|---|---|
| 0 ms | RS ↗ |
| 33 ms | Released; neutral |
| 67 ms | Complete; player's LS ↘ |

### 11 — Reverse Step Over

`RS ↓ ↘ →`, continuous, no LS exit.

| Time | Event |
|---|---|
| 0 ms | RS ↓ |
| 33 ms | RS ↘ |
| 67 ms | RS → |
| 100 ms | Released; complete |

### 12 — Multiple Step Overs

`RS → ↘ ↓` (the first quarter), then `↓ ↘ → ↗ ↑` (the half circle), continuous.

| Time | Event |
|---|---|
| 0 ms | RS → |
| 33 ms | RS ↘ |
| 67 ms | RS ↓ |
| 100 ms | RS ↘ |
| 133 ms | RS → |
| 167 ms | RS ↗ |
| 200 ms | RS ↑ |
| 233 ms | Released; complete |

### 13 — Sombrero (aliases: Sombrero Heel Flick, Bergkamp Flick, Advanced Sombrero)

Hold **R3** for 24 frames; the player drives LS toward the opponent while it is
held (stationary for Sombrero/heel flick/Bergkamp, moving for the advanced
variant). Requires Sombrero. The hold is deliberately long enough for a thumb to
reach the stick; the title needs the LS while R3 is down.

| Time | Event |
|---|---|
| 0 ms | R3 down |
| 400 ms | R3 up |
| 417 ms | Complete (all-clear frame) |

### 14 — Nutmeg / Run Around (alias: Riquelme's Nutmeg)

**RB** twice with a gap; player pushes LS toward the opponent (Riquelme: **↖**).

| Time | Event |
|---|---|
| 0 ms | RB down (2 frames) |
| 33 ms | RB up (2 frames) |
| 67 ms | RB down (2 frames) |
| 100 ms | RB up (all-clear) |
| 117 ms | Complete; player's LS picks the nutmeg |

### Moves with no wheel macro, and where the input lives

| Move | Why no macro | Where the player does it |
|---|---|---|
| Juggling | Continuous R3 hold, not a discrete gesture | The pad's **R3** control, held |
| Flick | R3 must be held **before** the pass arrives | The pad's **R3** control, held early |
| Through the Legs | RB must be held **through** the reception | The pad's **RB** control, held early |
| Knock the Ball | RT must lead the RS input | The pad's **RT** control + the skill stick |
| Knock Forward for Shot | RT + LS, both the player's | RT control + movement stick |
| Dummy (let the ball run) | RB hold with the stick centred | RB control, stick centred |
| Kick feints / Cruyff / Cut Behind / Rabona | X/B then A + LS; already a pad gesture | The fake-shot gesture (shoot held → pass) |
| Dive | Goalkeeper context, not an on-ball skill | LB + LT, then L3 + R3 |
| Cross Over Turn | Same RS half as the chop; 2017 existence unconfirmed | Chop entry + LS ↑/↗/↖ |

## Corrections, in the required form

Each correction lists the original proposal, the corrected mapping, input
order, timing, releases, conditions, evidence, and test result.

### C1 — Drag Back (#13)

1. **Proposed:** RS ↑ or ↓ → LS ← (and the old macro held RS ↓ for 10 frames,
   `leavesExitToPlayer = false`).
2. **Corrected:** RS ↓ flick, release, then the player's LS ←.
3. **Order:** RS ↓ (2 frames) → neutral (3 frames) → LS ← (player).
4. **Timing:** 0 ms RS down, 33 ms up, 67 ms complete.
5. **Releases:** RS must be released before the LS input; the title reads the
   flick's falling edge.
6. **Conditions:** on the ball; no ability gate.
7. **Evidence:** 2017 tutorial list, "DRAG BACK — PRESS RS ↑ or ↓ then PRESS LS
   ←". **Test: not run.**

### C2 — Ronaldo Chop (#30)

1. **Proposed:** RS → → LS ↓/↘/↗/↑ (two sequential right-stick pushes).
2. **Corrected:** one RS →, held briefly, then LS.
3. **Order:** RS → (3 frames) → LS (player).
4. **Timing:** 0 ms RS down, 50 ms up.
5. **Releases:** release RS before the LS.
6. **Conditions:** on the ball.
7. **Evidence:** 2017 list, "RONALDO CHOP — PRESS RS → then PRESS LS ↓, ↘, ↗ or
   ↑". **Test: not run.**

### C3 — Sombrero family (#35, #36, #38, #37)

1. **Proposed:** hold RS (direction) then LS toward the opponent.
2. **Corrected:** hold **R3** (stick click), then LS toward the opponent.
3. **Order:** R3 down (24 frames) → LS toward opponent (player) → R3 up.
4. **Timing:** 0 ms R3 down, 400 ms up, 417 ms complete.
5. **Releases:** R3 released after the LS push; the title needs the click held
   while the LS moves.
6. **Conditions:** Sombrero ability; stationary for Sombrero, heel flick and
   Bergkamp; moving for the advanced sombrero.
7. **Evidence:** 2017 list, "SOMBRERO — Whilst stationary HOLD R3 then PRESS LS
   towards opponent", "BERGKAMP FLICK — Skill required: Sombrero — Whilst
   stationary HOLD R3 + LS towards opponent", "ADVANCED SOMBRERO — While
   dribbling PRESS and HOLD R3 + LS towards opponent". **Test: not run.**

### C4 — Juggling and Flick (#32, #33)

1. **Proposed:** hold RS while moving / before receiving.
2. **Corrected:** hold **R3**; there is no stick direction.
3. **Order:** R3 down, held.
4. **Timing:** continuous; no macro length is correct.
5. **Releases:** release R3 to stop.
6. **Conditions:** on the ball (juggling) / ball incoming (flick).
7. **Evidence:** 2017 list, "JUGGLING — While dribbling PRESS and HOLD R3";
   "FLICK — Before you receive the ball PRESS and HOLD R3". **Test: not run.**

### C5 — Knock Forward for Shot (#51)

1. **Proposed:** unverified; suspect RS.
2. **Corrected:** stationary, hold RT, then push **LS** in the chosen direction
   (the manual's Knock-on), which improves the next shot or pass.
3. **Order:** RT down → LS (player) → RT up.
4. **Timing:** the hold is the player's, not a macro.
5. **Releases:** RT released after the LS push.
6. **Conditions:** stationary, on the ball.
7. **Evidence:** official manual, "Knock-on — Whilst stationary, press [RT]
   followed then push [LS] in the desired direction". **Test: not run.**

### C6 — Dive (#50)

1. **Proposed:** LB + LT → LS click + RS click, "verify if this is actually a
   skill-wheel move".
2. **Corrected:** the input is right, but it is a **goalkeeper/context command**,
   not an on-ball dribble; it is not added to the wheel.
3. **Order:** LB + LT down → L3 + R3 (same event).
4. **Timing:** the modifiers must precede the clicks by one frame at least.
5. **Releases:** all four released after the click.
6. **Conditions:** goalkeeper context.
7. **Evidence:** 2017 guide's skill list, "DIVE — HOLD L1 + L2 then PRESS L3 +
   R3". **Test: not run.**

## Removed from the selectable catalogue

No existing index was removed: 0-7 are stored in players' `controls.json`, so
removing or reordering them would silently change a saved pad. What was removed
is the addition of entries that would have been duplicates or inventions:

| Removed entry | Reason |
|---|---|
| Drag Back Dummy | No `DUMMY`/`DRAG_BACK` feint kind in the executable and no command-list entry. |
| Flick Up | Not in the 2017 list; unestablished. |
| Elastico to Diagonal Take | Identical RS half to the Elastico; LS-only difference. |
| In and Out | Identical input to the Elastico. |
| Out and In | Identical input to Reverse Elastico. |
| McGeady Spin | Identical input to Spin; ability-gated. |
| One-Foot Roulette | Identical input to the Roulette; LS → is one of its exits. |
| Neymar Signature Move | Identical RS half to the double touch; Neymar + LS ↓ selects it. |
| Sombrero Heel Flick, Bergkamp Flick | Identical input to the Sombrero. |
| Stationary Fake Shot | Identical input to the Fancy Fake Shot. |
| Riquelme's Nutmeg | Identical buttons to the Nutmeg; LS ↖ selects it. |
| Cross Over Turn | No `CROSSOVER` feint kind in the executable; a PES 2019 skill, absent from 2017. |
| Dive | Goalkeeper context, not an on-ball skill. |
| Dummy Burst | `FEINT_KIND_BURST` is the speed burst (RB ×2), not a dummy; no "dummy burst" command. |

Scissors Feint, Reverse Scissors Feint, High Speed Scissors and Draw and Open
are **not** in that table: the executable showed them to be the step-over family
(`FEINT_KIND_SCISSORS_*`) and the draw open/close command
(`PLAY_DATA_DRAW_OPEN`), so they are aliases named in the catalogue rather than
inventions.

Aliases are named in the catalogue comments and in the timelines above, so no
information is lost by not giving each name its own wheel entry. The one
exception kept deliberately is indices 0, 3 and 4 (see the catalogue table):
they predate this pass and are stored in players' layouts.

## Implemented catalogue (`runtime/skill_moves.h`)

| Index | id | Name(s) | Script | Player exit | Ability / context |
|---|---|---|---|---|---|
| 0 | `double-touch` | Double Touch; Inside Double Touch (+Behind); Neymar | RS ↑ 2f, neutral 3f | LS → (inside: ↑, behind: ↖, Neymar: ↓) | Neymar only for his move |
| 1 | `roulette` | Roulette; Marseille Turn; One-Foot Roulette; Spin; McGeady Spin | 360°, 9 × 2f | LS ↘/→/↗ or ↑/↓ | Weak Foot Use 1 (one-foot), Marseille Turn (McGeady) |
| 2 | `rainbow-flick` | Rainbow Flick | R3 ×2 | — | — |
| 3 | `drag-back` | Drag Back | RS ↓ 2f, neutral 3f | LS ← | — |
| 4 | `body-feint` | Body Feint | RS ↓ 2f, neutral 2f | none | — |

Entries 0, 3 and 4 are the same press family (the title accepts up or down for
all three); only the player's LS picks the move. They remain separate wheel
entries for saved-layout compatibility, and this is the one shared-gesture
exception left in the table. Everything else in the catalogue is a distinct
right-stick or button script.
| 5 | `step-over` | Step Over Feint; Scissors Feint; High Speed Scissors | RS → ↗ ↑, 2f each, continuous | LS ↘ | Scissors Feint for the high-speed variant |
| 6 | `ronaldo-chop` | Ronaldo Chop; Cross Over Turn | RS → 3f | LS ↓/↘/↗/↑ | — |
| 7 | `elastico` | Elastico; Flip Flap; In and Out; Elastico to Diagonal | RS ↘ 3f | LS ↗ | Flip Flap |
| 8 | `inside-feint` | Inside Bounce; Drag and Close; Draw and Close; Draw and Open; L-Feint; Scotch Move | RS ← 2f, neutral 2f | LS →/↘/↑/↓ | Scotch Move (bounce/scotch) |
| 9 | `hocus-pocus` | Hocus Pocus | RS ↖ 2f, neutral 2f | LS ↘ | Flip Flap |
| 10 | `reverse-elastico` | Reverse Elastico; Reverse Flip Flap; Out and In | RS ↗ 2f, neutral 2f | LS ↘ | Flip Flap |
| 11 | `reverse-step-over` | Reverse Step Over | RS ↓ ↘ →, continuous | none | — |
| 12 | `multiple-step-overs` | Multiple Step Overs | RS → ↘ ↓ ↘ → ↗ ↑, continuous | none | — |
| 13 | `sombrero` | Sombrero; Sombrero Heel Flick; Bergkamp Flick; Advanced Sombrero | R3 held 24f | LS toward opponent | Sombrero |
| 14 | `nutmeg` | Nutmeg / Run Around; Riquelme's Nutmeg | RB 2f, gap 2f, RB 2f | LS toward opponent / ↖ | — |

`SkillMoves.COUNT` in the app is 15 and the order is the contract: a saved
layout stores indices, and the runtime refuses an index it does not know.

## The metadata, and where it is used

`Move::exitHint` and `Move::ability` are not decorative fields:

- **The log.** `android::input::PlaySkill` writes both to the runtime log
  (`LOGI`, the `touch:` line), e.g.
  `touch: skill 'sombrero', 0 steps, 1 clicks, the left stick is the player's |
  next: hold R3 while pushing LS toward the opponent (stationary) | needs:
  Sombrero`.
  A move that does nothing can then be read against the half the player owed and
  the ability gate, instead of guessing from the pad.
- **The editor.** The wheel's move picker (`ControlEditPanel.pickSkillMove`)
  shows the hint and the ability beside the name, because the wheel stops where
  the player's thumb takes over and because a player without the ability sees
  the same thing as a broken move.
- **The app mirror.** `SkillMoves.hint`/`SkillMoves.ability` carry the same text
  for the picker; `runtime/skill_moves.h` stays authoritative and
  `tests/skill_moves_test.cpp` pins the native values. Changing a hint or a gate
  means changing both, like `COUNT` and the names.

## What a device must still prove

This is the checklist that turns "source-verified" into "working". None of it
can be done in this workspace.

1. Auto-Feint off; a player with no abilities; confirm each right-stick entry
   produces its named animation and not a different one.
2. **Hold lengths**: the 2-frame flick and 1-frame gap survive a 30 Hz poll;
   the 360° reads as a rotation, not as eight flicks.
3. **Exit windows**: how long after the RS flick the LS is still accepted
   (double touch, chop, elastico, step over). The 3-frame neutral window in
   entry 0 is a guess that has to be measured.
4. **Sombrero**: 400 ms of R3 is long enough to reach the stick and short
   enough to be a sombrero and not juggling.
5. **Nutmeg**: two RB presses 67 ms apart read as a double tap (not the speed
   burst) and the title accepts the LS afterwards.
6. **Rainbow**: the 17 ms R3 release between clicks is seen as two clicks.
7. **Aliased moves**: with the LS the table names, each alias produces its own
   animation (e.g. Elastico vs In and Out).
8. Ability-gated entries do nothing (or fall back to a plain feint) on a player
   without the ability — confirm graceful failure.
9. Same tests at the FPS limit the app applies, and with the skill stick in
   absolute mode rather than flick mode.

Record the result per index in this file before calling any entry fully
functional.

## Engine and game-file evidence

- The linked engine APK (`https://files.catbox.moe/pwdg20.apk`, 58,671,890
  bytes, SHA-256 `05930d89af8f990783ec58bf7cce4a47b5942023af831bd4df33b905132d8140`)
  contains `lib/arm64-v8a/libpes17.so` with SHA-256
  `1133fa0b9309b802fbaa8e074b1decde8f5a7912599be409937807ac6478eb69`, which is
  exactly `ENGINE_SO_SHA256` in `tools/engine_pin.sh`. The **engine this branch
  packages is byte-identical to the one in that APK**; only the surrounding APK
  container is a different build, so the pin is left on the published V0.2.4
  asset that `tools/fetch_engine.sh` downloads.
- The supplied `default.xex` is 32,493,568 bytes, SHA-256
  `b914ec9a1abb3a9b42a64255b6aa8ff27312da126f7c18f8c880106b832779e6`. The
  installer/recompiler hashes are generated from whichever dump is built
  (`CMakeLists.txt`), so this is recorded as the dump used for this
  investigation, not as an expected value in code.
- The XEX is compressed, so its own command strings had to be decompressed
  first. That was done with the repository's own path (retail AES key from
  `XenonUtils/xex.h`, libmspack LZX from `third_party/XenonRecomp`): the image is
  34,471,936 bytes and the internal `FEINT_KIND_*` names quoted above come from
  it. The tool was a throwaway in `/tmp` and is not part of the build; the
  evidence it produced is recorded here.
- What the binary does **not** settle: the order, hold length or release timing
  of any gesture. Those still rest on the command-list transcript and are
  provisional until the device checklist is run.

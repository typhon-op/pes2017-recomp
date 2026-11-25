#pragma once
// The one-tap skill moves: what each wheel sector sends, in guest frames.
//
// The catalogue is the verified PES 2017 set, not a list of every name a guide
// uses. docs/skill-moves.md holds the evidence: the 53 proposed mappings, the
// verdict on each, the timelines, and why the entries that are not here were
// left out. Read that before adding a move. The evidence is the title's own
// command list plus the FEINT_KIND_* enumeration recovered from the supplied
// default.xex; where a move's English name and its internal name differ (e.g.
// Step Over Feint is SCISSORS_OUT), the entry names both.
//
// What is played here is only the half the pad can play. PES 2017 builds almost
// every skill from a right-stick gesture followed by a *left-stick* direction,
// and the left stick belongs to the player: a wheel cannot know which way they
// want to go, and it must not freeze movement to find out. So a move plays the
// right stick (and, for a few, buttons) and stops; `exitHint` says what the
// thumb supplies next. Moves whose right-stick halves are identical are one
// entry with several names, because the only difference is the LS direction the
// player already supplies -- adding a second entry would be two wheel sectors
// sending the same press. The one exception is indices 0/3/4, kept apart
// because a saved layout stores them (docs/skill-moves.md).
//
// Conditions for every stick move, from the title's own command list:
//
//   * Auto-Feint must be off (Personal Data Settings), or the title picks its
//     own feint and the command is not guaranteed to produce the named move;
//   * the player must be dribbling (a few are stationary, noted in `ability`);
//   * the named ability must be on the player, or the command does nothing.
//
// STATUS: every mapping below matches the title's command list, but no macro
// has been played inside the title. The frame counts are the sequencer's
// documented minimums (2-frame hold, 1-frame gap) plus the leg counts; they are
// provisional until docs/skill-moves.md's device checklist is filled in. Do not
// describe an entry as working until then.
//
// The order of kMoves is shared with the app: a wheel sector or a palette
// button stores an index into this table, so a move may be added at the end but
// never reordered or removed -- a saved layout would silently change otherwise.
// Indices 0-7 predate this pass and keep their meaning; 8-14 are the moves the
// verification added.
#include "stick_pulses.h"

#include <cstdint>

namespace skill
{
    using Step = ::input::StickPulses::Step;

    /**
     * One leg of a button script. Buttons are the face/shoulder/stick-click
     * bits; the analogue triggers are not available here (no move that needs
     * them is in the table -- see knock-the-ball in docs/skill-moves.md).
     */
    struct ButtonStep
    {
        std::uint16_t buttons = 0;
        std::uint8_t frames = 1;
    };

    /**
     * Plays a button script one guest frame at a time, the way StickPulses
     * plays a stick script. It owns the buttons it sets for the length of the
     * script and ends by sending one frame of nothing, so the press that
     * started it cannot merge with whatever the thumb does next.
     *
     * The values are the pad's own bits (runtime/android/android.h). They are
     * repeated here, rather than included from there, so this header and its
     * test stay free of Android headers; the test checks the values against the
     * pad the runtime uses.
     */
    class ButtonSeq
    {
    public:
        /** A move is a handful of clicks; more than this is a mistake. */
        static constexpr int kMaxSteps = 8;

        /** The pad bit for a click of the right stick (kRightThumb). */
        static constexpr std::uint16_t kR3 = 0x0080;

        /** The pad bit for the right bumper (kRightShoulder), the nutmeg's tap. */
        static constexpr std::uint16_t kRB = 0x0200;

        /** Hand over a whole script. It is copied, so the caller's array may go. */
        void Play(const ButtonStep* from, int count)
        {
            if (from == nullptr || count <= 0) { Clear(); return; }
            int taken = 0;
            for (; taken < count && taken < kMaxSteps; taken++)
            {
                ButtonStep step = from[taken];
                // A step that shows for no frames is not a step: it would make
                // a click exist only in the gap between two others.
                if (step.frames == 0) step.frames = 1;
                steps[taken] = step;
            }
            stepCount = taken;
            stepIndex = 0;
            stepLeft = int(steps[0].frames);
            active = true;
        }

        /**
         * One guest frame. Writes the buttons to add and returns whether this
         * script has an opinion about them; when it returns false the caller's
         * own state stands. Called once per poll, from the guest thread.
         */
        bool Sample(std::uint16_t& buttons)
        {
            if (!active)
            {
                buttons = 0;
                return false;
            }
            if (stepIndex >= stepCount)
            {
                // The end: one frame of nothing, so the release edge is as
                // clean as the press that started the sequence.
                active = false;
                buttons = 0;
                return true;
            }
            buttons = steps[stepIndex].buttons;
            if (--stepLeft <= 0)
            {
                stepIndex++;
                stepLeft = stepIndex < stepCount ? int(steps[stepIndex].frames) : 0;
            }
            return true;
        }

        /** Let go now, without draining: the pad was hidden or the mode changed. */
        void Clear()
        {
            active = false;
            stepCount = stepIndex = stepLeft = 0;
        }

        /** Whether a script is still being played out. */
        bool Active() const { return active; }

    private:
        ButtonStep steps[kMaxSteps]{};
        int stepCount = 0;
        int stepIndex = 0;
        int stepLeft = 0;
        bool active = false;
    };

    struct Move
    {
        /** Stable name, for the log and for a test that has to find it. */
        const char* id;
        Step steps[10];
        int count;
        /**
         * True when the gesture continues into a left-stick direction the
         * player picks. The wheel plays the right-stick half and leaves that
         * to the thumb; the flag also lets the editor say so.
         */
        bool leavesExitToPlayer;
        /** The button half, if the move has one. Empty for a stick-only move. */
        ButtonStep buttons[6];
        int buttonCount = 0;
        /** What the player supplies next (or during), for the editor and the log. */
        const char* exitHint = nullptr;
        /** The ability or context the title needs, or null for none. */
        const char* ability = nullptr;
    };

    namespace pads
    {
        // 0.85 of the pad's range, the magnitude runtime/skill_stick.h sends:
        // comfortably above the title's own ~14% deadzone.
        inline constexpr std::int16_t kFull = 27852;
        // The same at 45 degrees.
        inline constexpr std::int16_t kDiag = 19695;
    }

    inline constexpr Move kMoves[] =
    {
        // 0. Double Touch: RS up, then the window the player's own exit lands
        // in. The title also accepts RS down; up is used here so this entry and
        // the body feint below are not the literal same press. The same right
        // half is the first input of Drag Back (LS left), Inside Double Touch
        // (LS up), Inside Double Touch Behind (LS up-left) and Neymar's move
        // (LS down, Neymar only) -- the LS chooses which one.
        { "double-touch",
          { { 0, pads::kFull, 2 }, { 0, 0, 3 } }, 2, true,
          {}, 0,
          "then LS right (drag back: left, inside: up, behind: up-left, Neymar: down)",
          nullptr },

        // 1. Roulette: right stick all the way round. The one move that is a
        // rotation rather than a sequence of flicks, so it runs continuously.
        // Marseille Turn and Spin are the same rotation with another exit;
        // One-Foot Roulette needs Weak Foot Use 1 and exits right; McGeady
        // needs Marseille Turn.
        { "roulette",
          { {  pads::kFull,        0, 2, 1 },
            {  pads::kDiag,  pads::kDiag, 2, 1 },
            {         0,  pads::kFull, 2, 1 },
            { -pads::kDiag,  pads::kDiag, 2, 1 },
            { -pads::kFull,        0, 2, 1 },
            { -pads::kDiag, -pads::kDiag, 2, 1 },
            {         0, -pads::kFull, 2, 1 },
            {  pads::kDiag, -pads::kDiag, 2, 1 },
            {  pads::kFull,        0, 2, 1 } }, 9, true,
          {}, 0,
          "then LS down-right / right / up-right (spin: up or down)",
          nullptr },

        // 2. Rainbow Flick: two clicks of the right stick, the way the sheet
        // says (2x R3). No stick deflection at all, so a thumb on the glass is
        // never mistaken for part of the move.
        { "rainbow-flick",
          {}, 0, false,
          { { ButtonSeq::kR3, 1 }, { 0, 1 }, { ButtonSeq::kR3, 1 }, { 0, 3 } }, 4,
          nullptr, nullptr },

        // 3. Drag Back: RS down or up, then LS left. It used to hold RS down for
        // ten frames and claim there was no exit; the title wants a flick, and
        // the LS left is what finishes the move. Down is one of the two
        // directions the title accepts.
        { "drag-back",
          { { 0, -pads::kFull, 2 }, { 0, 0, 3 } }, 2, true,
          {}, 0,
          "then LS left",
          nullptr },

        // 4. Body Feint: one flick down, no exit required. Distinct from the
        // double touch above by direction; the title accepts up or down.
        { "body-feint",
          { { 0, -pads::kFull, 2 }, { 0, 0, 2 } }, 2, false,
          {}, 0,
          "none needed (steer as normal)",
          nullptr },

        // 5. Step Over Feint: a quarter circle, right, up-right, up, run
        // continuously so the title sees one sweep rather than three flicks;
        // then LS down-right. The title's own name for the family is
        // FEINT_KIND_SCISSORS_OUT / _LOOP; SCISSORS_OUT_HIGHSPEED is the same
        // input at speed (ability: ml_get_skill_scissors).
        { "step-over",
          { {  pads::kFull,        0, 2, 1 },
            {  pads::kDiag,  pads::kDiag, 2, 1 },
            {         0,  pads::kFull, 2, 0 } }, 3, true,
          {}, 0,
          "then LS down-right",
          nullptr },

        // 6. Ronaldo Chop: one flick to the right (not two), then the LS picks
        // the chop or the Cross Over Turn.
        { "ronaldo-chop",
          { { pads::kFull, 0, 3 } }, 1, true,
          {}, 0,
          "then LS down / down-right / up-right / up",
          nullptr },

        // 7. Elastico: one flick down-right; the title requires Flip Flap. The
        // Elastico to Diagonal Take and In and Out are the same press with
        // another LS.
        { "elastico",
          { {  pads::kDiag, -pads::kDiag, 3 } }, 1, true,
          {}, 0,
          "then LS up-right (diagonal take: down-right)",
          "Flip Flap" },

        // 8. Inside feint family: RS left, then the LS picks Inside Bounce or
        // Drag and Close (right), Draw and Open (right/down-right), Draw and
        // Close (up), L-Feint or Scotch Move (down). The draw pair is one
        // command in the title: PLAY_DATA_DRAW_OPEN and
        // FEINT_KIND_DRAWOPENCLOSE. One press, five named moves: they differ only in the LS and
        // in the ability that picks the animation.
        { "inside-feint",
          { { -pads::kFull, 0, 2 }, { 0, 0, 2 } }, 2, true,
          {}, 0,
          "then LS right (bounce / drag close), up (draw close), down (L-feint)",
          "Scotch Move for the bounce / Scotch" },

        // 9. Hocus Pocus: RS up-left, then LS down-right. Requires Flip Flap.
        { "hocus-pocus",
          { { -pads::kDiag, pads::kDiag, 2 }, { 0, 0, 2 } }, 2, true,
          {}, 0,
          "then LS down-right",
          "Flip Flap" },

        // 10. Reverse Elastico (Reverse Flip Flap, Out and In): RS up-right,
        // then LS down-right. Requires Flip Flap.
        { "reverse-elastico",
          { { pads::kDiag, pads::kDiag, 2 }, { 0, 0, 2 } }, 2, true,
          {}, 0,
          "then LS down-right",
          "Flip Flap" },

        // 11. Reverse Step Over: the quarter circle the other way, down,
        // down-right, right, continuous; no LS exit.
        { "reverse-step-over",
          { {         0, -pads::kFull, 2, 1 },
            {  pads::kDiag, -pads::kDiag, 2, 1 },
            {  pads::kFull,        0, 2, 0 } }, 3, false,
          {}, 0,
          nullptr,
          nullptr },

        // 12. Multiple Step Overs: RS right-down (a quarter), then the half
        // circle down-right-up, continuous. Seven legs, no frames of nothing
        // inside it, or the title sees separate flicks.
        { "multiple-step-overs",
          { {  pads::kFull,        0, 2, 1 },
            {  pads::kDiag, -pads::kDiag, 2, 1 },
            {         0, -pads::kFull, 2, 1 },
            {  pads::kDiag, -pads::kDiag, 2, 1 },
            {  pads::kFull,        0, 2, 1 },
            {  pads::kDiag,  pads::kDiag, 2, 1 },
            {         0,  pads::kFull, 2, 0 } }, 7, false,
          {}, 0,
          nullptr,
          nullptr },

        // 13. Sombrero family: hold R3, then push the LS toward the opponent.
        // The title's input is a click of the right stick, not a deflection,
        // and it must still be down while the LS moves -- so the hold is long
        // enough for a thumb to reach the stick. Requires Sombrero. The heel
        // flick and Bergkamp flick are the same command stationary; the
        // advanced sombrero is the same command while dribbling.
        { "sombrero",
          {}, 0, true,
          { { ButtonSeq::kR3, 24 } }, 1,
          "hold R3 while pushing LS toward the opponent (stationary)",
          "Sombrero" },

        // 14. Nutmeg / Run Around: RB twice, then the LS toward the opponent.
        // Riquelme's nutmeg is the same double tap with LS up-left. The gap is
        // what makes the two presses read as a double tap and not the speed
        // burst.
        { "nutmeg",
          {}, 0, true,
          { { ButtonSeq::kRB, 2 }, { 0, 2 }, { ButtonSeq::kRB, 2 } }, 3,
          "then LS toward the opponent (Riquelme: up-left)",
          nullptr },
    };

    inline constexpr int kMoveCount = int(sizeof(kMoves) / sizeof(kMoves[0]));

    /** The move at an index, or null when the app asked for one that is not here. */
    inline const Move* At(int index)
    {
        if (index < 0 || index >= kMoveCount) return nullptr;
        return &kMoves[index];
    }
}

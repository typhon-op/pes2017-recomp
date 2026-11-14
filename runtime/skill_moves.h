#pragma once
// The one-tap skill moves: what each palette button sends, in guest frames.
//
// A move is a short script for the *right* stick only. That is deliberate. The
// console gestures on the sheet end in a left-stick direction the player
// chooses -- "then Left Stick diagonal" -- and a button cannot know which way
// the player wants to go. So a palette button plays the fiddly right-stick half
// and leaves the left stick alone: the player's own thumb supplies the exit,
// which is exactly what it does on a pad, minus the part that needs a stick.
//
// Nothing here drives the left stick, so movement is never frozen by a skill:
// the sequencer takes the right stick and only the right stick.
//
// The frame counts are the ones that look right on paper and are the part that
// must be checked against the title on a device. They are in frames, not
// milliseconds, so they mean the same thing at 30 fps and at 60 (see
// runtime/stick_pulses.h); a move that wants to feel slower needs more frames,
// not a longer timer. `tools/` and PES17_INPUT_SCRIPT can replay any of these
// as `rx+`/`ry+` holds before they are trusted.
//
// The order of kMoves is shared with the app: ControlElement.value is an index
// into this table, so a move may be added at the end but never reordered.
#include "stick_pulses.h"

#include <cstdint>

namespace skill
{
    using Step = ::input::StickPulses::Step;

    struct Move
    {
        /** Stable name, for the log and for a test that has to find it. */
        const char* id;
        Step steps[10];
        int count;
        /**
         * True when the gesture continues into a left-stick direction the
         * player picks. The button plays the right-stick half and leaves that
         * to the thumb; the flag is here so it is written down rather than
         * assumed.
         */
        bool leavesExitToPlayer;
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
        // Double Touch: right stick left, then right. The sheet's "then Left
        // Stick diagonal" is the player's; the trailing neutral is the window
        // it lands in.
        { "double-touch",
          { { -pads::kFull, 0, 2 }, { pads::kFull, 0, 2 }, { 0, 0, 3 } }, 3, true },

        // Roulette: right stick all the way round. The one move that is a
        // rotation rather than a sequence of flicks, so it runs continuously.
        { "roulette",
          { {  pads::kFull,        0, 2, 1 },
            {  pads::kDiag,  pads::kDiag, 2, 1 },
            {         0,  pads::kFull, 2, 1 },
            { -pads::kDiag,  pads::kDiag, 2, 1 },
            { -pads::kFull,        0, 2, 1 },
            { -pads::kDiag, -pads::kDiag, 2, 1 },
            {         0, -pads::kFull, 2, 1 },
            {  pads::kDiag, -pads::kDiag, 2, 1 },
            {  pads::kFull,        0, 2, 1 } }, 9, false },

        // Rainbow Flick: right stick twice, the same way both times.
        { "rainbow-flick",
          { { 0, pads::kFull, 2 }, { 0, pads::kFull, 2 } }, 2, false },

        // Drag Back: hold the right stick back, then let go.
        { "drag-back",
          { { 0, -pads::kFull, 10 } }, 1, false },

        // Body Feint: right stick left, then right. The sheet gives this the
        // same right-stick shape as the Double Touch; the difference is that
        // nothing is expected of the left stick afterwards, so there is no
        // trailing window.
        { "body-feint",
          { { -pads::kFull, 0, 2 }, { pads::kFull, 0, 2 } }, 2, false },
    };

    inline constexpr int kMoveCount = int(sizeof(kMoves) / sizeof(kMoves[0]));

    /** The move at an index, or null when the app asked for one that is not here. */
    inline const Move* At(int index)
    {
        if (index < 0 || index >= kMoveCount) return nullptr;
        return &kMoves[index];
    }
}

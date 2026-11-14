#pragma once
// The skill stick's directions, held for whole guest frames.
//
// A touchscreen reports positions; the title samples the pad once a frame. A
// direction that exists for less than a frame therefore never exists as far as
// the title is concerned -- and a skill move is recognised from the *edges*
// between directions, so a lost edge is a lost move. This is the same problem
// input_pulses.h answers for buttons, and it needs the same answer for a stick:
// what the game is told is queued, and the poll spends it one frame at a time.
//
// Two rules make a flick legible to a recogniser that wants to see separate
// inputs:
//
//   * a new direction is held for at least kMinHoldFrames, whatever the finger
//     does next, so the edge into it cannot fall between two polls;
//   * two directions in a row are separated by kGapFrames of exactly nothing,
//     so "left, then right" reads as two moves rather than as one sweep round
//     the socket -- which is the difference between a Double Touch and a
//     Roulette.
//
// Timing is counted in polls, not milliseconds, so the same sequence means the
// same thing at 30 Hz and at 60 Hz (the app has an FPS limit).
//
// There are two ways the stick is driven, and they do not share:
//
//   * Live. Set is called from the touch thread as the thumb moves. A change
//     that arrives during a dwell is remembered as one pending direction, so
//     the direction on screen always gets its full dwell before anything may
//     replace it: a stray touch or a shift of the thumb cannot cut a feint's
//     window short. The newest request wins, so a fast thumb cannot build a
//     backlog.
//   * Played back. Play hands over a sequence of steps and owns the stick until
//     the last one is spent. Set is ignored for its whole length: a move being
//     played is one gesture from the title's point of view, and a thumb resting
//     on the glass, or landing on it, must not cancel it half way. Play is what
//     a one-tap skill or a recorded macro uses, and it is the only thing that
//     can interrupt another played sequence.
//
// Set and Play are called from the touch thread, Sample from the guest thread,
// so the state is behind a mutex -- the same shape as the rest of the input
// layer, and cheap enough at one call a frame.
#include <algorithm>
#include <cstdint>
#include <mutex>

namespace input
{
    class StickPulses
    {
    public:
        /** How many frames a direction is shown before another may replace it. */
        static constexpr int kMinHoldFrames = 2;

        /** The frames of nothing between two directions. */
        static constexpr int kGapFrames = 1;

        /** How long a played-back sequence may be. A move is a handful of steps. */
        static constexpr int kMaxSteps = 24;

        /**
         * One leg of a played-back move: a deflection, how long it shows, and
         * whether the next leg follows it without a frame of nothing between.
         * The gap is what makes two flicks read as two flicks; a rotation is
         * the one move that wants to be continuous, so it opts out.
         */
        struct Step
        {
            std::int16_t x = 0, y = 0;
            std::uint8_t frames = 1;
            std::uint8_t noGap = 0;
        };

        /**
         * Ask for a direction as a thumb moves. Ignored while a played-back
         * sequence is running, which is what keeps a stray touch from cutting a
         * move short. The value is what the stick should read from now on: a
         * held direction keeps being sent until it is changed, which is what
         * "hold the stick back" means. (0, 0) lets go.
         */
        void Set(std::int16_t x, std::int16_t y)
        {
            std::lock_guard guard(lock);
            if (phase == Phase::Play) return;   // a played move owns the stick
            if (phase == Phase::Idle)
            {
                // Nothing to interrupt: adopt it at once.
                if (x == 0 && y == 0) return;
                heldX = x; heldY = y;
                phase = Phase::Hold;
                hold = kMinHoldFrames;
                hasPending = false;
                return;
            }
            // Mid-sequence: the newest request wins, and the one it replaced is
            // dropped -- a thumb that has already moved on is not played back.
            pendingX = x; pendingY = y; hasPending = true;
        }

        /**
         * Hand over a whole move. It is copied, so the caller's array may go out
         * of scope, and anything the thumb had asked for is dropped: from here
         * until the last step is spent the sequence is the only thing on the
         * stick. Returns how many steps were taken, which is `count` unless the
         * move is longer than kMaxSteps.
         */
        int Play(const Step* from, int count)
        {
            if (from == nullptr || count <= 0) return 0;
            std::lock_guard guard(lock);
            int taken = 0;
            for (; taken < count && taken < kMaxSteps; taken++)
            {
                Step step = from[taken];
                // A step that shows for no frames is not a step: it would make
                // a direction exist only in the gap between two others.
                if (step.frames == 0) step.frames = 1;
                steps[taken] = step;
            }
            stepCount = taken;
            stepIndex = 0;
            stepLeft = int(steps[0].frames);
            gapPending = false;
            phase = Phase::Play;
            hold = 0;
            heldX = heldY = 0;
            pendingX = pendingY = 0;
            hasPending = false;
            return taken;
        }

        /** Let go now, without draining: the pad was hidden or the mode changed. */
        void Clear()
        {
            std::lock_guard guard(lock);
            phase = Phase::Idle;
            hold = 0;
            gap = 0;
            heldX = heldY = 0;
            pendingX = pendingY = 0;
            hasPending = false;
            stepCount = stepIndex = stepLeft = 0;
            gapPending = false;
        }

        /**
         * One guest frame. Writes the stick to send and returns whether this
         * sequence has an opinion about it; when it returns false the caller's
         * own stick stands. Called once per poll, from the guest thread.
         */
        bool Sample(std::int16_t& x, std::int16_t& y)
        {
            std::lock_guard guard(lock);

            if (phase == Phase::Idle)
            {
                x = y = 0;
                return false;
            }

            if (phase == Phase::Play)
            {
                if (gapPending)
                {
                    gapPending = false;
                    x = y = 0;
                    return true;
                }
                if (stepIndex >= stepCount)
                {
                    // The end: one frame of nothing, so the move has a release
                    // edge as clean as the press that started it -- and so a
                    // thumb waiting on the glass cannot take the stick on the
                    // same frame the move finished.
                    phase = Phase::Idle;
                    x = y = 0;
                    return true;
                }
                const Step& step = steps[stepIndex];
                x = step.x;
                y = step.y;
                if (--stepLeft <= 0)
                {
                    const bool wasDirection = step.x != 0 || step.y != 0;
                    stepIndex++;
                    const bool more = stepIndex < stepCount;
                    const bool nextDirection =
                        more && (steps[stepIndex].x != 0 || steps[stepIndex].y != 0);
                    if (more) stepLeft = int(steps[stepIndex].frames);
                    if (wasDirection && nextDirection && step.noGap == 0) gapPending = true;
                }
                return true;
            }

            if (phase == Phase::Gap)
            {
                // A frame of nothing, so the edge down is as visible as the
                // edge up. The frame this phase was entered on was the first.
                x = y = 0;
                if (--gap > 0) return true;
                heldX = pendingX; heldY = pendingY;
                hasPending = false;
                if (heldX == 0 && heldY == 0) { phase = Phase::Idle; return true; }
                phase = Phase::Hold;
                hold = kMinHoldFrames;
                x = heldX; y = heldY;
                return true;
            }

            // Holding. The dwell is spent before anything may replace it, so
            // even a one-frame flick is kMinHoldFrames frames wide and a thumb
            // that shifts mid-move cannot cut it short.
            if (hold == 0 && hasPending)
            {
                if (pendingX == heldX && pendingY == heldY)
                {
                    hasPending = false;             // asked for what is showing
                }
                else if ((heldX != 0 || heldY != 0) && (pendingX != 0 || pendingY != 0))
                {
                    phase = Phase::Gap;
                    gap = kGapFrames;
                    x = y = 0;
                    return true;                    // this frame is the gap's first
                }
                else
                {
                    // To or from neutral needs no gap: the zero is the gap.
                    heldX = pendingX; heldY = pendingY;
                    hasPending = false;
                    if (heldX == 0 && heldY == 0)
                    {
                        phase = Phase::Idle;
                        x = y = 0;
                        return true;
                    }
                    hold = kMinHoldFrames;
                }
            }

            x = heldX; y = heldY;
            if (hold > 0) --hold;
            return true;
        }

        /** Whether a sequence is still being played out. */
        bool Active() const
        {
            std::lock_guard guard(lock);
            return phase != Phase::Idle;
        }

        /**
         * Whether a played-back move is running. While it is, live input is not
         * heard: the caller should not offer it one either, so the thumb's own
         * reading is not left half-way through a gesture nobody is watching.
         */
        bool Playing() const
        {
            std::lock_guard guard(lock);
            return phase == Phase::Play;
        }

    private:
        enum class Phase { Idle, Hold, Gap, Play };

        mutable std::mutex lock;
        Phase phase = Phase::Idle;
        int hold = 0;
        int gap = 0;
        std::int16_t heldX = 0, heldY = 0;
        std::int16_t pendingX = 0, pendingY = 0;
        bool hasPending = false;

        // The played-back move, copied so a caller's array may go away.
        Step steps[kMaxSteps]{};
        int stepCount = 0;
        int stepIndex = 0;
        int stepLeft = 0;
        bool gapPending = false;
    };
}

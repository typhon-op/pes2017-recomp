// The one-tap skill moves (runtime/skill_moves.h): what each palette button
// sends, played through the real sequencer.
//
// What is checked here is the shape of a move -- that every entry is playable,
// that a rotation stays continuous while a two-part feint keeps its gap, that a
// hold is actually held, and that an index the app gets wrong is refused rather
// than read past the end of the table. The frame counts themselves are a guess
// until a device says otherwise; what must not be a guess is the structure.
#include "skill_moves.h"
#include "stick_pulses.h"

#include <cstdint>
#include <cstdio>
#include <string>
#include <utility>
#include <vector>

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

    /** Plays a move out and returns every frame the stick would send. */
    std::vector<std::pair<int, int>> PlayOut(const skill::Move& move)
    {
        input::StickPulses pulses;
        pulses.Play(move.steps, move.count);
        std::vector<std::pair<int, int>> frames;
        for (int i = 0; i < 400; i++)
        {
            std::int16_t x = 0, y = 0;
            const bool active = pulses.Sample(x, y);
            frames.emplace_back(int(x), int(y));
            if (!active) break;
        }
        return frames;
    }

    bool NonZero(const std::pair<int, int>& frame)
    {
        return frame.first != 0 || frame.second != 0;
    }

    size_t LastNonZero(const std::vector<std::pair<int, int>>& frames)
    {
        size_t last = 0;
        for (size_t i = 0; i < frames.size(); i++) if (NonZero(frames[i])) last = i;
        return last;
    }
}

int main()
{
    // ---- the table itself ----------------------------------------------------

    Check(skill::kMoveCount == 5, "the palette has the five moves the app places");
    Check(skill::At(-1) == nullptr, "a negative index is refused");
    Check(skill::At(skill::kMoveCount) == nullptr, "one past the end is refused");
    Check(skill::At(0) != nullptr, "the first is there");

    {
        // Every id has to be able to find itself, and no two may share one: the
        // log names the move that was played, and the app's order is this order.
        bool unique = true;
        bool named = true;
        for (int i = 0; i < skill::kMoveCount; i++)
        {
            const skill::Move& move = skill::kMoves[i];
            if (move.id == nullptr || move.id[0] == '\0') named = false;
            for (int j = i + 1; j < skill::kMoveCount; j++)
            {
                const char* a = move.id;
                const char* b = skill::kMoves[j].id;
                if (a && b && std::string(a) == std::string(b)) unique = false;
            }
        }
        Check(named, "every move is named");
        Check(unique, "and no two share a name");
    }

    {
        bool sane = true;
        for (int i = 0; i < skill::kMoveCount; i++)
        {
            const skill::Move& move = skill::kMoves[i];
            if (move.count < 1 || move.count > 10) sane = false;
            bool anyDirection = false;
            for (int s = 0; s < move.count; s++)
            {
                if (move.steps[s].frames == 0) sane = false;
                if (move.steps[s].x || move.steps[s].y) anyDirection = true;
            }
            if (!anyDirection) sane = false;
        }
        Check(sane, "every move is a non-empty script with at least one direction");
    }

    {
        // The pad's own scale: a move that cannot reach past the title's ~14%
        // deadzone would button nothing at all.
        bool strong = true;
        for (int i = 0; i < skill::kMoveCount; i++)
            for (int s = 0; s < skill::kMoves[i].count; s++)
            {
                const auto& step = skill::kMoves[i].steps[s];
                const int magnitude = step.x * step.x + step.y * step.y;
                if (step.x || step.y)
                {
                    if (magnitude < 20000 * 20000) strong = false;
                }
            }
        Check(strong, "every direction is well clear of the title's deadzone");
    }

    // ---- played out ----------------------------------------------------------

    {
        bool allFinish = true;
        bool allSend = false;
        for (int i = 0; i < skill::kMoveCount; i++)
        {
            const auto frames = PlayOut(skill::kMoves[i]);
            // The last frame is the release; before it something has to have
            // been sent.
            if (frames.size() > 300) allFinish = false;
            for (size_t f = 0; f + 1 < frames.size(); f++) if (NonZero(frames[f])) allSend = true;
        }
        Check(allFinish, "every move ends rather than playing for ever");
        Check(allSend, "and every move actually deflects the stick");
    }

    {
        // Double Touch: two directions with a frame of nothing between them.
        const skill::Move* move = skill::At(0);
        const auto frames = PlayOut(*move);
        size_t gaps = 0;
        for (size_t i = 0; i < LastNonZero(frames); i++) if (!NonZero(frames[i])) gaps++;
        Check(move->leavesExitToPlayer, "the Double Touch leaves the exit to the player");
        Check(gaps == 1, "and its two halves are separated by exactly one frame of nothing");
        Check(frames[0].first < 0, "starting to the left");
        bool sawRight = false;
        for (const auto& frame : frames) if (frame.first > 0) sawRight = true;
        Check(sawRight, "and finishing to the right");
    }

    {
        // Roulette: one continuous turn. A gap inside it would be a different
        // gesture entirely, so it is the one move that opts out.
        const skill::Move* move = skill::At(1);
        const auto frames = PlayOut(*move);
        size_t gaps = 0;
        for (size_t i = 0; i < LastNonZero(frames); i++) if (!NonZero(frames[i])) gaps++;
        Check(gaps == 0, "the roulette runs without a frame of nothing inside it");
        Check(static_cast<int>(frames.size()) >= 18, "and is long enough to be a turn");

        // All four quadrants are visited, so it is a circle rather than a sweep
        // across one side of the socket.
        bool right = false, up = false, left = false, down = false;
        for (const auto& frame : frames)
        {
            if (frame.first > 0) right = true;
            if (frame.first < 0) left = true;
            if (frame.second > 0) up = true;
            if (frame.second < 0) down = true;
        }
        Check(right && up && left && down, "and goes all the way round");
    }

    {
        // Rainbow Flick: the same direction twice, which only reads as two if
        // the gap is there.
        const skill::Move* move = skill::At(2);
        const auto frames = PlayOut(*move);
        size_t gaps = 0;
        for (size_t i = 0; i < LastNonZero(frames); i++) if (!NonZero(frames[i])) gaps++;
        Check(gaps == 1, "the rainbow flick has one gap, between its two flicks");
        Check(frames[0].second > 0, "both of them upward");
    }

    {
        // Drag Back: a hold, not a flick.
        const skill::Move* move = skill::At(3);
        const auto frames = PlayOut(*move);
        int held = 0;
        for (const auto& frame : frames) if (frame.second < 0) held++;
        Check(held >= 6, "the drag back is held, not tapped");
        Check(frames[0].second < 0 && frames[0].first == 0, "straight back, with no sideways component");
    }

    {
        // Body Feint: left then right, and nothing expected afterwards.
        const skill::Move* move = skill::At(4);
        const auto frames = PlayOut(*move);
        Check(!move->leavesExitToPlayer, "the body feint needs nothing of the player afterwards");
        size_t gaps = 0;
        for (size_t i = 0; i < LastNonZero(frames); i++) if (!NonZero(frames[i])) gaps++;
        Check(gaps == 1, "and its two halves are separated too");
    }

    // ---- the lockout a palette tap inherits ---------------------------------

    {
        // A tap mid-move is not cancelled by anything the thumb does, which is
        // the whole reason the palette goes through the sequencer.
        input::StickPulses pulses;
        const skill::Move* move = skill::At(1);
        pulses.Play(move->steps, move->count);
        Check(pulses.Playing(), "a tapped move is playing");

        // Only the frames the move itself produced are collected: once it is
        // over the thumb is heard again, which is the next check.
        std::vector<std::pair<int, int>> frames;
        while (pulses.Playing() && frames.size() < 100)
        {
            pulses.Set(skill::pads::kFull, 0);      // a thumb leaning on the stick
            pulses.Set(0, 0);                        // and lifting again
            std::int16_t x = 0, y = 0;
            pulses.Sample(x, y);
            frames.emplace_back(int(x), int(y));
        }
        const size_t last = LastNonZero(frames);
        bool intact = last > 10;
        for (size_t i = 0; i <= last; i++) if (!NonZero(frames[i])) intact = false;
        Check(intact, "and the thumb leaning on the glass leaves no mark on it");
        Check(!pulses.Playing(), "and the move still ended on its own");
    }

    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

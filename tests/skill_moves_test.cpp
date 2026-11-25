// The one-tap skill moves (runtime/skill_moves.h): what each wheel sector
// sends, played through the real sequencer.
//
// What is checked here is the shape of a move -- that every entry is playable,
// that a rotation stays continuous while a two-part feint keeps its gap, that a
// hold is actually held, that the verified directions are the ones the title's
// command list names, and that an index the app gets wrong is refused rather
// than read past the end of the table. The frame counts themselves are a guess
// until a device says otherwise (docs/skill-moves.md); what must not be a guess
// is the structure and the direction of every input.
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

    /** The frames a button script would send, one per sample, ending clear. */
    std::vector<std::uint16_t> PlayClicks(const skill::Move& move)
    {
        skill::ButtonSeq clicks;
        clicks.Play(move.buttons, move.buttonCount);
        std::vector<std::uint16_t> sent;
        for (int i = 0; i < 400; i++)
        {
            std::uint16_t bits = 0;
            const bool active = clicks.Sample(bits);
            sent.push_back(bits);
            if (!active) break;
        }
        return sent;
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

    bool Continuous(const std::vector<std::pair<int, int>>& frames)
    {
        const size_t last = LastNonZero(frames);
        for (size_t i = 0; i < last; i++) if (!NonZero(frames[i])) return false;
        return true;
    }
}

int main()
{
    // ---- the table itself ----------------------------------------------------

    // 0-7 are the original eight the wheel binds; 8-14 are the moves the
    // verification added. The count is a contract with SkillMoves.kt.
    Check(skill::kMoveCount == 15, "the table carries the eight original moves and the seven verified ones");
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
        // The metadata the log and the editor show. Every move that leaves an
        // exit says which one; the closed circles and the rainbow say nothing
        // because there is nothing to say.
        bool hinted = true;
        for (int i = 0; i < skill::kMoveCount; i++)
        {
            const skill::Move& move = skill::kMoves[i];
            if (!move.leavesExitToPlayer) continue;
            if (move.exitHint == nullptr || move.exitHint[0] == '\0') hinted = false;
        }
        Check(hinted, "every move that leaves an exit says what the player owes");
        Check(skill::kMoves[2].exitHint == nullptr, "the rainbow has no exit to hint at");
        Check(skill::kMoves[11].exitHint == nullptr, "the reverse step over is closed");
        Check(skill::kMoves[12].exitHint == nullptr, "the multiple step overs are closed");

        auto names = [](const skill::Move& move, const char* text) {
            return move.ability != nullptr &&
                   std::string(move.ability).find(text) != std::string::npos;
        };
        Check(names(skill::kMoves[7], "Flip Flap"), "the elastico names Flip Flap");
        Check(names(skill::kMoves[8], "Scotch"), "the inside family names Scotch Move");
        Check(names(skill::kMoves[13], "Sombrero"), "the sombrero names Sombrero");
        Check(skill::kMoves[14].ability == nullptr, "the nutmeg needs no ability");
    }

    {
        // The bit values the button scripts use are the pad's own. The test
        // keeps this header free of Android headers, so the values are pinned
        // here instead (runtime/android/android.h: kRightThumb 0x0080,
        // kRightShoulder 0x0200).
        Check(skill::ButtonSeq::kR3 == 0x0080, "R3 is the pad's right-stick click");
        Check(skill::ButtonSeq::kRB == 0x0200, "RB is the pad's right bumper");
    }

    {
        bool sane = true;
        for (int i = 0; i < skill::kMoveCount; i++)
        {
            const skill::Move& move = skill::kMoves[i];
            if (move.count < 0 || move.count > 10) sane = false;
            if (move.buttonCount < 0 || move.buttonCount > 6) sane = false;
            // A move drives the stick, the buttons, or both -- the rainbow is
            // clicks only -- but never neither.
            if (move.count == 0 && move.buttonCount == 0) sane = false;
            bool anyDirection = false;
            for (int s = 0; s < move.count; s++)
            {
                if (move.steps[s].frames == 0) sane = false;
                if (move.steps[s].x || move.steps[s].y) anyDirection = true;
            }
            bool anyClick = false;
            for (int b = 0; b < move.buttonCount; b++)
            {
                if (move.buttons[b].frames == 0) sane = false;
                if (move.buttons[b].buttons) anyClick = true;
            }
            if (move.count > 0 && !anyDirection) sane = false;
            if (move.buttonCount > 0 && !anyClick) sane = false;
        }
        Check(sane, "every move is a non-empty script of stick frames, clicks, or both");
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
        Check(allSend, "and the stick moves actually deflect the stick");
    }

    {
        // Double Touch: one flick up, then the window the player's exit lands
        // in. The title also allows down; up is what this entry sends.
        const skill::Move* move = skill::At(0);
        const auto frames = PlayOut(*move);
        Check(move->leavesExitToPlayer, "the Double Touch leaves the exit to the player");
        Check(frames[0].first == 0 && frames[0].second > 0, "it flicks the stick up");
        Check(NonZero(frames[1]) && !NonZero(frames[2]),
              "and then holds a neutral window for the exit");
        Check(move->exitHint != nullptr, "and says which left-stick direction finishes it");
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
        // Rainbow Flick: the sheet's 2x R3, played as button clicks, so it
        // deflects nothing. Only a release between the clicks makes it two.
        const skill::Move* move = skill::At(2);
        Check(move->count == 0, "the rainbow flick leaves the stick alone");
        Check(move->buttonCount == 4, "and is four button frames long");

        const auto sent = PlayClicks(*move);
        int presses = 0;
        int gaps = 0;
        for (size_t i = 0; i < sent.size(); i++)
        {
            if (sent[i] == skill::ButtonSeq::kR3) presses++;
            if (sent[i] == 0 && i + 1 < sent.size()) gaps++;
        }
        Check(presses == 2, "the rainbow is two R3 clicks");
        Check(gaps >= 1, "with a release between them, so they read as two");
        Check(!sent.empty() && sent.back() == 0, "and it ends with everything let go");
    }

    {
        // Drag Back: a flick, then the player's LS left. It used to be a long
        // hold with no exit, which was neither what the title asks for nor a
        // different press from the body feint.
        const skill::Move* move = skill::At(3);
        const auto frames = PlayOut(*move);
        Check(frames[0].second < 0 && frames[0].first == 0, "straight back, with no sideways component");
        Check(frames.size() <= 8, "as a flick, not a hold");
        Check(move->leavesExitToPlayer, "and the left stick finishes it");
        Check(move->exitHint != nullptr, "with the direction written down");
    }

    {
        // Body Feint: one flick down, no exit required.
        const skill::Move* move = skill::At(4);
        const auto frames = PlayOut(*move);
        Check(frames[0].first == 0 && frames[0].second < 0, "it flicks the stick down");
        Check(frames[0].second != PlayOut(*skill::At(0))[0].second,
              "and so is a different press from the Double Touch");
    }

    {
        // Step Over: a continuous quarter circle, right, up-right, up -- no
        // frame of nothing in the middle, or the title would see three flicks.
        const skill::Move* move = skill::At(5);
        const auto frames = PlayOut(*move);
        Check(move->count == 3, "the step over is three legs");
        Check(frames[0].first > 0 && frames[0].second == 0, "starting to the right");
        Check(Continuous(frames), "with no gap between the legs");
        Check(move->steps[2].x == 0 && move->steps[2].y > 0, "and finishing upward");
    }

    {
        // Ronaldo Chop: one flick right, the exit is the player's.
        const skill::Move* move = skill::At(6);
        const auto frames = PlayOut(*move);
        Check(frames[0].first > 0 && frames[0].second == 0, "the chop flicks the stick right");
        Check(frames.size() <= 5, "once, and not for long");
        Check(move->ability == nullptr, "with no ability gate");
    }

    {
        // Elastico: one flick down-right, Flip Flap required.
        const skill::Move* move = skill::At(7);
        const auto frames = PlayOut(*move);
        Check(frames[0].first > 0 && frames[0].second < 0, "the elastico flicks down-right");
        Check(move->leavesExitToPlayer, "and leaves the exit to the player");
        Check(move->ability != nullptr, "and names Flip Flap");
    }

    {
        // Inside Feint family: one flick left, then the LS picks Inside Bounce,
        // Drag and Close, Draw and Close, L-Feint or Scotch Move.
        const skill::Move* move = skill::At(8);
        const auto frames = PlayOut(*move);
        Check(frames[0].first < 0 && frames[0].second == 0, "the inside family flicks the stick left");
        Check(move->leavesExitToPlayer, "and the player's left stick chooses the move");
        Check(move->ability != nullptr, "and names the Scotch Move gate");
    }

    {
        // Hocus Pocus: up-left, then LS down-right, Flip Flap.
        const skill::Move* move = skill::At(9);
        const auto frames = PlayOut(*move);
        Check(frames[0].first < 0 && frames[0].second > 0, "hocus pocus flicks up-left");
        Check(move->leavesExitToPlayer, "and waits for the left stick");
    }

    {
        // Reverse Elastico: up-right, then LS down-right, Flip Flap.
        const skill::Move* move = skill::At(10);
        const auto frames = PlayOut(*move);
        Check(frames[0].first > 0 && frames[0].second > 0, "reverse elastico flicks up-right");
        Check(move->ability != nullptr, "and names Flip Flap");
    }

    {
        // Reverse Step Over: the quarter circle the other way, down through
        // down-right to right, continuous.
        const skill::Move* move = skill::At(11);
        const auto frames = PlayOut(*move);
        Check(move->count == 3, "reverse step over is three legs");
        Check(frames[0].first == 0 && frames[0].second < 0, "starting down");
        Check(Continuous(frames), "with no gap between the legs");
        Check(move->steps[2].x > 0 && move->steps[2].y == 0, "and finishing right");
    }

    {
        // Multiple Step Overs: right-down quarter then the down-right-up half
        // circle, all one sweep.
        const skill::Move* move = skill::At(12);
        const auto frames = PlayOut(*move);
        Check(move->count == 7, "multiple step overs are seven legs");
        Check(Continuous(frames), "with no gap inside it");
        bool down = false, up = false, right = false;
        for (const auto& frame : frames)
        {
            if (frame.second < 0) down = true;
            if (frame.second > 0) up = true;
            if (frame.first > 0) right = true;
        }
        Check(down && up && right, "and sweeps down, right and up");
    }

    {
        // Sombrero: hold R3, then the player pushes LS. The click has to be
        // held, not tapped, or the title never sees the sombrero.
        const skill::Move* move = skill::At(13);
        Check(move->count == 0, "the sombrero drives no stick direction itself");
        Check(move->buttonCount == 1, "and is one button step");
        Check(move->buttons[0].buttons == skill::ButtonSeq::kR3, "an R3 click");
        const auto sent = PlayClicks(*move);
        int held = 0;
        for (auto bits : sent) if (bits == skill::ButtonSeq::kR3) held++;
        Check(held >= 20, "held long enough for a thumb to reach the stick");
        Check(!sent.empty() && sent.back() == 0, "and released at the end");
        Check(move->leavesExitToPlayer, "with the left stick left to the player");
        Check(move->ability != nullptr, "and names Sombrero");
    }

    {
        // Nutmeg: RB twice with a release between, then the player's LS.
        const skill::Move* move = skill::At(14);
        Check(move->count == 0, "the nutmeg drives no stick direction itself");
        const auto sent = PlayClicks(*move);
        int presses = 0;
        int gaps = 0;
        for (size_t i = 0; i < sent.size(); i++)
        {
            // Count presses, not frames: each press is held for two frames.
            if (sent[i] == skill::ButtonSeq::kRB && (i == 0 || sent[i - 1] != skill::ButtonSeq::kRB))
                presses++;
            if (sent[i] == 0 && i + 1 < sent.size()) gaps++;
        }
        Check(presses == 2, "it is two RB presses");
        Check(gaps >= 1, "with a release between them");
        Check(!sent.empty() && sent.back() == 0, "and it ends clear");
        Check(move->leavesExitToPlayer, "and the left stick is the player's");
    }

    {
        // A button script is ignored until it is played, and letting go of it
        // early sends nothing more: the pad must not keep an R3 held.
        skill::ButtonSeq clicks;
        std::uint16_t bits = 0;
        Check(!clicks.Sample(bits), "an empty button sequence has no opinion");
        clicks.Play(skill::kMoves[2].buttons, skill::kMoves[2].buttonCount);
        clicks.Sample(bits);
        clicks.Clear();
        Check(!clicks.Sample(bits), "and clearing it stops it at once");
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

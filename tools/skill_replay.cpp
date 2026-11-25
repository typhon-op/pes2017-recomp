// Prints a PES17_INPUT_SCRIPT sequence for every skill move in
// runtime/skill_moves.h, for working through the device checklist in
// docs/skill-moves.md.
//
// The wheel and the input script must send the same gesture for a test to mean
// anything, and the script is the only way to send it without a thumb. This
// translates the table's frame counts into the script's wall-clock entries:
// each direction is held for its frames, a leg that follows another starts on
// the frame the previous one ended (so a rotation stays one sweep), and a leg
// that is meant to have a gap leaves the axis up for the gap frames.
//
//   g++ -std=c++20 -I runtime -o skill_replay tools/skill_replay.cpp
//   ./skill_replay                 # 60 Hz, first press 20 s into the run
//   ./skill_replay --fps 30        # when the app's FPS limit is 30
//   ./skill_replay --at 45 --index 1
//
// The script is wall-clock: set --fps to the rate the title is actually drawn
// at, or a two-frame hold is one frame at 30 and two thirds of one at 90. At a
// steady rate a one-frame click and a one-frame gap each meet exactly one poll,
// but the script is not queued the way the wheel's presses are: a dropped frame
// can swallow one, so the wheel remains the last word on the rainbow's 17 ms
// release. The start time puts the gesture inside a match; it does not move the
// player, so the ball still has to be at the player's feet, Auto-Feint off, and
// the ability on the squad where the table names one.
//
// A diagonal is two presses at the same time, which the script reads as both
// axes. They are full deflection, where the wheel sends 0.85 -- the same
// direction, a little further out.
#include "skill_moves.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace
{
    struct Press
    {
        long startMs = 0;
        long heldMs = 0;
        const char* name = nullptr;
    };

    // The names PES17_INPUT_SCRIPT knows for the pad bits in ButtonSeq. The
    // table uses only R3 and RB today; the rest are here so a move added with
    // another button still prints a script rather than a blank.
    const char* ButtonName(std::uint16_t bit)
    {
        switch (bit)
        {
            case 0x0001: return "up";
            case 0x0002: return "down";
            case 0x0004: return "left";
            case 0x0008: return "right";
            case 0x0010: return "start";
            case 0x0020: return "back";
            case 0x0040: return "lthumb";
            case 0x0080: return "rthumb";
            case 0x0100: return "lb";
            case 0x0200: return "rb";
            case 0x1000: return "a";
            case 0x2000: return "b";
            case 0x4000: return "x";
            case 0x8000: return "y";
            default: return nullptr;
        }
    }

    long FrameMs(long frame, double fps)
    {
        return std::lround(frame * 1000.0 / fps);
    }

    // The stick half, in the order PES17_INPUT_SCRIPT evaluates it. Sets
    // lengthFrames to what StickPulses plays: the steps, the gaps between two
    // directions, and the frame of nothing at the end.
    std::vector<Press> StickPresses(const skill::Move& move, double fps, long& lengthFrames)
    {
        std::vector<Press> presses;
        long frame = 0;
        for (int i = 0; i < move.count; i++)
        {
            const skill::Step& step = move.steps[i];
            const bool direction = step.x != 0 || step.y != 0;
            if (direction)
            {
                const long start = FrameMs(frame, fps);
                const long held = FrameMs(frame + step.frames, fps) - start;
                if (step.x != 0) presses.push_back({ start, held, step.x > 0 ? "rx+" : "rx-" });
                if (step.y != 0) presses.push_back({ start, held, step.y > 0 ? "ry+" : "ry-" });
            }
            frame += step.frames;
            // StickPulses puts kGapFrames of nothing between two directions
            // unless the step asked to run on; in the script that is simply the
            // space before the next press starts.
            const bool next = i + 1 < move.count &&
                              (move.steps[i + 1].x != 0 || move.steps[i + 1].y != 0);
            if (direction && next && step.noGap == 0) frame += input::StickPulses::kGapFrames;
        }
        lengthFrames = frame + 1;
        return presses;
    }

    // The button half: one press per nonzero bit, held for its frames, with the
    // table's own zero-bit steps as the rests between them.
    std::vector<Press> ButtonPresses(const skill::Move& move, double fps, long& lengthFrames)
    {
        std::vector<Press> presses;
        long frame = 0;
        for (int i = 0; i < move.buttonCount; i++)
        {
            const skill::ButtonStep& step = move.buttons[i];
            const long start = FrameMs(frame, fps);
            const long held = FrameMs(frame + step.frames, fps) - start;
            for (int bit = 0; bit < 16; bit++)
                if (step.buttons & (1u << bit))
                    presses.push_back({ start, held, ButtonName(std::uint16_t(1u << bit)) });
            frame += step.frames;
        }
        lengthFrames = frame + 1;
        return presses;
    }

    void PrintMove(const skill::Move& move, int index, double fps, double at)
    {
        long stickFrames = 0;
        long clickFrames = 0;
        std::vector<Press> presses = StickPresses(move, fps, stickFrames);
        const std::vector<Press> clicks = ButtonPresses(move, fps, clickFrames);
        presses.insert(presses.end(), clicks.begin(), clicks.end());

        std::printf("# %d %s: %zu press%s, %ld ms at %.0f Hz\n", index, move.id,
                    presses.size(), presses.size() == 1 ? "" : "es",
                    FrameMs(stickFrames > clickFrames ? stickFrames : clickFrames, fps), fps);
        if (move.exitHint)
            std::printf("#   left stick: %s\n", move.exitHint);
        if (move.ability)
            std::printf("#   needs: %s\n", move.ability);

        std::string script;
        for (const Press& press : presses)
        {
            if (press.name == nullptr)
            {
                std::printf("#   (a button bit with no PES17_INPUT_SCRIPT name)\n\n");
                return;
            }
            char entry[64];
            std::snprintf(entry, sizeof entry, "%s%.3f:%s:%.3f",
                          script.empty() ? "" : ",", at + press.startMs / 1000.0,
                          press.name, press.heldMs / 1000.0);
            script += entry;
        }
        std::printf("PES17_INPUT_SCRIPT=\"%s\"\n\n", script.c_str());
    }

    int Usage()
    {
        std::fprintf(stderr,
                     "usage: skill_replay [--fps 30|60] [--at seconds] [--index n]\n");
        return 2;
    }
}

int main(int argc, char** argv)
{
    double fps = 60.0;
    double at = 20.0;
    int only = -1;
    for (int i = 1; i < argc; i++)
    {
        if (std::strcmp(argv[i], "--fps") == 0 && i + 1 < argc)
            fps = std::atof(argv[++i]);
        else if (std::strcmp(argv[i], "--at") == 0 && i + 1 < argc)
            at = std::atof(argv[++i]);
        else if (std::strcmp(argv[i], "--index") == 0 && i + 1 < argc)
            only = std::atoi(argv[++i]);
        else
            return Usage();
    }
    if (fps < 1 || fps > 240 || at < 0 || only < -1 || only >= skill::kMoveCount)
        return Usage();

    std::printf("# One PES17_INPUT_SCRIPT per skill move, from runtime/skill_moves.h.\n"
                "# First press %.3f s in, at %.0f frames a second; the left-stick half is\n"
                "# yours, as each \"left stick\" line says. See docs/skill-moves.md.\n\n",
                at, fps);

    for (int i = 0; i < skill::kMoveCount; i++)
        if (only < 0 || only == i) PrintMove(skill::kMoves[i], i, fps, at);
    return 0;
}

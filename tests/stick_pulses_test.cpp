// The skill stick's frame timing (runtime/stick_pulses.h): a direction is held
// for whole guest frames, two directions in a row are separated by a frame of
// exactly nothing, and a sequence that has drained stops speaking.
//
// It asserts rather than reporting. The thread section is the point of the
// header being a queue at all: the overlay writes from the touch thread while
// the guest reads from its own, and neither may lose the other's work.
#include "stick_pulses.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <thread>
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

    constexpr std::int16_t kLeft = -28000;
    constexpr std::int16_t kRight = 28000;
}

int main()
{
    // ---- silence -------------------------------------------------------------

    {
        input::StickPulses pulses;
        std::int16_t x = 5, y = 5;
        Check(!pulses.Sample(x, y), "a sequencer nobody has fed says nothing");
        Check(x == 0 && y == 0, "and leaves the stick at zero");
        Check(!pulses.Active(), "and is not active");
    }

    {
        // Letting go of nothing must not wake it up.
        input::StickPulses pulses;
        pulses.Set(0, 0);
        std::int16_t x = 1, y = 1;
        Check(!pulses.Sample(x, y), "setting zero on an idle sequencer stays idle");
        Check(!pulses.Active(), "and does not report itself active");
    }

    // ---- one direction, held for its dwell ----------------------------------

    {
        input::StickPulses pulses;
        pulses.Set(kLeft, 0);
        Check(pulses.Active(), "setting a direction makes it active");

        int held = 0;
        for (int i = 0; i < 10; i++)
        {
            std::int16_t x = 0, y = 0;
            pulses.Sample(x, y);
            if (x == kLeft) held++;
        }
        Check(held == 10, "a direction nobody changed keeps being sent");
    }

    {
        // The dwell is what makes a one-frame flick visible: it is widened.
        input::StickPulses pulses;
        pulses.Set(kLeft, 0);
        pulses.Set(0, 0);           // the finger was already gone
        std::vector<std::int16_t> sent;
        for (int i = 0; i < 6; i++)
        {
            std::int16_t x = 0, y = 0;
            pulses.Sample(x, y);
            sent.push_back(x);
        }
        Check(sent[0] == kLeft && sent[1] == kLeft,
              "a flick shorter than a frame is held for two");
        Check(sent[2] == 0, "and then released");
        bool quiet = true;
        for (size_t i = 3; i < sent.size(); i++) if (sent[i] != 0) quiet = false;
        Check(quiet, "and stays at zero");
        Check(!pulses.Active(), "and the sequence ends");
    }

    // ---- two directions in a row --------------------------------------------

    {
        // The Double Touch: left, then right. The gap is the whole move.
        input::StickPulses pulses;
        pulses.Set(kLeft, 0);
        std::int16_t x = 0, y = 0;
        pulses.Sample(x, y);
        pulses.Sample(x, y);            // the dwell is spent here

        pulses.Set(kRight, 0);
        std::vector<std::int16_t> sent;
        for (int i = 0; i < 8; i++)
        {
            pulses.Sample(x, y);
            sent.push_back(x);
        }

        Check(sent[0] == 0, "the change starts with a frame of nothing");
        Check(sent[1] == kRight && sent[2] == kRight,
              "then the new direction for its dwell");
        Check(sent[3] == kRight, "and it is still held afterwards");

        // No two different directions ever touch without a zero between them.
        std::int16_t previous = kLeft;
        bool separated = true;
        for (std::int16_t value : sent)
        {
            if (value != 0 && previous != 0 && value != previous) separated = false;
            previous = value;
        }
        Check(separated, "no two directions arrive with nothing between them");
    }

    {
        // A change that arrives during the dwell is not lost, and is not shown
        // early either: the first direction still gets its two frames.
        input::StickPulses pulses;
        pulses.Set(kLeft, 0);
        pulses.Set(kRight, 0);          // arrived before a single frame was taken

        std::vector<std::int16_t> sent;
        for (int i = 0; i < 6; i++)
        {
            std::int16_t x = 0, y = 0;
            pulses.Sample(x, y);
            sent.push_back(x);
        }
        Check(sent[0] == kLeft, "the first direction is shown");
        Check(sent[1] == kLeft, "for its dwell, even though it has been replaced");
        Check(sent[2] == 0, "then the gap");
        Check(sent[3] == kRight, "then the newest direction");
    }

    {
        // Three requests inside one frame collapse to the newest: a fast thumb
        // must not leave a backlog the game replays later.
        input::StickPulses pulses;
        pulses.Set(kLeft, 0);
        pulses.Set(10000, 0);
        pulses.Set(kRight, 0);

        std::vector<std::int16_t> sent;
        for (int i = 0; i < 6; i++)
        {
            std::int16_t x = 0, y = 0;
            pulses.Sample(x, y);
            sent.push_back(x);
        }
        bool skipped = true;
        for (std::int16_t value : sent) if (value == 10000) skipped = false;
        Check(skipped, "a superseded request is dropped, not queued");
        Check(sent[3] == kRight, "and the newest one is what arrives");
    }

    // ---- to and from neutral ------------------------------------------------

    {
        // Going to neutral needs no gap: the zero is the separation, and an
        // extra frame of nothing would just be latency.
        input::StickPulses pulses;
        pulses.Set(kLeft, 0);
        std::vector<std::int16_t> sent;
        for (int i = 0; i < 4; i++)
        {
            std::int16_t x = 0, y = 0;
            pulses.Sample(x, y);
            sent.push_back(x);
            if (i == 1) pulses.Set(0, 0);
        }
        Check(sent[0] == kLeft && sent[1] == kLeft, "the direction is held");
        Check(sent[2] == 0, "and letting go is immediate once the dwell is spent");
        Check(!pulses.Active(), "and the sequencer is idle");
    }

    {
        // Coming back from neutral needs none either.
        input::StickPulses pulses;
        pulses.Set(0, 20000);
        std::int16_t x = 0, y = 0;
        pulses.Sample(x, y);
        Check(y == 20000, "a direction from nothing arrives at once");
        Check(x == 0, "with no invented sideways component");
    }

    // ---- the whole pad, not just one axis -----------------------------------

    {
        input::StickPulses pulses;
        pulses.Set(19800, -19800);      // down-right, as the pad carries it
        std::int16_t x = 0, y = 0;
        pulses.Sample(x, y);
        Check(x == 19800 && y == -19800, "both axes are carried together");
    }

    {
        // Clear is not a release: it is for a pad being hidden, where nothing
        // should be drained onto the game afterwards.
        input::StickPulses pulses;
        pulses.Set(kLeft, 0);
        std::int16_t x = 0, y = 0;
        pulses.Sample(x, y);
        pulses.Clear();
        Check(!pulses.Active(), "clearing ends the sequence at once");
        pulses.Sample(x, y);
        Check(x == 0 && y == 0, "and nothing is left to send");
    }

    // ---- a played-back move owns the stick ---------------------------------

    {
        // Double Touch, played back: two directions, the gap between them, and a
        // frame of nothing at the end so the move has a release edge.
        input::StickPulses pulses;
        const input::StickPulses::Step move[] = {
            { kLeft, 0, 2 },
            { kRight, 0, 2 },
        };
        Check(pulses.Play(move, 2) == 2, "a two-step move is taken whole");
        Check(pulses.Playing(), "and is playing");

        std::vector<std::int16_t> sent;
        for (int i = 0; i < 7; i++)
        {
            std::int16_t x = 999, y = 999;
            pulses.Sample(x, y);
            sent.push_back(x);
            if (i == 0) Check(y == 0, "and the other axis is not invented");
        }
        Check(sent[0] == kLeft && sent[1] == kLeft, "the first direction gets its frames");
        Check(sent[2] == 0, "then the gap");
        Check(sent[3] == kRight && sent[4] == kRight, "then the second direction");
        Check(sent[5] == 0, "then a frame of nothing to end on");
        Check(!pulses.Playing(), "and it is over");
        Check(!pulses.Active(), "and the sequencer is idle");
    }

    {
        // The whole point: stray input during a move changes nothing.
        input::StickPulses pulses;
        const input::StickPulses::Step move[] = {
            { 0, kRight, 3 },
            { kLeft, 0, 3 },
        };
        pulses.Play(move, 2);

        std::vector<std::int16_t> sent;
        for (int i = 0; i < 10; i++)
        {
            // A thumb fidgeting on the glass, and a fresh touch landing on it.
            pulses.Set(kLeft, kLeft);
            pulses.Set(1234, -1234);
            pulses.Set(0, 0);
            pulses.Set(kRight, kRight);

            std::int16_t x = 0, y = 0;
            pulses.Sample(x, y);
            sent.push_back(x == 0 ? y : x);
        }

        Check(sent[0] == kRight && sent[1] == kRight && sent[2] == kRight,
              "the played direction runs for its frames despite the fidgeting");
        Check(sent[3] == 0, "and its gap is still there");
        Check(sent[4] == kLeft && sent[5] == kLeft && sent[6] == kLeft,
              "and the second step is not cancelled either");
        Check(sent[7] == 0, "and the move ends on nothing");
        bool nothingStray = true;
        for (std::int16_t value : sent)
            if (value != 0 && value != kLeft && value != kRight) nothingStray = false;
        Check(nothingStray, "no stray value ever reached the pad");
    }

    {
        // A direction the thumb was holding when the move began is dropped: the
        // move is the gesture, not the thumb.
        input::StickPulses pulses;
        pulses.Set(kLeft, 0);
        const input::StickPulses::Step move[] = { { kRight, 0, 2 } };
        pulses.Play(move, 1);
        std::int16_t x = 0, y = 0;
        pulses.Sample(x, y);
        Check(x == kRight, "a move replaces what the thumb had asked for");
    }

    {
        // And when it is over the thumb is heard again.
        input::StickPulses pulses;
        const input::StickPulses::Step move[] = { { kLeft, 0, 1 } };
        pulses.Play(move, 1);
        for (int i = 0; i < 5; i++)
        {
            std::int16_t x = 0, y = 0;
            pulses.Sample(x, y);
        }
        Check(!pulses.Playing(), "the move finished");
        pulses.Set(kRight, 0);
        std::int16_t x = 0, y = 0;
        pulses.Sample(x, y);
        Check(x == kRight, "and live input is taken again");
    }

    {
        // The array does not have to outlive the call, and a move longer than
        // the sequencer holds is clamped rather than overflowing it.
        input::StickPulses pulses;
        std::vector<input::StickPulses::Step> longMove(input::StickPulses::kMaxSteps + 5);
        for (auto& step : longMove) { step.x = kLeft; step.frames = 1; }
        const int taken = pulses.Play(longMove.data(), int(longMove.size()));
        Check(taken == input::StickPulses::kMaxSteps, "an oversized move is clamped, not overrun");
        longMove.clear();

        int frames = 0;
        while (pulses.Active() && frames < 500)
        {
            std::int16_t x = 0, y = 0;
            pulses.Sample(x, y);
            frames++;
        }
        Check(frames <= input::StickPulses::kMaxSteps * 2 + 2,
              "and it plays out from its own copy");
    }

    {
        // A step of no frames would be a direction with no edge; it is one
        // frame instead.
        input::StickPulses pulses;
        const input::StickPulses::Step move[] = { { kLeft, 0, 0 } };
        Check(pulses.Play(move, 1) == 1, "a zero-length step is still a step");
        std::int16_t x = 0, y = 0;
        pulses.Sample(x, y);
        Check(x == kLeft, "and it is shown for a frame rather than skipped");
    }

    {
        // An empty move is not a move: it must not leave the stick owned.
        input::StickPulses pulses;
        Check(pulses.Play(nullptr, 4) == 0, "a null move is refused");
        Check(!pulses.Playing(), "and owns nothing");
        const input::StickPulses::Step none[] = { { kLeft, 0, 1 } };
        Check(pulses.Play(none, 0) == 0, "an empty move is refused");
        Check(!pulses.Playing(), "and owns nothing either");
    }

    {
        // A pad being hidden cancels the move outright: there is nothing left to
        // play it to.
        input::StickPulses pulses;
        const input::StickPulses::Step move[] = { { kLeft, 0, 30 }, { kRight, 0, 30 } };
        pulses.Play(move, 2);
        std::int16_t x = 0, y = 0;
        pulses.Sample(x, y);
        Check(pulses.Playing(), "playing");
        pulses.Clear();
        Check(!pulses.Playing() && !pulses.Active(), "clearing cancels it");
        Check(!pulses.Sample(x, y), "and the stick is nobody's");
    }

    {
        // A second move replaces the first: a pressed skill button is a new
        // intent, and it is the only thing allowed to interrupt a move.
        input::StickPulses pulses;
        const input::StickPulses::Step first[] = { { kLeft, 0, 30 } };
        pulses.Play(first, 1);
        std::int16_t x = 0, y = 0;
        pulses.Sample(x, y);
        Check(x == kLeft, "the first move started");
        const input::StickPulses::Step second[] = { { kRight, 0, 2 } };
        pulses.Play(second, 1);
        pulses.Sample(x, y);
        Check(x == kRight, "a new move takes the stick over");
    }

    // ---- the guest thread and the touch thread ------------------------------

    {
        // The poll runs 240 times while another thread changes the direction
        // constantly. Nothing may be lost, stuck or deadlocked, and the
        // sequencer must end up quiet.
        input::StickPulses pulses;
        std::atomic<bool> stop{ false };
        std::atomic<int> writes{ 0 };

        std::thread writer([&] {
            while (!stop.load(std::memory_order_relaxed))
            {
                pulses.Set(kLeft, 0);
                pulses.Set(kRight, 0);
                pulses.Set(0, 0);
                writes.fetch_add(1, std::memory_order_relaxed);
            }
        });

        bool sane = true;
        for (int i = 0; i < 20000; i++)
        {
            std::int16_t x = 0, y = 0;
            pulses.Sample(x, y);
            if (x != 0 && x != kLeft && x != kRight) sane = false;
            if (y != 0) sane = false;
        }
        stop.store(true, std::memory_order_relaxed);
        writer.join();

        Check(sane, "every frame the reader took was a value the writer asked for");
        Check(writes.load() > 0, "and both threads actually ran");
        pulses.Clear();
        std::int16_t x = 1, y = 1;
        Check(!pulses.Sample(x, y), "and it still ends");
    }

    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

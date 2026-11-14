// Presses waiting for the title's poll (runtime/input_pulses.h): bits add up
// without being lost, a take clears them, and a press arriving from another
// thread while a poll is taking its bits is still delivered.
//
// It asserts rather than reporting, and includes nothing that needs the guest,
// the disc, a GPU or an Android device.
#include "input_pulses.h"

#include <cstdio>
#include <thread>

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

    using input::PressPulses;

    constexpr std::uint16_t A = 0x1000;
    constexpr std::uint16_t B = 0x2000;
}

int main()
{
    {
        PressPulses pulses;
        Check(!pulses.Pending(), "an empty queue says so");
        Check(pulses.Take() == 0, "taking nothing answers nothing");
    }

    {
        PressPulses pulses;
        pulses.Add(A);
        Check(pulses.Take() == A, "a press comes back once");
        Check(pulses.Take() == 0, "and is not delivered twice");
    }

    {
        PressPulses pulses;
        pulses.Add(A);
        pulses.Add(B);
        Check(pulses.Take() == (A | B), "two presses before one poll are both carried");
    }

    {
        PressPulses pulses;
        pulses.Add(A);
        pulses.Add(A);
        Check(pulses.Take() == A, "the same button pressed twice is one bit for one poll");
    }

    {
        PressPulses pulses;
        pulses.Add(0);
        Check(pulses.Take() == 0, "adding nothing changes nothing");
    }

    {
        PressPulses pulses;
        pulses.Add(A);
        pulses.Clear();
        Check(pulses.Take() == 0, "Clear drops what was waiting");
    }

    {
        // A poll on one thread while presses arrive on another: whatever is
        // taken may be partial, but nothing may be lost for good.
        PressPulses pulses;
        std::thread presser([&] {
            for (int i = 0; i < 2000; ++i) pulses.Add(A);
        });
        std::uint16_t seen = 0;
        while (presser.joinable())
        {
            seen |= pulses.Take();
            if (seen == A) break;
            std::this_thread::yield();
        }
        presser.join();
        Check((seen | pulses.Take()) == A, "a press from another thread survives a poll taking the queue");
    }

    std::printf("%d checks, %d failed\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

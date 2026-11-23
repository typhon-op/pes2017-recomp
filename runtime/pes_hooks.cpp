// Hooks that make PES 2017 behave on a phone (as opposed to pes_debug.cpp's probes).
#include <ppc_recomp_shared.h>
#include "title.h"
#include "game_phase.h"
#include "guest.h"
#include "log.h"
#include "crash.h"
#include "kernel/kernel.h"
#include <cstring>
#include <string>
#include "gpu/gpu.h"
#include <atomic>
#include <chrono>
#include <thread>

// D3D's fence poll (sub_823F7C38; r3 -> { device, ?, last-seen retired, time }).
// It answers "still waiting" (1) until the word at [*(device+11024)] -- written by
// the command processor's end-of-pipe event -- reaches the fence, and the caller
// loops straight back into it. On the console that spin is free; here it ran the
// main thread at 100% of a big core, 40% of it in clock_gettime for the title's
// own timeout arithmetic, to wait out the 16 ms the GPU needs for a frame. When
// the answer is "still waiting", give the processor away first: the GPU thread
// that will move the word gets it, and the phone stays cool.
GUEST_HOOK(823F7C38)
{
    GUEST_ORIG(823F7C38)(ctx, base);
    if (ctx.r3.u32 == 1)
    {
        // A yield costs a syscall; a very short sleep lets the core idle. Short
        // enough that the frame is not delayed by it (the fence moves in ms).
        std::this_thread::sleep_for(std::chrono::microseconds(100));
    }
}

// Intro/ProcInstall, the step after the first-run language screens, is a four-state
// machine (object+88). It asks the install manager (a global, sub_83105358) whether a
// job is running: if there is one it goes to state 3, which sends the "proceed" event
// and the flow carries on to the data-pack steps and the main menu; if there is none it
// goes 0 -> 1 -> 2, and state 2 sets the main loop's exit flag -- the title ends itself.
// No install job is ever started here, so every first run quit to the launcher and the
// next one began at the first-run screens again (nothing is saved). Starting at state 3
// skips the quit and reaches TopMenu.
GUEST_HOOK(82F3CBD0)
{
    const uint32_t self = ctx.r3.u32;
    if (*GuestPtr<be32>(self + 88) == 0) *GuestPtr<be32>(self + 88) = 3;
    GUEST_ORIG(82F3CBD0)(ctx, base);
}

// The first-run flow reaches Intro/ProcInstall without ever running
// FirstFlow/ProcSystemDataSave, so no system data is created and the flow repeats on
// every launch. On the console the install step is where the title ends and is run
// again; here the procedure that makes the save is asked for in its place, once.
// (Its save is data.tmp, renamed to data.bin: NtSetInformationFile's rename.) The next
// launch then finds the package by enumerating (XamContentCreateEnumerator).
namespace
{
    std::string GuestString16(uint32_t address)
    {
        if (!address) return {};
        const uint32_t size = *GuestPtr<be32>(address + 16), capacity = *GuestPtr<be32>(address + 20);
        if (size > 256) return {};
        const uint32_t data = capacity >= 16 ? uint32_t(*GuestPtr<be32>(address)) : address;
        return std::string(reinterpret_cast<const char*>(guest::Base() + data), size);
    }
}

GUEST_HOOK(82F00CE8)
{
    static std::atomic<int> once{ 0 }, logged{ 0 };
    const std::string name = GuestString16(ctx.r5.u32);
    if (logged++ < 400)
        LOGW("procedure: %s (from %08X on '%s')", name.c_str(), uint32_t(ctx.lr), crash::CurrentThreadName());
    // This name is the title's own answer to "what is running", so it is also
    // the app's phase: a "Match/..." flow is a match (MatchSetup from kick-off,
    // MatchIdle in play) and anything else is a menu. See runtime/game_phase.h.
    // Said once per change, because the switch runs on every transition.
    static std::atomic<int> lastFlow{ 0 };
    if (!name.empty())
    {
        const ::phase::Phase said = ::phase::NoteProcedure(name.c_str(), unsigned(name.size()));
        const int word = said == ::phase::Phase::Match ? 2 : 1;
        if (lastFlow.exchange(word) != word)
            LOGI("phase: %s -- %s", word == 2 ? "a match is running" : "the menus are up",
                 name.c_str());
    }
    if (name == "Intro/ProcInstall" && once++ == 0)
    {
        const char text[] = "FirstFlow/ProcSystemDataSave";
        const uint32_t buffer = kernel::AllocateGuest(64, 16);
        std::memcpy(guest::Base() + buffer, text, sizeof text);
        *GuestPtr<be32>(ctx.r5.u32 + 0) = buffer;
        *GuestPtr<be32>(ctx.r5.u32 + 16) = uint32_t(sizeof text - 1);
        *GuestPtr<be32>(ctx.r5.u32 + 20) = 63;
    }
    GUEST_ORIG(82F00CE8)(ctx, base);
}


// D3D's ring kicks (they store CP_RB_WPTR). See gpu::WaitAfterKick: while kits
// are being composed, the CPU waits for the command processor after each kick,
// so a resolve's write-back lands before the title reuses its memory.
GUEST_HOOK(823F64B8)
{
    GUEST_ORIG(823F64B8)(ctx, base);
    gpu::WaitAfterKick();
}
GUEST_HOOK(8240CD00)
{
    GUEST_ORIG(8240CD00)(ctx, base);
    gpu::WaitAfterKick();
}

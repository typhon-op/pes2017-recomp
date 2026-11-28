# In-match performance on the phone (Galaxy S20 FE, Adreno 650)

Measured with the `play` controller macro (Settings: CAPTURE button / `MACRO` broadcast) so every run
is the same input from game start to a match. Tools added for it:

- `files/gpu_timing` (or `PES17_GPU_TIMING=1`): GPU timestamps around every render pass; a
  `gpu:` line every 600 frames lists the costliest pass kinds (size, samples, depth) and ms a frame.
- `files/yield_tuning` ("<sleep us> <free yields>") and `PES17_YIELD_SLEEP_US`: back-off for the
  guest's `NtYieldExecution` spin loops (`runtime/kernel/yield_backoff.h`).
- `files/small_passes` (or `PES17_SMALL_PASSES=1`): render passes open over what their draws touch (a clearing rectangle is sized from its own corners, `ClearRectangleReach`). Off by default: it clipped the crowd to a rectangle.
- `files/scene_scale`, `files/no_msaa`: existing overrides.

## What was found

- The limit in a match is heat. At skin 47-48 C (thermal status CRITICAL) the GPU is held to 305-400 MHz and
  the big CPU cores to about 1.5 GHz, and fps swings 42-60 between identical runs. A cool phone ran 57-60.
- CPU: a quarter of all cycles are the guest's yield spin loops (kernel `sched_yield`) on the main thread and
  three workers. Sleeping in them cut cycles by a fifth at 100 us but cost about 6 fps (the waits are latency
  critical); a gentle variant (30 us after 20000 yields) changed nothing. Off by default.
- GPU: about 8 ms a frame in 20-22 passes. Three `960x1536` passes cost about 1 ms each and the `960x624`
  ones about 0.4 ms each. The `960x1536` surface is the scene's colour+depth at 0.75 scale, 2048 rows tall in
  the title's pixels, while the picture is 540. They began with a clear rectangle whose scissor is the whole
  surface, so passes now take their size from the rectangle's corners. The scene passes are 960x576 now
  (about 1 ms each, so most of that cost is the shading, not the load/store), total pass time fell about 20%
  (about 11-12 ms to 9-10 ms a frame), fps did not move beyond the run-to-run noise (47 vs 47, 40 vs 43),
  and the match picture in a CAPTURE screenshot looks right.
- Pipeline/shader cache writes were throttled to one in 30 s (they were every 5 pipelines, on the draw thread
  and under the pipeline-build lock): the cutscene start froze for about 5 s while a warmup compiled.

- `PES17_STREAM_TRACE=1` (or `files/stream_trace`): `stream:` lines for the title's own asset
  streaming (`runtime/stream_trace.h`): each guest file read (thread, core, file, fread ms), each
  texture first uploaded or restaged, and every 500 ms each thread's ms running and ms runnable
  (sampled from `/proc/self/task/*/stat`; this kernel has no schedstat, and a thread name is cut
  to 15 characters, so two guest threads can share one). Measured on the S20 FE, cold start: the
  loader thread (`dt*_x36.cpk` reads, 78 MB in 30 s after MatchSetup) spends 0.3 s in `fread` and
  sleeps on a futex for about 25 s of that, so streaming is paced by the game, not by the disk or
  by core starvation; `PES17_PINNING=off` changed nothing for it. Texture work on the command
  thread is small (decode+read+stage about 1.2 s for the whole session). What a player sees as
  popping is the game's own sequence of loads; the visible hitching and missing draws of a cold
  start came from pipeline builds (see the bundled shader seed in shaders.md).

## Heat cuts added 2026-10-26 (S20 FE)

Each trades a little quality or latency for less work, since heat is what limits a match. None
has had its effect on temperature measured yet (see the test below).

- **Spin waits sleep when the frame has room** (`runtime/kernel/yield_backoff.h`). A quarter of
  the CPU went to the guest's yield spin loops. With `PES17_YIELD_SLEEP_US` unset, they now sleep
  up to 100 us once they spin, but only under an FPS limit below 60 (the spin only waits out the
  limiter) or while Android reports thermal status MODERATE or worse (from the thermal listener,
  `nativeSetThermalStatus`). At 60 on a cool phone they stay plain yields, since sleeping there
  cost about 6 fps. An explicit `PES17_YIELD_SLEEP_US`, or `files/yield_tuning`, fixes the cap;
  0 turns it off. The game log's `yield:` line says which mode is on. The S20 FE still has a
  `files/yield_tuning` of `0 32` from earlier measuring, which keeps this off there.
- **Anisotropic filtering capped at 4x on Android** (`PES17_MAX_ANISO`, `texture_cache.cpp`,
  default set in `android/perf.cpp`). The title asks for up to 16x; the slanted pitch and stands
  are most of the frame. The far end of the pitch is a little softer. `PES17_MAX_ANISO=16` gives
  the old look, `1` the cheapest.
- **Frame generation** (`PES17_FRAMEGEN=1`, Settings > Graphics > Frame generation). The game
  draws 30 frames a second and the window shows a frame made from each two real ones between
  them, so the 60 Hz screen stays smooth for about half the guest and scene work. The made
  frame costs a few compute passes (0.23 ms on an M3 Pro; not timed on a phone yet) and adds one
  real frame of latency. Pitch, camera and players move at 60, and so does the ball: the
  renderer reads its exact position from its draw. What the motion search cannot follow (fast
  limbs, the ball's shadow) is shown whole at 30. The S20 FE showed 61 fps on screen with the
  game at 30 fps, and the user found the ball smooth. Design, results and what was dropped:
  [frame-generation.md](frame-generation.md).

The test still to run, on a cool, unplugged phone with the `play` macro, about 5 minutes in a
match each, temperatures from `dumpsys thermalservice` (SKIN, AP) every 10 s: 60 fps; 30 fps;
30 fps with frame generation. 30 against 60 says what halving the work saves; frame generation
against plain 30 says what the made frames cost. Note: the S20 FE dropped off USB three times
when hot and under `adb shell screenrecord`; record with the phone's own recorder instead.

## CPU Pinning: Automatic vs Wide (MediaTek, not yet measured)

Settings > Performance > CPU Pinning (`CpuPinning.kt`) offers two of the engine's
own pinning modes and nothing else: **Automatic** (the default) sends no value
for `PES17_PINNING`, so the runtime keeps the role pinning every number in this
file was taken under, and **Wide** sends `PES17_PINNING=wide`, which also lets
the title's own threads use the worker cluster. `shared`, `big`, `render` and
`off` are left out because the POCO section of [PORTING.md](PORTING.md) records
a regression or no benefit for each. Whether Wide earns its place on a phone
that is not the POCO is unmeasured; this is the plan that settles it.

**Hypothesis.** On a 1+3+4 part the title's sixty-odd threads share the three
performance cores and the busy ones wait about as long as they run. Wide adds
the little cluster to the mask. If that diagnosis is right, the guest threads'
*runnable* time should fall and fps p10 should hold or rise. If the little
cores are too slow for latency-critical work, the median may hold while p10 and
the per-second `worst` fall -- which is a reason to delete the option, not to
tune it.

**Before the first run.** Record the topology, or the result cannot be
interpreted:

    adb shell cat /proc/cpuinfo | grep -c processor
    adb shell 'for c in /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq; do echo $c $(cat $c); done'
    adb shell getprop ro.product.model      # two phones are used here; confirm it is the one you mean

Phone cool (CPU sensor under ~40 C in `dumpsys thermalservice`), unplugged,
`files/macros/.remote_control` present, `macros` set up as the other runs do.

**The run.** `tools/ab_window.sh` waits for the state (`Match/MatchIdle`) and
then a fixed window, which is what makes runs comparable; it writes the env
pairs you give it to `files/env_override`, so the mode is chosen without
touching the settings UI. Interleave, never block:

    export ANDROID_SERIAL=<serial>
    export MACRO=play OUT=/tmp/pin_ab
    tools/ab_window.sh auto-1
    tools/ab_window.sh wide-1 PES17_PINNING=wide
    tools/ab_window.sh auto-2
    tools/ab_window.sh wide-2 PES17_PINNING=wide
    tools/ab_window.sh auto-3
    tools/ab_window.sh wide-3 PES17_PINNING=wide

Three pairs is the minimum. One config varied about +/-7 fps median run to run
on the S20 FE, and a macro's late scene is not the same scene every time, so
only scene-matched windows count -- the same reason `tools/crowd_windows.py`
exists. `W0` (default 125 s after playback starts) and `MEASURE` (default 80 s)
select the window; a run that never reached `Match/MatchIdle` or logged no fps
is invalid and is repeated.

**What to record, per run.** The `pinning:` line from `game.log` (it is not in
the script's summary, which only reads the window), then from the script's own
output: window median and
the `worst` column of `perf: N fps ... ms a frame waiting for the GPU`, the
draws a frame (scene match), the per-thread CPU (`cpu% of a core`), the clocks
and the CPU temperature. Two of its fields are Adreno/S20 FE shaped and will be
empty or wrong on a MediaTek part: the `cpu0/3/4/7` clock sample and the
`devfreq-13000000.mali` cooling level -- read the clusters from the topology
above instead.

**The two measurements that decide it** are both in that output already:

- **The mode the runtime took**: one `pinning:` line per run in `game.log` --
  `pinning: PES17_PINNING="wide" taken as wide; clusters prime 0x80 perf 0x70
  worker 0xf` -- the value received, how it was read (`auto` for the empty value
  the row sends for Automatic), and the cluster masks the ordering was built
  from. Check it before reading any other number: a run whose line is missing,
  or says `auto` when it was meant to be `wide`, is a broken run and not a
  result.
- **Affinity** (`<label>.aff`): `Cpus_allowed_list` for `pes17`, the GPU ring
  consumer, the presenter and a guest thread -- the per-thread result of that
  decision, and the thing to compare when the same mode is set two ways.
- **Runnable against running**: with `PES17_STREAM_TRACE=1` the log's `stream:`
  lines give each thread's ms running and ms runnable every 500 ms. That ratio
  is the measurement the POCO notes used to call the threads starved, and this
  kernel has no schedstat, so it is the only run-queue evidence available.

**Also check the app path, once per mode.** The settings row does not write
`env_override`: `GameActivity` calls `nativeSetOption("PES17_PINNING", ...)`
before the runtime starts. Pick each radio button in the app, run
`tools/ab_window.sh` with no pairs, and compare the `affinity:` line with the
`env_override` run of the same mode -- the `pinning:` line must read `auto` for
Automatic and `wide` for Wide in both -- and the two paths' affinities must
agree, with the choice surviving `am force-stop` and a relaunch. This is the
only check that the row itself works on a device.

**Decision rule.** Keep Wide only if, across the three pairs, its median is not
below Automatic's and its p10/`worst` are not worse. Drop it if either fails,
whatever the means say, and do not change the default on the strength of a
single pair.

**A second device class.** The mode assumes a distinct little cluster. Repeat
once on a phone of a different shape (4+4, 2+6, or a Dimensity with different
cluster sizes): on a symmetric part Wide should behave like Automatic, and a
run that shows otherwise means the mask is not what the notes describe.

## Frame times against Winlator Mali (audit, 2026-11-28 -- nothing measured here)

A player reports this port's frame rate is less stable than the same title in
Winlator Mali Bionic 1.2.1 on a MediaTek phone. No phone ran any of this: what
follows is what the code says, the instrumentation added so the report can be
answered with numbers, and the comparison to run. Nothing here is a claim that
frame times improved.

### What the two actually do

Winlator Mali's own notes describe its presentation: a native EGL renderer
(`renderer/egl.cpp`) replacing the old stack, with "cached blit targets and
native synchronization fences", and OpenGL/EGL frames shared with a Vulkan
compute device through `AHardwareBuffer` rather than copied. Its commit history
is the more useful part: an "FPS limiter: releases spaced 1/limit apart, late
frames released at once" and a "one beat per new game frame" pacing change, and
then a **revert** of "Frame pacing: with an FPS limit, game frames are presented
on an even vsync-aligned beat (eglPresentationTimeANDROID)". It tried putting
every present on the panel's boundary and took it back out.

This port does the opposite on purpose: `pacing.h` places each frame's deadline
on the display's measured boundary (`NextBoundary`), the swapchain is FIFO, and
at 60 the guest's blank follows the panel through `VK_KHR_present_wait`
(`presenter.cpp`, `SawDisplayed`). That is a good design for a title that hits
its rate, and a poor one for a title that sits *at* it: a frame that misses the
boundary by a millisecond waits a whole refresh, so 58 fps of work becomes 60
and 30 alternating. The shape of the complaint matches that mechanism -- and so
does Winlator's revert.

### The audit

Rendering and presentation, in the order a frame goes:

- **Presentation mode is FIFO, always** (`presenter.cpp`: `info.presentMode`),
  with `minImageCount = max(caps.minImageCount, 2)` and `kFramesInFlight = 2`.
  FIFO is the only mode with a guaranteed present; a frame that misses its
  refresh is shown a refresh later. This is the single most likely source of
  "unstable" that is not the GPU's fault, and it is now selectable for a test
  (`PES17_PRESENT_MODE`, below). No default was changed.
- **The renderer never writes an image the presenter is reading**: a frame is
  handed over through a bounded queue (`ShowImage`) and the render thread waits
  on `vk::WaitUntilTaken` (`renderer_resolve.cpp`) for the copy to be submitted,
  bounded at 250 ms so a hidden window cannot hold the guest. This is a real
  synchronisation point, but it is per frame and bounded, not a stall.
- **Copies**: one full-frame GPU blit per present (`DrawPicture`, bilinear, or
  FSR then a 1:1 copy). There are no CPU-side frame copies on the hot path --
  the overlay's `memcpy` runs once per picture, not per frame. Nothing here to
  remove.
- **Blocking calls**: `vkAcquireNextImageKHR(..., UINT64_MAX)` and the slot
  fence wait in `Acquire()` are where the presenter sleeps a refresh; both are
  on the presenter's own thread, and the guest does not wait on them. The
  `vkDeviceWaitIdle` calls are lifecycle only (surface replaced, teardown,
  texture-cache eviction, upscaler/framegen init), never per frame.
- **Frame pacing**: `frame_rate.h` and `pacing.h`; at 60 no software pacer runs
  and the title retires frames at the blank; at 30-55 the pacer aligns to the
  panel and `PES17_PACING=compatibility` restores the old independent clock,
  which is close to what Winlator settled on. `PES17_TRACE_PACING` writes the
  present timeline.
- **Texture loading, streaming, audio, input**: `PES17_STREAM_TRACE=1` measured
  the streaming as paced by the title itself (loader asleep on a futex, not on
  the disk or on core starvation), texture work on the command thread at about
  1.2 s a session, and the cold-start hitching attributed to pipeline builds.
  `stutters.h` already attributes every >25 ms world-frame gap to shader
  translation, pipeline builds, texture reads, GPU waits, occlusion reads or
  ring batches, and counts frames lost by length.
- **What was missing** is the number the report is actually about: no
  percentile, no 1% low, no spike count. `stutters` counts; the pacing trace
  prints mean/min/max; neither separates a smooth 55 from a 60 with a hitch
  every second.

### What was added

- `runtime/frame_stats.h`: a window of frame intervals (1024, halved when full)
  answering mean, p50/p90/p99, worst, the 1% low (mean of the slowest
  hundredth) and the count over 1.5x and 2x the run's own target. Pure
  arithmetic; `tests/frame_stats_test.cpp` checks it (29 checks) without a
  phone.
- One `fstats:` line per 600 presents in `game.log` (about ten seconds at 60,
  twenty at 30), with the resolution, the limit, the present mode and the
  phone's thermal status beside the numbers. A gap over 500 ms (a load, a menu,
  a pause) restarts the window instead of becoming its worst frame.
- `PES17_PRESENT_MODE=fifo|mailbox|immediate`: the present mode is now chosen
  from the environment, checked against what the surface lists, and logged with
  the full list at swapchain creation. **Unset is FIFO, so behaviour is
  unchanged**; this exists to test the FIFO hypothesis on a phone. Mailbox is
  the driver's "show the newest frame at the next refresh" mode: a late frame
  is replaced rather than queued, which is the closest Vulkan equivalent to
  what Winlator's revert chose.
- `vk::renderer::ThermalStatus()`, so the line can say what the phone was doing.

Deliberately not added: frame generation changes, CPU affinity changes, or a
new "performance mode". None of them is evidence-backed for this question.

### The comparison to run

Same phone, same match, same macro, same resolution and limit, one variable at
a time; three interleaved pairs per cell, as in the CPU Pinning plan above. The
two cells that matter are `fifo` against `mailbox`, at 60 first (where the
mechanism above bites) and then at 45.

    adb shell am force-stop com.pes2017.recomp
    adb shell "echo PES17_PRESENT_MODE=mailbox > /sdcard/Android/data/com.pes2017.recomp/files/env_override"
    # ... run the macro, then from the log:
    adb shell "grep -E 'fstats:|pinning:|vulkan: present mode' /sdcard/Android/data/com.pes2017.recomp/files/game.log"

Record per run: the `fstats:` fields (fps, p50/p99, 1% low, spikes over 1.5x
and 2x, duplicated blanks, frames dropped), `dumpsys thermalservice` CPU/skin
temperature at start and end, and the resolution the app asked for. Run every
supported limit -- 30, 35, 40, 45, 50, 55, 60 -- for whichever presentation mode
the first comparison favours, because the pacer's behaviour changes with the
limit (30 presents in order, 35/45/55 alternate blanks).

**What would justify changing a default**: mailbox (or Compatibility pacing)
showing a lower p99 and a better 1% low at the same limit, on the same scene, in
at least two of three pairs -- not a higher mean. The reverse result deletes
`PES17_PRESENT_MODE` rather than keeping it as a switch nobody should use.

## Leads not tried yet (workload and heat)

- **Sun shadow map at a lower resolution.** The shadow map is one 1024x2048 depth surface holding two
  cascades, filled by two resolves a frame ([rendering.md](rendering.md)). Targets already carry
  their own scale (`target.scale`, set from `SceneScaleFor` in renderer_targets.cpp), and texture
  fetches normalise over the image, so a half-size shadow image would be sampled in the right place.
  What has to move with it: the resolve rectangles and the row a second resolve lands on
  (`resolveSurfaces`, renderer_resolve.cpp), and the passes' viewport and scissor. The cost is the
  title's own filtering: its PCF offsets and depth bias are in 1024-texel units, so shadows get softer
  and may shimmer or acne. Check with a CAPTURE screenshot and `files/gpu_timing` (shadow passes'
  ms) before keeping it; a switch such as `PES17_SHADOW_SCALE=0.5` keeps it optional.
- **The ball's shadow under frame generation:** it is held at 30 while the ball moves at 60. The
  renderer could find the shadow's draw the way it finds the ball's and place it the same way.


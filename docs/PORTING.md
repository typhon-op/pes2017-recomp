# PES 2017 → Android: status and what is left

PES 2017 (Xbox 360, Europe, `default.xex`, 32 MB) is recompiled ahead of time
with [XenonRecomp](https://github.com/hedge-dev/XenonRecomp) into C++ that is
built for arm64 with the Android NDK, on the same architecture as `pes17-android`
(the reference port in this workspace): a host runtime stands in for the
console's kernel, GPU (Vulkan) and audio, and the Kotlin app installs the game
files and starts the native library. The second example, `pinyon-shift`
(Forza Horizon on ReXGlue), is the other route to the same end.

This is **not** a finished, playable port. It is the complete, reproducible
front half of one, and a PES 2017 runtime.

## Done (and reproducible with `./build.sh`)

| step | result |
|---|---|
| XEX → PE | `pes/default.pe`: retail-encrypted, basic-compressed XEX2 decoded by `tools/xexdump.py`; PPC big-endian, image base `0x82000000`, entry `0x82416708`, `.text` `0x823F0000`+`0x1986684` (25 MB), 73,078 `.pdata` functions |
| Jump tables | `config/pes_switch_tables.toml`: 1,327 tables from XenonAnalyse; 3 over-long ones trimmed by `tools/trim_switch_tables.py` |
| Prologue helpers | save/rest GPR/FPR/VMX addresses located by byte pattern, in `config/PES.toml` |
| Function bounds | 205 leaf functions whose jump tables XenonAnalyse ended early (`tools/fixbounds.py`) plus one `bdzf` ladder (`tools/fix_ladders.py`); `build.sh` loops recompile → fix until nothing changes |
| Recompile | `ppc/`: 0 switch-table errors; 44 undecodable words at the tail of `.text` (data after the last function, `0x83D76674`…) |
| Kernel imports | 281 used by the game; 168 implemented by the runtime, 113 stubbed with a log-once stub (`ppc/stubs_generated.cpp`) |
| Runtime | `runtime/title.h` written for PES; every runtime source compiles for arm64 |
| Install filter | the installer copies `*.cpk` and `nxeart` from the disc (PES's archives) |
| App | Gradle project: one app, `com.pes2017.recomp`, "PES 2017" |

## Pipeline changes from the research pass

See [RECOMP_RESEARCH.md](RECOMP_RESEARCH.md): `PROFILE=safe|balanced|aggressive`
(register locals; `balanced` is −18 % arm64 code on a sample unit), ARMv8.2
baseline with a CPU check, `-ffp-contract=off`, `-Bsymbolic-functions`,
automatic helper location, and a 16 KB-alignment check after the link.

## The engine a build packages

The recompiled title cannot be built from this tree — it is linked from the
disc's own executables — so every apk here carries a prebuilt `libpes17.so`.
Which one is pinned in one committed file, `tools/engine_pin.sh`: release
`V0.2.3` of this repository (`app-debug.apk`, commit `ee4af63`), with the
SHA-256 of the asset and of the `libpes17.so` inside it. `tools/fetch_engine.sh`
downloads it, enforces both hashes, unpacks `lib/arm64-v8a/` into
`libpes17_prebuilt/` and prints the tag, commit and hashes; both workflows call
that script and nothing else, so a build here and a build on a laptop package
the same engine. A re-uploaded asset, or a library that is not the pinned one,
fails the build with the hash it actually got instead of shipping silently.

```sh
tools/fetch_engine.sh                  # into ./libpes17_prebuilt (git-ignored)
tools/fetch_engine.sh --audit          # and report the entry points it exports
cd android && ./gradlew :app:assembleDebug -PprebuiltNative=../libpes17_prebuilt
```

A new engine release is cut from a checkout with `tools/release_engine.sh [tag]`
(default `V0.2.4`): it builds the engine from that commit with `./build.sh`,
packages a debug apk around it, publishes it as the tag with the asset name
`app-debug.apk`, downloads the published asset again to hash what the release
really serves, rewrites `tools/engine_pin.sh`, proves the new pin with
`tools/fetch_engine.sh --audit`, and commits and pushes it. It needs the disc's
`default.xex`, the NDK and a signed-in `gh` — no CI runner can build the engine,
so this step is the release machine's. The tag is made at the commit the apk was
built from; the pin bump is the commit after it.

To move to another engine by hand, bump the tag, commit and two hashes in
`tools/engine_pin.sh`; the workflows follow on the next push, and the change is
reviewable in the diff. `tools/deploy.sh` is the exception by design: it builds
an engine locally from these sources and passes that directory to
`-PprebuiltNative`, which is how the runtime itself gets tested on a device.

## On a device (Galaxy S20 FE, Snapdragon 865 / Adreno 650, Android 13)

`tools/deploy.sh [seconds]` rebuilds the runtime on every core (about 20 s when
only runtime files changed), packages the already-built library with
`-PprebuiltNative` (no second compile of the generated tree), installs, launches
and saves `logs-device.txt`. Game files go straight to
`Android/data/com.pes2017.recomp/files/game/` with `adb push` (6.5 GB, about a minute).

Where it stands: the recompiled code boots, the engine starts its threads
(Konami's names: `FileReadProcessThread`, `FileCriFsExecuteMainThread`,
`cobra::compression::job`, ...), D3D creates its device, and the command
processor presents about 60 frames a second. **The title/loading screen renders
correctly** (Camp Nou with the PES 2017 logo) after the memory-model and audio
fixes below.

Fixed on the way, each found from the device log:

| Symptom | Cause | Fix |
|---|---|---|
| indirect calls to functions that exist | the 51 MB function table overlapped the heap | table moved to 0x10000000 (outside the bank), `static_assert`s |
| `NtAllocateVirtualMemory: out of guest heap (512 MB)` | PES reserves 512 MB at once | far heap 0x20000000-0x80000000, backed on touch |
| critical section at `000101C1` (main thread stuck in D3D init) | kernel data imports left as import records | `ResolveDataImports()`: every variable gets a block, function slots get thunks |
| `KeBugCheck(0)` after a thread exits | `ExTerminateThread` returned | it now unwinds to the thread's entry |
| 42 stubbed imports the boot reaches | not previously implemented | `runtime/kernel/pes_imports.cpp` (semaphores, printf family, DPCs, XEX header fields, time, SHA-1, UI) |
| garbage image; 3 shaders "control flow does not terminate" (their microcode read as zeros) | heap and physical arena were separate memory, and the GPU's physical addresses were guessed back to the wrong one once both held data at the same offset | one 512 MB bank mapped at 0x80000000, 0xA0000000 and 0xC0000000 with a single allocator over physical offsets, as on the console |
| loading stops after ~26 MB, black screen | the XAudio render callback was passed its argument, not a pointer to it; it read the engine object's vtable as the object and waited on garbage | the argument is wrapped in a guest word, as the kernel (and Xenia) do |

## Not done (audited 2026-10-23)

The original list below this heading predated the match work. Status now, checked
against the code and the other docs; "unverified" means no device run was made for
this audit.

| # | Item | Status |
|---|---|---|
| 1 | Audio handshake | **Unverified.** The XAudio render driver and XMA decoder are in `runtime/apu` ([audio.md](audio.md)); the old "engine done events never signalled" note has no later mention. Check sound on a device. The `WAIT_REG_MEM` timeouts and write-watch coverage of the cached window are covered by the flip handshake in [d3d9-seam.md](d3d9-seam.md). |
| 2 | D3D9 `Present` / `ArenaWait` hooks | **Present located and hooked** (`T_D3D_Present` = `sub_823F98E0`, D3D's swap). **ArenaWait still open** (not needed so far). The 30 fps of cutscenes is the title's own request: the flip request D3D writes to scratch register 5 carries the present interval in bits 8-11, `0x100` in play and `0x200` in cutscenes and loading. Forcing it to 1 was tried and made the match intro slower (5-13 fps against 5-28), so there is no 60 fps cutscene option; see "The match intro" below. |
| 3 | Xenos GPU coverage | **Mostly done.** Matches render correctly (pitch, kits, shadows, oDepth, kill ops, resolve write-back). Open: cutscene flicker. (The board stencil bug is fixed, see "The team board" below.) See `.claude/docs/HANDOFF.md`. |
| 4 | Game I/O | **Works** (the game boots from the installed folder). Large-file overlapped reads are not separately verified. |
| 5 | Threading and memory | **Fixed for the match load** (see "Memory for the match load"). 16 KB-page devices untested. |
| 6 | XMA audio | **Implemented**, listening check outstanding (see 1). |
| 7 | Input, saves, UI | **Done** for touch, gamepad and system-data saves. **Open:** the language screens repeat every launch, and the streaming install to `cache:` is not implemented. |
| 8 | APK | **Done**: `android/app/build/outputs/apk/release` exists. |

## Findings: the front-end text (October 2026)

What made the dialogs and menus draw as empty boxes, and how it was found.

1. **XenonRecomp silently dropped ten instruction kinds** (`vandc`, `vnor128`,
   `vcmpgtuw`, `vaddsws`, `vrfip128`, `lvehx`, `stfsux`, `mulhd`, `rlwnm`,
   `bdnzt`). `logs-recomp.txt` says "Unrecognized instruction" for each, and the
   generated code simply has the comment and nothing else. The one that mattered:
   the game's sin/cos routine (`sub_832106B0`) used `vandc`, so a register kept a
   stale value and the result depended on what ran before it: cos(0) came out
   +-infinity, every UI text matrix got an infinite scale, and the glyphs
   collapsed. The cases are in `third_party/XenonRecomp/XenonRecomp/recompiler.cpp` (helpers in
   `XenonUtils/ppc_context.h`); `patches/xenonrecomp-pes17.patch` is regenerated from
   them. After a recompile, check `grep -c Unrecognized logs-recomp.txt`.
   The same fix made the intro movie draw.
2. **`VGT_INDX_OFFSET` (register 0x2102) was ignored.** The UI draws a batch of
   sprites from one vertex buffer, one draw a quad, stepping this offset by four.
   Every glyph draw read quad 0. It is now added to the vertex window and passed
   to `vkCmdDraw` as the first vertex (`vertexOffset`).
3. **Vertex data was served from a stale shadow of guest memory**
   (`gpu::watch::Serve`), so the glyph quads the CPU had just written read back as
   zero. Vertex fetches are copied from guest memory now
   (`kServeVertexShadow` in `renderer_arena.cpp`; turning it back on is the
   performance work).

## Debugging tools added

| Tool | What it does |
|---|---|
| `tools/to_dialog.sh [png]` | relaunch, press Start until the first dialog, screenshot |
| `tools/watch.sh` | follow `game.log`, one line per new error kind (run it under Monitor) |
| `files/dump_frame` | one frame's draws, shader microcode (`tools/xenos_shader.py`), vertex data, constants, register diffs, CPU-evaluated vertex shader outputs |
| `files/draw_range` (`"lo hi"`) | only draw numbers lo..hi of each frame |
| `files/dump_guest` (`"addr len"`) | write guest memory to `files/guest_dump.bin` |
| `files/watch_phys`, `files/watch_guest` | who writes this memory (host call stack per write) |
| `files/trap_inf` | report stores of +-inf (needs `-DPES_TRAP_INF=ON`, slows every store) |
| `tools/dump_phys.sh` | pull the 512 MB physical bank (root) |
| `runtime/gpu/xe/` | Xenia's shader interpreter, vendored with shims, evaluates a vertex shader on the CPU |

Probes at `[T]` level are traces; only faults print as `[E]` (the launcher's
error count was mostly stack dumps).

## The first-run quit, and the main menu

Start -> "Select a Storage Device" -> language -> auto-save notice ->
`Intro/ProcInstall`: that step is a four-state machine (`sub_82F3CBD0`, state at
object+88). It asks the install manager (global, `sub_83105358`) whether a job runs.
With a job it goes to state 3 and sends the "proceed" event; with none it goes
0 -> 1 -> 2 and state 2 sets the main loop's exit flag, so the title ended itself and
the next run began at the first-run screens again (nothing is saved). No install job is
ever started here, so `runtime/pes_hooks.cpp` starts the machine at state 3. The flow
then runs `Intro/DataPackApply/*` and reaches `TopMenu/TopMenu`: the main menu
(Home / Match / Competition / Football Life / Extras) draws and takes input.

### System data now saves

The first-run flow reaches `Intro/ProcInstall` without running
`FirstFlow/ProcSystemDataSave`, so nothing was ever saved and every launch began at
the first-run screens. Three pieces make it persist:

1. `runtime/pes_hooks.cpp` asks for `FirstFlow/ProcSystemDataSave` in place of
   `Intro/ProcInstall` the first time (the procedure switch is `sub_82F00CE8`).
2. `NtSetInformationFile` handles `FileRenameInformation`: the save is written as
   `data.tmp` (+ `data.inf`, `data.png`) and renamed to `data.bin`.
3. Content enumerators list the packages on disk (`kernel::ListContent`); the title
   finds `SYSTEM00000000` by enumerating, not by name. `XamContentCreateEx` keeps the
   title's XCONTENT_DATA in `<package>/.xcontent`.

Launch 2 then loads the save and skips `FirstFlow/ProcStart`. Open: the two
`Intro/SystemSetting/ProcLangInGame*` language screens still appear once per launch (the
language the title saves is not what it compares against), and the streaming install to
`cache:` is not implemented.

## Memory for the match load (October 2026)

The Exhibition kick-off runs the 512 MB bank dry ("MmAllocatePhysicalMemoryEx: out of
physical heap"). The fixes so far:

1. **Physical page size follows the protect flags** (`runtime/kernel/memory.cpp`), as in
   Xenia: 64 KB only with `MEM_LARGE_PAGES`, 16 MB with `0x80000000`, else 4 KB. A flat
   64 KB rule ran the bank out at the team select (thousands of 320-byte text meshes).
2. **The cached window is the bank too** on Linux/Android (`MapPhysicalAperture`). Only the
   physical and aperture windows were mapped from the bank descriptor, so the image and every
   heap allocation (handed out at cached-window addresses) were invisible to anything reading
   them by physical address. Check: `dump_guest` of `A2000000` and `82000000` now match (the
   image's `MZ` header).
3. **The function lookup table moved out of the bank** to guest `0x10000000` (51 MB back to
   the title). `PPC_CALL_INDIRECT_FUNC` is redefined in the force-included
   `runtime/mmio_hook.h`; runtime code uses `guest::FunctionSlotOffset`. `kHeapBase` is now
   `0x84100000`. Changing `mmio_hook.h` recompiles all of `ppc/` (about 20 minutes).
4. **Guest stacks** are what the thread asks for, at least 64 KB (was at least 512 KB), and go
   back to the bank when the thread exits.

5. **The actual leak: `NtQueryVirtualMemory`.** The CRT malloc maps large chunks itself
   (`sub_82597188` -> `82595DA8` -> `82595B80`, `VirtualAlloc` commit|reserve) and its munmap
   (`sub_82595C00`) releases a block only after `VirtualQuery` says BaseAddress ==
   AllocationBase == the block, State MEM_COMMIT and RegionSize <= size. The stub answered
   "the whole heap" for every address, so every big free failed and 340 MB leaked by the
   kick-off. It now looks the block up (`kernel::FindAllocation`). Bank use at the match load
   fell from 446 MB (full) to about 190 MB, and no allocation fails.
6. **`MmQueryStatistics`** fills X_MM_STATISTICS in pages, as Xenia does (XAPI's
   `GlobalMemoryStatus`, `sub_832D1F90`, reads TotalPhysicalPages, AvailablePages and the
   virtual totals; the stub wrote bytes and left the rest as stack garbage).

`report_memory` also lists the 30 biggest holders by guest call chain (three return
addresses up the back chain).

Not the fix: sending `NtAllocateVirtualMemory` to the far heap (see `.claude/docs/HANDOFF.md`).
`touch files/report_memory` prints live bytes per API and per heap.

## The match: a stack the recompiled code left unbalanced (October 2026)

With memory fixed, the load stopped at "Game Tips". A job worker (`sub_83576C40`) deleted
"job 0x10": its callee-saved r30 came back wrong from the job's step. The trail:

1. The step `sub_835770A0` returned with r1 0x3B0 lower than it entered, its own saved frame
   intact (`jobregs` probe). So a callee returned without popping its frame, and every
   epilogue above reloaded r30/r31 from the wrong place.
2. `tools/gen_r1_probes.py ROOT [DEPTH]` generates `runtime/pes_r1_probes.cpp`: a hook per
   function reachable from ROOT that logs a return with r1 moved. It found `sub_834E62A0`,
   whose frame is exactly 0x3B0.
3. Its two jump tables switched on **r12, the table's address**. `tools/trim_switch_tables.py`
   had overwritten `r` (the index register) with the trimmed label count, so no case
   matched, and the recompiler's `default: return;` left without the epilogue.
4. `tools/fix_switch_regs.py` (run by `build.sh` after the trim) reads the real index
   register from each dispatch (`rlwinm rX,rI,2` -> `lwzx`, or `lbzx` on the raw index).
   30 of 1327 tables were wrong: the three trimmed ones, plus 27 where the compiler compares
   one copy of the index and dispatches on another (`cmplwi r10` / `rlwinm r0,r11`).
5. Then 400 calls to `82800D08` "not identified as a function start". `fixbounds.py` ended
   an extended function at the next known start (a `bl` target or `.pdata`), and so
   swallowed functions reached only through vtables. It now ends at the first `blr` or
   outgoing `b` that no internal branch jumps past. Seven entries shrank, and XenonRecomp
   analyses the freed gaps into functions of their own.

After these, kick-off reaches `Match/MatchPostPause`. The match runs: clock, score, radar
and name bars. The 3D view draws white (next item).

`tools/to_match.sh [prefix] [shots] [kickoff|prematch|teams]` reaches kick-off in about
60 s. It waits on the procedures the game logs and recognises the pre-match screen by its
KICK-OFF tile.

## The dark match image (October 2026)

Each of these took part of the brightness or colour away. They're listed in the order they were found, each with the frame-dump evidence.

1. **Pixel centres.** D3D9 (`PA_SU_VTX_CNTL.pix_center` = 0) centres pixels on integers, Vulkan half a pixel on. The viewport is now shifted by half a pixel.
   - Before: the exposure, written by one triangle into a 1×1 R32F target, never landed, because the triangle's edge ran through the Vulkan centre.
   - The scene kept a stale 0.06 instead of about 1.2.
2. **Quad lists** (primitive 13) are expanded to triangles. The crowd billboards use them.
3. **A resolve copy is used for a fetch only if the format matches and the fetch fits inside it.**
   - The pitch's 2048×2048 DXT1 sat where an earlier screen had resolved a 512×128 image, and was drawn from that copy: grey grass.
4. **4× clear rectangles** (2×2 samples) now find the 1× surface they alias (`pitch << min(samples, 1)`) and scale y by 2.
5. **Param gen.** `SQ_PROGRAM_CNTL.param_gen` puts the pixel position in the register `SQ_CONTEXT_MISC.param_gen_pos` names.
   - The translator had no param gen at all, so every screen-space lookup read texel (0,0).
   - It now goes in bits 8–15 of push word 3, applied with constant-index selects. A dynamic index into the temporaries put every pixel shader's registers in memory: 1 fps.
6. **EDRAM aliasing of a depth clear.** PES clears depth (mode DepthOnly) at tile 0 to zero and then MAX-blends a player-shadow mask into the colour surface at tile 0, expecting black.
   - Colour images at the same tile are now cleared too, for zero clears.
   - Before: the mask was drawn over the previous frame and the pitch shading read garbage.
7. **Texture budget.** The app's preference defaulted to 128 MB, which overrode the runtime's sizing (memory / 6, 1 GB here). BC textures are decoded to RGBA on Adreno, so the match thrashed. The default is now 0 ("automatic").

8. **Resolve write-back** (the kits and the pre-match 3D player preview). PES draws each player's shirt, numbers and name during the load and resolves them. The kit textures the match fetches are then made from those bytes in guest memory, but resolves never left the GPU here, so players were black.
   - Small 8_8_8_8 resolves (up to 512×512) are now written back synchronously: submit, wait, read back, and store with Xenos 2D tiling, the R/B swap bit and 8-in-32 endian.
   - A destination also resolved in the previous frame is a per-frame effect and stays GPU-only.
   - Each resolve destination's first use is logged as `resolve: first to …`.

Tools added:
- `tools/xenos_disasm.py`: ALU and fetch disassembler for `DUMP ucode` lines.
- The frame dump now also logs `ps c*` (every non-zero constant the first time a shader appears), `DUMP resolve`, `DUMP   resolve clear`, `DUMP   skipped`, and `samples` for targets, depth and resolve copies, read a frame later.
- It writes the large images to `files/frame_images/*.pnm`.
- `files/skip_ps` (pixel shader hashes to skip) and `files/draw_range` bisect a frame. `files/quad_debug` is a temporary switch.

## Kits, crowd, performance (October 2026, late)

**Wrong kits** (a defender in the keeper's kit, an opponent in ours, varying per load).
- The game composes each person's kit on the GPU during the load: 25 compositions, 10 red, 10 yellow, 3 teal (2 keepers and the referee) and 2 dark (the assistants).
- Each composition is resolved and written back, and the game builds the kit texture in guest memory, reusing the addresses for the next composition.
- A fetch of such an address used the GPU-side resolve copy, which by then could be another person's kit.
- **Rule:** a resolve that was written back is read from guest memory (`Resolved::writtenBack`).
- Two other fixes for black players:
  - Write-backs for a destination resolved in many recent frames are skipped (per-frame effects).
  - Pipelines for small off-screen targets (kit drawing, the crowd atlas) compile synchronously, because a skipped one-time composition is lost for good.
- `tools/kits.sh [dumps] [gap]` checks it. It merges several frame dumps (each player draw's kit texture with its average colour) and reads the load-time `kitcompose` log, so every person is counted on camera or not. More than 10 of a team colour in one frame is a definite error.

**Crowd.** Still missing. Found and fixed on the way:
- Render targets are capped at 4096 rows, not 2048 (the atlas is 128×3328).
- EDRAM depth clears cover only the tiles their rectangle spans.
- Vertex buffers of 128 KB or less are copied whole, since the billboard records are indexed by `index/4` and the corner table by `index mod 4`.

The billboards now cover the stands with sensible UVs (verified with a temporary UV-as-colour shader). What's left is in sampling the atlas.

**Performance**, steady match 31–42 fps → **52 fps (19.2 ms)** on the stock driver:
- Narrowed the whole-buffer copy (1 MB → 128 KB; the command thread was copying megabytes a frame).
- Write-backs of per-frame resolves stop after 8 consecutive frames (each one stalls the CPU until the GPU idles).
- Draw-requested pipelines compile with no backpressure sleep (`QueueItem::urgent`). Prewarm sleeps 25 ms in play, 15 ms otherwise (was 200+ / 120).
- **Pipelines the worker builds for draws are now saved** (`TakeBuiltForDraws`). Before, everything compiled on demand on Android was forgotten each run, so the intro and goal scenes skipped about 1,100 draws every time (players missing, effects popping in).
- `tools/fps.sh [seconds]` logs fps per 600 swaps (`perf: … fps`).

**Turnip** (Mesa, K11MCH1 v26.0.0 R8) loads with libadrenotools (`android/fetch_deps.sh`; `deploy.sh` packages the hook libraries). On this phone it was slower, 29 fps against 49, with the job threads spinning. The package stays installed at `files/drivers/turnip_r8`; the selection was cleared.

**Testing aids:**
- `tools/pad.sh set|tap|clear`: a virtual controller through `files/pad`. User 1 is a second controller.
- `files/skip_ps` and `files/draw_range` bisect a frame. The frame dump logs `vs c*` / `ps c*` constants and kit lines.

## Reproducing

```bash
GAME=../GAME ./build.sh                 # generate ppc/ and build libpes17.so
RECOMPILE_ONLY=1 GAME=../GAME ./build.sh  # generate ppc/ only
```

If the executable ever changes (another region or a new version), delete
the two generated blocks in `config/PES.toml` (`fixbounds`, `fix_ladders`) and
run `build.sh` again; it re-derives them.

## Legal

No game code, data or generated translations belong in version control; the
`.gitignore` excludes `pes/`, `ppc/`, `GAME/` and the build trees. Build only
from a disc you own.

## Crowd, second pass (October 2026): the kill that was never emitted

How the crowd is drawn (all from one frame dump during the match intro):

1. **Atlases** `06C50000` and `06EA0000` (128x3328, resolved from EDRAM tile 0, pitch 160): about 25 seated-person sprites,
   drawn by the person shader `AB28D67A…` as **data, not colour**: R,G = the person texture's UV, B,A = a tangent-space normal.
   The background is cleared to (0,0,1,1).
2. **Two G-buffers** (`07EB0000` and `08370000`, 1280x720, tile 0 pitch 1280): the billboards (primitive 13 quad lists, 4 to 6 draws, ps `B4BBEA…`
   then `70F64A…`) sample an atlas and write what they find. Their pixel shader discards the sprite's empty background:
   `mad r1.x = -r0.z*r0.w + c255.x; kill_gt _, c255.yyyy, r1.xxxx` with c255 = (0.998, 0, 0, 0), so a texel with z*w = 1 (the background) is killed.
3. **The composite**, one full-screen rectangle (ps `4934EC…`, stencil-tested, into the HDR scene target): reads both G-buffers, the crowd's colour
   texture (256x2048 DXT1) and the exposure, and lights the crowd.

**Bug found and fixed:** the translator skipped any vector instruction whose write mask is empty ("the vector unit carries no state"). A `kill_*`
that writes to `_` is exactly that, so no kill ever ran: every billboard drew its whole quad (solid blue boxes in the G-buffer). `TranslateAlu` now
always builds the kill opcodes. This is not crowd-specific: any shader that discards through a masked kill (alpha-tested foliage, fences, sprites)
was affected. Verified: the G-buffer now holds only the spectators (alpha 240/245 where it held 255 boxes), and `08370000` looks right.

**Fixed (same day), two more causes:**

1. **A depth-only clear wiped the crowd's stencil mask.** PES marks crowd pixels in the stencil buffer (REPLACE, ref 0x10) in the first G-buffer pass and
   tests the mark (EQUAL 0x10) in the second pass and the composite, with a depth clear drawn in between whose stencil test is **off**. On the console that
   clear leaves the stencil bits alone; `ClearAliasedDepth` cleared depth and stencil together, so every later pass tested against zero. It now clears the
   stencil only when the clear rectangle has the stencil test on with REPLACE and a full write mask (to the reference). Found by dumping the stencil
   buffer before every crowd draw (`DUMP   stencil before draw …`).
2. **The crowd's colour atlas was black because a resolve was never copied back to memory.** The title draws 256x2048 colour swatches, resolves them
   (8_8_8_8), and **packs that memory into the 256x2048 DXT1 colour atlas on the CPU**. The write-back of small resolves (kits) was limited to 1024 on each
   side, so this 2048-tall resolve stayed on the GPU, the CPU packed zeros, and the atlas held `00000000 FFFFFFFF` (black) blocks for the whole run. The limit
   is now an area (at most 1M pixels, each side up to 2048). Checked: the dump's `crowd colour atlas` line goes from 0 to 32768 of 32768 blocks lit, and the
   stands fill with supporters in red.

Also found, and why small render passes are off by default: with `small_passes` the crowd's two data passes were clipped to a rectangle.

Tools added while hunting this: `files/crowd_hunt` (finds the black DXT1 pattern in physical memory as soon as it appears and watches it for writers),
`DUMP   stencil before draw`, `frame_images/at_<addr>_<draw>.pnm` (a resolve as it was when made), `frame_images/tex_*.pnm` (the crowd composite's textures
as the GPU has them), and a `shader:` log line for any program whose memory exports are dropped.

## The match intro (October 2026)

The intro after kick-off loading (players walking out, about 1000-1200 draws a frame) dropped to 7-20 fps for its
first five seconds, then held 25-30. Found with `introonly`/`intro` macros (the `play` macro cut before it skips),
per-pass GPU timing (`files/gpu_timing`, 10-frame windows), `simpleperf` and a tally of the consumer's fence waits:

1. **It is not the 30 fps cap.** That is the title's interval 2 (above), and the steady part reaches it.
2. **GPU time.** About 23-28 ms of pass time a frame at 0.75 scale: eight scene passes at ~2 ms and the
   `x1536`/`x1024` passes. Halving the scale only took the scene passes from 2.0 to 1.5 ms each, so it is not
   pixel-bound. The load rule of the dynamic scale decided over 90-frame windows with two of pause, i.e. after the
   intro: `dynamic_scale.h` now has a sudden rule (8 frames over 1.5x the budget move the scale straight to the
   step that fits; after one, a single calm window puts it back). Tested in `tests/dynamic_scale_test.cpp`.
3. **Resolve write-backs.** Each is a submit, a wait for the GPU and a read. The intro starts a dozen per-frame
   destinations at once (`640x360`, `320x180`, the crowd's `64x1664`/`32x832`/`16x416`), each written back for
   eight frames before it counted as per-frame: about 100 stalls, 740 ms in the first eight frames (7 fps).
   Three frames now (`kPerFrameAfter`); kits (`tools/kits.sh`: 10 red, 10 yellow, 3 teal, 2 official) and the
   crowd are unchanged.
4. **Write-backs no longer wait one by one.** Each used to submit, wait for the GPU to go idle and read the
   image back on the spot. Now the copy-out is queued in the frame into a host-visible buffer and the frame is
   submitted at once, so the GPU works on it while the consumer records on; the pixels are stored into guest memory
   when the title could next look (an end-of-pipe write or interrupt, `vk::renderer::PublishWriteBacks`, called from
   `WritePhysical` except for the arena's own progress reports; and the swap), waiting only for that submission.
   `files/sync_writeback` brings back the old wait for comparison. Measured on the S20 FE, alternating runs with
   the cooler: the GPU wait inside the write-backs fell from 1670-1790 ms to 710-830 ms over the intro, and its
   middle seconds from 12-17 to 15-22 fps; the first second (5-12 fps) and the last (30) did not move. Kits and
   crowd are unchanged (`tools/kits.sh`: 10 red, 10 yellow, 3 teal, 2 official).
5. **What is left is the title's own serialisation.** The guest main thread spins in `sub_8386CA10` (a yield loop,
   77 % of its cycles in `sched_yield`) waiting on the render thread, which waits on D3D fences that follow each
   player-texture composition (`512x512`, `128x128` resolves read back on the CPU); the GPU sits at 820 MHz and
   51-74 % busy. Holding the title's end-of-pipe writes until the copies land (without the early submit) gave no
   gain. Next: the GPU cost of the intro's scene passes, and the first second (new textures and pipelines).

Measure on a cool phone: after a few back-to-back runs the S20 FE reaches heat status 3-4 and the GPU is capped,
which by itself costs the intro a third of its frame rate.

## The team board over the crowd (October 2026)

The match intro's "Exhibition / KONAMI Stadium" board (and the half-time stats) drew with the stands showing through
it. Found by dumping the board's frame (`touch files/dump_frame` the moment a screenshot matched the banner) and
reading `DUMP   stencil before draw`: the interface's sprites test stencil `EQUAL 1`, and just before them the 1x
depth image still held the crowd's marks (`10:86400`, then `10:46237 11:40163` after the panel wrote bit 0), so about
100,000 pixels failed the test wherever crowd lay behind. The interface clears the stencil with a rectangle through the
4x depth image at the same tile (one EDRAM buffer on the console); `ClearAliasedStencil` clears the other images of
that tile, but only for `clearRectangles`, which needs the depth write on. This rectangle is stencil-only and in
depth-only mode (`RB_MODECONTROL` 5, depth control `0x8701`: ALWAYS, REPLACE, write mask FF, no depth test or write), so
it fell through as an ordinary draw. `renderer_draw.cpp` now also treats a rect list with no viewport transform and
that stencil state as a clear (`stencilClearRectangle`), whatever the EDRAM mode. Verified: the board is an opaque
teal panel, the match crowd and kits are unchanged.

## The black bars in the stands (October 2026)

Thin black vertical lines flickered in the crowd, from a few pixels to a person's height. Found from screenshots every
half second, then a dump of a kickoff frame: the crowd's G-buffer (`resolve_07EB0000`) had 1-pixel columns of
alpha 0 where no sprite had written, 14-25 pixels tall, and each such column was covered by 12-19 overlapping
billboards (18x37 px, dumped from the vertex shader on the CPU), so it was not a missing sprite. The atlas the
billboards sample (`resolve_06C50000`, 128x3328) had its last column and its last row at (0,0,0,0) instead of the
background (0,0,1,1): the kill that drops the background (`z*w > 0.998`) does not catch transparent black, so a
billboard sampling the atlas edge drew black, marked the stencil and wrote depth, hiding what was behind it.

The atlas is cleared by a pair of rectangles (4x half from -0.5 to 39.5, 1x half from 79.5 to 127.5, rows -0.5 to
3327.5). With the viewport transform off the window coordinates become NDC in `renderer_draw.cpp` with no
half-pixel shift, and D3D9's pixel centres are on integers (`ViewportOf` shifts for that): the rectangle ended exactly
on column 127's centre as Vulkan has it, an edge a pixel is not covered past, so column 127 and row 3327 were never
cleared. The NDC offset now has the same half pixel (`PA_SU_VTX_CNTL` pix_center 0). Verified: the atlas's edges
are background, isolated 1-pixel gap lines in the G-buffer fall from 22 to 1 in a stand block, the stands show no
bars, and the title, team board, kits (10/10/3/2) and the match are unchanged. Every viewport-off draw (clears and
pre-transformed quads) lost its last column and row before this; none of the others was visible.

## The POCO X7 Pro (Dimensity 8400, Mali-G720): loading and the unsteady match (October 2026)

Measured with a recorded macro on the phone, a stamped log stream, per-thread scheduler times, a Perfetto
scheduler trace and `PES17_PROFILE` (below).

**Fixed.**
1. *The GPU ring consumer shared the main thread's core.* The consumer is created from the main thread, which is
   pinned to the prime core, and inherited it. During the match load it waited 45 s of 58 in the run queue and the game sat
   at 1-3 fps for the minute and a half after kick-off. The consumer and the presenter now go to the performance
   cluster (`ThreadAffinityRole::RenderCore`; `PES17_PINNING=shared` restores the old placement). Same macro: the
   stall fell to about twenty seconds, then 45-55 fps.
2. *Pipelines were rebuilt every launch.* The on-disk VkPipelineCache was off on Mali (old driver bugs). On this driver the
   blob loads and verifies run after run (248 KB, then 3214 KB as more pipelines are met), and prewarming 40 pipelines
   falls from 609 ms to about 50-90 ms. It is the default now; `PES17_PERSIST_PIPELINE_CACHE=0` turns it off.

**Diagnosed, not fixed: the match holds 30-50 fps.** GPU pass time is about 4.5 ms a frame and the runtime waits 0.00 ms for
the GPU. The CPU is the limit, in a particular way:
- The clocks are held well under the hardware maximum while it runs: in a trace the prime core averaged 1.36 GHz (3.25 maximum)
  and never passed 1.7, the mid cluster 1.3 (3.0), the little cores 0.6. Game Mode Performance (`cmd game mode performance
  com.pes2017.recomp`) lifted the ceiling to 2.0 GHz, no more. The cap is the system's, not the app's.
- Sampling shows the main thread is not working at 95%, it is waiting: about half its samples are the engine's
  yield-spin (`sub_8386CA10` -> `NtYieldExecution` -> `sched_yield`) and about 38% sleep inside the D3D fence wait
  (`sub_823F70F8`); under 10% is game code. The render thread (routine `83863380`) sleeps in its own D3D waits
  (`sub_823F6C98`); the ring consumer spends 40% of its time in the 50 us polling sleep of `WAIT_REG_MEM`
  and the rest mostly in `vk::renderer::Draw` (33%) and the resolve write-back (9-17%). No thread is saturated: a frame is
  a chain of handoffs, each a sleep and a wake on a slow core.
- Tried, no gain: the write watch off (`PES17_NO_SHADOW=1`; an earlier claim of 25,000 faults a second was a
  mis-parsed `/proc/<tid>/stat`: the real minor-fault rate is under 200 a second), a priority boost for the render
  threads, and 60 fps cutscenes. Releasing the flip handshake from the blank (flip request interval 0) was much worse (13-14 fps
  against 32).

**`PES17_PROFILE=<names>`** (`runtime/profiler.cpp`, `tools/profile_report.py`): a sampling profiler for phones where
simpleperf is refused. It signals the threads whose names contain a comma-separated substring about 500 times a second,
records pc and return addresses, and `touch files/profile_dump` writes them to the log; the report script names the
functions from the unstripped `build-android/libpes17.so` and the libc offsets (pull the phone's libc and symbolize).
Set it through `files/env_override`. The first dump after a load is the load's; take a second for the match.

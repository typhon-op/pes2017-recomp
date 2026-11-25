# PES 2017 Android (Xbox 360 → ARM64 recompilation)

An ahead-of-time recompilation of *Pro Evolution Soccer 2017* (Xbox 360) to native ARM64 code for Android.
The game's PowerPC code is translated to C++ with [XenonRecomp](https://github.com/hedge-dev/XenonRecomp)
and built with the Android NDK. A host runtime stands in for the console's kernel, GPU (Vulkan) and audio,
and a Kotlin app installs the game files, configures the run and starts the native library.

Status: work in progress. The game boots, renders and plays a match on a Galaxy S20 FE (Snapdragon 865 /
Adreno 650); performance is limited by heat before it is limited by code. [docs/PORTING.md](docs/PORTING.md)
says exactly what works and what is left.

You need your own copy of the game. Nothing from it is in this repository.

## Quickstart

1. **Check your phone:** arm64 Android 8.0 or newer with Vulkan, and about 9 GB of free storage (the game files
   are about 6.5 GB). A recent Snapdragon phone is the tested target.
2. **Get the game files:** see [Which copy of the game you need](#which-copy-of-the-game-you-need) below, and
   copy them to the phone.
3. **Install the app:** install the APK over USB or by opening it on the phone (build it from `android/` with
   `./gradlew :app:assembleRelease`, see [Build](#build)), and allow installs from your file manager if asked.
4. **Install the game:** open the app, tap **INSTALL FROM DISC IMAGE**, choose **Browse the phone's storage** (or **Choose with the Files app**),
   pick the ISO or folder, and tap **Install**. If it stops, start again: it carries on from what was copied.
5. **Pick a preset:** open **Settings**; the app suggests High, Medium or Low for your phone. Lower the render
   quality or turn off multisampling if the match stutters.
6. **Play:** tap **KICK OFF** in the launcher. Use the on-screen buttons (move or hide them with **Edit controls**) or
   connect a gamepad.
7. **Back up your saves:** **Settings > Back up saves** makes a zip you can keep in Downloads or Drive.

If something goes wrong, open **Log** in the launcher for a short summary, or share the bug report.

## Which copy of the game you need

The recompiled code is made from one exact executable, so the copy has to match it.

- **Game and version:** *Pro Evolution Soccer 2017* for **Xbox 360, Europe** (the `default.xex` of about 32 MB
  that this repository was built from). Other regions, other versions, the PC or PS4 releases, and other
  games will not work. The app checks `default.xex` by its SHA-256 and warns you when it is a different one.
- **Your own dump:** rip your own disc. Nothing from the game is distributed here.
- **Same copy for the build and the phone:** the APK is recompiled from a specific `default.xex`, so if you
  build it yourself, use the same disc dump you install on the phone.

Either form works:

| Form | What it must contain | Notes |
|---|---|---|
| **Disc image** (`.iso`) | an Xbox 360 disc image of the game, at least 500 MB | the installer reads it directly and copies only what the game needs |
| **Extracted folder** | `default.xex`, every `.cpk` archive (`dt00_x36.cpk`, `dt10_x36.cpk`, the `dt20_*` language packs, ...) and the `nxeart` file, together in one folder | pick the folder that holds `default.xex` itself, not its parent |

Only `default.xex`, the `.cpk` files and `nxeart` are copied. Other disc content (such as `$SystemUpdate`
and the `su2008d...` folder) is ignored, so it does not matter whether it is there. The full set is about
6.5 GB, so keep about 9 GB free while installing. Extract the whole disc, not a selection of
the `.cpk` files.

## Build

```bash
GAME=../GAME ./build.sh            # recompile PPC -> C++ and build libpes17.so (arm64-v8a)
tools/deploy.sh                    # runtime-only rebuild, package, install and launch on the connected phone
```

`GAME` is the folder holding the disc's `default.xex`. The first full build takes about 30 minutes;
`RECOMPILE_ONLY=1` stops after the C++ is generated, and `PROFILE=safe|balanced|aggressive` trades generated
code size against how much of the guest register file stays in locals
([docs/RECOMP_RESEARCH.md](docs/RECOMP_RESEARCH.md)). The APK is built from `android/` with Gradle.
Game files go to `Android/data/com.pes2017.recomp/files/game/` (about 6.5 GB; plan on 9 GB free).

## Features

- **Install from the app:** pick your disc folder or ISO; an interrupted copy carries on where it stopped.
- **Picture settings:** display resolution, internal render multiplier (1x to 3x), match render quality, FSR
  sharpening upscale, multisampling for clean pitch lines, and a larger match ball that is easier to follow on a
  small screen.
- **Widescreen:** fills the whole phone screen instead of showing black bars.
- **Performance presets:** High, Medium and Low, chosen from what your phone looks like. Medium and Low lower
  the picture quality when the phone gets hot or the GPU falls behind, to keep the frame rate steadier.
- **FPS limit:** matches your screen's refresh rate by default.
- **Touch controls:** on-screen buttons you can move and resize, and hide when you play on a pad.
- **Gamepad:** physical controllers work, including a second one for player 2.
- **Custom GPU driver:** import a Turnip driver (`.zip`) on Adreno phones for better speed or compatibility.
- **Save backup and restore:** Master League, Become a Legend, edit data and settings in one zip, with an
  automatic copy to roll back to.
- **Updates and support:** in-app update check, a log viewer with a short error summary, bug-report sharing,
  and one-tap clearing of the shader cache and logs.
- **Smoother and lighter:** the shader cache is saved without freezing the game, and an optional texture
  compression mode cuts texture memory by about four times on phones without native support.

## What you need to build it

| Requirement | Used for |
|---|---|
| macOS or Linux host, `cmake`, `ninja`, `clang`, `python3` 3.10+, `git` | recompiler build, generators, `build.sh` |
| Android NDK 28.x (`28.2.13676358`), Android SDK platform 35, JDK 17 | native library and Gradle app (`minSdk` 26, arm64-v8a only) |
| An Android phone with Vulkan 1.0+, `adb` | running and profiling; developed on a rooted Galaxy S20 FE |
| Your own PES 2017 (Xbox 360, Europe) disc files, `default.xex` | the input to the recompiler |
| Network on first build | CMake fetches SDL3 and Dear ImGui |

## Documentation

| | |
|---|---|
| [docs/PORTING.md](docs/PORTING.md) | status, what is fixed, what blocks next |
| [docs/performance.md](docs/performance.md) | in-match measurements on the phone |
| [docs/RECOMP_RESEARCH.md](docs/RECOMP_RESEARCH.md) | recompiler findings and pipeline options |
| [docs/runtime.md](docs/runtime.md), [rendering](docs/rendering.md), [shaders](docs/shaders.md), [textures](docs/textures.md), [audio](docs/audio.md), [d3d9-seam](docs/d3d9-seam.md) | how the runtime stands in for the console |
| [docs/switches.md](docs/switches.md) | `PES17_*` runtime switches |
| [docs/skill-moves.md](docs/skill-moves.md) | the 53 proposed skill mappings verified against the title, with timelines |
| [docs/pillars/](docs/pillars/README.md) | the mobile performance roadmap |
| [.claude/docs/](.claude/docs/) | Claude session handovers and research notes: device setup, tools, open issues |

## Credits

This port stands on other people's work.

- **[XenonRecomp](https://github.com/hedge-dev/XenonRecomp)** by hedge-dev: the PowerPC → C++ recompiler and
  XenonAnalyse, vendored in `third_party/XenonRecomp` with local fixes (see `UPSTREAM.md` there). It bundles
  [fmt](https://github.com/fmtlib/fmt), [libmspack](https://github.com/kyz/libmspack),
  [simde](https://github.com/simd-everywhere/simde), [tiny-AES-c](https://github.com/kokke/tiny-AES-c),
  [toml++](https://github.com/marzer/tomlplusplus) and [xxHash](https://github.com/Cyan4973/xxHash).
- **[mw2-recompiled](https://github.com/PaulCombal/mw2-recompiled)** by PaulCombal and contributors (and
  GuruMachanica and contributors), GPL-3.0: the scaffold this port was built on. Its Xbox 360 runtime (kernel,
  XAM, GPU command processor, Xenos shader translator, Vulkan renderer, launcher) was adapted for PES 2017.
- **[Xenia](https://xenia.jp)** and its contributors: the reference for Xbox 360 kernel behaviour, the Xenos
  GPU, texture formats and the XMA decoder; `third_party/ffmpeg-xenia` is Xenia's FFmpeg subset for XMA.
- **ReXGlue SDK**: sibling recompilation runtime we compared against
  ([.claude/docs/REXGLUE-EVALUATION.md](.claude/docs/REXGLUE-EVALUATION.md)); the FSR 1 SPIR-V in `runtime/gpu/vulkan/ffx/`
  comes from its SDK.
- **AMD FidelityFX Super Resolution 1** (EASU/RCAS, MIT): the optional upscaler.
- **[libadrenotools](https://github.com/bylaws/libadrenotools)** by bylaws: loading custom Adreno Vulkan drivers.
- **[SDL3](https://libsdl.org)**, **[Dear ImGui](https://github.com/ocornut/imgui)**,
  **[FFmpeg](https://ffmpeg.org)**, **[RenderDoc](https://renderdoc.org)** (`renderdoc_app.h`, GPU capture).
- **Barlow Condensed** (Jeremy Tribby, SIL OFL 1.1; `third_party/fonts`): launcher typeface.
- **Android NDK / SDK / Gradle** and the **Khronos Vulkan** headers and SPIR-V tooling.
- **Konami**: *Pro Evolution Soccer 2017* is their game and property. This project contains none of it and
  is not affiliated with or endorsed by Konami. The app's icon (drawn by `tools/make_artwork.py`), menu
  backdrop and wordmark are original artwork; the game's name is used only to say what it runs.

## Licence

GPL-3.0-only, see [LICENSE](LICENSE).

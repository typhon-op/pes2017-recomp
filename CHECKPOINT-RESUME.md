# PES 2017 recomp — tfix6 session checkpoint

Everything needed to resume without rebuilding anything.

Branch `tfix6`, HEAD `df1ff6cbd933207e4edf390771a2e8e87aababe1`
("Lock the Snapdragon path before any low-end work lands"), pushed to
`origin/tfix6`.

## What is in this archive

| Path | What it is |
|---|---|
| `pes17-tfix6.bundle` | Every ref of the repo (`git bundle`). `git clone pes17-tfix6.bundle` restores the worktree at HEAD. |
| `tree/` | The working tree at HEAD, plain files (docs, patches, `android/`, `runtime/`, sources) so nothing depends on git being readable first. |
| `_checkpoint/pes17-native-engine-checkpoint-tfix5/` | The engine checkpoint: source, `build-android/` (CMake/Ninja tree with objects and the incremental cache), `engine6`–`engine9` prebuilt `libmain.so` sets, `MANIFEST.sha256`, `BUILD-INFO.txt`, `check-sources.sh`. |
| `android/app/build/` | The Gradle build tree as of the 0.3.4 release APK: intermediates, merged libs, R class, test results. Reused by `assembleRelease`. |
| `_kit/` | Toolchain scripts and the portable kit (`kit.tar.zst`, `kit.list`, `nsbuild.sh`, `supplied.apk`, `default.xex`). The *extracted* `_kit/resume-kit/` (2.2 GB) is deliberately left out: it is `kit.tar.zst` unpacked, and it stays in the persistent workspace. |
| `_apk/…0.3.4….apk` | The newest APK (0.3.4 / 54). Older APKs stay in the workspace only. |
| `PROJECT_HANDOFF.md`, `docs/`, `patches/` | Same as the tree, duplicated here for convenience. |

## Restore

```bash
mkdir -p ~/pes && cd ~/pes
git clone /path/to/checkpoint/pes17-tfix6.bundle pes17-recomp
cd pes17-recomp && git checkout tfix6        # exact HEAD, full history
```

Then place the build trees back where the scripts expect them:

```bash
# engine checkpoint (source + build-android + engineN libs)
mv /path/to/checkpoint/_checkpoint .          # ./_checkpoint/pes17-native-engine-checkpoint-tfix5
# android build tree (incremental, optional but avoids a full Gradle build)
mv /path/to/checkpoint/android/app/build android/app/
# toolchain, if the machine is new
mkdir -p _kit && tar -xf /path/to/checkpoint/_kit/kit.tar.zst -C _kit
```

## Build commands (unchanged from the port's own docs)

Android APK, reusing the Gradle tree (no `clean`):

```bash
cd android
JAVA_HOME=$HOME/toolchain/jdk-17 ANDROID_HOME=$HOME/toolchain \
PES17_VERSION_NAME=0.3.5 PES17_VERSION_CODE=55 \
./gradlew :app:assembleRelease \
  -PprebuiltNative=$PWD/../_checkpoint/pes17-native-engine-checkpoint-tfix5/engine9 \
  --no-daemon --max-workers=2 -x lint -x lintVitalRelease
```

Environment variables must come **before** `./gradlew`.

Engine, incremental (only touched translation units rebuild):

```bash
cd _checkpoint/pes17-native-engine-checkpoint-tfix5
cmake -S engine -B build-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DPES17_VULKAN_DYNAMIC=1
ninja -C build-android            # do NOT delete build-android; it holds the objects
```

Unit tests (423 tests, 0 failures at this commit):

```bash
cd android && JAVA_HOME=$HOME/toolchain/jdk-17 ANDROID_HOME=$HOME/toolchain \
  ./gradlew :app:testDebugUnitTest --no-daemon --max-workers=2
```

Native tests:

```bash
cmake -S tests -B /tmp/build-tests -G Ninja && ninja -C /tmp/build-tests -k 0
ctest --test-dir /tmp/build-tests -E '^bc$'
```

## Where the work stands

- Lowest preset (opt-in, never detected), preset-switch fix, preset-row hint,
  frame-stats pairing patch: all committed and pushed.
- Newest change is test-only: `DeviceProfileTest` locks the Snapdragon column of
  the GPU table, the High preset's values, "only Lowest turns the crowd off", and
  the unknown-GPU → Medium fallback.
- Nothing low-end is applied globally; the renderer's vendor branches
  (`pipeline.cpp:299/308` Adreno, `:466` Qualcomm, `:801` `IsMali()`) are unchanged.
- Unverified: any on-device claim (G85 30 FPS, FIFO vs mailbox, pinning A/B, the
  hint's on-screen look). No device or emulator in this environment.
- Toolchain comes from the kit: JDK 17, `build-tools/35.0.0`, NDK, `platforms`,
  all under `$HOME/toolchain` (`$ANDROID_HOME`, `$ANDROID_NDK`).

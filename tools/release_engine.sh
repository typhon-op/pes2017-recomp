#!/usr/bin/env bash
# Cut an engine release from this checkout, publish it, and pin builds to it.
#
#   tools/release_engine.sh [tag] [--no-push]
#
# The engine cannot be built in CI: libpes17.so is the recompiled title, made
# from the disc's own default.xex, which no repository holds. So this runs on a
# machine that has the game, the NDK and a signed-in `gh`, and does four steps
# in order:
#
#   1. build this checkout with ./build.sh (ppc/ regenerated, then the library),
#      strip and stage it, and package a debug apk around it;
#   2. publish that apk as release <tag> (default V0.2.4), asset app-debug.apk --
#      the name tools/engine_pin.sh expects;
#   3. download the published asset again and hash *that*, so the pin names what
#      the release actually serves rather than what was uploaded;
#   4. rewrite tools/engine_pin.sh, prove the new pin with
#      tools/fetch_engine.sh --audit, and commit and push it -- which is what
#      makes every later CI and laptop build package this engine.
#
# The tag is created at the commit the apk was built from; the pin bump is the
# commit after it, so `git show <tag>..HEAD` is what the release changed.
#
# The apk's version moves here and nowhere else: versionName becomes the tag and
# versionCode goes up by one in android/app/build.gradle.kts before the build,
# so a branch whose engine is still the old one keeps saying so rather than
# labelling the previous engine as this release.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT"

TAG=V0.2.4
PUSH=1
for a in "$@"; do
    case "$a" in
        --no-push) PUSH=0 ;;
        -*) echo "unknown option: $a" >&2; exit 2 ;;
        *) TAG=$a ;;
    esac
done

TAG_LOWER=$(printf '%s' "$TAG" | tr 'A-Z' 'a-z')
NOTES=release/notes-${TAG_LOWER#v}.md
GAME=${GAME:-$ROOT/../GAME}

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# shellcheck source=tools/engine_pin.sh
. "$ROOT/tools/engine_pin.sh"

sha_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}
TMP=()
cleanup() { [ ${#TMP[@]} -eq 0 ] || rm -rf "${TMP[@]}"; }
trap cleanup EXIT

# ---- the release this will replace -----------------------------------------
say "current pin: $ENGINE_TAG ($ENGINE_COMMIT)"
say "new release: $TAG"

# ---- the machine has to be able to do this at all --------------------------
[ -f "$GAME/default.xex" ] || die "no game executable: put default.xex in $GAME, or set GAME=<folder holding default.xex>"
[ -f "$NOTES" ] || die "no release notes: write $NOTES first (release/notes-0.2.0.md is the shape of one)"
command -v gh >/dev/null 2>&1 || die "gh is not installed"
gh auth status >/dev/null 2>&1 || die "gh is not signed in (gh auth login)"
# A list of `a && b` would end the script here on the *success* of the first
# command under `set -e` only if it failed; written out so either way is clear.
if gh release view "$TAG" >/dev/null 2>&1; then
    die "release $TAG already exists; pick another tag or delete it first"
fi
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
    die "tag $TAG already exists on origin; pick another tag"
fi
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "tracked files have uncommitted changes; commit or stash them first"

NDK=${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/ndk/28.* 2>/dev/null | tail -1)}
[ -n "$NDK" ] && [ -d "$NDK" ] || die "no NDK 28: set ANDROID_NDK_HOME or install it under \$ANDROID_HOME/ndk"
HOST=$(uname -s | tr 'A-Z' 'a-z')-x86_64
TOOLBIN=$NDK/toolchains/llvm/prebuilt/$HOST/bin
[ -x "$TOOLBIN/llvm-strip" ] || die "no NDK tools at $TOOLBIN"

COMMIT=$(git rev-parse HEAD)
SHORT=$(git rev-parse --short HEAD)

# ---- 1. the engine, and an apk around it -----------------------------------
say ""
say "==> 1/4 build the engine from $SHORT"
say "    game: $GAME"
say "    ndk:  $NDK"
GAME="$GAME" ./build.sh

# Stage what goes into the apk: the library, the C++ runtime it links against,
# and the adrenotools hook libraries when an imported driver is in the tree.
# Same layout deploy.sh packages, so a release and a device test agree.
STAGE=$ROOT/build-android/stage
rm -rf "$STAGE"
mkdir -p "$STAGE/arm64-v8a"
cp build-android/libpes17.so "$STAGE/arm64-v8a/"
"$TOOLBIN/llvm-strip" --strip-debug "$STAGE/arm64-v8a/libpes17.so"
for hook in build-android/adrenotools/src/hook/lib*_hook.so \
            build-android/adrenotools/src/hook/libhook_impl.so; do
    if [ -f "$hook" ]; then cp "$hook" "$STAGE/arm64-v8a/"; fi
done
cp "$NDK/toolchains/llvm/prebuilt/$HOST/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" \
   "$STAGE/arm64-v8a/"
ANDROID_NDK_ROOT="$NDK" tools/verify_android_libs.sh "$STAGE/arm64-v8a/libpes17.so"
ls -lh "$STAGE/arm64-v8a"

say "==> 1/4 package the debug apk"
VERSION=${TAG_LOWER#v}
python3 - "$ROOT/android/app/build.gradle.kts" "$VERSION" <<'PY'
import re, sys
path, version = sys.argv[1:3]
text = open(path).read()
text, n = re.subn(r'(versionCode = setting\("PES17_VERSION_CODE"\)\?\.toIntOrNull\(\) \?: )(\d+)',
                  lambda m: f"{m.group(1)}{int(m.group(2)) + 1}", text, count=1)
if n != 1:
    sys.exit(f"versionCode default is not in {path}")
text, n = re.subn(r'(versionName = setting\("PES17_VERSION_NAME"\) \?: )"[^"]*"',
                  lambda m: f'{m.group(1)}"{version}"', text, count=1)
if n != 1:
    sys.exit(f"versionName default is not in {path}")
open(path, "w").write(text)
PY
say "    apk version: $VERSION, versionCode +1"
# app/build.gradle.kts reads the version from the environment, not from -P; the
# variable is what makes the artifact the tag even if the edit above is undone.
( cd android && PES17_VERSION_NAME="$VERSION" ./gradlew --no-daemon :app:assembleDebug \
    -PprebuiltNative="$STAGE" )
APK=$(ls "$ROOT"/android/app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
[ -n "$APK" ] && [ -f "$APK" ] || die "gradle produced no apk"
ls -lh "$APK"
# The library is read out of the apk, not from the build tree: what the release
# serves has to be the file the pin will name.
WORK=$(mktemp -d); TMP+=("$WORK")
unzip -p "$APK" lib/arm64-v8a/libpes17.so > "$WORK/libpes17.so" \
    || die "$APK has no lib/arm64-v8a/libpes17.so -- it would install and die on launch"
[ -s "$WORK/libpes17.so" ] || die "$APK's lib/arm64-v8a/libpes17.so is empty"
APK_SHA=$(sha_file "$APK")
SO_SHA=$(sha_file "$WORK/libpes17.so")
say "  apk sha256            $APK_SHA"
say "  libpes17.so in the apk $SO_SHA"

# ---- 2. publish it ---------------------------------------------------------
say ""
say "==> 2/4 publish $TAG"
gh release create "$TAG" "$APK#app-debug.apk" \
    --target "$COMMIT" --title "$TAG" --notes-file "$NOTES"
gh release view "$TAG" --json url,assets \
    --jq '.url, (.assets[] | "  \(.name) \(.size) bytes")'

# ---- 3. hash what the release serves, not what was uploaded ----------------
say ""
say "==> 3/4 read the published asset back"
DL=$(mktemp -d); TMP+=("$DL")
gh release download "$TAG" --pattern app-debug.apk --dir "$DL"
published=$(sha_file "$DL/app-debug.apk")
[ "$published" = "$APK_SHA" ] || die "the published asset hashes to $published, not the apk that was uploaded ($APK_SHA)"
unzip -p "$DL/app-debug.apk" lib/arm64-v8a/libpes17.so > "$DL/libpes17.so"
published_so=$(sha_file "$DL/libpes17.so")
[ "$published_so" = "$SO_SHA" ] || die "the published asset's libpes17.so hashes to $published_so, not $SO_SHA"
say "the release serves exactly the apk that was built"

# ---- 4. pin builds to it ---------------------------------------------------
say ""
say "==> 4/4 pin builds to $TAG"
python3 - "$ROOT/tools/engine_pin.sh" "$TAG" "$COMMIT" "$APK_SHA" "$SO_SHA" <<'PY'
import re, sys
path, tag, commit, apk_sha, so_sha = sys.argv[1:6]
text = open(path).read()
for key, value in (("ENGINE_TAG", tag), ("ENGINE_COMMIT", commit),
                   ("ENGINE_APK_SHA256", apk_sha), ("ENGINE_SO_SHA256", so_sha)):
    text, n = re.subn(rf"^{key}=.*$", f"{key}={value}", text, count=1, flags=re.M)
    if n != 1:
        sys.exit(f"{key} is not in {path}")
open(path, "w").write(text)
PY

# The pin is only worth committing if it resolves: fetch_engine.sh downloads the
# asset, enforces both hashes and audits the entry points, so a bad bump fails
# here instead of in the next build.
VERIFY=$(mktemp -d); TMP+=("$VERIFY")
tools/fetch_engine.sh "$VERIFY/prebuilt" --audit

git add tools/engine_pin.sh "$NOTES" android/app/build.gradle.kts
git commit -q -m "$(cat <<EOF
Pin builds to the $TAG engine built from $SHORT

$TAG publishes app-debug.apk, the debug apk of $SHORT (and so of the XMP
music-query and big-core fixes), and moves the apk to version $VERSION. Both
hashes in tools/engine_pin.sh were taken from the published asset: the apk as
served, and the libpes17.so inside it.
EOF
)"
say ""
say "committed $(git rev-parse --short HEAD): $(git log -1 --format=%s)"

if [ "$PUSH" = 1 ]; then
    BRANCH=$(git rev-parse --abbrev-ref HEAD)
    git push origin "HEAD:$BRANCH"
    say "pushed to $BRANCH"
else
    say "--no-push: commit is local; push it so CI builds pick the new pin up"
fi

say ""
say "done: $TAG ($COMMIT)"
say "  apk     $APK_SHA"
say "  engine  $SO_SHA"
say "The next debug-apk run on CI fetches this release through"
say "tools/fetch_engine.sh and packages it; a laptop build does the same."

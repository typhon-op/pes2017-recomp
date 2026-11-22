#!/bin/bash
# Put the pinned engine where the Gradle build expects it.
#
#   tools/fetch_engine.sh [dest] [--audit]
#
# dest defaults to <repo>/libpes17_prebuilt, the layout -PprebuiltNative wants
# (arm64-v8a/*.so), and is git-ignored. The engine and its hashes come from
# tools/engine_pin.sh; this script enforces them and prints what it got, so a
# build log says which engine it packaged without anyone having to guess.
#
# Both apk and library are checked. The library check is the one that matters
# for the apk; the apk check catches a release asset re-uploaded under the same
# name, which would otherwise change a build that claims to be the same build.
#
# --audit also reports which of the app's JNI entry points this engine exports
# (tools/engine_symbols.sh). An engine from before an entry point existed is a
# supported configuration -- the app asks before it calls -- so a missing one is
# reported, not fatal.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=tools/engine_pin.sh
. "$ROOT/tools/engine_pin.sh"

DEST=
AUDIT=
for a in "$@"; do
    case "$a" in
        --audit) AUDIT=1 ;;
        -*) echo "unknown option: $a" >&2; exit 2 ;;
        *) DEST=$a ;;
    esac
done
DEST=${DEST:-$ROOT/libpes17_prebuilt}

say() { printf '%s\n' "$*"; }

say "engine pin: $ENGINE_REPO $ENGINE_TAG ($ENGINE_COMMIT)"
say "  asset:     $ENGINE_ASSET"
say "  apk sha256 $ENGINE_APK_SHA256"
say "  libpes17   $ENGINE_SO_SHA256"

sha_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        # macOS, and the git bash shell on Windows.
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
APK=$TMP/$ENGINE_ASSET

# -f so a 404 or a login wall exits non-zero instead of leaving an html page
# that fails the hash check with a misleading message.
if ! curl -fsSL --retry 3 --retry-delay 2 -o "$APK" "$ENGINE_URL"; then
    say "ERROR: could not download the pinned engine from $ENGINE_URL" >&2
    exit 1
fi

got=$(sha_of "$APK")
if [ "$got" != "$ENGINE_APK_SHA256" ]; then
    say "ERROR: $ENGINE_ASSET does not match the pin." >&2
    say "  expected $ENGINE_APK_SHA256" >&2
    say "  got      $got" >&2
    say "A re-uploaded asset? Update tools/engine_pin.sh to the new release," >&2
    say "or restore the asset the pin names -- do not build with this one." >&2
    exit 1
fi
say "apk sha256 ok"

mkdir -p "$DEST/arm64-v8a"
# Old libraries would otherwise sit beside a new one and Gradle packages the
# whole directory.
rm -f "$DEST"/arm64-v8a/*.so
unzip -j -o -q "$APK" "lib/arm64-v8a/*" -d "$DEST/arm64-v8a"

SO=$DEST/arm64-v8a/libpes17.so
if [ ! -f "$SO" ]; then
    say "ERROR: $ENGINE_ASSET has no lib/arm64-v8a/libpes17.so" >&2
    exit 1
fi

got=$(sha_of "$SO")
if [ "$got" != "$ENGINE_SO_SHA256" ]; then
    say "ERROR: the libpes17.so in $ENGINE_ASSET does not match the pin." >&2
    say "  expected $ENGINE_SO_SHA256" >&2
    say "  got      $got" >&2
    exit 1
fi
say "libpes17.so sha256 ok"

say "unpacked into $DEST/arm64-v8a:"
ls -lh "$DEST/arm64-v8a"
say "packaged engine: $ENGINE_REPO $ENGINE_TAG ($ENGINE_COMMIT)"

if [ -n "$AUDIT" ]; then
    "$ROOT/tools/engine_symbols.sh" "$SO"
fi

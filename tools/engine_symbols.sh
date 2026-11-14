#!/bin/bash
# Which of the app's JNI entry points does the libpes17.so in this apk have?
#
#   tools/engine_symbols.sh <libpes17.so> [NativeBridge.kt] [--strict]
#
# The Android build packages a libpes17.so built elsewhere (the recompiled title
# needs the player's own disc, so it cannot be built here). An engine from
# before an entry point existed simply does not export it, and a call to one is
# an UnsatisfiedLinkError. The app asks before it calls, so a missing one costs
# that feature rather than the whole app -- but which features those are is
# worth seeing in the log rather than inferring from how the controls feel.
#
# Without --strict this reports and succeeds: an older engine is a supported
# configuration. With --strict it fails, which is what a job that expects a
# matching engine wants.
set -euo pipefail

STRICT=
ARGS=()
for a in "$@"; do
    case "$a" in
        --strict) STRICT=--strict ;;
        *) ARGS+=("$a") ;;
    esac
done
SO=${ARGS[0]:-}
[ -n "$SO" ] || { echo "usage: engine_symbols.sh <libpes17.so> [NativeBridge.kt] [--strict]"; exit 2; }
ROOT=$(cd "$(dirname "$0")/.." && pwd)
KT=${ARGS[1]:-$ROOT/android/app/src/main/java/com/pes2017/recomp/NativeBridge.kt}

[ -f "$SO" ] || { echo "no such library: $SO"; exit 2; }
[ -f "$KT" ] || { echo "no such source: $KT"; exit 2; }

have=$(mktemp); want=$(mktemp)
trap 'rm -f "$have" "$want"' EXIT

grep -a -o 'Java_com_pes2017_recomp_NativeBridge_[A-Za-z0-9_]*' "$SO" \
    | sed 's/.*_NativeBridge_//' | sort -u > "$have"
grep -o 'external fun native[A-Za-z0-9_]*' "$KT" \
    | sed 's/external fun //' | sort -u > "$want"

echo "engine:   $SO"
echo "app wants $(wc -l < "$want") entry points; this engine exports $(wc -l < "$have")."
echo

missing=$(comm -23 "$want" "$have" || true)
if [ -z "$missing" ]; then
    echo "OK: every entry point the app calls is in this engine."
    exit 0
fi

echo "NOT in this engine (the features they drive fall back or stay off):"
echo "$missing" | sed 's/^/  /'
echo

# What each one costs, so the log says more than a name.
cat <<'NOTES'
  nativeDpadSynthesis   menus cannot be worked with the stick (app does it itself)
  nativeTouchPulse      taps shorter than a guest frame are dropped (app holds them)
  nativeStickSettings   the stick is shaped by the app instead of the engine
  nativeSkill*          the skill stick and its palette are not offered
NOTES

if [ "$STRICT" = "--strict" ]; then
    echo
    echo "ERROR: --strict was asked for, and this engine is missing entry points."
    exit 1
fi
exit 0

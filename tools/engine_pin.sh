#!/bin/bash
# The engine every build here packages.
#
# This file is the single place that decides which prebuilt libpes17.so goes
# into an apk. tools/fetch_engine.sh reads it, both workflows call that script,
# and nothing else downloads an engine -- so a local build and a CI build either
# carry exactly this engine or fail saying so.
#
# The engine and the app ship together: a build packaged with another engine has
# features nobody can place, and a run log that does not name its engine cannot
# be compared with a bug report. Hence the three identifiers below rather than a
# moving URL:
#
#   * the tag and the commit the asset was cut from (what a report should say),
#   * the SHA-256 of the asset itself (a re-upload under the same name is the
#     drift this is for; without the hash a rebuild can silently change),
#   * the SHA-256 of the libpes17.so inside it (what actually lands in the apk).
#
# To move to a new engine: bump these five lines to that release. The hash of
# the apk is the value `sha256sum` prints for the downloaded file; the hash of
# the library is that of the file extracted from lib/arm64-v8a/ -- do not use one
# in place of the other, they are different files and the check will refuse it.
#
# Sourced, not executed: no shell options, no output.

ENGINE_REPO=typhon-op/pes2017-recomp
ENGINE_TAG=V0.2.3
ENGINE_COMMIT=ee4af63bb5785602d4ffcbaca493abcdad441e84
ENGINE_ASSET=app-debug.apk
ENGINE_URL="https://github.com/$ENGINE_REPO/releases/download/$ENGINE_TAG/$ENGINE_ASSET"

# The release asset as published.
ENGINE_APK_SHA256=7d233cbd4354aa4af6badc598052c0204f0bb158646d0faba98e5008c553c106
# lib/arm64-v8a/libpes17.so inside that asset.
ENGINE_SO_SHA256=cc273ab9d9eb2eb687de51bfd39842aa61bb3029165a2937188c58ff14b657a2

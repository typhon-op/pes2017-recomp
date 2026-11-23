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
ENGINE_TAG=V0.2.4
ENGINE_COMMIT=d98029c2fd880bbad6897d2d1fc1455ee9adac16
ENGINE_ASSET=pes17native.apk
# The 0.2.4 apk itself, not a bare library: tools/fetch_engine.sh unpacks
# lib/arm64-v8a out of whichever asset the pin names, hooks and all, which is
# why every engine release here has been a whole apk. This one is the hand-cut
# build (versionName 0.2.4-auto2, versionCode 47) whose engine is the Release
# build of d98029c -- it carries 46950ca (the XMP query answers, the
# capacity-based fast cores, the corrected 8 GB budgets) and 1679e2c (the
# running flow read from the procedure switch), which the V0.2.3 asset, cut
# from ee4af63, does not. The asset is a container, not a build input: builds
# take lib/arm64-v8a from it and the app's own classes from this tree, so the
# apk published here and an apk built from these sources share an engine.
ENGINE_URL="https://github.com/$ENGINE_REPO/releases/download/$ENGINE_TAG/$ENGINE_ASSET"

# The asset as published.
ENGINE_APK_SHA256=f06290ebbd1a5025ec0becd5f6458492ed2ea2eb11e33885914c1af774266c02
# lib/arm64-v8a/libpes17.so inside that asset.
ENGINE_SO_SHA256=1133fa0b9309b802fbaa8e074b1decde8f5a7912599be409937807ac6478eb69

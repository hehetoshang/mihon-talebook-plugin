#!/usr/bin/env bash

set -euo pipefail

readonly UPSTREAM_URL="https://github.com/keiyoushi/extensions-source.git"
readonly UPSTREAM_SHA="b0fc6429905a707ea59e107dd40b37f6edd570ee"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly OUTPUT_DIR="${1:-$REPOSITORY_DIR/artifacts}"
BUILD_DIR="$(mktemp -d)"

cleanup() {
    rm -rf -- "$BUILD_DIR"
}
trap cleanup EXIT

git clone --filter=blob:none --no-checkout "$UPSTREAM_URL" "$BUILD_DIR/extensions-source"
git -C "$BUILD_DIR/extensions-source" fetch --depth=1 origin "$UPSTREAM_SHA"
git -C "$BUILD_DIR/extensions-source" checkout --detach FETCH_HEAD
cp -a "$REPOSITORY_DIR/src/all/talebook" "$BUILD_DIR/extensions-source/src/all/talebook"

"$BUILD_DIR/extensions-source/gradlew" \
    -p "$BUILD_DIR/extensions-source" \
    --max-workers=2 \
    -Dorg.gradle.parallel=false \
    :src:all:talebook:testDebugUnitTest \
    :src:all:talebook:lintRelease \
    :src:all:talebook:assembleRelease

mkdir -p "$OUTPUT_DIR"
find "$BUILD_DIR/extensions-source/src/all/talebook/build/outputs" \
    -type f \( -name '*.apk' -o -name '*.jar' \) \
    -exec cp '{}' "$OUTPUT_DIR/" ';'

find "$OUTPUT_DIR" -maxdepth 1 -type f -printf '%f\n' | sort

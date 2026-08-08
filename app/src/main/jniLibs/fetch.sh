#!/usr/bin/env bash
# Fetch the TDLib native libraries this app links against.
#
# They are Git-LFS blobs in the upstream Telegram X bundle and are git-ignored
# here (too large to commit), so a fresh clone must run this once. Re-run it
# after bumping TDLib — the .so files and the vendored Java bindings in
# app/src/main/java/org/drinkless/tdlib/ must come from the same upstream commit.
#
# Usage:  bash app/src/main/jniLibs/fetch.sh
#
# The ABI list must stay in sync with `abiFilters` in app/build.gradle.kts;
# a missing ABI here becomes a silently-missing ABI slot in the built APK.

set -euo pipefail

abis=(arm64-v8a armeabi-v7a x86 x86_64)
base=https://media.githubusercontent.com/media/TGX-Android/tdlib/main
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for abi in "${abis[@]}"; do
    mkdir -p "$dir/$abi"
    echo "==> $abi"
    curl -fsSL -o "$dir/$abi/libtdjni.so"   "$base/src/main/libs/$abi/libtdjni.so"
    curl -fsSL -o "$dir/$abi/libcryptox.so" "$base/openssl/$abi/lib/libcryptox.so"
    curl -fsSL -o "$dir/$abi/libsslx.so"    "$base/openssl/$abi/lib/libsslx.so"
done

echo "Done. Fetched $(( ${#abis[@]} * 3 )) libraries into $dir"

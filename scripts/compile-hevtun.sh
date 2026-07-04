#!/bin/bash
# Builds hev-socks5-tunnel as a JNI shared library (libhev-socks5-tunnel.so)
# for all Android ABIs. The JNI symbols are registered on the Java class
# com.grka.xray.core.TProxyService via the PKGNAME define below.
#
# Requirements: $NDK_HOME points to an Android NDK, and the hev-socks5-tunnel
# sources (with submodules) are checked out at $HEV_SRC (default: repo root).
set -o errexit
set -o pipefail
set -o nounset

__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEV_SRC="${HEV_SRC:-$__dir/hev-socks5-tunnel}"
OUT="${OUT:-$__dir/app/src/main/jniLibs}"
ABIS="armeabi-v7a arm64-v8a x86 x86_64"

if [[ ! -d "$NDK_HOME" ]]; then
  echo "Android NDK: NDK_HOME not found. please set env \$NDK_HOME"
  exit 1
fi
if [[ ! -d "$HEV_SRC" ]]; then
  echo "hev-socks5-tunnel sources not found at $HEV_SRC"
  exit 1
fi

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

mkdir -p "$TMPDIR/jni"
ln -s "$HEV_SRC" "$TMPDIR/jni/hev-socks5-tunnel"
echo 'include $(call all-subdir-makefiles)' > "$TMPDIR/jni/Android.mk"

pushd "$TMPDIR"
"$NDK_HOME/ndk-build" \
    NDK_PROJECT_PATH=. \
    APP_BUILD_SCRIPT=jni/Android.mk \
    "APP_ABI=$ABIS" \
    APP_PLATFORM=android-26 \
    NDK_LIBS_OUT="$TMPDIR/libs" \
    NDK_OUT="$TMPDIR/obj" \
    "APP_CFLAGS=-O3 -DPKGNAME=com/grka/xray/core" \
    "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"
popd

mkdir -p "$OUT"
cp -r "$TMPDIR/libs/"* "$OUT/"
echo "JNI libraries staged to $OUT:"
find "$OUT" -name '*.so'

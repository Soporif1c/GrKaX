#!/bin/sh
# Fetches the prebuilt LibXray.xcframework into apple/vendor.
#
# The framework is ~100 MB, so it stays out of the repository the same way the
# xray and tun2socks binaries do on the Compose side — CI and a fresh checkout
# both run this script instead.
set -eu

VERSION="${1:-v26.7.28}"
ASSET="libxray-apple-cgo.zip"
URL="https://github.com/XTLS/libXray/releases/download/${VERSION}/${ASSET}"

root=$(cd "$(dirname "$0")/.." && pwd)
vendor="$root/vendor"
stamp="$vendor/.libxray-version"

if [ -d "$vendor/LibXray.xcframework" ] && [ "$(cat "$stamp" 2>/dev/null || true)" = "$VERSION" ]; then
    echo "LibXray.xcframework $VERSION already present"
    exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "Downloading libXray $VERSION..."
curl -fsSL "$URL" -o "$tmp/$ASSET"
unzip -q "$tmp/$ASSET" -d "$tmp/unpacked"

# The archive nests the framework one directory deep; find it rather than
# hardcoding the layout, which has moved between releases.
found=$(find "$tmp/unpacked" -maxdepth 3 -name 'LibXray.xcframework' -type d | head -n 1)
if [ -z "$found" ]; then
    echo "LibXray.xcframework not found inside $ASSET" >&2
    exit 1
fi

rm -rf "$vendor/LibXray.xcframework"
mkdir -p "$vendor"
mv "$found" "$vendor/LibXray.xcframework"
printf '%s' "$VERSION" > "$stamp"

echo "LibXray.xcframework $VERSION installed in apple/vendor"

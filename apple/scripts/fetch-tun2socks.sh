#!/bin/sh
# Fetches the tun2socks binary that the TUN helper script drives.
#
# Same release the Compose CI pins, so both clients build the tunnel the same
# way. Not in the repository, like every other binary here.
set -eu

VERSION="${TUN2SOCKS_VERSION:-v2.6.0}"

case "${1:-$(uname -m)}" in
    arm64|aarch64) asset="tun2socks-darwin-arm64.zip" ;;
    x86_64|amd64)  asset="tun2socks-darwin-amd64.zip" ;;
    *) echo "unsupported architecture: ${1:-$(uname -m)}" >&2; exit 1 ;;
esac

root=$(cd "$(dirname "$0")/.." && pwd)
target="$root/vendor"
mkdir -p "$target"

if [ -x "$target/tun2socks" ]; then
    echo "tun2socks already present"
    exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "Downloading tun2socks $VERSION ($asset)..."
curl -fsSL --retry 3 -o "$tmp/tun2socks.zip" \
    "https://github.com/xjasonlyu/tun2socks/releases/download/${VERSION}/${asset}"
unzip -q -o "$tmp/tun2socks.zip" -d "$tmp/unpacked"

# The archive names the binary after the platform; normalise it.
found=$(find "$tmp/unpacked" -type f -name 'tun2socks*' | head -n 1)
if [ -z "$found" ]; then
    echo "tun2socks binary not found inside $asset" >&2
    exit 1
fi
mv "$found" "$target/tun2socks"
chmod +x "$target/tun2socks"

echo "tun2socks $VERSION installed in apple/vendor"

#!/bin/sh
# Fetches the tun2socks binary that the TUN helper script drives.
#
# Same release the Compose CI pins, so both clients build the tunnel the same
# way. Not in the repository, like every other binary here.
set -eu

VERSION="${TUN2SOCKS_VERSION:-v2.6.0}"

# `universal` fuses both slices into one binary. The app builds universal from
# a single Xcode run, so a single-architecture helper beside it would leave the
# other half of the users with a tunnel that cannot start.
case "${1:-$(uname -m)}" in
    arm64|aarch64) arches="arm64" ;;
    x86_64|amd64)  arches="amd64" ;;
    universal)     arches="arm64 amd64" ;;
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

slices=""
for arch in $arches; do
    asset="tun2socks-darwin-${arch}.zip"
    echo "Downloading tun2socks $VERSION ($asset)..."
    curl -fsSL --retry 3 -o "$tmp/$asset" \
        "https://github.com/xjasonlyu/tun2socks/releases/download/${VERSION}/${asset}"
    unzip -q -o "$tmp/$asset" -d "$tmp/unpacked-$arch"

    # The archive names the binary after the platform; normalise it.
    found=$(find "$tmp/unpacked-$arch" -type f -name 'tun2socks*' | head -n 1)
    if [ -z "$found" ]; then
        echo "tun2socks binary not found inside $asset" >&2
        exit 1
    fi
    mv "$found" "$tmp/tun2socks-$arch"
    slices="$slices $tmp/tun2socks-$arch"
done

# shellcheck disable=SC2086
if [ "$arches" = "arm64 amd64" ]; then
    lipo -create $slices -output "$target/tun2socks"
else
    mv $slices "$target/tun2socks"
fi
chmod +x "$target/tun2socks"

echo "tun2socks $VERSION installed in apple/vendor ($(lipo -archs "$target/tun2socks"))"

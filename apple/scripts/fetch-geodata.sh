#!/bin/sh
# Fetches geoip.dat / geosite.dat into apple/vendor/geodata.
#
# Same source the Compose CI uses, so both clients route on identical rule sets.
# Roughly 27 MB, so it stays out of the repository like the framework does.
set -eu

BASE="https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download"

root=$(cd "$(dirname "$0")/.." && pwd)
target="$root/vendor/geodata"
mkdir -p "$target"

for name in geoip.dat geosite.dat; do
    if [ -s "$target/$name" ]; then
        echo "$name already present"
        continue
    fi
    echo "Downloading $name..."
    curl -fL --retry 3 -o "$target/$name" "$BASE/$name"
done

echo "geo data ready in apple/vendor/geodata"

#!/bin/sh
# Runs the GrKaXKit test suite.
#
# XRAY_LOCATION_ASSET has to be exported before the process starts: the Go
# runtime inside libXray snapshots the environment at startup, so nothing the
# test code does to it afterwards is visible to the core. See
# XrayCore.assetDirectory.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)

if [ ! -f "$root/vendor/geodata/geoip.dat" ]; then
    "$root/scripts/fetch-geodata.sh"
fi
if [ ! -d "$root/vendor/LibXray.xcframework" ]; then
    "$root/scripts/fetch-libxray.sh"
fi

XRAY_LOCATION_ASSET="$root/vendor/geodata" \
    swift test --package-path "$root/GrKaXKit" "$@"

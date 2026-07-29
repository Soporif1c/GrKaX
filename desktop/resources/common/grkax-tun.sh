#!/bin/sh
# GrKa X — macOS TUN helper.
#
# Runs with administrator rights (a utun device and route table changes both
# need root). Kept as a standalone script so the privileged surface is small
# and auditable; when an Apple Developer account exists this is what gets
# replaced by a signed launchd daemon or a NetworkExtension provider.
#
#   grkax-tun.sh up   <tun2socks-bin> <socks-port> <interface> <service> <dns>
#                     <gateway> [server-ip...]
#   grkax-tun.sh down <service>

set -e

STATE_DIR="/var/run/grkax"
PID_FILE="$STATE_DIR/tun2socks.pid"
DEV_FILE="$STATE_DIR/tun.dev"
LOG_FILE="$STATE_DIR/tun2socks.log"
# Host routes we installed, so `down` removes exactly those and nothing else.
ROUTES_FILE="$STATE_DIR/routes"

# 198.18.0.0/15 is the RFC 2544 benchmarking range — safe to steal, unlike a
# 10.x address a home router might already be using.
TUN_ADDR="198.18.0.1"
TUN_PEER="198.18.0.2"

# tun2socks logs one JSON object per line. The `msg` field is the only part a
# user can act on, so lift it out and drop the stacktrace — this text ends up
# verbatim in the app's error banner, via osascript's stderr.
log_tail() {
    if [ -s "$LOG_FILE" ]; then
        tail -3 "$LOG_FILE" \
            | sed 's/,"stacktrace":".*//' \
            | sed -n 's/.*"msg":"//p' \
            | sed 's/"}*$//; s/\\"/"/g' \
            | tr '\n' ' '
    fi
}

# Same, but always says something — used where the log may be empty.
why() {
    reason=$(log_tail)
    if [ -n "$reason" ]; then
        printf '%s(see %s)' "$reason" "$LOG_FILE"
    else
        printf 'see %s' "$LOG_FILE"
    fi
}

pick_device() {
    i=200
    while [ "$i" -lt 250 ]; do
        if ! ifconfig "utun$i" >/dev/null 2>&1; then
            echo "utun$i"
            return 0
        fi
        i=$((i + 1))
    done
    return 1
}

cmd_up() {
    BIN="$1"; PORT="$2"; IFACE="$3"; SERVICE="$4"; DNS="$5"; GATEWAY="$6"
    # Whatever is left are the proxy server addresses to keep off the tunnel.
    shift 6 2>/dev/null || shift $#

    # We run as root here, so repair the executable bit rather than refusing:
    # packaging into the .app does not carry it through reliably.
    [ -x "$BIN" ] || chmod +x "$BIN" 2>/dev/null || true
    [ -x "$BIN" ] || { echo "tun2socks binary not executable: $BIN" >&2; exit 1; }

    mkdir -p "$STATE_DIR"
    DEV=$(pick_device) || { echo "no free utun device" >&2; exit 1; }
    echo "$DEV" > "$DEV_FILE"

    # tun2socks only accepts debug|info|warn|error|silent — anything else and it
    # exits before creating the device.
    "$BIN" -device "$DEV" -proxy "socks5://127.0.0.1:$PORT" -interface "$IFACE" \
        -loglevel warn > "$LOG_FILE" 2>&1 &
    TUN_PID=$!
    echo "$TUN_PID" > "$PID_FILE"

    # tun2socks creates the interface; give it a moment to show up. A process
    # that already died is not worth waiting on — bad arguments fail this way,
    # and the timeout below would hide the reason behind "never appeared".
    n=0
    while [ "$n" -lt 50 ]; do
        ifconfig "$DEV" >/dev/null 2>&1 && break
        kill -0 "$TUN_PID" 2>/dev/null || { echo "tun2socks exited: $(why)" >&2; exit 1; }
        sleep 0.1
        n=$((n + 1))
    done
    ifconfig "$DEV" >/dev/null 2>&1 || { echo "$DEV never appeared: $(why)" >&2; exit 1; }

    ifconfig "$DEV" "$TUN_ADDR" "$TUN_PEER" up

    # The /1 routes below swallow every destination — including the core's own
    # connection to the proxy server, which would then be dialled through the
    # tunnel that connection is supposed to carry. Pin the server to the
    # physical gateway first: a /32 outranks a /1, and going first means the
    # loop never exists, not even for the moment between the two calls.
    : > "$ROUTES_FILE"
    for ip in "$@"; do
        [ -n "$ip" ] || continue
        route -n add -host "$ip" "$GATEWAY" >/dev/null 2>&1 || true
        echo "$ip" >> "$ROUTES_FILE"
    done

    # Two /1 routes outrank the existing default route without deleting it,
    # so tearing the tunnel down cannot strand the machine offline.
    route -n add -net 0.0.0.0/1 "$TUN_PEER" >/dev/null
    route -n add -net 128.0.0.0/1 "$TUN_PEER" >/dev/null

    /usr/sbin/networksetup -setdnsservers "$SERVICE" "$DNS"
    echo "$DEV"
}

cmd_down() {
    SERVICE="$1"

    if [ -f "$PID_FILE" ]; then
        kill "$(cat "$PID_FILE")" 2>/dev/null || true
        rm -f "$PID_FILE"
    fi

    route -n delete -net 0.0.0.0/1 "$TUN_PEER" >/dev/null 2>&1 || true
    route -n delete -net 128.0.0.0/1 "$TUN_PEER" >/dev/null 2>&1 || true

    # Drop the server pins we added — leaving them behind would quietly keep
    # sending that one address past a gateway that may not be current later.
    if [ -f "$ROUTES_FILE" ]; then
        while IFS= read -r ip; do
            [ -n "$ip" ] || continue
            route -n delete -host "$ip" >/dev/null 2>&1 || true
        done < "$ROUTES_FILE"
        rm -f "$ROUTES_FILE"
    fi

    if [ -n "$SERVICE" ]; then
        /usr/sbin/networksetup -setdnsservers "$SERVICE" Empty || true
    fi

    rm -f "$DEV_FILE"
}

case "$1" in
    up)   shift; cmd_up "$@" ;;
    down) shift; cmd_down "$@" ;;
    *)    echo "usage: $0 up|down ..." >&2; exit 2 ;;
esac

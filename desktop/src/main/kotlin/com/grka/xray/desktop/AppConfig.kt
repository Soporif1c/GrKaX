package com.grka.xray.desktop

object AppConfig {
    const val TAG = "GrKaX"
    const val APP_NAME = "GrKa X"

    const val LOOPBACK = "127.0.0.1"
    const val DEFAULT_SOCKS_PORT = 10808
    const val DEFAULT_HTTP_PORT = 10809
    /** dokodemo-door inbound the core exposes its StatsService on. */
    const val DEFAULT_API_PORT = 10810

    const val DEFAULT_MTU = 9000

    // Point-to-point TUN addressing used by the macOS helper.
    const val TUN_IPV4_CLIENT = "198.18.0.1"
    const val TUN_IPV4_GATEWAY = "198.18.0.2"
    const val TUN_IPV4_MASK = "255.255.0.0"

    const val DELAY_TEST_URL = "https://www.gstatic.com/generate_204"
    const val DELAY_TEST_URL2 = "https://www.google.com/generate_204"

    const val DEFAULT_REMOTE_DNS = "1.1.1.1"
    const val DEFAULT_DIRECT_DNS = "77.88.8.8"

    /**
     * Traffic mode — the Karing-style switch that sits next to the power
     * button. Per-app routing is deliberately absent: it needs process-name
     * rules, which the Xray core does not implement (and Xray is required
     * here for XHTTP obfuscation passthrough).
     */
    const val MODE_RULE = "rule"
    const val MODE_GLOBAL = "global"
    const val MODE_DIRECT = "direct"

    /** Built-in rule sets used when [MODE_RULE] has no template to follow. */
    const val ROUTE_BYPASS_LAN = "bypass_lan"
    const val ROUTE_BYPASS_RU = "bypass_ru"

    /**
     * How the OS is pointed at the core.
     *  - [NET_SYSTEM_PROXY]: `networksetup` writes the SOCKS/HTTP proxy into
     *    the active network service. No privileges, but only catches apps that
     *    honour the system proxy.
     *  - [NET_TUN]: a privileged helper opens a utun device and pumps it into
     *    our SOCKS inbound. Catches everything, costs one admin prompt.
     */
    const val NET_SYSTEM_PROXY = "system_proxy"
    const val NET_TUN = "tun"

    // UI themes — same three as the Android client.
    const val THEME_AURORA = "aurora"
    const val THEME_OCEAN = "ocean"
    const val THEME_PEARL = "pearl"

    const val RELEASES_API = "https://api.github.com/repos/Soporif1c/GrKaX/releases"
    const val RELEASES_PAGE = "https://github.com/Soporif1c/GrKaX/releases/latest"
}

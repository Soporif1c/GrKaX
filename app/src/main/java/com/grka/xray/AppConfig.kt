package com.grka.xray

object AppConfig {
    const val TAG = "GrKaX"

    const val LOOPBACK = "127.0.0.1"
    const val DEFAULT_SOCKS_PORT = 10808

    // Point-to-point TUN addresses (same scheme as proven v2rayNG defaults)
    const val TUN_IPV4_CLIENT = "10.10.14.1"
    const val TUN_IPV6_CLIENT = "fc00::10:10:14:1"
    const val DEFAULT_MTU = 1500

    const val DELAY_TEST_URL = "https://www.gstatic.com/generate_204"
    const val DELAY_TEST_URL2 = "https://www.google.com/generate_204"

    const val DEFAULT_REMOTE_DNS = "1.1.1.1"
    const val DEFAULT_DIRECT_DNS = "77.88.8.8"

    // Routing presets
    const val ROUTE_GLOBAL = "global"
    const val ROUTE_BYPASS_LAN = "bypass_lan"
    const val ROUTE_BYPASS_RU = "bypass_ru"

    // Per-app proxy modes
    const val PER_APP_OFF = "off"
    const val PER_APP_BYPASS = "bypass"
    const val PER_APP_ALLOW = "allow"

    // UI themes
    const val THEME_AURORA = "aurora"
    const val THEME_OCEAN = "ocean"
    const val THEME_PEARL = "pearl"
}

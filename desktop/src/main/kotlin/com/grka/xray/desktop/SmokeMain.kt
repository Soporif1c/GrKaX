package com.grka.xray.desktop

import com.grka.xray.desktop.config.ConfigBuilder
import com.grka.xray.desktop.config.JsonSubscriptionParser
import com.grka.xray.desktop.config.LinkParser
import com.grka.xray.desktop.data.SettingsSnapshot
import java.io.File

/**
 * Renders a config for every routing mode so CI can hand them to the real Xray
 * core (`xray run -test`) before packaging. This is the cheapest guard we have
 * against a config-shape regression: a malformed config only shows up as "core
 * exited immediately" at runtime, on a machine none of us is looking at.
 *
 * Run with `./gradlew smoke -PsmokeOut=<dir>`.
 */
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: ".").apply { mkdirs() }

    // What TunMode resolves before raising the tunnel. 203.0.113.0/24 is the
    // documentation range, so a config that leaks out of CI reaches nothing.
    val pins = mapOf("example.com" to listOf("203.0.113.10"))

    fun settings(mode: String, iface: String?, hostPins: Map<String, List<String>> = emptyMap()) =
        SettingsSnapshot(
        socksPort = 10808,
        httpPort = 10809,
        apiPort = 10810,
        remoteDns = "1.1.1.1",
        directDns = "77.88.8.8",
        mode = mode,
        routingPreset = AppConfig.ROUTE_BYPASS_RU,
        blockQuic = true,
        bypassTorrent = true,
        sniffing = true,
        routeOnly = false,
        mux = false,
        logLevel = "warning",
        bindInterface = iface,
        serverPins = hostPins,
    )

    val link = "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
        "?type=xhttp&mode=packet-up&security=reality&pbk=SGVsbG9Xb3JsZEhlbGxvV29ybGRIZWxsb1dvcmxkMDA" +
        "&fp=chrome&sni=www.microsoft.com&sid=aabb&path=%2Fxhttp&encryption=none#Smoke"
    val fromLink = requireNonNull(LinkParser.parse(link), "share link did not parse")

    // An xray-json subscription body, the shape Remnawave serves — this is the
    // path that must preserve the outbound verbatim.
    val subBody = """
        {"remarks":"Smoke","outbounds":[
          {"tag":"proxy-a","protocol":"vless","settings":{"vnext":[{"address":"example.com","port":443,
            "users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]},
           "streamSettings":{"network":"xhttp","security":"tls",
             "tlsSettings":{"serverName":"www.microsoft.com","fingerprint":"chrome"},
             "xhttpSettings":{"host":"www.microsoft.com","path":"/x","mode":"packet-up",
               "sessionKey":"sid","extra":{"noSSEHeader":true,"sessionLength":8}}}},
          {"tag":"direct","protocol":"freedom"},
          {"tag":"block","protocol":"blackhole"}],
         "routing":{"domainStrategy":"IPIfNonMatch","rules":[
           {"type":"field","outboundTag":"direct","domain":["geosite:category-ru"]},
           {"type":"field","outboundTag":"proxy-a","network":"tcp,udp"}]},
         "dns":{"servers":["1.1.1.1"]}}
    """.trimIndent()
    val fromJson = requireNonNull(
        JsonSubscriptionParser.parse(subBody)?.profiles?.firstOrNull(),
        "xray-json subscription did not parse",
    )

    val cases = listOf(
        Triple("link-rule", fromLink to AppConfig.MODE_RULE, null),
        Triple("link-global", fromLink to AppConfig.MODE_GLOBAL, null),
        Triple("link-direct", fromLink to AppConfig.MODE_DIRECT, null),
        Triple("link-tun", fromLink to AppConfig.MODE_RULE, "en0"),
        Triple("sub-rule", fromJson to AppConfig.MODE_RULE, null),
        Triple("sub-global", fromJson to AppConfig.MODE_GLOBAL, null),
        Triple("sub-tun", fromJson to AppConfig.MODE_RULE, "en0"),
    )

    for ((name, profileAndMode, iface) in cases) {
        val (profile, mode) = profileAndMode
        // Pins only exist in TUN mode, which is exactly where `iface` is set.
        val config = ConfigBuilder.build(profile, settings(mode, iface, if (iface != null) pins else emptyMap()))
        File(outDir, "$name.json").writeText(config)
        println("wrote $name.json (${config.length} bytes)")
    }

    // The verbatim-passthrough guarantee: obfuscation fields must survive.
    val subConfig = File(outDir, "sub-rule.json").readText()
    for (marker in listOf("noSSEHeader", "sessionIDKey", "sessionIDLength", "packet-up")) {
        check(subConfig.contains(marker)) { "lost XHTTP field: $marker" }
    }
    println("xhttp passthrough: ok")

    val tunConfig = File(outDir, "sub-tun.json").readText()
    check(tunConfig.contains("\"interface\":\"en0\"")) { "tun mode did not pin the interface" }
    println("tun interface pinning: ok")

    // A server named by hostname has to reach the core as an address it already
    // knows: in TUN mode the core's resolver is behind the tunnel, and asking it
    // deadlocks. Both the built-in DNS and the subscription's own must carry it.
    for (name in listOf("link-tun", "sub-tun")) {
        val cfg = File(outDir, "$name.json").readText()
        check(cfg.contains("\"example.com\":[\"203.0.113.10\"]")) {
            "$name lost the server pin — hostname servers will not connect"
        }
    }
    check(!File(outDir, "sub-rule.json").readText().contains("203.0.113.10")) {
        "server pin leaked into a non-TUN config"
    }
    println("server DNS pinning: ok")

    // The dial has to target the resolved address while the handshake still
    // presents the hostname — swap one and lose the other and TLS fails.
    // Both shapes matter. A subscription that ships a ready-made outbound takes
    // the verbatim path, and that is precisely the one that shipped broken:
    // everything was preserved byte for byte, including the hostname the core
    // could not resolve.
    for (name in listOf("link-tun", "sub-tun")) {
        val cfg = File(outDir, "$name.json").readText()
        check(cfg.contains("\"address\":\"203.0.113.10\"")) {
            "$name still dials the hostname; the core would have to resolve it"
        }
        check(cfg.contains("\"serverName\":\"www.microsoft.com\"")) {
            "$name lost its SNI while swapping the address"
        }
    }
    check(File(outDir, "link-rule.json").readText().contains("\"address\":\"example.com\"")) {
        "non-TUN config should keep dialling the hostname"
    }
    println("server address substitution: ok")
}

private fun <T : Any> requireNonNull(value: T?, message: String): T =
    value ?: error(message)

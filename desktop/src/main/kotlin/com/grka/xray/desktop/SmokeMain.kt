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

    fun settings(mode: String, iface: String?) = SettingsSnapshot(
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
        val config = ConfigBuilder.build(profile, settings(mode, iface))
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
}

private fun <T : Any> requireNonNull(value: T?, message: String): T =
    value ?: error(message)

package com.grka.xray.desktop.config

import com.grka.xray.desktop.AppConfig
import com.grka.xray.desktop.data.Profile
import com.grka.xray.desktop.data.SettingsSnapshot
import com.grka.xray.desktop.util.Utils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the Xray core JSON config from a profile plus app settings.
 *
 * Differences from the Android build:
 *  - two local inbounds (SOCKS + HTTP), because macOS system-proxy mode needs
 *    an HTTP proxy for apps that ignore SOCKS;
 *  - a dokodemo-door `api` inbound so traffic counters can be polled with
 *    `xray api statsquery` (the desktop core is a child process, not a
 *    gomobile binding with a stats callback);
 *  - the traffic mode switch (Rule / Global / Direct) decides whether the
 *    subscription's own routing is honoured at all.
 *
 * The stream/security section is intentionally identical to the Android
 * builder so XHTTP and REALITY dial the same way on both clients.
 */
object ConfigBuilder {

    private val json = Json { ignoreUnknownKeys = true }

    fun build(
        profile: Profile,
        s: SettingsSnapshot,
        routingJson: String? = null,
        useSubRouting: Boolean = true,
        customTemplate: String? = null,
    ): String {
        // A user-pasted template's routing/dns take top priority — this is how a
        // panel's routing gets applied even when the subscription arrives as
        // plain share links (which carry no routing/dns).
        val tplRouting = extractFromTemplate(customTemplate, "routing")
        val tplDns = extractFromTemplate(customTemplate, "dns")

        // Global and Direct deliberately ignore whatever routing the panel
        // shipped: the switch would otherwise be a lie.
        val honourSubRouting = useSubRouting && s.mode == AppConfig.MODE_RULE

        if (honourSubRouting) {
            profile.fullConfig?.takeIf { it.isNotBlank() }?.let { full ->
                runCatching { buildFromFullConfig(full, profile, s, tplRouting, tplDns) }
                    .getOrNull()?.let { return it }
            }
        }

        val routingOverride = when (s.mode) {
            AppConfig.MODE_RULE -> tplRouting ?: parseRouting(routingJson)
            else -> null
        }

        val root = buildJsonObject {
            putJsonObject("log") { put("loglevel", s.logLevel) }
            putStatsBlock(s)

            if (s.mode == AppConfig.MODE_RULE && tplDns != null) {
                put("dns", ensureDns(tplDns, s))
            } else {
                put("dns", defaultDns(s))
            }

            put("inbounds", inbounds(s))

            putJsonArray("outbounds") {
                add(bindToInterface(buildProxyOutbound(profile, s), s.bindInterface))
                add(bindToInterface(directOutbound(), s.bindInterface))
                add(blockOutbound())
            }

            put("routing", withApiRule(routingOverride ?: buildRouting(s)))
        }
        return root.toString()
    }

    // ---------------- full-config (xray-json subscription) path ----------------

    private fun buildFromFullConfig(
        full: String,
        p: Profile,
        s: SettingsSnapshot,
        tplRouting: JsonObject? = null,
        tplDns: JsonObject? = null,
    ): String {
        val cfg = json.parseToJsonElement(full).jsonObject
        val outbounds = (cfg["outbounds"] as? JsonArray) ?: JsonArray(emptyList())
        val reordered = JsonArray(
            ensureFallbackOutbounds(reorderOutbounds(outbounds, p.proxyTag))
                .map { bindToInterface(it as JsonObject, s.bindInterface) }
        )

        val root = buildJsonObject {
            putJsonObject("log") { put("loglevel", s.logLevel) }
            putStatsBlock(s)
            put("dns", ensureDns(tplDns ?: cfg["dns"] as? JsonObject, s))
            put("inbounds", inbounds(s))
            put("outbounds", reordered)

            val routing = tplRouting ?: cfg["routing"] as? JsonObject
            put("routing", withApiRule(routing ?: buildRouting(s)))
        }
        return root.toString()
    }

    // ---------------- inbounds / api ----------------

    private fun inbounds(s: SettingsSnapshot): JsonArray = buildJsonArray {
        addJsonObject {
            put("tag", "socks")
            put("listen", AppConfig.LOOPBACK)
            put("port", s.socksPort)
            put("protocol", "socks")
            putJsonObject("settings") {
                put("auth", "noauth")
                put("udp", true)
                put("userLevel", 8)
            }
            put("sniffing", sniffing(s))
        }
        addJsonObject {
            // System-proxy mode points macOS at this one for HTTP/HTTPS.
            put("tag", "http")
            put("listen", AppConfig.LOOPBACK)
            put("port", s.httpPort)
            put("protocol", "http")
            putJsonObject("settings") { put("userLevel", 8) }
            put("sniffing", sniffing(s))
        }
        addJsonObject {
            put("tag", "api")
            put("listen", AppConfig.LOOPBACK)
            put("port", s.apiPort)
            put("protocol", "dokodemo-door")
            putJsonObject("settings") { put("address", AppConfig.LOOPBACK) }
        }
    }

    private fun sniffing(s: SettingsSnapshot): JsonObject = buildJsonObject {
        put("enabled", s.sniffing)
        putJsonArray("destOverride") {
            add("http"); add("tls"); add("quic")
        }
        put("routeOnly", s.routeOnly)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putStatsBlock(s: SettingsSnapshot) {
        putJsonObject("api") {
            put("tag", "api")
            putJsonArray("services") { add("StatsService") }
        }
        putJsonObject("stats") {}
        putJsonObject("policy") {
            putJsonObject("levels") {
                putJsonObject("8") {
                    put("handshake", 4)
                    put("connIdle", 300)
                }
            }
            putJsonObject("system") {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            }
        }
    }

    /**
     * The stats API is only reachable if routing sends the api inbound to the
     * api tag, and that rule has to win over everything else — so it goes in
     * front of whatever rules the panel or the user supplied.
     */
    private fun withApiRule(routing: JsonObject): JsonObject {
        val apiRule = buildJsonObject {
            put("type", "field")
            putJsonArray("inboundTag") { add("api") }
            put("outboundTag", "api")
        }
        val existing = (routing["rules"] as? JsonArray) ?: JsonArray(emptyList())
        return buildJsonObject {
            for ((k, v) in routing) if (k != "rules") put(k, v)
            putJsonArray("rules") {
                add(apiRule)
                existing.forEach { add(it) }
            }
        }
    }

    // ---------------- dns ----------------

    private fun defaultDns(s: SettingsSnapshot): JsonObject = buildJsonObject {
        putJsonObject("hosts") { put("domain:googleapis.cn", "googleapis.com") }
        putJsonArray("servers") {
            add(s.remoteDns)
            if (s.mode == AppConfig.MODE_RULE && s.routingPreset == AppConfig.ROUTE_BYPASS_RU) {
                addJsonObject {
                    put("address", s.directDns)
                    put("port", 53)
                    putJsonArray("domains") { add("geosite:category-ru") }
                }
            }
        }
    }

    /** Keeps the template DNS but guarantees at least one resolver. */
    private fun ensureDns(dns: JsonObject?, s: SettingsSnapshot): JsonObject {
        if (dns == null) return defaultDns(s)
        val servers = dns["servers"] as? JsonArray
        if (servers != null && servers.isNotEmpty()) return dns
        return buildJsonObject {
            for ((k, v) in dns) if (k != "servers") put(k, v)
            putJsonArray("servers") { add(s.remoteDns) }
        }
    }

    // ---------------- outbounds ----------------

    private val proxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks")

    private fun directOutbound(): JsonObject = buildJsonObject {
        put("tag", "direct")
        put("protocol", "freedom")
        putJsonObject("settings") { put("domainStrategy", "UseIP") }
    }

    /**
     * Pins an outbound to a physical interface (`IP_BOUND_IF` on darwin). Only
     * used in TUN mode, where the default route points at our own utun device:
     * without this the core would dial its server through the tunnel it is
     * itself feeding and nothing would ever connect.
     */
    private fun bindToInterface(outbound: JsonObject, iface: String?): JsonObject {
        if (iface.isNullOrBlank()) return outbound
        if (outbound["protocol"]?.jsonPrimitive?.contentOrNull == "blackhole") return outbound

        val stream = outbound["streamSettings"] as? JsonObject
        val sockopt = stream?.get("sockopt") as? JsonObject
        val newSockopt = buildJsonObject {
            sockopt?.forEach { (k, v) -> put(k, v) }
            put("interface", iface)
        }
        val newStream = buildJsonObject {
            stream?.forEach { (k, v) -> if (k != "sockopt") put(k, v) }
            put("sockopt", newSockopt)
        }
        return buildJsonObject {
            for ((k, v) in outbound) if (k != "streamSettings") put(k, v)
            put("streamSettings", newStream)
        }
    }

    private fun blockOutbound(): JsonObject = buildJsonObject {
        put("tag", "block")
        put("protocol", "blackhole")
        putJsonObject("settings") { putJsonObject("response") { put("type", "http") } }
    }

    /** Moves this profile's proxy outbound to the front (so it is the routing
     *  default) and normalizes legacy XHTTP keys in every outbound. Falls back
     *  to the first proxy-protocol outbound when the tag is missing, so traffic
     *  never silently defaults to a "direct" outbound. */
    private fun reorderOutbounds(outbounds: JsonArray, proxyTag: String?): JsonArray {
        val list = outbounds.mapNotNull { it as? JsonObject }.map { normalizeOutbound(it) }
        var idx = -1
        if (!proxyTag.isNullOrBlank()) {
            idx = list.indexOfFirst { it["tag"]?.jsonPrimitive?.contentOrNull == proxyTag }
        }
        if (idx < 0) {
            idx = list.indexOfFirst { it["protocol"]?.jsonPrimitive?.contentOrNull in proxyProtocols }
        }
        if (idx <= 0) return JsonArray(list)
        val out = ArrayList<JsonObject>(list.size)
        out.add(list[idx])
        list.forEachIndexed { i, o -> if (i != idx) out.add(o) }
        return JsonArray(out)
    }

    /** Panels do not always ship direct/block outbounds; our rules reference
     *  both, and a missing tag makes the core refuse to start. */
    private fun ensureFallbackOutbounds(outbounds: JsonArray): JsonArray {
        val tags = outbounds.mapNotNull { (it as? JsonObject)?.get("tag")?.jsonPrimitive?.contentOrNull }.toSet()
        if ("direct" in tags && "block" in tags) return outbounds
        return buildJsonArray {
            outbounds.forEach { add(it) }
            if ("direct" !in tags) add(directOutbound())
            if ("block" !in tags) add(blockOutbound())
        }
    }

    private fun normalizeOutbound(ob: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in ob) {
            if (k == "streamSettings" && v is JsonObject) put("streamSettings", normalizeStream(v))
            else put(k, v)
        }
    }

    // ---------------- routing ----------------

    private fun buildRouting(s: SettingsSnapshot): JsonObject = buildJsonObject {
        put("domainStrategy", "IPIfNonMatch")
        putJsonArray("rules") {
            when (s.mode) {
                // Everything direct: the core stays up so the switch is instant.
                AppConfig.MODE_DIRECT -> {
                    addJsonObject {
                        put("type", "field")
                        put("outboundTag", "direct")
                        put("network", "tcp,udp")
                    }
                }

                // Everything proxied except the local network.
                AppConfig.MODE_GLOBAL -> {
                    addPrivateBypass()
                    if (s.blockQuic) addQuicBlock()
                }

                // Rule mode: the built-in preset, used when neither the
                // subscription nor the user template supplied routing.
                else -> {
                    if (s.routingPreset == AppConfig.ROUTE_BYPASS_RU) {
                        addJsonObject {
                            put("type", "field")
                            put("outboundTag", "direct")
                            put("port", "53")
                            putJsonArray("ip") { add(s.directDns) }
                        }
                    }
                    if (s.blockQuic) addQuicBlock()
                    if (s.bypassTorrent) {
                        addJsonObject {
                            put("type", "field")
                            put("outboundTag", "direct")
                            putJsonArray("protocol") { add("bittorrent") }
                        }
                    }
                    addPrivateBypass()
                    if (s.routingPreset == AppConfig.ROUTE_BYPASS_RU) {
                        addJsonObject {
                            put("type", "field")
                            put("outboundTag", "direct")
                            putJsonArray("domain") { add("geosite:category-ru") }
                        }
                        addJsonObject {
                            put("type", "field")
                            put("outboundTag", "direct")
                            putJsonArray("ip") { add("geoip:ru") }
                        }
                    }
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addPrivateBypass() {
        addJsonObject {
            put("type", "field")
            put("outboundTag", "direct")
            putJsonArray("ip") { add("geoip:private") }
        }
        addJsonObject {
            put("type", "field")
            put("outboundTag", "direct")
            putJsonArray("domain") { add("geosite:private") }
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addQuicBlock() {
        addJsonObject {
            put("type", "field")
            put("outboundTag", "block")
            put("network", "udp")
            put("port", "443")
        }
    }

    /** Pulls a top-level object (routing/dns) out of a user-pasted template.
     *  Accepts a full config, or a bare routing object when key == "routing". */
    private fun extractFromTemplate(template: String?, key: String): JsonObject? {
        val raw = template?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            when {
                obj[key] is JsonObject -> obj[key] as JsonObject
                key == "routing" && obj.containsKey("rules") -> obj
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Accepts either a bare routing object ({"rules":[...]}) or a full Xray
     * config that contains a "routing" key, and returns the routing object.
     */
    private fun parseRouting(routingJson: String?): JsonObject? {
        val raw = routingJson?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            when {
                obj.containsKey("rules") -> obj
                obj["routing"] != null -> obj["routing"]!!.jsonObject
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- proxy outbound ----------------

    private fun buildProxyOutbound(p: Profile, s: SettingsSnapshot): JsonObject {
        // When the profile carries the full outbound JSON (xray-json
        // subscription), use it verbatim — only retagged and with legacy XHTTP
        // key names normalized — so complex transports pass through untouched.
        p.rawOutbound?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { rebuildFromRaw(raw) }.getOrNull()?.let { return it }
        }
        return buildFlattenedOutbound(p, s)
    }

    /** Re-emits a stored outbound with tag "proxy" and normalized XHTTP keys. */
    private fun rebuildFromRaw(raw: String): JsonObject {
        val obj = json.parseToJsonElement(raw).jsonObject
        return buildJsonObject {
            put("tag", "proxy")
            for ((k, v) in obj) {
                when {
                    k == "tag" -> {}
                    k == "streamSettings" && v is JsonObject -> put("streamSettings", normalizeStream(v))
                    else -> put(k, v)
                }
            }
        }
    }

    private fun normalizeStream(ss: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in ss) {
            if (k == "xhttpSettings" && v is JsonObject) put("xhttpSettings", normalizeXhttp(v))
            else put(k, v)
        }
    }

    /**
     * Renames legacy XHTTP keys to the current Xray-core naming (the June 2026
     * rename was a cosmetic config-key change with identical wire behavior), so
     * a modern core reads obfuscation params generated by an older server.
     * The `extra` object is itself a SplitHTTPConfig, so recurse into it.
     */
    private fun normalizeXhttp(xh: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in xh) {
            val nk = when (k) {
                "sessionKey" -> "sessionIDKey"
                "sessionPlacement" -> "sessionIDPlacement"
                "sessionTable" -> "sessionIDTable"
                "sessionLength" -> "sessionIDLength"
                else -> k
            }
            if (k == "extra" && v is JsonObject) put(nk, normalizeXhttp(v))
            else put(nk, v)
        }
    }

    private fun buildFlattenedOutbound(p: Profile, s: SettingsSnapshot): JsonObject = buildJsonObject {
        put("tag", "proxy")
        put("protocol", p.protocol)
        putJsonObject("settings") {
            when (p.protocol) {
                "vless", "vmess" -> {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", p.server)
                            put("port", p.port)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", p.uuid)
                                    put("level", 8)
                                    if (p.protocol == "vless") {
                                        put("encryption", p.method?.takeIf { it.isNotBlank() } ?: "none")
                                        // xtls-rprx-vision only works over raw TCP; never emit it for xhttp
                                        val flow = p.flow?.takeIf { it.isNotBlank() && p.network == "tcp" }
                                        if (flow != null) put("flow", flow)
                                    } else {
                                        put("alterId", 0)
                                        put("security", p.method?.takeIf { it.isNotBlank() } ?: "auto")
                                    }
                                }
                            }
                        }
                    }
                }

                "trojan" -> {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", p.server)
                            put("port", p.port)
                            put("password", p.uuid)
                            put("level", 8)
                        }
                    }
                }

                "shadowsocks" -> {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", p.server)
                            put("port", p.port)
                            put("method", p.method?.takeIf { it.isNotBlank() } ?: "aes-256-gcm")
                            put("password", p.uuid)
                            put("level", 8)
                        }
                    }
                }
            }
        }
        put("streamSettings", buildStreamSettings(p))
        putJsonObject("mux") {
            // Mux is incompatible with xhttp; keep it off there regardless of the toggle
            put("enabled", s.mux && p.network != "xhttp")
            put("concurrency", 8)
        }
    }

    private fun buildStreamSettings(p: Profile): JsonObject = buildJsonObject {
        val network = p.network.ifBlank { "tcp" }
        put("network", network)

        // sniExt: the transport-derived SNI candidate (host header / authority).
        var sniExt: String? = null

        when (network) {
            "ws" -> putJsonObject("wsSettings") {
                put("path", p.path?.takeIf { it.isNotBlank() } ?: "/")
                p.host?.takeIf { it.isNotBlank() }?.let {
                    putJsonObject("headers") { put("Host", it) }
                }
                sniExt = p.host
            }

            "xhttp" -> {
                putJsonObject("xhttpSettings") {
                    put("host", p.host.orEmpty())
                    put("path", p.path?.takeIf { it.isNotBlank() } ?: "/")
                    p.xhttpMode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
                    p.xhttpExtra?.takeIf { it.isNotBlank() }?.let { extra ->
                        parseExtra(extra)?.let { put("extra", it) }
                    }
                }
                sniExt = p.host
            }

            "grpc" -> putJsonObject("grpcSettings") {
                put("serviceName", p.serviceName ?: "")
                put("authority", p.authority ?: "")
                put("multiMode", p.grpcMode == "multi")
                put("idle_timeout", 60)
                put("health_check_timeout", 20)
                sniExt = p.authority
            }

            "httpupgrade" -> {
                putJsonObject("httpupgradeSettings") {
                    put("host", p.host.orEmpty())
                    put("path", p.path?.takeIf { it.isNotBlank() } ?: "/")
                }
                sniExt = p.host
            }

            "kcp" -> putJsonObject("kcpSettings") {
                putJsonObject("header") {
                    put("type", p.headerType?.takeIf { it.isNotBlank() } ?: "none")
                }
                p.seed?.takeIf { it.isNotBlank() }?.let { put("seed", it) }
            }

            "h2", "http" -> {
                put("network", "http")
                putJsonObject("httpSettings") {
                    put("path", p.path?.takeIf { it.isNotBlank() } ?: "/")
                    val hosts = p.host.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    putJsonArray("host") { hosts.forEach { add(it) } }
                    sniExt = hosts.firstOrNull()
                }
            }

            else -> {
                // raw tcp, optionally with http header obfuscation
                if (p.headerType == "http") {
                    putJsonObject("tcpSettings") {
                        putJsonObject("header") {
                            put("type", "http")
                            putJsonObject("request") {
                                putJsonArray("path") {
                                    add(p.path?.takeIf { it.isNotBlank() } ?: "/")
                                }
                                putJsonObject("headers") {
                                    p.host?.takeIf { it.isNotBlank() }?.let { host ->
                                        putJsonArray("Host") {
                                            host.split(",").map { it.trim() }.forEach { add(it) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    sniExt = p.host?.split(",")?.firstOrNull()?.trim()
                }
            }
        }

        // Security (TLS / REALITY) — same settings object shape for both.
        val security = p.security?.takeIf { it == "tls" || it == "reality" }
        put("security", security ?: "none")
        if (security != null) {
            val sni = resolveSni(p, sniExt)
            val securityObj = buildJsonObject {
                sni?.takeIf { it.isNotBlank() }?.let { put("serverName", it) }
                put("allowInsecure", p.allowInsecure)
                val fp = p.fingerprint?.takeIf { it.isNotBlank() }
                    ?: if (security == "reality") "chrome" else null
                fp?.let { put("fingerprint", it) }
                p.alpn?.takeIf { it.isNotBlank() }?.let { alpn ->
                    putJsonArray("alpn") {
                        alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
                    }
                }
                if (security == "reality") {
                    p.publicKey?.takeIf { it.isNotBlank() }?.let { put("publicKey", it) }
                    p.shortId?.let { put("shortId", it) }
                    p.spiderX?.let { put("spiderX", it) }
                }
            }
            if (security == "tls") put("tlsSettings", securityObj) else put("realitySettings", securityObj)
        }
    }

    /**
     * Final SNI: explicit sni param, else a domain-valued transport host, else
     * the server if it is a domain, else the transport host. Mirrors v2rayNG.
     */
    private fun resolveSni(p: Profile, sniExt: String?): String? {
        p.sni?.takeIf { it.isNotBlank() }?.let { return it }
        if (!sniExt.isNullOrBlank() && isDomain(sniExt)) return sniExt
        if (p.server.isNotBlank() && isDomain(p.server)) return p.server
        return sniExt
    }

    private fun isDomain(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        if (Utils.isIpAddress(v)) return false
        return v.contains('.') && v.any { it.isLetter() }
    }

    private fun parseExtra(extra: String): JsonElement? = try {
        json.parseToJsonElement(extra)
    } catch (e: Exception) {
        null
    }
}

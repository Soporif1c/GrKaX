package com.grka.xray.config

import com.grka.xray.AppConfig
import com.grka.xray.data.Profile
import com.grka.xray.data.SettingsSnapshot
import com.grka.xray.util.Utils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
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
 * The stream/security section mirrors the field-for-field shape produced by
 * the proven v2rayNG outbound builder so that transports like XHTTP and
 * REALITY load and dial correctly.
 */
object ConfigBuilder {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param routingJson optional routing template (legacy path, e.g. from a
     *   Remnawave subscription) when the profile has no [Profile.fullConfig].
     * @param useSubRouting keep the subscription config's own routing/dns
     *   (with original outbound tags) instead of the app's preset.
     */
    fun build(
        profile: Profile,
        s: SettingsSnapshot,
        forTest: Boolean,
        routingJson: String? = null,
        useSubRouting: Boolean = true,
    ): String {
        // Preferred path: the profile carries the whole xray-json config, so we
        // keep its outbounds (original tags) + routing + dns and only swap in
        // our socks inbound. This makes the panel's routing template work.
        // Only when subscription routing is enabled — otherwise the app preset
        // (which references our proxy/direct/block tags) is used via the flat
        // path below.
        if (useSubRouting) {
            profile.fullConfig?.takeIf { it.isNotBlank() }?.let { full ->
                runCatching { buildFromFullConfig(full, profile, s, forTest) }
                    .getOrNull()?.let { return it }
            }
        }

        val routingOverride = parseRouting(routingJson)

        val root = buildJsonObject {
            putJsonObject("log") {
                put("loglevel", if (forTest) "none" else s.logLevel)
            }

            if (!forTest) {
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

                putJsonObject("dns") {
                    putJsonObject("hosts") {
                        put("domain:googleapis.cn", "googleapis.com")
                    }
                    putJsonArray("servers") {
                        add(s.remoteDns)
                        if (s.routingPreset == AppConfig.ROUTE_BYPASS_RU) {
                            addJsonObject {
                                put("address", s.directDns)
                                put("port", 53)
                                putJsonArray("domains") {
                                    add("geosite:category-ru")
                                }
                            }
                        }
                    }
                }

                putJsonArray("inbounds") {
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
                        putJsonObject("sniffing") {
                            put("enabled", s.sniffing)
                            putJsonArray("destOverride") {
                                add("http")
                                add("tls")
                                add("quic")
                            }
                            put("routeOnly", s.routeOnly)
                        }
                    }
                }
            }

            putJsonArray("outbounds") {
                add(buildProxyOutbound(profile, s))
                addJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    putJsonObject("settings") {
                        put("domainStrategy", "UseIP")
                    }
                }
                addJsonObject {
                    put("tag", "block")
                    put("protocol", "blackhole")
                    putJsonObject("settings") {
                        putJsonObject("response") {
                            put("type", "http")
                        }
                    }
                }
            }

            if (!forTest) {
                if (routingOverride != null) {
                    put("routing", routingOverride)
                } else {
                    put("routing", buildRouting(s))
                }
            }
        }
        return root.toString()
    }

    // ---------------- full-config (xray-json subscription) path ----------------

    private fun buildFromFullConfig(
        full: String,
        p: Profile,
        s: SettingsSnapshot,
        forTest: Boolean,
    ): String {
        val cfg = json.parseToJsonElement(full).jsonObject
        val outbounds = (cfg["outbounds"] as? JsonArray) ?: JsonArray(emptyList())
        val reordered = reorderOutbounds(outbounds, p.proxyTag)

        val root = buildJsonObject {
            putJsonObject("log") { put("loglevel", if (forTest) "none" else s.logLevel) }

            if (!forTest) {
                putJsonObject("stats") {}
                put("dns", ensureDns(cfg["dns"] as? JsonObject, s))
                putJsonArray("inbounds") { add(socksInbound(s)) }
            }

            put("outbounds", reordered)

            if (!forTest) {
                val routing = cfg["routing"] as? JsonObject
                put("routing", routing ?: buildRouting(s))
            }
        }
        return root.toString()
    }

    /** Moves this profile's proxy outbound to the front (routing default) and
     *  normalizes legacy XHTTP keys in every outbound. */
    private fun reorderOutbounds(outbounds: JsonArray, proxyTag: String?): JsonArray {
        val list = outbounds.mapNotNull { it as? JsonObject }.map { normalizeOutbound(it) }
        if (proxyTag.isNullOrBlank()) return JsonArray(list)
        val idx = list.indexOfFirst { it["tag"]?.jsonPrimitive?.contentOrNull == proxyTag }
        if (idx <= 0) return JsonArray(list)
        val out = ArrayList<JsonObject>(list.size)
        out.add(list[idx])
        list.forEachIndexed { i, o -> if (i != idx) out.add(o) }
        return JsonArray(out)
    }

    private fun normalizeOutbound(ob: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in ob) {
            if (k == "streamSettings" && v is JsonObject) put("streamSettings", normalizeStream(v))
            else put(k, v)
        }
    }

    /** Keeps the template DNS but guarantees at least one resolver. */
    private fun ensureDns(dns: JsonObject?, s: SettingsSnapshot): JsonObject {
        if (dns == null) {
            return buildJsonObject { putJsonArray("servers") { add(s.remoteDns) } }
        }
        val servers = dns["servers"] as? JsonArray
        if (servers != null && servers.isNotEmpty()) return dns
        return buildJsonObject {
            for ((k, v) in dns) if (k != "servers") put(k, v)
            putJsonArray("servers") { add(s.remoteDns) }
        }
    }

    private fun socksInbound(s: SettingsSnapshot): JsonObject = buildJsonObject {
        put("tag", "socks")
        put("listen", AppConfig.LOOPBACK)
        put("port", s.socksPort)
        put("protocol", "socks")
        putJsonObject("settings") {
            put("auth", "noauth")
            put("udp", true)
            put("userLevel", 8)
        }
        putJsonObject("sniffing") {
            put("enabled", s.sniffing)
            putJsonArray("destOverride") {
                add("http"); add("tls"); add("quic")
            }
            put("routeOnly", s.routeOnly)
        }
    }

    // ---------------- routing ----------------

    private fun buildRouting(s: SettingsSnapshot): JsonObject = buildJsonObject {
        put("domainStrategy", "IPIfNonMatch")
        putJsonArray("rules") {
            val bypass = s.routingPreset != AppConfig.ROUTE_GLOBAL
            if (s.routingPreset == AppConfig.ROUTE_BYPASS_RU) {
                addJsonObject {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("port", "53")
                    putJsonArray("ip") { add(s.directDns) }
                }
            }
            if (s.blockQuic) {
                addJsonObject {
                    put("type", "field")
                    put("outboundTag", "block")
                    put("network", "udp")
                    put("port", "443")
                }
            }
            if (s.bypassTorrent) {
                addJsonObject {
                    put("type", "field")
                    put("outboundTag", "direct")
                    putJsonArray("protocol") { add("bittorrent") }
                }
            }
            if (bypass) {
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

    /**
     * Accepts either a bare routing object ({"rules":[...]}) or a full Xray
     * config that contains a "routing" key, and returns the routing object.
     */
    private fun parseRouting(routingJson: String?): JsonObject? {
        val raw = routingJson?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val element = json.parseToJsonElement(raw)
            val obj = element.jsonObject
            when {
                obj.containsKey("rules") -> obj
                obj["routing"] != null -> obj["routing"]!!.jsonObject
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- outbound ----------------

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
                                        putJsonArray("Host") { host.split(",").map { it.trim() }.forEach { add(it) } }
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
            if (security == "tls") {
                put("tlsSettings", securityObj)
            } else {
                put("realitySettings", securityObj)
            }
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

package com.grka.xray.config

import com.grka.xray.data.Profile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses an xray-json / v2ray-json subscription body (as served by panels like
 * Remnawave) into profiles plus an optional routing template.
 *
 * Supported shapes:
 *  - a full config object: { "outbounds": [...], "routing": {...} }
 *  - an array of full configs: [ { "outbounds": [...], "routing": {...} }, ... ]
 *  - an array of bare outbound objects: [ { "protocol": ..., "settings": ... }, ... ]
 */
object JsonSubscriptionParser {

    data class Result(val profiles: List<Profile>, val routingJson: String?)

    private val json = Json { ignoreUnknownKeys = true }

    private val proxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks")

    fun looksLikeJson(body: String): Boolean {
        val t = body.trim()
        return t.startsWith("{") || t.startsWith("[")
    }

    fun parse(body: String): Result? = try {
        parseInner(body)
    } catch (e: Exception) {
        null
    }

    private fun parseInner(body: String): Result? {
        val root = json.parseToJsonElement(body.trim())

        val profiles = mutableListOf<Profile>()
        var routing: String? = null

        // The whole config is kept per profile so the panel's routing template
        // (with its original outbound tags) can be applied verbatim.
        fun handleConfig(obj: JsonObject) {
            val outbounds = obj["outbounds"]?.let { it as? JsonArray } ?: return
            if (routing == null) {
                obj["routing"]?.let { routing = it.toString() }
            }
            val fullConfig = obj.toString()
            // xray-json (Happ/Remnawave) carries the friendly server name here.
            val remarks = obj["remarks"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            var idx = 0
            for (ob in outbounds) {
                val outbound = ob as? JsonObject ?: continue
                val proto = outbound["protocol"]?.jsonPrimitive?.contentOrNull
                if (proto !in proxyProtocols) continue
                outboundToProfile(outbound, idx)?.let {
                    it.fullConfig = fullConfig
                    it.proxyTag = outbound["tag"]?.jsonPrimitive?.contentOrNull
                    if (remarks != null) {
                        it.name = if (idx == 0) remarks else "$remarks · ${it.proxyTag ?: (idx + 1)}"
                    }
                    profiles.add(it); idx++
                }
            }
        }

        when (root) {
            is JsonObject -> {
                when {
                    root.containsKey("outbounds") -> handleConfig(root)
                    root["protocol"] != null -> outboundToProfile(root, 0)?.let { profiles.add(it) }
                    else -> return null
                }
            }

            is JsonArray -> {
                var idx = 0
                for (item in root) {
                    val obj = item as? JsonObject ?: continue
                    when {
                        obj.containsKey("outbounds") -> handleConfig(obj)
                        obj["protocol"] != null -> outboundToProfile(obj, idx)?.let { profiles.add(it); idx++ }
                    }
                }
            }

            else -> return null
        }

        if (profiles.isEmpty()) return null
        return Result(profiles, routing)
    }

    private fun outboundToProfile(outbound: JsonObject, index: Int): Profile? {
        val protocol = outbound["protocol"]?.jsonPrimitive?.contentOrNull ?: return null
        if (protocol !in proxyProtocols) return null
        val settings = outbound["settings"] as? JsonObject ?: return null

        val p = Profile(protocol = protocol)

        when (protocol) {
            "vless", "vmess" -> {
                val vnext = (settings["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
                p.server = vnext.str("address") ?: return null
                p.port = vnext.int("port") ?: return null
                val user = (vnext["users"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
                p.uuid = user.str("id") ?: return null
                if (protocol == "vless") {
                    p.method = user.str("encryption") ?: "none"
                    p.flow = user.str("flow")
                } else {
                    p.method = user.str("security") ?: "auto"
                }
            }

            "trojan", "shadowsocks" -> {
                val server = (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
                p.server = server.str("address") ?: return null
                p.port = server.int("port") ?: return null
                p.uuid = server.str("password") ?: return null
                if (protocol == "shadowsocks") p.method = server.str("method")
            }
        }

        applyStreamSettings(p, outbound["streamSettings"] as? JsonObject)

        // Preserve the exact outbound so non-standard transport fields (XHTTP
        // obfuscation, extra, noSSEHeader, xmux, …) survive verbatim.
        p.rawOutbound = outbound.toString()

        val tag = outbound["tag"]?.jsonPrimitive?.contentOrNull
        p.name = tag?.takeIf { it.isNotBlank() && it != "proxy" && it != "out" }
            ?: "${p.server}:${p.port}"
        return p
    }

    private fun applyStreamSettings(p: Profile, ss: JsonObject?) {
        if (ss == null) return
        p.network = ss.str("network") ?: "tcp"
        if (p.network == "http") p.network = "h2"

        when (p.network) {
            "ws" -> (ss["wsSettings"] as? JsonObject)?.let { ws ->
                p.path = ws.str("path")
                p.host = ws.str("host") ?: (ws["headers"] as? JsonObject)?.str("Host")
            }

            "xhttp" -> (ss["xhttpSettings"] as? JsonObject)?.let { xh ->
                p.path = xh.str("path")
                p.host = xh.str("host")
                p.xhttpMode = xh.str("mode")
                xh["extra"]?.let { if (it is JsonObject) p.xhttpExtra = it.toString() }
            }

            "grpc" -> (ss["grpcSettings"] as? JsonObject)?.let { g ->
                p.serviceName = g.str("serviceName")
                p.authority = g.str("authority")
                p.grpcMode = if (g["multiMode"]?.jsonPrimitive?.contentOrNull == "true") "multi" else null
            }

            "httpupgrade" -> (ss["httpupgradeSettings"] as? JsonObject)?.let { h ->
                p.path = h.str("path")
                p.host = h.str("host")
            }

            "h2" -> (ss["httpSettings"] as? JsonObject)?.let { h ->
                p.path = h.str("path")
                p.host = (h["host"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.joinToString(",")
            }

            "tcp" -> (ss["tcpSettings"] as? JsonObject)?.let { tcp ->
                val header = tcp["header"] as? JsonObject
                p.headerType = header?.str("type")
            }
        }

        p.security = ss.str("security")?.takeIf { it == "tls" || it == "reality" }
        val secObj = (ss["tlsSettings"] ?: ss["realitySettings"]) as? JsonObject
        secObj?.let { sec ->
            p.sni = sec.str("serverName")
            p.fingerprint = sec.str("fingerprint")
            p.allowInsecure = sec["allowInsecure"]?.jsonPrimitive?.contentOrNull == "true"
            p.alpn = (sec["alpn"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.joinToString(",")
            p.publicKey = sec.str("publicKey")
            p.shortId = sec.str("shortId")
            p.spiderX = sec.str("spiderX")
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() ?: it.jsonPrimitive.contentOrNull?.toIntOrNull() }
}

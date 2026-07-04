package com.grka.xray.config

import com.grka.xray.data.Profile
import com.grka.xray.util.Utils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * Parses share links (vless:// vmess:// trojan:// ss://) into [Profile]s,
 * following the same query-parameter conventions as v2rayNG/Xray share links —
 * including xhttp (type=xhttp&mode=...&extra=...) and REALITY (pbk/sid/spx/fp).
 */
object LinkParser {

    /** Parses raw text: a list of links, or a base64-encoded subscription payload. */
    fun parseBatch(text: String): List<Profile> {
        var content = text.trim()
        if (!content.contains("://")) {
            content = Utils.tryDecodeBase64(content) ?: content
        }
        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line -> runCatching { parse(line) }.getOrNull() }
            .toList()
    }

    fun parse(link: String): Profile? = when {
        link.startsWith("vless://") -> parseVless(link)
        link.startsWith("vmess://") -> parseVmess(link)
        link.startsWith("trojan://") -> parseTrojan(link)
        link.startsWith("ss://") -> parseShadowsocks(link)
        else -> null
    }

    // ---------------- vless ----------------

    private fun parseVless(link: String): Profile? {
        val uri = URI(sanitize(link))
        val host = uri.host ?: return null
        if (uri.port <= 0) return null
        val userInfo = uri.userInfo ?: return null
        val q = queryMap(uri.rawQuery ?: return null)

        val p = Profile(
            protocol = "vless",
            server = host,
            port = uri.port,
            uuid = userInfo,
            rawLink = link,
        )
        p.method = q["encryption"] ?: "none"
        applyCommonQuery(p, q)
        p.name = fragmentName(uri, host, uri.port)
        return p
    }

    // ---------------- vmess ----------------

    private fun parseVmess(link: String): Profile? {
        val body = link.removePrefix("vmess://")
        val decoded = Utils.tryDecodeBase64(body) ?: return null
        val obj = runCatching { Json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return null

        val server = obj.str("add") ?: return null
        val port = obj.str("port")?.toIntOrNull() ?: return null
        val uuid = obj.str("id") ?: return null

        val p = Profile(
            protocol = "vmess",
            server = server,
            port = port,
            uuid = uuid,
            rawLink = link,
        )
        p.name = obj.str("ps") ?: "$server:$port"
        p.method = obj.str("scy") ?: "auto"
        p.network = obj.str("net")?.takeIf { it.isNotBlank() } ?: "tcp"
        p.headerType = obj.str("type")
        p.host = obj.str("host")
        p.path = obj.str("path")
        p.serviceName = obj.str("path")?.takeIf { p.network == "grpc" } ?: p.serviceName
        if (p.network == "grpc") {
            p.serviceName = obj.str("path")
            p.grpcMode = obj.str("type")
        }
        if (p.network == "xhttp") {
            p.xhttpMode = obj.str("type")
        }
        val tls = obj.str("tls")
        p.security = if (tls == "tls" || tls == "reality") tls else null
        p.sni = obj.str("sni")
        p.alpn = obj.str("alpn")
        p.fingerprint = obj.str("fp")
        return p
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotBlank() }

    // ---------------- trojan ----------------

    private fun parseTrojan(link: String): Profile? {
        val uri = URI(sanitize(link))
        val host = uri.host ?: return null
        if (uri.port <= 0) return null
        val password = uri.userInfo ?: return null
        val q = queryMap(uri.rawQuery ?: "")

        val p = Profile(
            protocol = "trojan",
            server = host,
            port = uri.port,
            uuid = password,
            rawLink = link,
        )
        applyCommonQuery(p, q)
        // trojan defaults to TLS when the link does not say otherwise
        if (p.security == null && q["security"] != "none") {
            p.security = "tls"
        }
        p.name = fragmentName(uri, host, uri.port)
        return p
    }

    // ---------------- shadowsocks ----------------

    private fun parseShadowsocks(link: String): Profile? {
        var body = link.removePrefix("ss://")
        var name = ""
        val fragIdx = body.indexOf('#')
        if (fragIdx >= 0) {
            name = Utils.urlDecode(body.substring(fragIdx + 1))
            body = body.substring(0, fragIdx)
        }
        val queryIdx = body.indexOf('?')
        if (queryIdx >= 0) {
            body = body.substring(0, queryIdx)
        }

        if (!body.contains('@')) {
            body = Utils.tryDecodeBase64(body) ?: return null
        }
        val at = body.lastIndexOf('@')
        if (at < 0) return null

        var userPart = Utils.urlDecode(body.substring(0, at))
        val hostPart = body.substring(at + 1)

        if (!userPart.contains(':')) {
            userPart = Utils.tryDecodeBase64(userPart) ?: return null
        }
        val colon = userPart.indexOf(':')
        if (colon < 0) return null
        val method = userPart.substring(0, colon)
        val password = userPart.substring(colon + 1)

        val portColon = hostPart.lastIndexOf(':')
        if (portColon < 0) return null
        val server = hostPart.substring(0, portColon).trim('[', ']')
        val port = hostPart.substring(portColon + 1).toIntOrNull() ?: return null

        return Profile(
            protocol = "shadowsocks",
            server = server,
            port = port,
            uuid = password,
            method = method,
            name = name.ifEmpty { "$server:$port" },
            rawLink = link,
        )
    }

    // ---------------- shared helpers ----------------

    private fun sanitize(link: String): String =
        link.trim().replace(" ", "%20").replace("|", "%7C")

    private fun queryMap(rawQuery: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx)
            val value = Utils.urlDecode(pair.substring(idx + 1))
            map[key] = value
        }
        return map
    }

    private fun fragmentName(uri: URI, host: String, port: Int): String {
        val frag = uri.fragment ?: uri.rawFragment?.let { Utils.urlDecode(it) }
        return frag?.takeIf { it.isNotBlank() } ?: "$host:$port"
    }

    private fun applyCommonQuery(p: Profile, q: Map<String, String>) {
        p.network = q["type"]?.takeIf { it.isNotBlank() } ?: "tcp"
        if (p.network == "http") p.network = "h2"
        p.headerType = q["headerType"]
        p.host = q["host"]
        p.path = q["path"]
        p.seed = q["seed"]
        p.serviceName = q["serviceName"]
        p.authority = q["authority"]
        if (p.network == "grpc") {
            p.grpcMode = q["mode"]
        }
        if (p.network == "xhttp") {
            p.xhttpMode = q["mode"]
            p.xhttpExtra = q["extra"]
        }

        p.security = q["security"]?.takeIf { it == "tls" || it == "reality" }
        p.allowInsecure = q["insecure"] == "1" || q["allowInsecure"] == "1" || q["allow_insecure"] == "1"
        p.sni = q["sni"]
        p.fingerprint = q["fp"]
        p.alpn = q["alpn"]
        p.publicKey = q["pbk"]
        p.shortId = q["sid"]
        p.spiderX = q["spx"]
        p.flow = q["flow"]
    }
}

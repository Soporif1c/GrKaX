package com.grka.xray.config

import com.grka.xray.AppConfig
import com.grka.xray.data.Profile
import com.grka.xray.data.SettingsSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the Xray core JSON config from a profile plus app settings.
 *
 * The overall shape (stats/policy/socks inbound with sniffing/proxy+direct+block
 * outbounds/routing) follows the proven v2rayNG layout so that DNS and routing
 * behave correctly and the tunnel actually passes traffic.
 */
object ConfigBuilder {

    fun build(profile: Profile, s: SettingsSnapshot, forTest: Boolean): String {
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

            putJsonObject("routing") {
                put("domainStrategy", "IPIfNonMatch")
                putJsonArray("rules") {
                    if (!forTest) {
                        val bypass = s.routingPreset != AppConfig.ROUTE_GLOBAL
                        if (s.routingPreset == AppConfig.ROUTE_BYPASS_RU) {
                            // Plain-DNS queries to the domestic resolver must go direct
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
            }
        }
        return root.toString()
    }

    private fun buildProxyOutbound(p: Profile, s: SettingsSnapshot): JsonObject = buildJsonObject {
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
                                        p.flow?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
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
        put("network", p.network)
        val security = p.security ?: "none"
        put("security", security)

        if (security == "tls") {
            putJsonObject("tlsSettings") {
                val sniValue = p.sni?.takeIf { it.isNotBlank() }
                    ?: p.host?.takeIf { it.isNotBlank() }
                    ?: p.server
                put("serverName", sniValue)
                put("allowInsecure", p.allowInsecure)
                p.fingerprint?.takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
                p.alpn?.takeIf { it.isNotBlank() }?.let { alpn ->
                    putJsonArray("alpn") {
                        alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
                    }
                }
            }
        }

        if (security == "reality") {
            putJsonObject("realitySettings") {
                put("serverName", p.sni ?: "")
                put("publicKey", p.publicKey ?: "")
                put("shortId", p.shortId ?: "")
                put("spiderX", p.spiderX ?: "")
                put("fingerprint", p.fingerprint?.takeIf { it.isNotBlank() } ?: "chrome")
                put("show", false)
            }
        }

        when (p.network) {
            "ws" -> putJsonObject("wsSettings") {
                put("path", p.path?.takeIf { it.isNotBlank() } ?: "/")
                p.host?.takeIf { it.isNotBlank() }?.let {
                    putJsonObject("headers") { put("Host", it) }
                }
            }

            "xhttp" -> putJsonObject("xhttpSettings") {
                put("path", p.path?.takeIf { it.isNotBlank() } ?: "/")
                p.host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                put("mode", p.xhttpMode?.takeIf { it.isNotBlank() } ?: "auto")
                p.xhttpExtra?.takeIf { it.isNotBlank() }?.let { extra ->
                    runCatching { Json.parseToJsonElement(extra) }.getOrNull()?.let { el ->
                        put("extra", el)
                    }
                }
            }

            "grpc" -> putJsonObject("grpcSettings") {
                put("serviceName", p.serviceName ?: "")
                p.authority?.takeIf { it.isNotBlank() }?.let { put("authority", it) }
                put("multiMode", p.grpcMode == "multi")
            }

            "httpupgrade" -> putJsonObject("httpupgradeSettings") {
                put("path", p.path?.takeIf { it.isNotBlank() } ?: "/")
                p.host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            }

            "kcp" -> putJsonObject("kcpSettings") {
                putJsonObject("header") {
                    put("type", p.headerType?.takeIf { it.isNotBlank() } ?: "none")
                }
                p.seed?.takeIf { it.isNotBlank() }?.let { put("seed", it) }
            }

            "h2", "http" -> putJsonObject("httpSettings") {
                put("path", p.path?.takeIf { it.isNotBlank() } ?: "/")
                p.host?.takeIf { it.isNotBlank() }?.let { host ->
                    putJsonArray("host") { add(host) }
                }
            }

            else -> {
                // plain tcp with optional http header obfuscation
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
                                        putJsonArray("Host") { add(host) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

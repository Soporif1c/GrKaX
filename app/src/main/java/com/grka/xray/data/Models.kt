package com.grka.xray.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Profile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var protocol: String = "vless", // vless | vmess | trojan | shadowsocks
    var server: String = "",
    var port: Int = 443,
    var uuid: String = "", // uuid or password
    var method: String? = null, // ss cipher / vless encryption / vmess security
    var flow: String? = null,

    // transport
    var network: String = "tcp", // tcp | ws | grpc | httpupgrade | xhttp | kcp | h2
    var host: String? = null,
    var path: String? = null,
    var headerType: String? = null,
    var seed: String? = null,
    var serviceName: String? = null,
    var authority: String? = null,
    var grpcMode: String? = null,
    var xhttpMode: String? = null,
    var xhttpExtra: String? = null,

    // security
    var security: String? = null, // tls | reality | null
    var sni: String? = null,
    var alpn: String? = null,
    var fingerprint: String? = null,
    var allowInsecure: Boolean = false,
    var publicKey: String? = null,
    var shortId: String? = null,
    var spiderX: String? = null,

    // meta
    var subId: String? = null,
    var rawLink: String? = null,
) {
    fun protoLabel(): String = when (protocol) {
        "vless" -> "VLESS"
        "vmess" -> "VMess"
        "trojan" -> "Trojan"
        "shadowsocks" -> "SS"
        else -> protocol.uppercase()
    }

    fun transportLabel(): String {
        val net = network.uppercase()
        val sec = when (security) {
            "tls" -> "TLS"
            "reality" -> "REALITY"
            else -> null
        }
        return if (sec != null) "$net · $sec" else net
    }

    /** Rough identity used to keep selection across subscription updates. */
    fun identityKey(): String = "$protocol|$server|$port|$uuid|$network"
}

@Serializable
data class Subscription(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var url: String = "",
    var lastUpdate: Long = 0,
    // From the subscription-userinfo header (Remnawave and others); -1 = unknown
    var upload: Long = -1,
    var download: Long = -1,
    var total: Long = -1,
    var expire: Long = -1,
)

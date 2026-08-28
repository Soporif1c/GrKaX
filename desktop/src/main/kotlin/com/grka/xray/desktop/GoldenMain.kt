package com.grka.xray.desktop

import com.grka.xray.desktop.config.ConfigBuilder
import com.grka.xray.desktop.config.JsonSubscriptionParser
import com.grka.xray.desktop.config.LinkParser
import com.grka.xray.desktop.data.Profile
import com.grka.xray.desktop.data.SettingsSnapshot
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Dumps what the Kotlin parsers actually produce, so the Swift port can be
 * checked against the real thing instead of against someone's reading of it.
 *
 * The Swift test suite decodes these files and compares them to its own output.
 * That turns "the port looks right" into a failing test when it is not — which
 * matters most for the cases below, every one of which is a link shape that
 * behaves differently under `java.net.URI` than under Foundation.
 *
 * Run with `./gradlew golden -PgoldenOut=<dir>`; the default lands in the Swift
 * test fixtures. Regenerate whenever the Kotlin parsers change.
 */
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "../apple/GrKaXKit/Tests/GrKaXKitTests/Golden").apply { mkdirs() }

    val links = listOf(
        // The smoke link: xhttp + REALITY, the combination the app exists for.
        "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?type=xhttp&mode=packet-up&security=reality&pbk=SGVsbG9Xb3JsZEhlbGxvV29ybGRIZWxsb1dvcmxkMDA" +
            "&fp=chrome&sni=www.microsoft.com&sid=aabb&path=%2Fxhttp&encryption=none#Smoke",
        // Bare minimum vless.
        "vless://aaaa-bbbb@example.org:8443?encryption=none#Plain",
        // type=http has to become h2.
        "vless://aaaa-bbbb@example.org:8443?type=http&security=tls#H2",
        // grpc carries mode and serviceName.
        "vless://aaaa-bbbb@h.example:2053?type=grpc&mode=multi&serviceName=gun&security=tls#Grpc",
        // A name with a space and a pipe — both are rewritten by sanitize().
        "vless://aaaa-bbbb@h.example:443?encryption=none#Berlin 01 | fast",
        // A '+' in the fragment survives: URI decoding is not form decoding.
        "vless://aaaa-bbbb@h.example:443?encryption=none#Berlin+Fast",
        // Percent-encoded Cyrillic name.
        "vless://aaaa-bbbb@h.example:443?encryption=none#%D0%91%D0%B5%D1%80%D0%BB%D0%B8%D0%BD",
        // No fragment at all — the name falls back to host:port.
        "vless://aaaa-bbbb@h.example:443?encryption=none",
        // IPv6 literal in brackets.
        "vless://aaaa-bbbb@[2001:db8::1]:443?encryption=none#V6",
        // insecure spelled three different ways by three different panels.
        "vless://aaaa-bbbb@h.example:443?encryption=none&insecure=1#I1",
        "vless://aaaa-bbbb@h.example:443?encryption=none&allowInsecure=1#I2",
        "vless://aaaa-bbbb@h.example:443?encryption=none&allow_insecure=1#I3",
        // A path with an encoded slash and a query-looking tail.
        "vless://aaaa-bbbb@h.example:443?type=ws&path=%2Fws%3Fed%3D2048&host=cdn.example#Ws",

        // vmess: base64 JSON, numeric port, tls flag.
        "vmess://" + java.util.Base64.getEncoder().encodeToString(
            ("""{"v":"2","ps":"VMess Node","add":"vm.example","port":443,"id":"cccc-dddd",""" +
                """"scy":"auto","net":"ws","type":"none","host":"cdn.example","path":"/p","tls":"tls","sni":"s.example"}""")
                .toByteArray()
        ),
        // vmess with the port quoted instead — same profile must come out.
        "vmess://" + java.util.Base64.getEncoder().encodeToString(
            ("""{"ps":"Quoted","add":"vm2.example","port":"8443","id":"eeee-ffff","net":"grpc","type":"gun"}""")
                .toByteArray()
        ),
        // vmess with no ps: the name falls back to add:port.
        "vmess://" + java.util.Base64.getEncoder().encodeToString(
            ("""{"add":"vm3.example","port":443,"id":"gggg"}""").toByteArray()
        ),

        // trojan defaults to TLS...
        "trojan://password123@t.example:443#Trojan",
        // ...unless the link says otherwise.
        "trojan://password123@t.example:443?security=none#TrojanPlain",
        "trojan://password123@t.example:443?type=ws&path=/tr&security=tls&sni=s.example#TrojanWs",

        // shadowsocks, base64 user info (the classic form).
        "ss://" + java.util.Base64.getEncoder().encodeToString("aes-256-gcm:pass word".toByteArray()) +
            "@ss.example:8388#SS",
        // SIP002: plain method:password, percent-encoded.
        "ss://YWVzLTI1Ni1nY206cGFzcw%3D%3D@ss2.example:8389#SS2",
        // Whole body base64'd, including host.
        "ss://" + java.util.Base64.getEncoder().encodeToString(
            "chacha20-ietf-poly1305:secret@ss3.example:8390".toByteArray()
        ) + "#SS3",
        // A plugin query that must be dropped before the host is read.
        "ss://YWVzLTEyOC1nY206azE%3D@ss4.example:8391?plugin=obfs-local#SS4",

        // Rejected outright.
        "https://example.com",
        "vless://no-port@example.com?encryption=none",
        "not a link at all",
        "",
    )

    val cases = links.map { link ->
        GoldenLink(link = link, profile = LinkParser.parse(link)?.normalized())
    }

    val json = Json { prettyPrint = true; encodeDefaults = true }
    File(outDir, "links.json").writeText(
        json.encodeToString(ListSerializer(GoldenLink.serializer()), cases)
    )
    println("wrote links.json: ${cases.count { it.profile != null }}/${cases.size} parsed")

    // JSON round trips. The Swift port carries its own order-preserving JSON
    // type — Foundation loses key order and rewrites number literals, and both
    // leak into configs handed to the core. These pin it to kotlinx's output.
    val jsonSamples = listOf(
        """{"b":1,"a":2,"c":3}""",
        """{"z":{"y":{"x":[1,2,3]}}}""",
        """{"n":443,"f":1.5,"e":1e3,"neg":-7,"big":10000000000,"zero":0.0}""",
        """{"t":true,"f":false,"nul":null}""",
        """{"s":"quote\" back\\ slash/ tab\t nl\n"}""",
        """{"unicode":"Берлин","emoji":"🚀","escaped":"Aé"}""",
        """{"ctrl":"ab"}""",
        """[]""",
        """{}""",
        """{"empty_obj":{},"empty_arr":[]}""",
        """[{"a":1},{"a":2}]""",
        """{"dup":1,"dup":2}""",
        """  {"spaced"  :  [ 1 , 2 ]  }  """,
    )
    File(outDir, "json.json").writeText(
        json.encodeToString(
            ListSerializer(GoldenJson.serializer()),
            jsonSamples.map { sample ->
                GoldenJson(input = sample, compact = Json.parseToJsonElement(sample).toString())
            }
        )
    )
    println("wrote json.json: ${jsonSamples.size} round trips")

    // xray-json subscription bodies. The verbatim-passthrough guarantee lives
    // here: rawOutbound and fullConfig are compared as text, so any reordering
    // or renumbering the Swift JSON layer did would show up as a failure.
    val subBodies = listOf(
        // Remnawave's shape: remarks, tagged outbounds, routing, dns.
        """{"remarks":"Berlin","outbounds":[
          {"tag":"proxy-a","protocol":"vless","settings":{"vnext":[{"address":"example.com","port":443,
            "users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none","flow":"xtls-rprx-vision"}]}]},
           "streamSettings":{"network":"xhttp","security":"tls",
             "tlsSettings":{"serverName":"www.microsoft.com","fingerprint":"chrome","alpn":["h2","http/1.1"]},
             "xhttpSettings":{"host":"www.microsoft.com","path":"/x","mode":"packet-up",
               "extra":{"noSSEHeader":true,"sessionLength":8,"xmux":{"maxConcurrency":"16-32"}}}}},
          {"tag":"proxy-b","protocol":"trojan","settings":{"servers":[{"address":"t.example","port":8443,
            "password":"pw"}]},"streamSettings":{"network":"ws","security":"tls",
            "wsSettings":{"path":"/w","headers":{"Host":"cdn.example"}}}},
          {"tag":"direct","protocol":"freedom"},
          {"tag":"block","protocol":"blackhole"}],
         "routing":{"domainStrategy":"IPIfNonMatch","rules":[
           {"type":"field","outboundTag":"direct","domain":["geosite:category-ru"]},
           {"type":"field","outboundTag":"proxy-a","network":"tcp,udp"}]},
         "dns":{"servers":["1.1.1.1"]}}""",
        // A bare array of outbounds, no wrapper config.
        """[{"tag":"n1","protocol":"vmess","settings":{"vnext":[{"address":"vm.example","port":443,
            "users":[{"id":"cccc","security":"auto"}]}]},
           "streamSettings":{"network":"grpc","grpcSettings":{"serviceName":"gun","multiMode":true}}},
          {"tag":"n2","protocol":"shadowsocks","settings":{"servers":[{"address":"ss.example","port":8388,
            "password":"pw","method":"aes-256-gcm"}]}}]""",
        // reality, and a tag the parser must ignore in favour of host:port.
        """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"r.example",
            "port":443,"users":[{"id":"dddd","encryption":"none"}]}]},
           "streamSettings":{"network":"tcp","security":"reality",
             "realitySettings":{"serverName":"a.example","publicKey":"PK","shortId":"ab","spiderX":"/",
               "fingerprint":"chrome"},"tcpSettings":{"header":{"type":"http"}}}}]}""",
        // h2 with a host array, and httpupgrade.
        """[{"tag":"h","protocol":"vless","settings":{"vnext":[{"address":"h2.example","port":443,
            "users":[{"id":"eeee"}]}]},"streamSettings":{"network":"http",
            "httpSettings":{"path":"/h","host":["a.example","b.example"]}}},
          {"tag":"u","protocol":"vless","settings":{"vnext":[{"address":"hu.example","port":443,
            "users":[{"id":"ffff"}]}]},"streamSettings":{"network":"httpupgrade",
            "httpupgradeSettings":{"path":"/u","host":"up.example"}}}]""",
        // Nothing usable: only non-proxy outbounds.
        """{"outbounds":[{"tag":"direct","protocol":"freedom"}]}""",
        // Not a subscription at all.
        """{"hello":"world"}""",
    )
    File(outDir, "subscriptions.json").writeText(
        json.encodeToString(
            ListSerializer(GoldenSub.serializer()),
            subBodies.map { body ->
                val result = JsonSubscriptionParser.parse(body)
                GoldenSub(
                    body = body,
                    profiles = result?.profiles?.map { it.normalized() },
                    routingJson = result?.routingJson,
                )
            }
        )
    )
    println("wrote subscriptions.json: ${subBodies.size} bodies")

    // Built configs. This is the payload the core actually receives, so the
    // comparison is on the exact text — key order and all. The cases walk every
    // branch of ConfigBuilder, and in particular the ones the recent TUN fixes
    // landed in: interface pinning, DNS pins, and address substitution that has
    // to keep the SNI intact.
    val pins = mapOf("example.com" to listOf("203.0.113.10"), "t.example" to listOf("203.0.113.11"))

    fun settings(
        mode: String = AppConfig.MODE_RULE,
        preset: String = AppConfig.ROUTE_BYPASS_RU,
        iface: String? = null,
        hostPins: Map<String, List<String>> = emptyMap(),
        blockQuic: Boolean = true,
        bypassTorrent: Boolean = true,
        mux: Boolean = false,
        routeOnly: Boolean = false,
        sniffing: Boolean = true,
    ) = SettingsSnapshot(
        socksPort = 10808, httpPort = 10809, apiPort = 10810,
        remoteDns = "1.1.1.1", directDns = "77.88.8.8",
        mode = mode, routingPreset = preset,
        blockQuic = blockQuic, bypassTorrent = bypassTorrent,
        sniffing = sniffing, routeOnly = routeOnly, mux = mux,
        logLevel = "warning", bindInterface = iface, serverPins = hostPins,
    )

    val smokeLink = links[0]
    val fromLink = LinkParser.parse(smokeLink)!!
    val fromJson = JsonSubscriptionParser.parse(subBodies[0])!!.profiles.first()
    val subRouting = JsonSubscriptionParser.parse(subBodies[0])!!.routingJson

    // Every transport shape the flattened builder can emit.
    val wsProfile = LinkParser.parse(
        "vless://u1@ws.example:443?type=ws&path=%2Fw&host=cdn.example&security=tls&sni=s.example#Ws"
    )!!
    val grpcProfile = LinkParser.parse(
        "vless://u2@g.example:443?type=grpc&mode=multi&serviceName=gun&authority=a.example&security=tls#G"
    )!!
    val h2Profile = LinkParser.parse(
        "vless://u3@h2.example:443?type=http&path=%2Fh&host=a.example,b.example&security=tls#H"
    )!!
    val kcpProfile = LinkParser.parse(
        "vless://u4@k.example:443?type=kcp&headerType=wechat-video&seed=abc#K"
    )!!
    val tcpHeaderProfile = LinkParser.parse(
        "vless://u5@t2.example:443?headerType=http&path=%2Ft&host=a.example,b.example#T"
    )!!
    val realityProfile = LinkParser.parse(
        "vless://u6@r.example:443?security=reality&pbk=SGVsbG9Xb3JsZEhlbGxvV29ybGRIZWxsb1dvcmxkMDA" +
            "&sid=&spx=%2F&flow=xtls-rprx-vision#R"
    )!!
    val trojanProfile = LinkParser.parse("trojan://pw@t.example:443?type=ws&path=%2Ftr#Tj")!!
    val ssProfile = LinkParser.parse("ss://YWVzLTI1Ni1nY206cGFzcw%3D%3D@ss.example:8388#S")!!
    val vmessProfile = LinkParser.parse(
        "vmess://" + java.util.Base64.getEncoder().encodeToString(
            ("""{"ps":"VM","add":"vm.example","port":443,"id":"vm-id","net":"ws","path":"/v","tls":"tls"}""")
                .toByteArray()
        )
    )!!

    val template = """{"routing":{"domainStrategy":"AsIs","rules":[
        {"type":"field","outboundTag":"direct","domain":["example.org"]}]},
        "dns":{"servers":["8.8.8.8"],"hosts":{"a.example":"1.2.3.4"}}}"""

    val configCases = listOf(
        GoldenConfigCase("link-rule", fromLink, settings()),
        GoldenConfigCase("link-global", fromLink, settings(mode = AppConfig.MODE_GLOBAL)),
        GoldenConfigCase("link-direct", fromLink, settings(mode = AppConfig.MODE_DIRECT)),
        GoldenConfigCase("link-lan-preset", fromLink, settings(preset = AppConfig.ROUTE_BYPASS_LAN)),
        // TUN: interface pinning, DNS pins and address substitution together.
        GoldenConfigCase("link-tun", fromLink, settings(iface = "en0", hostPins = pins)),
        GoldenConfigCase("link-no-quic-no-torrent", fromLink, settings(blockQuic = false, bypassTorrent = false)),
        GoldenConfigCase("link-mux-routeonly", fromLink, settings(mux = true, routeOnly = true, sniffing = false)),
        // Verbatim path: the subscription's own outbound and routing.
        GoldenConfigCase("sub-rule", fromJson, settings(), routingJson = subRouting),
        GoldenConfigCase("sub-global", fromJson, settings(mode = AppConfig.MODE_GLOBAL)),
        GoldenConfigCase("sub-tun", fromJson, settings(iface = "en0", hostPins = pins)),
        GoldenConfigCase("sub-no-routing", fromJson, settings(), useSubRouting = false),
        // User template beats both preset and subscription.
        GoldenConfigCase("template", fromLink, settings(), customTemplate = template),
        GoldenConfigCase("template-over-sub", fromJson, settings(), customTemplate = template),
        // Transports.
        GoldenConfigCase("ws", wsProfile, settings()),
        GoldenConfigCase("grpc", grpcProfile, settings()),
        GoldenConfigCase("h2", h2Profile, settings()),
        GoldenConfigCase("kcp", kcpProfile, settings()),
        GoldenConfigCase("tcp-http-header", tcpHeaderProfile, settings()),
        GoldenConfigCase("reality-empty-sid", realityProfile, settings()),
        GoldenConfigCase("trojan", trojanProfile, settings()),
        GoldenConfigCase("shadowsocks", ssProfile, settings()),
        GoldenConfigCase("vmess", vmessProfile, settings()),
        GoldenConfigCase("vmess-mux", vmessProfile, settings(mux = true)),
    )

    File(outDir, "configs.json").writeText(
        json.encodeToString(
            ListSerializer(GoldenConfig.serializer()),
            configCases.map { c ->
                GoldenConfig(
                    name = c.name,
                    profile = c.profile.normalized(),
                    settings = c.settings.toGolden(),
                    routingJson = c.routingJson,
                    useSubRouting = c.useSubRouting,
                    customTemplate = c.customTemplate,
                    config = ConfigBuilder.build(
                        profile = c.profile,
                        s = c.settings,
                        routingJson = c.routingJson,
                        useSubRouting = c.useSubRouting,
                        customTemplate = c.customTemplate,
                    ),
                )
            }
        )
    )
    println("wrote configs.json: ${configCases.size} configs")

    // A batch payload, base64'd the way a subscription serves it.
    val batchBody = links.take(6).joinToString("\n")
    val batchEncoded = java.util.Base64.getEncoder().encodeToString(batchBody.toByteArray())
    File(outDir, "batch.json").writeText(
        json.encodeToString(
            GoldenBatch.serializer(),
            GoldenBatch(
                encoded = batchEncoded,
                profiles = LinkParser.parseBatch(batchEncoded).map { it.normalized() },
            )
        )
    )
    println("wrote batch.json")
}

/** Ids are random per parse, so they are blanked on both sides before comparing. */
private fun Profile.normalized(): Profile = copy(id = "")

@Serializable
private data class GoldenLink(val link: String, val profile: Profile?)

@Serializable
private data class GoldenJson(val input: String, val compact: String)

private data class GoldenConfigCase(
    val name: String,
    val profile: Profile,
    val settings: SettingsSnapshot,
    val routingJson: String? = null,
    val useSubRouting: Boolean = true,
    val customTemplate: String? = null,
)

@Serializable
private data class GoldenSettings(
    val socksPort: Int, val httpPort: Int, val apiPort: Int,
    val remoteDns: String, val directDns: String,
    val mode: String, val routingPreset: String,
    val blockQuic: Boolean, val bypassTorrent: Boolean,
    val sniffing: Boolean, val routeOnly: Boolean, val mux: Boolean,
    val logLevel: String,
    val bindInterface: String?,
    val serverPins: Map<String, List<String>>,
)

private fun SettingsSnapshot.toGolden() = GoldenSettings(
    socksPort, httpPort, apiPort, remoteDns, directDns, mode, routingPreset,
    blockQuic, bypassTorrent, sniffing, routeOnly, mux, logLevel,
    bindInterface, serverPins,
)

@Serializable
private data class GoldenConfig(
    val name: String,
    val profile: Profile,
    val settings: GoldenSettings,
    val routingJson: String?,
    val useSubRouting: Boolean,
    val customTemplate: String?,
    val config: String,
)

@Serializable
private data class GoldenSub(
    val body: String,
    val profiles: List<Profile>?,
    val routingJson: String?,
)

@Serializable
private data class GoldenBatch(val encoded: String, val profiles: List<Profile>)

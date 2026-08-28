import Foundation

/// Builds the Xray core JSON config from a profile plus app settings.
///
/// Differences from the Android build:
///  - two local inbounds (SOCKS + HTTP), because macOS system-proxy mode needs
///    an HTTP proxy for apps that ignore SOCKS;
///  - a dokodemo-door `api` inbound so traffic counters can be polled off the
///    core's StatsService;
///  - the traffic mode switch (Rule / Global / Direct) decides whether the
///    subscription's own routing is honoured at all.
///
/// The stream/security section is intentionally identical to the Android
/// builder so XHTTP and REALITY dial the same way on both clients.
///
/// Key order is not incidental here. `withApiRule` moves `rules` to the end,
/// `rebuildFromRaw` puts `tag` first, and a verbatim outbound keeps whatever
/// order the panel sent — all of it reproduced from the Kotlin original, and
/// all of it checked against golden configs.
public enum ConfigBuilder {

    private static let proxyProtocols: Set<String> = ["vless", "vmess", "trojan", "shadowsocks"]

    public static func build(
        profile: Profile,
        settings s: SettingsSnapshot,
        routingJson: String? = nil,
        useSubRouting: Bool = true,
        customTemplate: String? = nil
    ) -> String {
        // A user-pasted template's routing/dns take top priority — this is how a
        // panel's routing gets applied even when the subscription arrives as
        // plain share links (which carry no routing/dns).
        let tplRouting = extractFromTemplate(customTemplate, key: "routing")
        let tplDns = extractFromTemplate(customTemplate, key: "dns")

        // Global and Direct deliberately ignore whatever routing the panel
        // shipped: the switch would otherwise be a lie.
        let honourSubRouting = useSubRouting && s.mode == AppConfig.modeRule

        if honourSubRouting,
           let full = profile.fullConfig, !full.isEmpty,
           let built = buildFromFullConfig(full, profile: profile, s: s, tplRouting: tplRouting, tplDns: tplDns) {
            return built
        }

        let routingOverride: JSONObject? = s.mode == AppConfig.modeRule
            ? (tplRouting ?? parseRouting(routingJson))
            : nil

        var root = JSONObject()
        root["log"] = .object(JSONObject([("loglevel", .string(s.logLevel))]))
        putStatsBlock(&root)

        if s.mode == AppConfig.modeRule, let tplDns {
            root["dns"] = .object(ensureDns(tplDns, s))
        } else {
            root["dns"] = .object(defaultDns(s))
        }

        root["inbounds"] = .array(inbounds(s))
        root["outbounds"] = .array([
            .object(bindToInterface(buildProxyOutbound(profile, s), s.bindInterface)),
            .object(bindToInterface(directOutbound(), s.bindInterface)),
            .object(blockOutbound()),
        ])
        root["routing"] = .object(withApiRule(routingOverride ?? buildRouting(s)))

        return JSON.object(root).compact
    }

    // MARK: - full-config (xray-json subscription) path

    private static func buildFromFullConfig(
        _ full: String,
        profile p: Profile,
        s: SettingsSnapshot,
        tplRouting: JSONObject?,
        tplDns: JSONObject?
    ) -> String? {
        guard let cfg = JSON.parse(full)?.objectValue else { return nil }
        let outbounds = cfg["outbounds"]?.arrayValue ?? []
        let reordered = ensureFallbackOutbounds(reorderOutbounds(outbounds, proxyTag: p.proxyTag))
            .map { bindToInterface(pinOutbound($0, s), s.bindInterface) }

        var root = JSONObject()
        root["log"] = .object(JSONObject([("loglevel", .string(s.logLevel))]))
        putStatsBlock(&root)
        root["dns"] = .object(ensureDns(tplDns ?? cfg["dns"]?.objectValue, s))
        root["inbounds"] = .array(inbounds(s))
        root["outbounds"] = .array(reordered.map { .object($0) })

        let routing = tplRouting ?? cfg["routing"]?.objectValue
        root["routing"] = .object(withApiRule(routing ?? buildRouting(s)))

        return JSON.object(root).compact
    }

    // MARK: - inbounds / api

    private static func inbounds(_ s: SettingsSnapshot) -> [JSON] {
        [
            .object(JSONObject([
                ("tag", .string("socks")),
                ("listen", .string(AppConfig.loopback)),
                ("port", .int(s.socksPort)),
                ("protocol", .string("socks")),
                ("settings", .object(JSONObject([
                    ("auth", .string("noauth")),
                    ("udp", .bool(true)),
                    ("userLevel", .int(8)),
                ]))),
                ("sniffing", .object(sniffing(s))),
            ])),
            .object(JSONObject([
                // System-proxy mode points macOS at this one for HTTP/HTTPS.
                ("tag", .string("http")),
                ("listen", .string(AppConfig.loopback)),
                ("port", .int(s.httpPort)),
                ("protocol", .string("http")),
                ("settings", .object(JSONObject([("userLevel", .int(8))]))),
                ("sniffing", .object(sniffing(s))),
            ])),
            .object(JSONObject([
                ("tag", .string("api")),
                ("listen", .string(AppConfig.loopback)),
                ("port", .int(s.apiPort)),
                ("protocol", .string("dokodemo-door")),
                ("settings", .object(JSONObject([("address", .string(AppConfig.loopback))]))),
            ])),
        ]
    }

    private static func sniffing(_ s: SettingsSnapshot) -> JSONObject {
        JSONObject([
            ("enabled", .bool(s.sniffing)),
            ("destOverride", .array([.string("http"), .string("tls"), .string("quic")])),
            ("routeOnly", .bool(s.routeOnly)),
        ])
    }

    private static func putStatsBlock(_ root: inout JSONObject) {
        root["api"] = .object(JSONObject([
            ("tag", .string("api")),
            ("services", .array([.string("StatsService")])),
        ]))
        root["stats"] = .object(JSONObject())
        root["policy"] = .object(JSONObject([
            ("levels", .object(JSONObject([
                ("8", .object(JSONObject([
                    ("handshake", .int(4)),
                    ("connIdle", .int(300)),
                ]))),
            ]))),
            ("system", .object(JSONObject([
                ("statsOutboundUplink", .bool(true)),
                ("statsOutboundDownlink", .bool(true)),
            ]))),
        ]))
    }

    /// The stats API is only reachable if routing sends the api inbound to the
    /// api tag, and that rule has to win over everything else — so it goes in
    /// front of whatever rules the panel or the user supplied.
    private static func withApiRule(_ routing: JSONObject) -> JSONObject {
        let apiRule = JSON.object(JSONObject([
            ("type", .string("field")),
            ("inboundTag", .array([.string("api")])),
            ("outboundTag", .string("api")),
        ]))
        let existing = routing["rules"]?.arrayValue ?? []

        var out = JSONObject()
        for (key, value) in routing.pairs where key != "rules" {
            out[key] = value
        }
        // `rules` is re-added last, so it moves to the end even when the panel
        // had it in the middle.
        out["rules"] = .array([apiRule] + existing)
        return out
    }

    // MARK: - dns

    private static func defaultDns(_ s: SettingsSnapshot) -> JSONObject {
        var hosts = JSONObject([("domain:googleapis.cn", .string("googleapis.com"))])
        putServerPins(&hosts, s)

        var servers: [JSON] = [.string(s.remoteDns)]
        if s.mode == AppConfig.modeRule, s.routingPreset == AppConfig.routeBypassRu {
            servers.append(.object(JSONObject([
                ("address", .string(s.directDns)),
                ("port", .int(53)),
                ("domains", .array([.string("geosite:category-ru")])),
            ])))
        }

        return JSONObject([("hosts", .object(hosts)), ("servers", .array(servers))])
    }

    /// Keeps the template DNS but guarantees at least one resolver, and adds the
    /// server pins — a template that resolves its own servers through the tunnel
    /// deadlocks exactly like the default one would.
    private static func ensureDns(_ dns: JSONObject?, _ s: SettingsSnapshot) -> JSONObject {
        guard let dns else { return defaultDns(s) }
        let servers = dns["servers"]?.arrayValue

        var out = JSONObject()
        for (key, value) in dns.pairs where key != "servers" && key != "hosts" {
            out[key] = value
        }
        var hosts = JSONObject()
        if let existing = dns["hosts"]?.objectValue {
            for (key, value) in existing.pairs { hosts[key] = value }
        }
        putServerPins(&hosts, s)
        out["hosts"] = .object(hosts)

        if let servers, !servers.isEmpty {
            out["servers"] = .array(servers)
        } else {
            out["servers"] = .array([.string(s.remoteDns)])
        }
        return out
    }

    /// Answers the core would otherwise have to ask the network for, at a moment
    /// when the network cannot answer. See `SettingsSnapshot.serverPins`.
    private static func putServerPins(_ hosts: inout JSONObject, _ s: SettingsSnapshot) {
        // Sorted so the emitted config is stable; a Swift dictionary has no
        // order of its own and an unstable config is impossible to diff.
        for host in s.serverPins.keys.sorted() {
            guard let ips = s.serverPins[host], !ips.isEmpty else { continue }
            hosts[host] = .array(ips.map { .string($0) })
        }
    }

    // MARK: - outbounds

    private static func directOutbound() -> JSONObject {
        JSONObject([
            ("tag", .string("direct")),
            ("protocol", .string("freedom")),
            ("settings", .object(JSONObject([("domainStrategy", .string("UseIP"))]))),
        ])
    }

    /// Pins an outbound to a physical interface (`IP_BOUND_IF` on darwin). Only
    /// used in TUN mode, where the default route points at our own utun device:
    /// without this the core would dial its server through the tunnel it is
    /// itself feeding and nothing would ever connect.
    private static func bindToInterface(_ outbound: JSONObject, _ iface: String?) -> JSONObject {
        guard let iface, !iface.trimmingCharacters(in: .whitespaces).isEmpty else { return outbound }
        if outbound["protocol"]?.stringValue == "blackhole" { return outbound }

        let stream = outbound["streamSettings"]?.objectValue

        var sockopt = JSONObject()
        if let existing = stream?["sockopt"]?.objectValue {
            for (key, value) in existing.pairs { sockopt[key] = value }
        }
        sockopt["interface"] = .string(iface)

        var newStream = JSONObject()
        if let stream {
            for (key, value) in stream.pairs where key != "sockopt" { newStream[key] = value }
        }
        newStream["sockopt"] = .object(sockopt)

        var out = JSONObject()
        for (key, value) in outbound.pairs where key != "streamSettings" { out[key] = value }
        out["streamSettings"] = .object(newStream)
        return out
    }

    private static func blockOutbound() -> JSONObject {
        JSONObject([
            ("tag", .string("block")),
            ("protocol", .string("blackhole")),
            ("settings", .object(JSONObject([
                ("response", .object(JSONObject([("type", .string("http"))]))),
            ]))),
        ])
    }

    /// Moves this profile's proxy outbound to the front (so it is the routing
    /// default) and normalizes legacy XHTTP keys in every outbound. Falls back
    /// to the first proxy-protocol outbound when the tag is missing, so traffic
    /// never silently defaults to a "direct" outbound.
    private static func reorderOutbounds(_ outbounds: [JSON], proxyTag: String?) -> [JSONObject] {
        let list = outbounds.compactMap { $0.objectValue }.map { normalizeOutbound($0) }
        var index = -1
        if let proxyTag, !proxyTag.trimmingCharacters(in: .whitespaces).isEmpty {
            index = list.firstIndex { $0["tag"]?.stringValue == proxyTag } ?? -1
        }
        if index < 0 {
            index = list.firstIndex {
                guard let proto = $0["protocol"]?.stringValue else { return false }
                return proxyProtocols.contains(proto)
            } ?? -1
        }
        guard index > 0 else { return list }
        var out = [list[index]]
        for (position, object) in list.enumerated() where position != index {
            out.append(object)
        }
        return out
    }

    /// Panels do not always ship direct/block outbounds; our rules reference
    /// both, and a missing tag makes the core refuse to start.
    private static func ensureFallbackOutbounds(_ outbounds: [JSONObject]) -> [JSONObject] {
        let tags = Set(outbounds.compactMap { $0["tag"]?.stringValue })
        if tags.contains("direct"), tags.contains("block") { return outbounds }
        var out = outbounds
        if !tags.contains("direct") { out.append(directOutbound()) }
        if !tags.contains("block") { out.append(blockOutbound()) }
        return out
    }

    private static func normalizeOutbound(_ ob: JSONObject) -> JSONObject {
        var out = JSONObject()
        for (key, value) in ob.pairs {
            if key == "streamSettings", let stream = value.objectValue {
                out["streamSettings"] = .object(normalizeStream(stream))
            } else {
                out[key] = value
            }
        }
        return out
    }

    // MARK: - routing

    private static func buildRouting(_ s: SettingsSnapshot) -> JSONObject {
        var rules: [JSON] = []

        switch s.mode {
        // Everything direct: the core stays up so the switch is instant.
        case AppConfig.modeDirect:
            rules.append(.object(JSONObject([
                ("type", .string("field")),
                ("outboundTag", .string("direct")),
                ("network", .string("tcp,udp")),
            ])))

        // Everything proxied except the local network.
        case AppConfig.modeGlobal:
            addPrivateBypass(&rules)
            if s.blockQuic { addQuicBlock(&rules) }

        // Rule mode: the built-in preset, used when neither the subscription
        // nor the user template supplied routing.
        default:
            if s.routingPreset == AppConfig.routeBypassRu {
                rules.append(.object(JSONObject([
                    ("type", .string("field")),
                    ("outboundTag", .string("direct")),
                    ("port", .string("53")),
                    ("ip", .array([.string(s.directDns)])),
                ])))
            }
            if s.blockQuic { addQuicBlock(&rules) }
            if s.bypassTorrent {
                rules.append(.object(JSONObject([
                    ("type", .string("field")),
                    ("outboundTag", .string("direct")),
                    ("protocol", .array([.string("bittorrent")])),
                ])))
            }
            addPrivateBypass(&rules)
            if s.routingPreset == AppConfig.routeBypassRu {
                rules.append(.object(JSONObject([
                    ("type", .string("field")),
                    ("outboundTag", .string("direct")),
                    ("domain", .array([.string("geosite:category-ru")])),
                ])))
                rules.append(.object(JSONObject([
                    ("type", .string("field")),
                    ("outboundTag", .string("direct")),
                    ("ip", .array([.string("geoip:ru")])),
                ])))
            }
        }

        return JSONObject([
            ("domainStrategy", .string("IPIfNonMatch")),
            ("rules", .array(rules)),
        ])
    }

    private static func addPrivateBypass(_ rules: inout [JSON]) {
        rules.append(.object(JSONObject([
            ("type", .string("field")),
            ("outboundTag", .string("direct")),
            ("ip", .array([.string("geoip:private")])),
        ])))
        rules.append(.object(JSONObject([
            ("type", .string("field")),
            ("outboundTag", .string("direct")),
            ("domain", .array([.string("geosite:private")])),
        ])))
    }

    private static func addQuicBlock(_ rules: inout [JSON]) {
        rules.append(.object(JSONObject([
            ("type", .string("field")),
            ("outboundTag", .string("block")),
            ("network", .string("udp")),
            ("port", .string("443")),
        ])))
    }

    /// Pulls a top-level object (routing/dns) out of a user-pasted template.
    /// Accepts a full config, or a bare routing object when key == "routing".
    private static func extractFromTemplate(_ template: String?, key: String) -> JSONObject? {
        guard let raw = template, !raw.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        guard let object = JSON.parse(raw)?.objectValue else { return nil }
        if let nested = object[key]?.objectValue { return nested }
        if key == "routing", object.has("rules") { return object }
        return nil
    }

    /// Accepts either a bare routing object (`{"rules":[...]}`) or a full Xray
    /// config that contains a "routing" key, and returns the routing object.
    private static func parseRouting(_ routingJson: String?) -> JSONObject? {
        guard let raw = routingJson, !raw.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        guard let object = JSON.parse(raw)?.objectValue else { return nil }
        if object.has("rules") { return object }
        if let routing = object["routing"]?.objectValue { return routing }
        return nil
    }

    // MARK: - proxy outbound

    private static func buildProxyOutbound(_ p: Profile, _ s: SettingsSnapshot) -> JSONObject {
        // When the profile carries the full outbound JSON (xray-json
        // subscription), use it verbatim — only retagged and with legacy XHTTP
        // key names normalized — so complex transports pass through untouched.
        if let raw = p.rawOutbound, !raw.isEmpty, let rebuilt = rebuildFromRaw(raw, s) {
            return rebuilt
        }
        return buildFlattenedOutbound(p, s)
    }

    /// Re-emits a stored outbound with tag "proxy" and normalized XHTTP keys.
    private static func rebuildFromRaw(_ raw: String, _ s: SettingsSnapshot) -> JSONObject? {
        guard let object = JSON.parse(raw)?.objectValue else { return nil }
        let stream = object["streamSettings"]?.objectValue

        // "tag" goes in first and the original is skipped below, so the proxy
        // tag leads the outbound whatever the panel did.
        var out = JSONObject([("tag", .string("proxy"))])
        for (key, value) in object.pairs {
            switch key {
            case "tag":
                continue
            case "streamSettings" where value.objectValue != nil:
                out["streamSettings"] = .object(normalizeStream(value.objectValue!))
            case "settings" where value.objectValue != nil:
                out["settings"] = .object(pinServerAddress(value.objectValue!, stream: stream, s))
            default:
                out[key] = value
            }
        }
        return out
    }

    /// Swaps the server hostname inside a verbatim outbound for the address
    /// resolved before the tunnel went up. Everything else is left exactly as
    /// the subscription sent it — that is the whole point of this path — but the
    /// name has to go: the core would resolve it through its own resolver, which
    /// in TUN mode is reachable only through the connection this dial creates.
    ///
    /// Skipped unless the handshake still knows which name to present. Replacing
    /// the address where no serverName is set would send an IP as the SNI and
    /// break TLS, which is worse than the slow failure it fixes.
    private static func pinServerAddress(
        _ settings: JSONObject,
        stream: JSONObject?,
        _ s: SettingsSnapshot
    ) -> JSONObject {
        guard keepsServerName(stream) else { return settings }
        var out = JSONObject()
        for (key, value) in settings.pairs {
            if key == "vnext" || key == "servers", let entries = value.arrayValue {
                out[key] = .array(entries.map { withPinnedAddress($0, s) })
            } else {
                out[key] = value
            }
        }
        return out
    }

    private static func withPinnedAddress(_ entry: JSON, _ s: SettingsSnapshot) -> JSON {
        guard let object = entry.objectValue else { return entry }
        guard let host = object["address"]?.stringValue else { return entry }
        guard let pinned = s.serverPins[host]?.first else { return entry }

        var out = JSONObject()
        for (key, value) in object.pairs {
            out[key] = key == "address" ? .string(pinned) : value
        }
        return .object(out)
    }

    /// `pinServerAddress` applied to a whole outbound object.
    private static func pinOutbound(_ o: JSONObject, _ s: SettingsSnapshot) -> JSONObject {
        guard let settings = o["settings"]?.objectValue else { return o }
        let pinned = pinServerAddress(settings, stream: o["streamSettings"]?.objectValue, s)
        if pinned == settings { return o }

        var out = JSONObject()
        for (key, value) in o.pairs {
            out[key] = key == "settings" ? .object(pinned) : value
        }
        return out
    }

    /// True when the handshake carries its own name, or needs none at all.
    private static func keepsServerName(_ stream: JSONObject?) -> Bool {
        let security = stream?["security"]?.stringValue
        if security == nil || security == "none" { return true }
        let sec = (stream?["tlsSettings"] ?? stream?["realitySettings"])?.objectValue
        guard let name = sec?["serverName"]?.stringValue else { return false }
        return !name.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private static func normalizeStream(_ ss: JSONObject) -> JSONObject {
        var out = JSONObject()
        for (key, value) in ss.pairs {
            if key == "xhttpSettings", let xh = value.objectValue {
                out["xhttpSettings"] = .object(normalizeXhttp(xh))
            } else {
                out[key] = value
            }
        }
        return out
    }

    /// Renames legacy XHTTP keys to the current Xray-core naming (the June 2026
    /// rename was a cosmetic config-key change with identical wire behaviour),
    /// so a modern core reads obfuscation params generated by an older server.
    /// The `extra` object is itself a SplitHTTPConfig, so recurse into it.
    private static func normalizeXhttp(_ xh: JSONObject) -> JSONObject {
        var out = JSONObject()
        for (key, value) in xh.pairs {
            let renamed: String
            switch key {
            case "sessionKey": renamed = "sessionIDKey"
            case "sessionPlacement": renamed = "sessionIDPlacement"
            case "sessionTable": renamed = "sessionIDTable"
            case "sessionLength": renamed = "sessionIDLength"
            default: renamed = key
            }
            if key == "extra", let nested = value.objectValue {
                out[renamed] = .object(normalizeXhttp(nested))
            } else {
                out[renamed] = value
            }
        }
        return out
    }

    private static func buildFlattenedOutbound(_ p: Profile, _ s: SettingsSnapshot) -> JSONObject {
        var settings = JSONObject()

        switch p.proto {
        case "vless", "vmess":
            var user = JSONObject([
                ("id", .string(p.uuid)),
                ("level", .int(8)),
            ])
            if p.proto == "vless" {
                user["encryption"] = .string(nonBlank(p.method) ?? "none")
                // xtls-rprx-vision only works over raw TCP; never emit it for xhttp
                if let flow = nonBlank(p.flow), p.network == "tcp" {
                    user["flow"] = .string(flow)
                }
            } else {
                user["alterId"] = .int(0)
                user["security"] = .string(nonBlank(p.method) ?? "auto")
            }
            settings["vnext"] = .array([.object(JSONObject([
                ("address", .string(serverAddress(p, s))),
                ("port", .int(p.port)),
                ("users", .array([.object(user)])),
            ]))])

        case "trojan":
            settings["servers"] = .array([.object(JSONObject([
                ("address", .string(serverAddress(p, s))),
                ("port", .int(p.port)),
                ("password", .string(p.uuid)),
                ("level", .int(8)),
            ]))])

        case "shadowsocks":
            settings["servers"] = .array([.object(JSONObject([
                ("address", .string(serverAddress(p, s))),
                ("port", .int(p.port)),
                ("method", .string(nonBlank(p.method) ?? "aes-256-gcm")),
                ("password", .string(p.uuid)),
                ("level", .int(8)),
            ]))])

        default:
            break
        }

        return JSONObject([
            ("tag", .string("proxy")),
            ("protocol", .string(p.proto)),
            ("settings", .object(settings)),
            ("streamSettings", .object(buildStreamSettings(p))),
            ("mux", .object(JSONObject([
                // Mux is incompatible with xhttp; keep it off there regardless
                // of the toggle.
                ("enabled", .bool(s.mux && p.network != "xhttp")),
                ("concurrency", .int(8)),
            ]))),
        ])
    }

    private static func buildStreamSettings(_ p: Profile) -> JSONObject {
        let network = p.network.isEmpty ? "tcp" : p.network
        var out = JSONObject([("network", .string(network))])

        // sniExt: the transport-derived SNI candidate (host header / authority).
        var sniExt: String?

        switch network {
        case "ws":
            var ws = JSONObject([("path", .string(nonBlank(p.path) ?? "/"))])
            if let host = nonBlank(p.host) {
                ws["headers"] = .object(JSONObject([("Host", .string(host))]))
            }
            out["wsSettings"] = .object(ws)
            sniExt = p.host

        case "xhttp":
            var xh = JSONObject([
                ("host", .string(p.host ?? "")),
                ("path", .string(nonBlank(p.path) ?? "/")),
            ])
            if let mode = nonBlank(p.xhttpMode) { xh["mode"] = .string(mode) }
            if let extra = nonBlank(p.xhttpExtra), let parsed = JSON.parse(extra) {
                xh["extra"] = parsed
            }
            out["xhttpSettings"] = .object(xh)
            sniExt = p.host

        case "grpc":
            out["grpcSettings"] = .object(JSONObject([
                ("serviceName", .string(p.serviceName ?? "")),
                ("authority", .string(p.authority ?? "")),
                ("multiMode", .bool(p.grpcMode == "multi")),
                ("idle_timeout", .int(60)),
                ("health_check_timeout", .int(20)),
            ]))
            sniExt = p.authority

        case "httpupgrade":
            out["httpupgradeSettings"] = .object(JSONObject([
                ("host", .string(p.host ?? "")),
                ("path", .string(nonBlank(p.path) ?? "/")),
            ]))
            sniExt = p.host

        case "kcp":
            var kcp = JSONObject([
                ("header", .object(JSONObject([("type", .string(nonBlank(p.headerType) ?? "none"))]))),
            ])
            if let seed = nonBlank(p.seed) { kcp["seed"] = .string(seed) }
            out["kcpSettings"] = .object(kcp)

        case "h2", "http":
            // Reassigning keeps the original position, so "network" still leads.
            out["network"] = .string("http")
            let hosts = (p.host ?? "")
                .split(separator: ",", omittingEmptySubsequences: false)
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
            out["httpSettings"] = .object(JSONObject([
                ("path", .string(nonBlank(p.path) ?? "/")),
                ("host", .array(hosts.map { .string($0) })),
            ]))
            sniExt = hosts.first

        default:
            // raw tcp, optionally with http header obfuscation
            if p.headerType == "http" {
                var headers = JSONObject()
                if let host = nonBlank(p.host) {
                    headers["Host"] = .array(
                        host.split(separator: ",", omittingEmptySubsequences: false)
                            .map { .string($0.trimmingCharacters(in: .whitespaces)) }
                    )
                }
                out["tcpSettings"] = .object(JSONObject([
                    ("header", .object(JSONObject([
                        ("type", .string("http")),
                        ("request", .object(JSONObject([
                            ("path", .array([.string(nonBlank(p.path) ?? "/")])),
                            ("headers", .object(headers)),
                        ]))),
                    ]))),
                ]))
                sniExt = p.host?.split(separator: ",", omittingEmptySubsequences: false)
                    .first
                    .map { $0.trimmingCharacters(in: .whitespaces) }
            }
        }

        // Security (TLS / REALITY) — same settings object shape for both.
        let security = p.security.flatMap { $0 == "tls" || $0 == "reality" ? $0 : nil }
        out["security"] = .string(security ?? "none")

        if let security {
            var sec = JSONObject()
            if let sni = nonBlank(resolveSni(p, sniExt)) { sec["serverName"] = .string(sni) }
            sec["allowInsecure"] = .bool(p.allowInsecure)
            let fingerprint = nonBlank(p.fingerprint) ?? (security == "reality" ? "chrome" : nil)
            if let fingerprint { sec["fingerprint"] = .string(fingerprint) }
            if let alpn = nonBlank(p.alpn) {
                sec["alpn"] = .array(
                    alpn.split(separator: ",", omittingEmptySubsequences: false)
                        .map { $0.trimmingCharacters(in: .whitespaces) }
                        .filter { !$0.isEmpty }
                        .map { .string($0) }
                )
            }
            if security == "reality" {
                if let publicKey = nonBlank(p.publicKey) { sec["publicKey"] = .string(publicKey) }
                // Deliberately not blank-checked, matching the original: an
                // empty shortId is a meaningful REALITY value.
                if let shortId = p.shortId { sec["shortId"] = .string(shortId) }
                if let spiderX = p.spiderX { sec["spiderX"] = .string(spiderX) }
            }
            out[security == "tls" ? "tlsSettings" : "realitySettings"] = .object(sec)
        }

        return out
    }

    /// The address the core actually dials. A hostname is replaced by the
    /// address resolved before the tunnel went up: left as a name, the core
    /// would look it up itself, and in TUN mode its resolver sits behind the
    /// very connection this dial is meant to open. Only bare-IP servers escaped
    /// that deadlock.
    ///
    /// Safe for TLS: `resolveSni` derives the SNI from the profile, not from
    /// this field, so the handshake still presents the hostname.
    private static func serverAddress(_ p: Profile, _ s: SettingsSnapshot) -> String {
        s.serverPins[p.server]?.first ?? p.server
    }

    /// Final SNI: explicit sni param, else a domain-valued transport host, else
    /// the server if it is a domain, else the transport host. Mirrors v2rayNG.
    private static func resolveSni(_ p: Profile, _ sniExt: String?) -> String? {
        if let sni = nonBlank(p.sni) { return sni }
        if let sniExt, !sniExt.isEmpty, isDomain(sniExt) { return sniExt }
        if !p.server.isEmpty, isDomain(p.server) { return p.server }
        return sniExt
    }

    private static func isDomain(_ value: String) -> Bool {
        let v = value.trimmingCharacters(in: .whitespaces)
        if v.isEmpty { return false }
        if Utils.isIPAddress(v) { return false }
        return v.contains(".") && v.contains { $0.isLetter }
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return value
    }
}

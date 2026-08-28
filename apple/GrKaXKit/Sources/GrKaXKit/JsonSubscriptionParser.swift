import Foundation

/// Parses an xray-json / v2ray-json subscription body (as served by panels like
/// Remnawave) into profiles plus an optional routing template.
///
/// Supported shapes:
///  - a full config object: `{ "outbounds": [...], "routing": {...} }`
///  - an array of full configs
///  - an array of bare outbound objects
public enum JsonSubscriptionParser {

    public struct Result: Equatable, Sendable {
        public let profiles: [Profile]
        public let routingJson: String?
    }

    private static let proxyProtocols: Set<String> = ["vless", "vmess", "trojan", "shadowsocks"]

    public static func looksLikeJson(_ body: String) -> Bool {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.hasPrefix("{") || trimmed.hasPrefix("[")
    }

    public static func parse(_ body: String) -> Result? {
        guard let root = JSON.parse(body.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            return nil
        }

        var profiles: [Profile] = []
        var routing: String?

        /// The whole config is kept per profile so the panel's routing template
        /// (with its original outbound tags) can be applied verbatim.
        func handleConfig(_ object: JSONObject) {
            guard let outbounds = object["outbounds"]?.arrayValue else { return }
            if routing == nil, let node = object["routing"] {
                routing = node.compact
            }
            let fullConfig = JSON.object(object).compact
            // xray-json (Happ/Remnawave) carries the friendly server name here.
            let remarks = object["remarks"]?.nonBlankString
            var index = 0
            for entry in outbounds {
                guard let outbound = entry.objectValue else { continue }
                guard let proto = outbound["protocol"]?.stringValue,
                      proxyProtocols.contains(proto) else { continue }
                guard var profile = outboundToProfile(outbound) else { continue }

                profile.fullConfig = fullConfig
                profile.proxyTag = outbound["tag"]?.stringValue
                if let remarks {
                    profile.name = index == 0
                        ? remarks
                        : "\(remarks) · \(profile.proxyTag ?? String(index + 1))"
                }
                profiles.append(profile)
                index += 1
            }
        }

        switch root {
        case .object(let object):
            if object.has("outbounds") {
                handleConfig(object)
            } else if object.has("protocol") {
                if let profile = outboundToProfile(object) { profiles.append(profile) }
            } else {
                return nil
            }

        case .array(let items):
            for item in items {
                guard let object = item.objectValue else { continue }
                if object.has("outbounds") {
                    handleConfig(object)
                } else if object.has("protocol") {
                    if let profile = outboundToProfile(object) { profiles.append(profile) }
                }
            }

        default:
            return nil
        }

        if profiles.isEmpty { return nil }
        return Result(profiles: profiles, routingJson: routing)
    }

    private static func outboundToProfile(_ outbound: JSONObject) -> Profile? {
        guard let proto = outbound["protocol"]?.stringValue, proxyProtocols.contains(proto) else {
            return nil
        }
        guard let settings = outbound["settings"]?.objectValue else { return nil }

        var profile = Profile(proto: proto)

        switch proto {
        case "vless", "vmess":
            guard let vnext = settings["vnext"]?.arrayValue?.first?.objectValue else { return nil }
            guard let address = vnext["address"]?.nonBlankString else { return nil }
            guard let port = vnext["port"]?.intValue else { return nil }
            guard let user = vnext["users"]?.arrayValue?.first?.objectValue else { return nil }
            guard let id = user["id"]?.nonBlankString else { return nil }
            profile.server = address
            profile.port = port
            profile.uuid = id
            if proto == "vless" {
                profile.method = user["encryption"]?.nonBlankString ?? "none"
                profile.flow = user["flow"]?.nonBlankString
            } else {
                profile.method = user["security"]?.nonBlankString ?? "auto"
            }

        case "trojan", "shadowsocks":
            guard let server = settings["servers"]?.arrayValue?.first?.objectValue else { return nil }
            guard let address = server["address"]?.nonBlankString else { return nil }
            guard let port = server["port"]?.intValue else { return nil }
            guard let password = server["password"]?.nonBlankString else { return nil }
            profile.server = address
            profile.port = port
            profile.uuid = password
            if proto == "shadowsocks" {
                profile.method = server["method"]?.nonBlankString
            }

        default:
            return nil
        }

        applyStreamSettings(&profile, outbound["streamSettings"]?.objectValue)

        // Preserve the exact outbound so non-standard transport fields (XHTTP
        // obfuscation, extra, noSSEHeader, xmux, …) survive verbatim. This is
        // the whole reason the JSON type keeps key order.
        profile.rawOutbound = JSON.object(outbound).compact

        let tag = outbound["tag"]?.nonBlankString
        profile.name = tag.flatMap { $0 == "proxy" || $0 == "out" ? nil : $0 }
            ?? "\(profile.server):\(profile.port)"
        return profile
    }

    private static func applyStreamSettings(_ p: inout Profile, _ ss: JSONObject?) {
        guard let ss else { return }
        p.network = ss["network"]?.nonBlankString ?? "tcp"
        if p.network == "http" { p.network = "h2" }

        switch p.network {
        case "ws":
            if let ws = ss["wsSettings"]?.objectValue {
                p.path = ws["path"]?.nonBlankString
                p.host = ws["host"]?.nonBlankString ?? ws["headers"]?["Host"]?.nonBlankString
            }

        case "xhttp":
            if let xh = ss["xhttpSettings"]?.objectValue {
                p.path = xh["path"]?.nonBlankString
                p.host = xh["host"]?.nonBlankString
                p.xhttpMode = xh["mode"]?.nonBlankString
                if let extra = xh["extra"], extra.objectValue != nil {
                    p.xhttpExtra = extra.compact
                }
            }

        case "grpc":
            if let g = ss["grpcSettings"]?.objectValue {
                p.serviceName = g["serviceName"]?.nonBlankString
                p.authority = g["authority"]?.nonBlankString
                // Compared as text, so both `true` and `"true"` count — which
                // is what `jsonPrimitive.content` does upstream, and panels
                // write it both ways.
                p.grpcMode = g["multiMode"]?.stringValue == "true" ? "multi" : nil
            }

        case "httpupgrade":
            if let h = ss["httpupgradeSettings"]?.objectValue {
                p.path = h["path"]?.nonBlankString
                p.host = h["host"]?.nonBlankString
            }

        case "h2":
            if let h = ss["httpSettings"]?.objectValue {
                p.path = h["path"]?.nonBlankString
                p.host = h["host"]?.arrayValue?.compactMap { $0.stringValue }.joined(separator: ",")
            }

        case "tcp":
            if let tcp = ss["tcpSettings"]?.objectValue {
                p.headerType = tcp["header"]?["type"]?.nonBlankString
            }

        default:
            break
        }

        p.security = ss["security"]?.nonBlankString.flatMap { $0 == "tls" || $0 == "reality" ? $0 : nil }
        if let sec = (ss["tlsSettings"] ?? ss["realitySettings"])?.objectValue {
            p.sni = sec["serverName"]?.nonBlankString
            p.fingerprint = sec["fingerprint"]?.nonBlankString
            p.allowInsecure = sec["allowInsecure"]?.stringValue == "true"
            p.alpn = sec["alpn"]?.arrayValue?.compactMap { $0.stringValue }.joined(separator: ",")
            p.publicKey = sec["publicKey"]?.nonBlankString
            p.shortId = sec["shortId"]?.nonBlankString
            p.spiderX = sec["spiderX"]?.nonBlankString
        }
    }
}

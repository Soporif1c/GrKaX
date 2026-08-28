import Foundation

/// Parses share links (vless:// vmess:// trojan:// ss://) into `Profile`s,
/// following the same query-parameter conventions as v2rayNG/Xray share links —
/// including xhttp (type=xhttp&mode=…&extra=…) and REALITY (pbk/sid/spx/fp).
///
/// Kept byte-for-byte in step with the Android client's parser: a link that
/// imports there must import here identically. The URI split is hand-rolled for
/// that reason — `URLComponents` disagrees with `java.net.URI` about userinfo,
/// about which decoding applies to a fragment, and about hosts it considers
/// malformed, and each disagreement would silently change how a link imports.
public enum LinkParser {

    /// Parses raw text: a list of links, or a base64-encoded subscription
    /// payload.
    public static func parseBatch(_ text: String) -> [Profile] {
        var content = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !content.contains("://") {
            content = Utils.tryDecodeBase64(content) ?? content
        }
        return content
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .compactMap { parse($0) }
    }

    public static func parse(_ link: String) -> Profile? {
        if link.hasPrefix("vless://") { return parseVless(link) }
        if link.hasPrefix("vmess://") { return parseVmess(link) }
        if link.hasPrefix("trojan://") { return parseTrojan(link) }
        if link.hasPrefix("ss://") { return parseShadowsocks(link) }
        return nil
    }

    // MARK: - vless

    private static func parseVless(_ link: String) -> Profile? {
        guard let uri = ShareURI(sanitize(link)) else { return nil }
        guard let host = uri.host, uri.port > 0 else { return nil }
        guard let userInfo = uri.userInfo else { return nil }
        guard let rawQuery = uri.rawQuery else { return nil }

        var profile = Profile(
            proto: "vless",
            server: host,
            port: uri.port,
            uuid: userInfo,
            rawLink: link
        )
        let q = queryMap(rawQuery)
        profile.method = q["encryption"] ?? "none"
        applyCommonQuery(&profile, q)
        profile.name = fragmentName(uri, host: host, port: uri.port)
        return profile
    }

    // MARK: - vmess

    private static func parseVmess(_ link: String) -> Profile? {
        let body = String(link.dropFirst("vmess://".count))
        guard let decoded = Utils.tryDecodeBase64(body) else { return nil }
        guard let object = try? JSONSerialization.jsonObject(with: Data(decoded.utf8)) as? [String: Any] else {
            return nil
        }

        guard let server = str(object, "add") else { return nil }
        guard let port = str(object, "port").flatMap({ Int($0) }) else { return nil }
        guard let uuid = str(object, "id") else { return nil }

        var profile = Profile(
            proto: "vmess",
            server: server,
            port: port,
            uuid: uuid,
            rawLink: link
        )
        profile.name = str(object, "ps") ?? "\(server):\(port)"
        profile.method = str(object, "scy") ?? "auto"
        profile.network = str(object, "net") ?? "tcp"
        profile.headerType = str(object, "type")
        profile.host = str(object, "host")
        profile.path = str(object, "path")
        if profile.network == "grpc" {
            profile.serviceName = str(object, "path")
            profile.grpcMode = str(object, "type")
        }
        if profile.network == "xhttp" {
            profile.xhttpMode = str(object, "type")
        }
        let tls = str(object, "tls")
        profile.security = (tls == "tls" || tls == "reality") ? tls : nil
        profile.sni = str(object, "sni")
        profile.alpn = str(object, "alpn")
        profile.fingerprint = str(object, "fp")
        return profile
    }

    /// vmess payloads are inconsistent about quoting — `"port":443` and
    /// `"port":"443"` both occur in the wild, and Kotlin's `jsonPrimitive
    /// .content` flattens the two. Blank is treated as absent, as it is there.
    private static func str(_ object: [String: Any], _ key: String) -> String? {
        guard let value = object[key], !(value is NSNull) else { return nil }
        let text: String
        switch value {
        case let string as String:
            text = string
        case let number as NSNumber:
            // Keep 443 rather than 443.0 — these are always integers in practice.
            text = number.stringValue
        default:
            return nil
        }
        return text.trimmingCharacters(in: .whitespaces).isEmpty ? nil : text
    }

    // MARK: - trojan

    private static func parseTrojan(_ link: String) -> Profile? {
        guard let uri = ShareURI(sanitize(link)) else { return nil }
        guard let host = uri.host, uri.port > 0 else { return nil }
        guard let password = uri.userInfo else { return nil }

        var profile = Profile(
            proto: "trojan",
            server: host,
            port: uri.port,
            uuid: password,
            rawLink: link
        )
        let q = queryMap(uri.rawQuery ?? "")
        applyCommonQuery(&profile, q)
        // trojan defaults to TLS when the link does not say otherwise
        if profile.security == nil, q["security"] != "none" {
            profile.security = "tls"
        }
        profile.name = fragmentName(uri, host: host, port: uri.port)
        return profile
    }

    // MARK: - shadowsocks

    private static func parseShadowsocks(_ link: String) -> Profile? {
        var body = String(link.dropFirst("ss://".count))
        var name = ""
        if let hash = body.firstIndex(of: "#") {
            name = Utils.urlDecode(String(body[body.index(after: hash)...]))
            body = String(body[..<hash])
        }
        if let question = body.firstIndex(of: "?") {
            body = String(body[..<question])
        }

        if !body.contains("@") {
            guard let decoded = Utils.tryDecodeBase64(body) else { return nil }
            body = decoded
        }
        guard let at = body.lastIndex(of: "@") else { return nil }

        var userPart = Utils.urlDecode(String(body[..<at]))
        let hostPart = String(body[body.index(after: at)...])

        if !userPart.contains(":") {
            guard let decoded = Utils.tryDecodeBase64(userPart) else { return nil }
            userPart = decoded
        }
        guard let colon = userPart.firstIndex(of: ":") else { return nil }
        let method = String(userPart[..<colon])
        let password = String(userPart[userPart.index(after: colon)...])

        guard let portColon = hostPart.lastIndex(of: ":") else { return nil }
        let server = String(hostPart[..<portColon])
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        guard let port = Int(hostPart[hostPart.index(after: portColon)...]) else { return nil }

        return Profile(
            name: name.isEmpty ? "\(server):\(port)" : name,
            proto: "shadowsocks",
            server: server,
            port: port,
            uuid: password,
            method: method,
            rawLink: link
        )
    }

    // MARK: - shared helpers

    private static func sanitize(_ link: String) -> String {
        link.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: " ", with: "%20")
            .replacingOccurrences(of: "|", with: "%7C")
    }

    private static func queryMap(_ rawQuery: String) -> [String: String] {
        var map: [String: String] = [:]
        for pair in rawQuery.split(separator: "&", omittingEmptySubsequences: false) {
            if pair.isEmpty { continue }
            guard let equals = pair.firstIndex(of: "=") else { continue }
            // `idx <= 0` upstream: a pair starting with '=' has no key.
            if equals == pair.startIndex { continue }
            let key = String(pair[..<equals])
            map[key] = Utils.urlDecode(String(pair[pair.index(after: equals)...]))
        }
        return map
    }

    /// `URI.getFragment()` percent-decodes but, unlike `URLDecoder`, leaves `+`
    /// alone — a node called `Berlin+Fast` keeps its plus.
    private static func fragmentName(_ uri: ShareURI, host: String, port: Int) -> String {
        guard let fragment = uri.fragment, !fragment.isEmpty else { return "\(host):\(port)" }
        return fragment
    }

    private static func applyCommonQuery(_ p: inout Profile, _ q: [String: String]) {
        p.network = q["type"].flatMap { $0.isEmpty ? nil : $0 } ?? "tcp"
        if p.network == "http" { p.network = "h2" }
        p.headerType = q["headerType"]
        p.host = q["host"]
        p.path = q["path"]
        p.seed = q["seed"]
        p.serviceName = q["serviceName"]
        p.authority = q["authority"]
        if p.network == "grpc" {
            p.grpcMode = q["mode"]
        }
        if p.network == "xhttp" {
            p.xhttpMode = q["mode"]
            p.xhttpExtra = q["extra"]
        }

        p.security = q["security"].flatMap { $0 == "tls" || $0 == "reality" ? $0 : nil }
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

/// The slice of `java.net.URI` these links need, split by hand so the
/// behaviour is pinned rather than inherited from Foundation.
struct ShareURI {
    let userInfo: String?
    let host: String?
    let port: Int
    let rawQuery: String?
    let fragment: String?

    init?(_ text: String) {
        guard let schemeEnd = text.range(of: "://") else { return nil }
        var rest = String(text[schemeEnd.upperBound...])

        // Fragment first, then query: '#' wins over a '?' that follows it.
        var fragmentPart: String?
        if let hash = rest.firstIndex(of: "#") {
            fragmentPart = String(rest[rest.index(after: hash)...])
            rest = String(rest[..<hash])
        }
        var queryPart: String?
        if let question = rest.firstIndex(of: "?") {
            queryPart = String(rest[rest.index(after: question)...])
            rest = String(rest[..<question])
        }
        // Anything from the first '/' on is the path, which these schemes carry
        // but never read.
        if let slash = rest.firstIndex(of: "/") {
            rest = String(rest[..<slash])
        }

        var authority = rest
        var user: String?
        if let at = authority.lastIndex(of: "@") {
            user = ShareURI.percentDecode(String(authority[..<at]))
            authority = String(authority[authority.index(after: at)...])
        }

        var hostPart = authority
        var parsedPort = -1
        if hostPart.hasPrefix("["), let close = hostPart.firstIndex(of: "]") {
            // IPv6 literal: the port colon is the one after the bracket.
            //
            // The brackets stay part of the host. `URI.getHost()` reports them
            // that way, and the config builder passes the value straight into
            // `address`, where Xray wants the bracketed form.
            let literal = String(hostPart[...close])
            let after = hostPart[hostPart.index(after: close)...]
            if after.hasPrefix(":"), let value = Int(after.dropFirst()) {
                parsedPort = value
            }
            hostPart = literal
        } else if let colon = hostPart.lastIndex(of: ":") {
            if let value = Int(hostPart[hostPart.index(after: colon)...]) {
                parsedPort = value
                hostPart = String(hostPart[..<colon])
            }
        }

        // `URI.getHost()` hands back null for an authority it cannot make sense
        // of, and every caller treats that as "not a link".
        let invalid = CharacterSet(charactersIn: " @/\\?#")
        if hostPart.isEmpty || hostPart.rangeOfCharacter(from: invalid) != nil {
            self.host = nil
        } else {
            self.host = hostPart
        }

        self.userInfo = user
        self.port = parsedPort
        self.rawQuery = queryPart
        self.fragment = fragmentPart.map(ShareURI.percentDecode)
    }

    /// Percent-decoding only — no `+` handling. This is what `URI` does to
    /// userinfo and fragments, and it differs from `URLDecoder`, which query
    /// values go through.
    static func percentDecode(_ text: String) -> String {
        text.removingPercentEncoding ?? text
    }
}

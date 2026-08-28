import Foundation

/// Fetches a subscription and replaces the profiles it owns.
public enum SubscriptionManager {

    public struct UpdateResult: Sendable {
        public let ok: Bool
        public let count: Int
        public let error: String?

        static func success(_ count: Int) -> UpdateResult {
            UpdateResult(ok: true, count: count, error: nil)
        }

        static func failure(_ message: String) -> UpdateResult {
            UpdateResult(ok: false, count: 0, error: message)
        }
    }

    /// Display version, used in the default User-Agent.
    public static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
    }

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 60
        return URLSession(configuration: configuration)
    }()

    @MainActor
    public static func update(_ sub: Subscription, store: Store = .shared) async -> UpdateResult {
        guard let url = URL(string: sub.url), url.scheme != nil else {
            return .failure("Некорректный адрес подписки")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(userAgent(for: sub), forHTTPHeaderField: "User-Agent")
        request.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        // Device headers Remnawave "Response Rules" match on. The OS value is
        // what decides which template the panel returns, so a macOS rule on the
        // panel side is what you want here.
        request.setValue("macOS", forHTTPHeaderField: "x-device-os")
        request.setValue(osVersion, forHTTPHeaderField: "x-ver-os")
        request.setValue("Mac", forHTTPHeaderField: "x-device-model")
        request.setValue(Locale.current.identifier(.bcp47), forHTTPHeaderField: "x-device-locale")
        if store.hwidEnabled {
            request.setValue(Utils.hwid, forHTTPHeaderField: "x-hwid")
        }

        let data: Data
        let response: HTTPURLResponse
        do {
            let (payload, raw) = try await session.data(for: request)
            guard let http = raw as? HTTPURLResponse else {
                return .failure("Неожиданный ответ сервера")
            }
            data = payload
            response = http
        } catch {
            return .failure(error.localizedDescription)
        }

        guard (200...299).contains(response.statusCode) else {
            return .failure("HTTP \(response.statusCode)")
        }
        let body = String(decoding: data, as: UTF8.self)
        guard !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return .failure("Пустой ответ")
        }

        // Prefer an xray-json body (Remnawave); fall back to link lists.
        var routingJson: String?
        var profiles: [Profile]
        if JsonSubscriptionParser.looksLikeJson(body), let parsed = JsonSubscriptionParser.parse(body) {
            routingJson = parsed.routingJson
            profiles = parsed.profiles
        } else {
            profiles = LinkParser.parseBatch(body)
        }

        guard !profiles.isEmpty else {
            return .failure("В ответе нет серверов")
        }
        for index in profiles.indices { profiles[index].subId = sub.id }

        var updated = sub
        updated.routingJson = routingJson
        updated.rawBody = String(body.prefix(200_000)) // keep for inspection (bounded)

        store.replaceSubscriptionProfiles(subId: sub.id, newProfiles: profiles)

        if let info = response.value(forHTTPHeaderField: "subscription-userinfo") {
            applyUserInfo(&updated, info)
        }
        if let title = response.value(forHTTPHeaderField: "profile-title"),
           updated.name.trimmingCharacters(in: .whitespaces).isEmpty {
            updated.name = decodeProfileTitle(title)
        }
        updated.lastUpdate = Int64(Date().timeIntervalSince1970 * 1000)
        store.saveSubscription(updated)

        return .success(profiles.count)
    }

    @MainActor
    public static func updateAll(store: Store = .shared) async -> [(Subscription, UpdateResult)] {
        var out: [(Subscription, UpdateResult)] = []
        for sub in store.subscriptions {
            out.append((sub, await update(sub, store: store)))
        }
        return out
    }

    /// Identify as ourselves by default.
    ///
    /// Panels that serve different formats per client can be satisfied by
    /// setting a per-subscription User-Agent; a bare "happ" is expanded to a
    /// full Happ UA so a "user-agent contains happ" rule on the panel matches.
    static func userAgent(for sub: Subscription) -> String {
        guard let raw = sub.userAgent?.trimmingCharacters(in: .whitespaces), !raw.isEmpty else {
            return "GrKaX/\(appVersion)"
        }
        if raw.lowercased() == "happ" { return "Happ/3.13.0" }
        return raw
    }

    private static var osVersion: String {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        return "\(version.majorVersion).\(version.minorVersion).\(version.patchVersion)"
    }

    /// Parses `upload=123; download=456; total=789; expire=1700000000`.
    static func applyUserInfo(_ sub: inout Subscription, _ header: String) {
        for part in header.split(separator: ";") {
            let pair = part.split(separator: "=", maxSplits: 1).map {
                $0.trimmingCharacters(in: .whitespaces)
            }
            guard pair.count == 2 else { continue }
            // Panels send these as plain integers, but some send `1.7e9`; the
            // JVM parsed as Double for that reason and this matches it.
            guard let value = Double(pair[1]).map({ Int64($0) }) else { continue }
            switch pair[0].lowercased() {
            case "upload": sub.upload = value
            case "download": sub.download = value
            case "total": sub.total = value
            case "expire": sub.expire = value
            default: break
            }
        }
    }

    static func decodeProfileTitle(_ title: String) -> String {
        let prefix = "base64:"
        guard title.hasPrefix(prefix) else { return title }
        return Utils.tryDecodeBase64(String(title.dropFirst(prefix.count))) ?? title
    }
}

import Foundation
import Observation

/// One heterogeneous settings value, matching the `JsonPrimitive` map the
/// Compose store persists.
///
/// Reads are lenient in the same way kotlinx's `booleanOrNull` and `intOrNull`
/// are: a value written as a string still parses as the type being asked for,
/// so a hand-edited settings file — or one written by an older build that
/// quoted its numbers — keeps loading.
enum SettingValue: Codable, Equatable, Sendable {
    case string(String)
    case bool(Bool)
    case int(Int)
    case int64(Int64)

    init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let value = try? container.decode(Bool.self) { self = .bool(value); return }
        if let value = try? container.decode(Int.self) { self = .int(value); return }
        if let value = try? container.decode(Int64.self) { self = .int64(value); return }
        if let value = try? container.decode(String.self) { self = .string(value); return }
        throw DecodingError.dataCorruptedError(
            in: container,
            debugDescription: "unsupported settings value"
        )
    }

    func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .int(let value): try container.encode(value)
        case .int64(let value): try container.encode(value)
        }
    }

    var stringValue: String? {
        switch self {
        case .string(let value): value
        case .bool(let value): String(value)
        case .int(let value): String(value)
        case .int64(let value): String(value)
        }
    }

    var boolValue: Bool? {
        switch self {
        case .bool(let value): value
        case .string(let value): Bool(value)
        case .int(let value): value != 0
        case .int64(let value): value != 0
        }
    }

    var intValue: Int? {
        switch self {
        case .int(let value): value
        case .int64(let value): Int(exactly: value)
        case .string(let value): Int(value)
        case .bool: nil
        }
    }

    var int64Value: Int64? {
        switch self {
        case .int(let value): Int64(value)
        case .int64(let value): value
        case .string(let value): Int64(value)
        case .bool: nil
        }
    }
}

/// Facts about the network plumbing that the config builder needs but the store
/// cannot know on its own.
///
/// This is the seam the tunnel implementation plugs into. Today it is answered
/// by the hand-built utun; a `NEPacketTunnelProvider` answers it with nothing at
/// all, because the system keeps the provider's own sockets out of the tunnel
/// and neither pin is needed any more.
@MainActor
public protocol NetworkFacts: AnyObject {
    /// Physical interface every outbound is pinned to while a tunnel is up.
    var bindInterface: String? { get }
    /// hostname → IPv4 answers resolved before the tunnel closed the door.
    func serverPins(for hosts: [String]) -> [String: [String]]
}

/// Plain-JSON replacement for the Android client's MMKV store. Three files in
/// the app data dir: profiles (ordered), subscriptions, settings.
@MainActor
@Observable
public final class Store {

    public static let shared = Store()

    public private(set) var profiles: [Profile] = []
    public private(set) var subscriptions: [Subscription] = []
    public private(set) var selectedID: String?

    /// Set by the app once a tunnel implementation exists.
    @ObservationIgnored
    public weak var networkFacts: (any NetworkFacts)?

    /// Deliberately *not* `@ObservationIgnored`.
    ///
    /// Every setting is a computed property over this dictionary, so this is
    /// the only stored property SwiftUI can observe them through. Ignoring it
    /// makes each toggle and picker in Settings look dead: the value is written
    /// and persisted, the view never re-renders, and the control springs back.
    private var settings: [String: SettingValue] = [:]

    private var profilesFile: URL { Paths.dataDir.appending(path: "profiles.json") }
    private var subsFile: URL { Paths.dataDir.appending(path: "subscriptions.json") }
    private var settingsFile: URL { Paths.dataDir.appending(path: "settings.json") }

    public init() {}

    public func load() {
        Paths.ensure(Paths.dataDir)
        importFromComposeIfEmpty()
        loadSettings()
        profiles = readList(profilesFile)
        subscriptions = readList(subsFile)
        selectedID = string("selected_profile", "").isEmpty ? nil : string("selected_profile", "")
    }

    /// Seeds a fresh install from the Compose client's directory, by copying.
    ///
    /// A copy, never a move or a shared handle: the Compose build stays
    /// installed and working, and nothing this build does can reach its files.
    private func importFromComposeIfEmpty() {
        let fm = FileManager.default
        guard !fm.fileExists(atPath: profilesFile.path) else { return }
        guard fm.fileExists(atPath: Paths.composeDataDir.path) else { return }

        for name in ["profiles.json", "subscriptions.json", "settings.json"] {
            let source = Paths.composeDataDir.appending(path: name)
            let target = Paths.dataDir.appending(path: name)
            guard fm.fileExists(atPath: source.path), !fm.fileExists(atPath: target.path) else { continue }
            try? fm.copyItem(at: source, to: target)
        }
    }

    // MARK: - Persistence primitives

    private func readList<T: Decodable>(_ file: URL) -> [T] {
        guard let data = try? Data(contentsOf: file) else { return [] }
        return (try? JSONDecoder().decode([T].self, from: data)) ?? []
    }

    private func write(_ data: Data, to file: URL) {
        // `.atomic` is the write-temp-then-rename the Compose store does by
        // hand: a crash mid-write must not leave a truncated profile list.
        try? data.write(to: file, options: .atomic)
    }

    private func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }

    private func persistProfiles() {
        if let data = try? encoder().encode(profiles) { write(data, to: profilesFile) }
    }

    private func persistSubs() {
        if let data = try? encoder().encode(subscriptions) { write(data, to: subsFile) }
    }

    private func loadSettings() {
        settings = [:]
        guard let data = try? Data(contentsOf: settingsFile) else { return }
        settings = (try? JSONDecoder().decode([String: SettingValue].self, from: data)) ?? [:]
    }

    private func persistSettings() {
        if let data = try? encoder().encode(settings) { write(data, to: settingsFile) }
    }

    // MARK: - Typed settings access

    private func string(_ key: String, _ fallback: String) -> String {
        settings[key]?.stringValue ?? fallback
    }

    private func bool(_ key: String, _ fallback: Bool) -> Bool {
        settings[key]?.boolValue ?? fallback
    }

    private func int(_ key: String, _ fallback: Int) -> Int {
        settings[key]?.intValue ?? fallback
    }

    private func int64(_ key: String, _ fallback: Int64) -> Int64 {
        settings[key]?.int64Value ?? fallback
    }

    private func put(_ key: String, _ value: SettingValue) {
        settings[key] = value
        persistSettings()
    }

    // MARK: - Profiles

    public func saveProfile(_ profile: Profile) {
        if let index = profiles.firstIndex(where: { $0.id == profile.id }) {
            profiles[index] = profile
        } else {
            profiles.append(profile)
        }
        persistProfiles()
        if selectedID == nil { selectProfile(profile.id) }
    }

    public func deleteProfile(id: String) {
        profiles.removeAll { $0.id == id }
        persistProfiles()
        if selectedID == id {
            if let next = profiles.first {
                selectProfile(next.id)
            } else {
                clearSelection()
            }
        }
    }

    public func selectProfile(_ id: String) {
        put("selected_profile", .string(id))
        selectedID = id
    }

    private func clearSelection() {
        settings.removeValue(forKey: "selected_profile")
        persistSettings()
        selectedID = nil
    }

    public func selectedProfile() -> Profile? {
        guard let selectedID else { return nil }
        return profiles.first { $0.id == selectedID }
    }

    /// Routing template of the subscription this profile belongs to, if any.
    public func routingTemplate(for profile: Profile) -> String? {
        guard let subId = profile.subId, useSubscriptionRouting else { return nil }
        guard let sub = subscriptions.first(where: { $0.id == subId }) else { return nil }
        return sub.routingJson.flatMap { $0.isEmpty ? nil : $0 }
    }

    /// Replaces all profiles of a subscription, keeping the selection when
    /// possible: by identity first, then by name, then by falling back to the
    /// first entry — a renamed node should not silently disconnect the user.
    public func replaceSubscriptionProfiles(subId: String, newProfiles: [Profile]) {
        let previouslySelected = profiles.first { $0.subId == subId && $0.id == selectedID }

        var incoming = newProfiles
        for index in incoming.indices { incoming[index].subId = subId }

        profiles = profiles.filter { $0.subId != subId } + incoming
        persistProfiles()

        if let previouslySelected {
            let match = incoming.first { $0.identityKey == previouslySelected.identityKey }
                ?? incoming.first { $0.name == previouslySelected.name }
                ?? incoming.first
            if let match {
                selectProfile(match.id)
            } else {
                clearSelection()
            }
        } else if selectedID == nil, let first = incoming.first {
            selectProfile(first.id)
        }
    }

    // MARK: - Subscriptions

    public func saveSubscription(_ sub: Subscription) {
        if let index = subscriptions.firstIndex(where: { $0.id == sub.id }) {
            subscriptions[index] = sub
        } else {
            subscriptions.append(sub)
        }
        subscriptions.sort { $0.name < $1.name }
        persistSubs()
    }

    public func deleteSubscription(id: String) {
        subscriptions.removeAll { $0.id == id }
        persistSubs()
        for orphan in profiles.filter({ $0.subId == id }).map(\.id) {
            deleteProfile(id: orphan)
        }
    }

    // MARK: - Settings

    public var theme: String {
        get { string("theme", AppConfig.themeAurora) }
        set { put("theme", .string(newValue)) }
    }

    /// Rule / Global / Direct.
    public var mode: String {
        get { string("mode", AppConfig.modeRule) }
        set { put("mode", .string(newValue)) }
    }

    /// System proxy or TUN.
    public var networkMode: String {
        get { string("network_mode", AppConfig.netSystemProxy) }
        set { put("network_mode", .string(newValue)) }
    }

    public var routingPreset: String {
        get { string("routing_preset", AppConfig.routeBypassLan) }
        set { put("routing_preset", .string(newValue)) }
    }

    public var blockQuic: Bool {
        get { bool("block_quic", true) }
        set { put("block_quic", .bool(newValue)) }
    }

    public var bypassTorrent: Bool {
        get { bool("bypass_torrent", true) }
        set { put("bypass_torrent", .bool(newValue)) }
    }

    public var sniffing: Bool {
        get { bool("sniffing", true) }
        set { put("sniffing", .bool(newValue)) }
    }

    public var routeOnly: Bool {
        get { bool("route_only", false) }
        set { put("route_only", .bool(newValue)) }
    }

    public var mux: Bool {
        get { bool("mux", false) }
        set { put("mux", .bool(newValue)) }
    }

    public var logLevel: String {
        get { string("log_level", "warning") }
        set { put("log_level", .string(newValue)) }
    }

    public var remoteDns: String {
        get { string("remote_dns", AppConfig.defaultRemoteDns) }
        set { put("remote_dns", .string(newValue)) }
    }

    public var directDns: String {
        get { string("direct_dns", AppConfig.defaultDirectDns) }
        set { put("direct_dns", .string(newValue)) }
    }

    public var socksPort: Int {
        get { int("socks_port", AppConfig.defaultSocksPort) }
        set { put("socks_port", .int(newValue)) }
    }

    public var httpPort: Int {
        get { int("http_port", AppConfig.defaultHttpPort) }
        set { put("http_port", .int(newValue)) }
    }

    public var apiPort: Int {
        get { int("api_port", AppConfig.defaultApiPort) }
        set { put("api_port", .int(newValue)) }
    }

    public var hwidEnabled: Bool {
        get { bool("hwid_enabled", true) }
        set { put("hwid_enabled", .bool(newValue)) }
    }

    public var useSubscriptionRouting: Bool {
        get { bool("use_sub_routing", true) }
        set { put("use_sub_routing", .bool(newValue)) }
    }

    /// User-pasted xray-json (or a bare routing/dns object) whose routing and
    /// dns override the preset — the escape hatch for panels that serve plain
    /// share links with no routing attached.
    public var configTemplate: String {
        get { string("config_template", "") }
        set { put("config_template", .string(newValue)) }
    }

    public var launchAtLogin: Bool {
        get { bool("launch_at_login", false) }
        set { put("launch_at_login", .bool(newValue)) }
    }

    public var autoConnect: Bool {
        get { bool("auto_connect", false) }
        set { put("auto_connect", .bool(newValue)) }
    }

    public var autoCheckUpdates: Bool {
        get { bool("auto_check_updates", true) }
        set { put("auto_check_updates", .bool(newValue)) }
    }

    public var lastUpdateCheck: Int64 {
        get { int64("last_update_check", 0) }
        set { put("last_update_check", .int64(newValue)) }
    }

    public func settingsSnapshot() -> SettingsSnapshot {
        let tunnelling = networkMode == AppConfig.netTun
        return SettingsSnapshot(
            socksPort: socksPort,
            httpPort: httpPort,
            apiPort: apiPort,
            remoteDns: remoteDns,
            directDns: directDns,
            mode: mode,
            routingPreset: routingPreset,
            blockQuic: blockQuic,
            bypassTorrent: bypassTorrent,
            sniffing: sniffing,
            routeOnly: routeOnly,
            mux: mux,
            logLevel: logLevel,
            // With the tunnel up the default route points at our own utun
            // device, so asking the route table would bind the core's outbounds
            // to the tunnel they feed. The tunnel remembers the interface it
            // was built on, and that is the only trustworthy answer.
            bindInterface: tunnelling ? networkFacts?.bindInterface : nil,
            serverPins: tunnelling
                ? (networkFacts?.serverPins(for: profiles.map(\.server)) ?? [:])
                : [:]
        )
    }
}

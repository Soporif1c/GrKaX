import Foundation
import Observation

public enum ConnState: Sendable {
    case disconnected
    case connecting
    case connected
    case stopping
}

public struct Traffic: Equatable, Sendable {
    public var upSpeed: Int64 = 0
    public var downSpeed: Int64 = 0
    public var upTotal: Int64 = 0
    public var downTotal: Int64 = 0

    public init(upSpeed: Int64 = 0, downSpeed: Int64 = 0, upTotal: Int64 = 0, downTotal: Int64 = 0) {
        self.upSpeed = upSpeed
        self.downSpeed = downSpeed
        self.upTotal = upTotal
        self.downTotal = downTotal
    }
}

/// How the OS is pointed at the core.
///
/// The tunnel and the system proxy sit behind this one protocol so the runtime
/// never learns which is in use — and so a `NEPacketTunnelProvider`
/// implementation can replace the hand-built tunnel without the runtime
/// changing at all.
@MainActor
public protocol NetworkController: NetworkFacts {
    /// Returns a message describing the failure, or nil on success.
    ///
    /// `servers` is every known server, not only the selected one: the tunnel
    /// pins them all outside itself while it is being raised, which is what lets
    /// a later server switch be a core restart with no route changes and no
    /// second password prompt.
    /// Works out what the config builder needs — the physical interface, the
    /// servers' addresses — without touching a route, a proxy setting or a
    /// password prompt. Runs before the config is built, so a config that turns
    /// out to be unbuildable still costs the user nothing.
    func prepare(mode: String, servers: [String]) async

    func enable(mode: String, socksPort: Int, httpPort: Int, servers: [String]) async -> String?

    /// Returns a message describing the failure, or nil on success.
    ///
    /// Teardown needs an authorization prompt, and the user can dismiss it. A
    /// failure here means the plumbing is *still in place*: the caller must not
    /// stop the core, because the tunnel would go on capturing traffic for a
    /// proxy that no longer exists.
    func disable() async -> String?
}

/// Owns the core's lifecycle: what is running, with which config, and how the
/// OS is pointed at it.
@MainActor
@Observable
public final class CoreRuntime {

    public static let shared = CoreRuntime()

    public private(set) var state: ConnState = .disconnected
    public private(set) var traffic = Traffic()
    public private(set) var connectedAt: Date?
    public private(set) var lastError: String?
    public private(set) var logs: [String] = []
    /// The config handed to the core on the last start — shown in the UI.
    public private(set) var lastConfig = ""

    private static let maxLogLines = 2000

    @ObservationIgnored
    public var network: (any NetworkController)?
    @ObservationIgnored
    private let store: Store
    @ObservationIgnored
    private var statsTask: Task<Void, Never>?
    /// Serialises connect / disconnect / reload against each other.
    @ObservationIgnored
    private var gate: Task<Void, Never>?

    public init(store: Store = .shared) {
        self.store = store
    }

    /// Begins mirroring the core's own stderr into the log screen.
    public func startCapturingCoreLog() {
        LogCapture.shared.start { line in
            Task { @MainActor in CoreRuntime.shared.log(line) }
        }
    }

    // MARK: - Log

    public func log(_ line: String) {
        let stamp = Date().formatted(.dateTime.hour().minute().second())
        logs.append("\(stamp)  \(line)")
        if logs.count > Self.maxLogLines {
            logs.removeFirst(logs.count - Self.maxLogLines)
        }
    }

    public func clearLogs() {
        logs.removeAll()
    }

    // MARK: - Lifecycle

    public func connect(profile: Profile) async -> Bool {
        await serialised {
            guard self.state != .connected, self.state != .connecting else { return true }
            self.state = .connecting
            self.lastError = nil

            let servers = self.store.profiles.map(\.server)

            // Before the config, because it decides what goes in it: the
            // server's address in place of a name it could not look up later,
            // and the interface its outbound binds to. Touches nothing on the
            // system, so a config that fails to build still costs no prompt.
            await self.network?.prepare(mode: self.store.networkMode, servers: servers)

            if let error = await self.startCore(profile: profile) {
                self.fail(error)
                return false
            }

            let networkError = await self.network?.enable(
                mode: self.store.networkMode,
                socksPort: self.store.socksPort,
                httpPort: self.store.httpPort,
                servers: servers
            )
            if let networkError {
                await Self.stopCoreOffMain()
                self.fail(networkError)
                return false
            }

            self.connectedAt = Date()
            self.state = .connected
            self.startStatsPolling()
            self.log("Подключено · \(profile.name) · \(Self.modeLabel(self.store.mode))")
            return true
        }
    }

    public func disconnect() async {
        await serialised {
            guard self.state != .disconnected else { return }
            self.state = .stopping
            self.statsTask?.cancel()
            self.statsTask = nil

            // Order matters, and so does stopping here. The tunnel forwards
            // every packet to the core's SOCKS inbound; while it is still up,
            // stopping the core does not disconnect the machine, it strands it.
            // Stay connected, keep the core serving, and let the user retry.
            if let error = await self.network?.disable() {
                self.lastError = error
                self.log("Ошибка: \(error)")
                self.state = .connected
                self.startStatsPolling()
                return
            }
            await Self.stopCoreOffMain()

            self.traffic = Traffic()
            self.connectedAt = nil
            self.lastError = nil
            self.state = .disconnected
            self.log("Отключено")
        }
    }

    /// Rule / Global / Direct switch. Xray cannot swap routing at runtime, so a
    /// live connection is restarted with the new config — the OS-level plumbing
    /// (system proxy or TUN) stays in place, which keeps the switch quick and
    /// avoids a second admin prompt.
    public func applyMode(_ mode: String) async {
        store.mode = mode
        guard state == .connected, let profile = store.selectedProfile() else { return }
        await reload(profile: profile, reason: "Режим → \(Self.modeLabel(mode))")
    }

    /// Switches to `profile`. A live connection follows it over without touching
    /// the tunnel: every known server was pinned outside it when the tunnel came
    /// up, so the new one is already reachable and no password is needed.
    public func switchProfile(_ profile: Profile) async {
        store.selectProfile(profile.id)
        guard state == .connected else { return }
        await reload(profile: profile, reason: "Сервер → \(profile.name)")
    }

    /// Rebuilds the config and relaunches the core, leaving the plumbing alone.
    private func reload(profile: Profile, reason: String) async {
        await serialised {
            if let error = await self.startCore(profile: profile) {
                self.fail(error)
                _ = await self.network?.disable()
            } else {
                self.log(reason)
            }
        }
    }

    /// Builds the config and (re)launches the core. Returns an error or nil.
    private func startCore(profile: Profile) async -> String? {
        let config = ConfigBuilder.build(
            profile: profile,
            settings: store.settingsSnapshot(),
            routingJson: store.routingTemplate(for: profile),
            useSubRouting: store.useSubscriptionRouting,
            customTemplate: store.configTemplate
        )
        lastConfig = config

        if !XrayCore.geoDataPresent {
            log("Внимание: geo-данные не найдены в \(XrayCore.assetDirectory.path)")
        }

        return await Task.detached(priority: .userInitiated) {
            do {
                // Stopping first matches the Compose behaviour: a reload is a
                // fresh core, not a second one.
                try? XrayCore.stop()
                try XrayCore.run(configJSON: config)
                return nil
            } catch {
                return error.localizedDescription
            }
        }.value
    }

    private static func stopCoreOffMain() async {
        await Task.detached(priority: .userInitiated) { try? XrayCore.stop() }.value
    }

    private func fail(_ message: String) {
        lastError = message
        log("Ошибка: \(message)")
        state = .disconnected
        connectedAt = nil
    }

    // MARK: - Stats

    private func startStatsPolling() {
        statsTask?.cancel()
        let apiPort = store.apiPort
        statsTask = Task { [weak self] in
            var upTotal: Int64 = 0
            var downTotal: Int64 = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }

                let delta = await Task.detached(priority: .utility) {
                    try? XrayStats.queryDelta(apiPort: apiPort)
                }.value
                guard let delta else { continue }

                upTotal += delta.up
                downTotal += delta.down
                await MainActor.run {
                    self?.traffic = Traffic(
                        upSpeed: delta.up,
                        downSpeed: delta.down,
                        upTotal: upTotal,
                        downTotal: downTotal
                    )
                }
            }
        }
    }

    // MARK: - Serialisation

    /// Runs `body` after whatever is already in flight, so a connect and a
    /// disconnect can never interleave inside the core.
    private func serialised<T: Sendable>(_ body: @escaping @MainActor () async -> T) async -> T {
        let previous = gate
        let task = Task { @MainActor in _ = await previous?.value }
        gate = task
        await task.value
        return await body()
    }

    public static func modeLabel(_ mode: String) -> String {
        switch mode {
        case AppConfig.modeGlobal: "Глобально"
        case AppConfig.modeDirect: "Прямое"
        default: "Правила"
        }
    }

    /// Best-effort teardown at app exit — never leave the system proxy set.
    public func shutdown() async {
        _ = await network?.disable()
        try? XrayCore.stop()
    }
}

import Foundation
import Testing
@testable import GrKaXKit

@MainActor
struct CoreRuntimeTests {

    /// Records what the runtime asked of the network layer, and can be told to
    /// refuse — the failure path is the one that leaves a machine offline if it
    /// is wrong.
    final class FakeNetwork: NetworkController {
        var enabled = false
        var enableCalls: [(mode: String, servers: [String])] = []
        var disableCount = 0
        var failWith: String?
        var failDisableWith: String?
        var prepareCalls = 0

        /// Both only exist once the tunnel has been raised — the real one
        /// resolves pins and picks the interface inside `enable`. Anything the
        /// config builder needs from here has to be asked for after that.
        nonisolated(unsafe) var pinsOnceEnabled: [String: [String]] = [:]
        nonisolated(unsafe) private var live = false

        nonisolated var bindInterface: String? { live ? "en0" : nil }
        nonisolated func serverPins(for hosts: [String]) -> [String: [String]] {
            live ? pinsOnceEnabled : [:]
        }

        func prepare(mode: String, servers: [String]) async {
            prepareCalls += 1
            live = true
        }

        func enable(mode: String, socksPort: Int, httpPort: Int, servers: [String]) async -> String? {
            enableCalls.append((mode, servers))
            if let failWith { return failWith }
            enabled = true
            return nil
        }

        func disable() async -> String? {
            disableCount += 1
            if let failDisableWith { return failDisableWith }
            enabled = false
            live = false
            return nil
        }
    }

    private static func fixture() throws -> (CoreRuntime, Store, FakeNetwork, Profile) {
        Paths.dataDir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "grkax-runtime-\(UUID().uuidString)")
        Paths.ensure(Paths.dataDir)

        let store = Store()
        store.load()
        // Direct mode needs no reachable server, and free ports keep repeat runs
        // from colliding on the inbounds.
        let ports = try XrayCore.freePorts(count: 3)
        store.socksPort = ports[0]
        store.httpPort = ports[1]
        store.apiPort = ports[2]
        store.mode = AppConfig.modeDirect
        store.routingPreset = AppConfig.routeBypassLan

        let profile = Profile(name: "Тест", proto: "vless", server: "unused.example", port: 443, uuid: "u")
        store.saveProfile(profile)

        let runtime = CoreRuntime(store: store)
        let network = FakeNetwork()
        runtime.network = network
        // The config builder asks the network layer for pins and the bind
        // interface through the store, exactly as the app wires it.
        store.networkFacts = network
        return (runtime, store, network, profile)
    }

    /// The failure that stranded a real machine: the teardown prompt was
    /// dismissed, the routes stayed in place, and the runtime stopped the core
    /// anyway — leaving `tun2socks` capturing every packet for a SOCKS inbound
    /// that no longer existed. Nothing reaches the network until the tunnel is
    /// torn down, so a failed teardown has to keep the core alive.
    @Test func failedTeardownKeepsTheCoreRunning() async throws {
        try await withExclusiveCore {
            try requireGeoData()
            let (runtime, _, network, profile) = try Self.fixture()

            #expect(await runtime.connect(profile: profile))
            network.failDisableWith = "пользователь отменил запрос пароля"

            await runtime.disconnect()

            let running = try XrayCore.isRunning()
            #expect(running, "ядро остановлено, пока туннель ещё поднят")
            #expect(runtime.state == .connected, "показано «отключено», хотя туннель на месте")
            #expect(runtime.lastError != nil, "провал снятия туннеля не показан пользователю")

            // And once the user lets it through, the disconnect completes.
            network.failDisableWith = nil
            await runtime.disconnect()
            #expect(runtime.state == .disconnected)
            #expect(!network.enabled)
        }
    }

    /// The tunnel has to be up before the config is built, not after.
    ///
    /// Pins and the physical interface are produced by raising the tunnel, and
    /// the config needs both: without them the core is handed a hostname it can
    /// only resolve through the tunnel it is itself supposed to feed, and an
    /// outbound bound to nothing. The plumbing looked fine and no traffic moved.
    @Test func tunnelIsRaisedBeforeTheConfigIsBuilt() async throws {
        try await withExclusiveCore {
            try requireGeoData()
            let (runtime, store, network, profile) = try Self.fixture()
            store.networkMode = AppConfig.netTun
            network.pinsOnceEnabled = [profile.server: ["203.0.113.7"]]

            #expect(await runtime.connect(profile: profile))

            #expect(runtime.lastConfig.contains("203.0.113.7"),
                    "конфиг собран до пинов — в нём осталось имя сервера")
            #expect(runtime.lastConfig.contains("\"interface\""),
                    "конфиг собран до туннеля — исходящие никуда не привязаны")
            #expect(network.prepareCalls == 1)

            await runtime.disconnect()
        }
    }

    @Test func connectStartsCoreAndNetwork() async throws {
        try await withExclusiveCore {
            try requireGeoData()
            let (runtime, store, network, profile) = try Self.fixture()

            let ok = await runtime.connect(profile: profile)
            #expect(ok)
            #expect(runtime.state == .connected)
            #expect(runtime.connectedAt != nil)
            #expect(network.enabled)
            let running = try XrayCore.isRunning()
            #expect(running)
            #expect(!runtime.lastConfig.isEmpty)

            // Every known server is offered to the network layer, not just the
            // selected one — that is what lets a later switch skip the tunnel.
            #expect(network.enableCalls.first?.servers == store.profiles.map(\.server))

            await runtime.disconnect()
        }
    }

    @Test func disconnectStopsBoth() async throws {
        try await withExclusiveCore {
            try requireGeoData()
            let (runtime, _, network, profile) = try Self.fixture()

            _ = await runtime.connect(profile: profile)
            await runtime.disconnect()

            #expect(runtime.state == .disconnected)
            #expect(runtime.connectedAt == nil)
            #expect(!network.enabled)
            #expect(network.disableCount >= 1)
            let stopped = try XrayCore.isRunning()
            #expect(stopped == false)
            #expect(runtime.traffic == Traffic())
        }
    }

    /// If the plumbing refuses, the core must not be left running behind it —
    /// otherwise a half-connected state survives with no way to notice.
    @Test func networkFailureTearsTheCoreBackDown() async throws {
        try await withExclusiveCore {
            try requireGeoData()
            let (runtime, _, network, profile) = try Self.fixture()
            network.failWith = "не удалось поднять TUN"

            let ok = await runtime.connect(profile: profile)
            #expect(!ok)
            #expect(runtime.state == .disconnected)
            #expect(runtime.lastError == "не удалось поднять TUN")
            let stillRunning = try XrayCore.isRunning()
            #expect(stillRunning == false, "ядро осталось работать после отказа сети")
        }
    }

    /// A config the core rejects must fail the connect, not half-succeed.
    @Test func badConfigFailsBeforeTouchingTheNetwork() async throws {
        try await withExclusiveCore {
            let (runtime, store, network, _) = try Self.fixture()
            // shadowsocks with an unknown cipher: builds fine, core refuses it.
            var broken = Profile(name: "плохой", proto: "shadowsocks", server: "s.example", port: 8388, uuid: "pw")
            broken.method = "not-a-cipher"
            store.saveProfile(broken)

            let ok = await runtime.connect(profile: broken)
            #expect(!ok)
            #expect(runtime.state == .disconnected)
            #expect(runtime.lastError != nil)
            #expect(network.enableCalls.isEmpty, "сеть трогать нельзя, пока ядро не поднялось")
        }
    }

    /// Switching mode restarts the core but must leave the plumbing alone: on a
    /// real tunnel, tearing it down would cost a second admin prompt.
    @Test func modeSwitchRestartsCoreWithoutTouchingNetwork() async throws {
        try await withExclusiveCore {
            try requireGeoData()
            let (runtime, store, network, profile) = try Self.fixture()

            _ = await runtime.connect(profile: profile)
            let configBefore = runtime.lastConfig
            let disablesBefore = network.disableCount

            await runtime.applyMode(AppConfig.modeGlobal)

            #expect(store.mode == AppConfig.modeGlobal)
            #expect(runtime.state == .connected)
            #expect(runtime.lastConfig != configBefore, "конфиг не перестроился под новый режим")
            #expect(network.disableCount == disablesBefore, "сеть трогали при смене режима")
            #expect(network.enableCalls.count == 1)

            await runtime.disconnect()
        }
    }

    @Test func modeSwitchWhileDisconnectedOnlyRecordsIt() async throws {
        let (runtime, store, network, _) = try Self.fixture()
        await runtime.applyMode(AppConfig.modeGlobal)

        #expect(store.mode == AppConfig.modeGlobal)
        #expect(runtime.state == .disconnected)
        #expect(network.enableCalls.isEmpty)
    }

    @Test func logKeepsOnlyTheLastLines() {
        let runtime = CoreRuntime(store: Store())
        for index in 0..<2500 { runtime.log("строка \(index)") }
        #expect(runtime.logs.count == 2000)
        #expect(runtime.logs.last?.contains("строка 2499") == true)
        #expect(runtime.logs.first?.contains("строка 500") == true)

        runtime.clearLogs()
        #expect(runtime.logs.isEmpty)
    }

    @Test func modeLabelsMatchTheSwitch() {
        #expect(CoreRuntime.modeLabel(AppConfig.modeRule) == "Правила")
        #expect(CoreRuntime.modeLabel(AppConfig.modeGlobal) == "Глобально")
        #expect(CoreRuntime.modeLabel(AppConfig.modeDirect) == "Прямое")
    }
}

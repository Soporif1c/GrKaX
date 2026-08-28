import Foundation
import Testing
@testable import GrKaXKit

@MainActor
struct StoreTests {

    /// Each test gets its own data directory — the store writes to disk on
    /// every mutation, and none of that may touch a real install.
    private static func isolated() -> Store {
        Paths.dataDir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "grkax-tests-\(UUID().uuidString)")
        Paths.ensure(Paths.dataDir)
        let store = Store()
        store.load()
        return store
    }

    @Test func persistsAndReloadsProfiles() {
        let store = Self.isolated()
        let profile = Profile(name: "Берлин", server: "a.example", port: 443)
        store.saveProfile(profile)

        let reloaded = Store()
        reloaded.load()
        #expect(reloaded.profiles.count == 1)
        #expect(reloaded.profiles.first?.name == "Берлин")
        // First profile saved becomes the selected one.
        #expect(reloaded.selectedID == profile.id)
    }

    @Test func persistsSettingsAcrossReload() {
        let store = Self.isolated()
        store.socksPort = 12345
        store.mux = true
        store.logLevel = "debug"
        store.lastUpdateCheck = 1_700_000_000_000

        let reloaded = Store()
        reloaded.load()
        #expect(reloaded.socksPort == 12345)
        #expect(reloaded.mux == true)
        #expect(reloaded.logLevel == "debug")
        #expect(reloaded.lastUpdateCheck == 1_700_000_000_000)
        // Untouched keys still answer with their defaults.
        #expect(reloaded.httpPort == AppConfig.defaultHttpPort)
        #expect(reloaded.blockQuic == true)
    }

    /// kotlinx reads a quoted number back as a number; a settings file written
    /// by the Compose build, or edited by hand, must not lose values here.
    @Test func readsQuotedValuesLeniently() throws {
        let store = Self.isolated()
        let file = Paths.dataDir.appending(path: "settings.json")
        try Data(#"{"socks_port":"9999","mux":"true","block_quic":0}"#.utf8).write(to: file)

        let reloaded = Store()
        reloaded.load()
        #expect(reloaded.socksPort == 9999)
        #expect(reloaded.mux == true)
        #expect(reloaded.blockQuic == false)
    }

    @Test func deletingSelectedProfileMovesSelection() {
        let store = Self.isolated()
        let first = Profile(name: "one", server: "a.example")
        let second = Profile(name: "two", server: "b.example")
        store.saveProfile(first)
        store.saveProfile(second)
        store.selectProfile(first.id)

        store.deleteProfile(id: first.id)
        #expect(store.selectedID == second.id)

        store.deleteProfile(id: second.id)
        #expect(store.selectedID == nil)
        #expect(store.selectedProfile() == nil)
    }

    /// A subscription refresh hands back brand-new ids. The user's selection
    /// has to follow the node it pointed at, or every update silently switches
    /// them to a different server.
    @Test func subscriptionRefreshKeepsSelectionByIdentity() {
        let store = Self.isolated()
        let sub = Subscription(name: "sub")
        store.saveSubscription(sub)

        let berlin = Profile(name: "Berlin", proto: "vless", server: "b.example", port: 443, uuid: "u1")
        let paris = Profile(name: "Paris", proto: "vless", server: "p.example", port: 443, uuid: "u2")
        store.replaceSubscriptionProfiles(subId: sub.id, newProfiles: [berlin, paris])
        store.selectProfile(store.profiles.first { $0.name == "Paris" }!.id)

        // Same nodes, fresh ids, and Paris renamed by the panel.
        let berlinAgain = Profile(name: "Berlin", proto: "vless", server: "b.example", port: 443, uuid: "u1")
        let parisRenamed = Profile(name: "Paris 02", proto: "vless", server: "p.example", port: 443, uuid: "u2")
        store.replaceSubscriptionProfiles(subId: sub.id, newProfiles: [berlinAgain, parisRenamed])

        #expect(store.selectedProfile()?.server == "p.example", "selection followed the wrong node")
        #expect(store.profiles.count == 2)
    }

    @Test func deletingSubscriptionRemovesItsProfiles() {
        let store = Self.isolated()
        let sub = Subscription(name: "sub")
        store.saveSubscription(sub)
        store.replaceSubscriptionProfiles(
            subId: sub.id,
            newProfiles: [Profile(name: "a", server: "a.example"), Profile(name: "b", server: "b.example")]
        )
        store.saveProfile(Profile(name: "manual", server: "m.example"))

        store.deleteSubscription(id: sub.id)
        #expect(store.profiles.map(\.name) == ["manual"])
    }

    /// Pins and the bound interface only exist while a tunnel is up; leaking
    /// them into a system-proxy config would pin outbounds to an interface that
    /// is not carrying them.
    @Test func snapshotOmitsTunnelFactsOutsideTunMode() {
        let store = Self.isolated()
        let facts = StubFacts()
        store.networkFacts = facts

        store.networkMode = AppConfig.netSystemProxy
        let proxy = store.settingsSnapshot()
        #expect(proxy.bindInterface == nil)
        #expect(proxy.serverPins.isEmpty)

        store.networkMode = AppConfig.netTun
        let tun = store.settingsSnapshot()
        #expect(tun.bindInterface == "en0")
        #expect(tun.serverPins == ["a.example": ["203.0.113.10"]])
    }
}

@MainActor
private final class StubFacts: NetworkFacts {
    var bindInterface: String? { "en0" }
    func serverPins(for hosts: [String]) -> [String: [String]] {
        ["a.example": ["203.0.113.10"]]
    }
}

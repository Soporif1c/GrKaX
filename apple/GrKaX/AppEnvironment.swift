import Foundation
import GrKaXKit
import Observation

/// Wires the layers together and holds them for the app's lifetime.
///
/// Assembled here rather than in `GrKaXKit` so the kit stays free of app-level
/// policy — which is also what lets the tests build a runtime with a fake
/// network layer instead of this one.
@MainActor
@Observable
final class AppEnvironment {

    let store: Store
    let runtime: CoreRuntime
    let network: MacNetworkController

    init() {
        store = Store.shared
        store.load()

        network = MacNetworkController { line in
            Task { @MainActor in CoreRuntime.shared.log(line) }
        }
        store.networkFacts = network

        runtime = CoreRuntime.shared
        runtime.network = network
        runtime.startCapturingCoreLog()

        // Undo plumbing a previous run left behind, before anything else
        // touches the network: a crash last time may still be holding the
        // machine offline, and until this runs nothing will connect.
        network.cleanStale(socksPort: store.socksPort)

        if !XrayCore.geoDataPresent {
            runtime.log("geo-данные не найдены в \(XrayCore.assetDirectory.path) — правила geoip/geosite не заработают")
        }
    }

    /// True while a subscription is being fetched — the screens disable their
    /// buttons on it.
    private(set) var busy = false

    /// Which screen the window shows.
    ///
    /// Held here rather than inside `RootView` because things outside the
    /// sidebar move between screens: picking a server sends you back to the
    /// connection, and so does the menu bar.
    var screen: Screen = .home

    // MARK: - Profiles

    /// Imports pasted text: share links, or a base64 subscription payload.
    /// Returns how many profiles were added.
    @discardableResult
    func importLinks(_ text: String) -> Int {
        let profiles = LinkParser.parseBatch(text)
        guard !profiles.isEmpty else {
            runtime.log("В тексте не нашлось ни одной ссылки")
            return 0
        }
        for profile in profiles { store.saveProfile(profile) }
        runtime.log("Добавлено серверов: \(profiles.count)")
        return profiles.count
    }

    func deleteProfile(_ profile: Profile) {
        store.deleteProfile(id: profile.id)
    }

    /// Selecting while connected follows the connection over to the new server.
    ///
    /// Lands back on Home: picking a server is the last step of an errand that
    /// started there, and staying on the list left you to find your own way
    /// back to see whether anything had happened.
    func select(_ profile: Profile) async {
        screen = .home
        await runtime.switchProfile(profile)
    }

    // MARK: - Subscriptions

    func addSubscription(name: String, url: String, userAgent: String) async {
        var sub = Subscription(name: name, url: url)
        sub.userAgent = userAgent.isEmpty ? nil : userAgent
        store.saveSubscription(sub)
        await update(sub)
    }

    func update(_ sub: Subscription) async {
        busy = true
        defer { busy = false }
        let result = await SubscriptionManager.update(sub, store: store)
        if result.ok {
            runtime.log("Подписка «\(sub.name.isEmpty ? sub.url : sub.name)»: серверов \(result.count)")
        } else {
            runtime.log("Подписка не обновилась: \(result.error ?? "неизвестная ошибка")")
        }
    }

    func updateAll() async {
        busy = true
        defer { busy = false }
        for (sub, result) in await SubscriptionManager.updateAll(store: store) {
            let label = sub.name.isEmpty ? sub.url : sub.name
            runtime.log(result.ok
                ? "Подписка «\(label)»: серверов \(result.count)"
                : "Подписка «\(label)» не обновилась: \(result.error ?? "ошибка)")")
        }
    }

    func deleteSubscription(_ sub: Subscription) {
        store.deleteSubscription(id: sub.id)
    }

    // MARK: - Connection

    /// Connects the selected profile, or reports that there is none.
    func toggleConnection() async {
        switch runtime.state {
        case .connected, .connecting:
            await runtime.disconnect()
        case .disconnected, .stopping:
            guard let profile = store.selectedProfile() else {
                runtime.log("Нет выбранного сервера")
                return
            }
            _ = await runtime.connect(profile: profile)
        }
    }
}

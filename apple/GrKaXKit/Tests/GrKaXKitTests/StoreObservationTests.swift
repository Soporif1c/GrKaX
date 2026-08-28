import Foundation
import Observation
import Testing
@testable import GrKaXKit

/// Settings are computed properties over one dictionary, so whether SwiftUI
/// sees a change depends entirely on that dictionary being observed.
///
/// When it was not, every toggle and picker in Settings looked broken while
/// quietly writing the right value to disk: the store updated, the view never
/// re-rendered, and the control snapped back to its old position.
@MainActor
struct StoreObservationTests {

    /// `onChange` is a `@Sendable` closure, so the flag it sets has to live
    /// behind a reference rather than be captured as a local var.
    private final class Fired: @unchecked Sendable {
        private let lock = NSLock()
        private var value = false
        func mark() { lock.withLock { value = true } }
        var didFire: Bool { lock.withLock { value } }
    }

    private static func isolated() -> Store {
        Paths.dataDir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "grkax-observe-\(UUID().uuidString)")
        Paths.ensure(Paths.dataDir)
        let store = Store()
        store.load()
        return store
    }

    /// The one the user hit: switching to TUN in Settings.
    @Test func networkModeChangeNotifiesObservers() {
        let store = Self.isolated()
        let fired = Fired()
        withObservationTracking {
            _ = store.networkMode
        } onChange: {
            fired.mark()
        }

        store.networkMode = AppConfig.netTun
        #expect(fired.didFire, "смена режима сети не уведомляет SwiftUI — переключатель откатится")
        #expect(store.networkMode == AppConfig.netTun)
    }

    @Test func everySettingKindNotifiesObservers() {
        // One per storage type, since they go through different accessors.
        let checks: [(String, (Store) -> Void, (Store) -> Void)] = [
            ("mode", { _ = $0.mode }, { $0.mode = AppConfig.modeGlobal }),
            ("blockQuic", { _ = $0.blockQuic }, { $0.blockQuic = false }),
            ("socksPort", { _ = $0.socksPort }, { $0.socksPort = 11111 }),
            ("remoteDns", { _ = $0.remoteDns }, { $0.remoteDns = "9.9.9.9" }),
            ("lastUpdateCheck", { _ = $0.lastUpdateCheck }, { $0.lastUpdateCheck = 42 }),
        ]

        for (name, read, write) in checks {
            let store = Self.isolated()
            let fired = Fired()
            withObservationTracking { read(store) } onChange: { fired.mark() }
            write(store)
            #expect(fired.didFire, "«\(name)» меняется молча")
        }
    }

    /// Selecting a profile has to repaint the server list too.
    @Test func profileSelectionNotifiesObservers() {
        let store = Self.isolated()
        let profile = Profile(name: "a", server: "a.example")
        store.saveProfile(profile)
        let other = Profile(name: "b", server: "b.example")
        store.saveProfile(other)

        let fired = Fired()
        withObservationTracking {
            _ = store.selectedID
        } onChange: {
            fired.mark()
        }
        store.selectProfile(other.id)
        #expect(fired.didFire)
    }
}

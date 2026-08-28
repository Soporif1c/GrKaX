import Foundation
import Testing
@testable import GrKaXKit

/// Serialises everything that starts the core.
///
/// There is one Xray instance per process — it lives in the Go runtime, not in
/// a type we construct — while Swift Testing runs suites concurrently. Without
/// this, one test's `stop` tears down another's core and the failure lands
/// somewhere unrelated to its cause.
///
/// A global gate rather than `.serialized` on each suite, because that trait
/// only orders tests *within* a suite; suites still run against each other.
private actor CoreGate {
    static let shared = CoreGate()

    private var locked = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func acquire() async {
        guard locked else {
            locked = true
            return
        }
        await withCheckedContinuation { waiting.append($0) }
    }

    func release() {
        if waiting.isEmpty {
            locked = false
        } else {
            waiting.removeFirst().resume()
        }
    }
}

/// Runs `body` with exclusive use of the core, and always leaves it stopped so
/// the next holder starts from a known state.
///
/// `isolation: #isolation` keeps the body in the caller's isolation domain — a
/// `@MainActor` test stays on the main actor rather than having to hand a
/// non-Sendable closure across an actor boundary.
///
/// `T: Sendable` because the result does cross one: it comes back out past the
/// gate's actor. Swift 6.3 infers that away for the `Void` every caller
/// returns; older toolchains, including the one on the CI runner, do not — and
/// they are right that the unconstrained signature promised more than it could
/// keep.
func withExclusiveCore<T: Sendable>(
    isolation: isolated (any Actor)? = #isolation,
    _ body: () async throws -> T
) async rethrows -> T {
    await CoreGate.shared.acquire()
    do {
        let result = try await body()
        try? XrayCore.stop()
        await CoreGate.shared.release()
        return result
    } catch {
        try? XrayCore.stop()
        await CoreGate.shared.release()
        throw error
    }
}

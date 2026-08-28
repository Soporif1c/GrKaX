import Foundation

/// Dispatches between the two ways of pointing the OS at the running core.
///
/// The runtime only ever sees `NetworkController`, so replacing the hand-built
/// tunnel with a `NEPacketTunnelProvider` is a matter of writing a second
/// conformance — nothing above this line knows which is in use.
@MainActor
public final class MacNetworkController: NetworkController {

    private let proxy: SystemProxy
    private let tun: TunMode
    private var active: String?

    public init(log: @escaping @Sendable (String) -> Void = { _ in }) {
        proxy = SystemProxy(log: log)
        tun = TunMode(log: log)
    }

    // MARK: - NetworkFacts

    public nonisolated var bindInterface: String? {
        tun.physicalInterface
    }

    /// Answers from the cache the tunnel filled while it was still down.
    ///
    /// Synchronous by protocol, because the config builder needs it inline —
    /// and safe, because by the time a config is built the addresses have
    /// already been resolved by `enable`.
    public nonisolated func serverPins(for hosts: [String]) -> [String: [String]] {
        tun.cachedPins
    }

    // MARK: - NetworkController

    public func prepare(mode: String, servers: [String]) async {
        // Only the tunnel needs this: the system proxy neither pins servers nor
        // binds the core to an interface.
        guard mode == AppConfig.netTun else { return }
        await tun.prepare(serverHosts: servers)
    }

    public func enable(mode: String, socksPort: Int, httpPort: Int, servers: [String]) async -> String? {
        if let error = await disable() { return error }

        let error: String?
        switch mode {
        case AppConfig.netTun:
            error = await tun.enable(socksPort: socksPort, serverHosts: servers)
        default:
            error = proxy.enable(socksPort: socksPort, httpPort: httpPort)
        }
        if error == nil { active = mode }
        return error
    }

    /// Leaves `active` set when teardown fails, so the plumbing that is still
    /// in place stays known — a retry tears down the same thing, and a caller
    /// that asks again does not silently switch to doing nothing.
    public func disable() async -> String? {
        let error: String?
        switch active {
        case AppConfig.netTun: error = tun.disable()
        case AppConfig.netSystemProxy: error = proxy.disable()
        default: error = nil
        }
        if error == nil { active = nil }
        return error
    }

    /// Undoes plumbing a previous run left behind.
    ///
    /// Call once at startup, before anything else touches the network: until it
    /// runs, a crash from last time may still be holding the machine offline.
    public func cleanStale(socksPort: Int) {
        tun.cleanStale()
        proxy.cleanStale(socksPort: socksPort)
    }
}

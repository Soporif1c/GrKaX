import Foundation

/// Points macOS at the local inbounds by writing the proxy into the active
/// network service.
///
/// Costs no privileges for admin users; when `networksetup` refuses (standard
/// accounts), the whole batch is retried behind one authorization prompt rather
/// than failing.
public final class SystemProxy: @unchecked Sendable {

    private static let networksetup = "/usr/sbin/networksetup"

    /// Hosts that must never be sent to the proxy.
    private static let bypassDomains = [
        "127.0.0.1", "localhost", "*.local", "169.254/16",
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
    ]

    private let lock = NSLock()
    private var appliedService: String?

    /// Where progress is reported. The runtime supplies the log screen.
    private let log: @Sendable (String) -> Void

    public init(log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.log = log
    }

    public func enable(socksPort: Int, httpPort: Int) -> String? {
        guard let service = MacNet.activeService() else {
            return "Не удалось определить активную сеть (Wi-Fi / Ethernet)"
        }

        let commands: [[String]] = [
            [Self.networksetup, "-setwebproxy", service, AppConfig.loopback, String(httpPort)],
            [Self.networksetup, "-setsecurewebproxy", service, AppConfig.loopback, String(httpPort)],
            [Self.networksetup, "-setsocksfirewallproxy", service, AppConfig.loopback, String(socksPort)],
            [Self.networksetup, "-setproxybypassdomains", service] + Self.bypassDomains,
        ]

        if !runAll(commands) {
            // Most likely a non-admin account: do it all in one elevated shot.
            let script = commands
                .map { $0.map(Shell.quote).joined(separator: " ") }
                .joined(separator: " && ")
            guard let elevated = Shell.runAsAdmin(script), elevated.ok else {
                return "Не удалось включить системный прокси"
            }
        }

        lock.withLock { appliedService = service }
        log("Системный прокси включён для «\(service)» (SOCKS \(socksPort), HTTP \(httpPort))")
        return nil
    }

    /// Turns off a proxy left pointing at a core that is no longer running.
    ///
    /// The same crash-and-SIGKILL gap `TunMode.cleanStale` covers, with the
    /// milder symptom of everything failing to connect rather than the routes
    /// being wrong.
    ///
    /// Deliberately narrow: it must be our own loopback address and port, with
    /// nothing listening there. Another client's proxy, or our own from a second
    /// running instance, is left untouched.
    public func cleanStale(socksPort: Int) {
        guard let service = MacNet.activeService() else { return }
        guard let current = Shell.capture(Self.networksetup, "-getsocksfirewallproxy", service) else { return }

        guard MacNet.describesOurProxy(current, port: socksPort) else { return }

        let listening = Shell.capture("/usr/sbin/lsof", "-nP", "-iTCP:\(socksPort)", "-sTCP:LISTEN") ?? ""
        guard listening.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        log("Найден системный прокси от прошлого запуска — выключаю")
        disable()
    }

    @discardableResult
    public func disable() -> String? {
        let service = lock.withLock {
            let applied = appliedService
            appliedService = nil
            return applied
        } ?? MacNet.activeService()
        guard let service else { return nil }

        let commands: [[String]] = [
            [Self.networksetup, "-setwebproxystate", service, "off"],
            [Self.networksetup, "-setsecurewebproxystate", service, "off"],
            [Self.networksetup, "-setsocksfirewallproxystate", service, "off"],
        ]
        if !runAll(commands) {
            let script = commands
                .map { $0.map(Shell.quote).joined(separator: " ") }
                .joined(separator: " && ")
            guard let elevated = Shell.runAsAdmin(script), elevated.ok else {
                lock.withLock { appliedService = service }
                log("Не удалось выключить системный прокси")
                return "Не удалось выключить системный прокси — он всё ещё направлен в ядро"
            }
        }
        log("Системный прокси выключен")
        return nil
    }

    /// True when every command succeeded.
    private func runAll(_ commands: [[String]]) -> Bool {
        for command in commands {
            guard let result = Shell.run(command[0], Array(command.dropFirst())), result.ok else {
                return false
            }
        }
        return true
    }
}

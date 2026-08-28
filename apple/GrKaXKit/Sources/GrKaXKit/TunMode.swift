import Darwin
import Foundation

/// Full-device tunnel: a utun interface fed into our SOCKS inbound by
/// `tun2socks`. Catches traffic from apps that ignore the system proxy.
///
/// Creating a utun device and editing the route table need root, and without a
/// paid Apple Developer account we cannot ship a code-signed privileged helper —
/// so the work happens in `grkax-tun.sh` behind a single authorization prompt.
///
/// The loop hazard is the core's own connection to the proxy server being routed
/// back into the tunnel it feeds. Two things keep it out, and both are needed:
/// `sockopt.interface` in ConfigBuilder, which binds outbound sockets to the
/// physical interface, and the /32 route installed here before the /1 routes.
/// The binding alone is not enough — the core has to know which address to dial
/// before it can dial it, and the lookup would go through the tunnel.
///
/// All of that is the price of building a tunnel by hand. A
/// `NEPacketTunnelProvider` excludes the provider's own sockets itself, and this
/// entire file collapses into a few dozen lines of `NETunnelProviderManager`.
public final class TunMode: @unchecked Sendable {

    /// Runtime state written by the helper script; the path must match it.
    private static let stateDir = URL(fileURLWithPath: "/var/run/grkax")

    private let lock = NSLock()
    private var appliedService: String?
    private var storedInterface: String?
    /// Whether the routes are actually installed. Kept apart from
    /// `storedInterface`, which is settled one step earlier by `prepare` so the
    /// config can be built before anything on the system is touched.
    private var tunnelUp = false
    private var pinKey: Set<String> = []
    private var pins: [String: [String]] = [:]

    private let log: @Sendable (String) -> Void

    public init(log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.log = log
    }

    /// The physical interface this tunnel was built on, remembered for as long
    /// as it is up.
    ///
    /// Once the /1 routes are in place the default route points at our own utun
    /// device, so anything that needs the real interface after that has to read
    /// it here rather than ask the route table.
    public var physicalInterface: String? {
        lock.withLock { storedInterface }
    }

    /// Pins resolved by the last `enable`, without touching the network.
    ///
    /// The config builder reads these while a tunnel is up, and at that point a
    /// fresh lookup is exactly what must not happen: the resolver is reachable
    /// only through the connection the config is being built to open.
    public var cachedPins: [String: [String]] {
        lock.withLock { pins }
    }

    /// Settles everything the config builder needs, changing nothing on the
    /// system: which interface is the physical one, and the servers' addresses.
    ///
    /// Split out from `enable` because the config has to carry both — an
    /// outbound bound to the physical interface, and the server as an address
    /// rather than a name. Resolving them only while raising the tunnel meant
    /// the core was configured before either existed, and then had to look the
    /// server up through the tunnel it was itself supposed to feed. A server
    /// given as a bare IP worked; every hostname hung.
    public func prepare(serverHosts: [String]) async {
        if let iface = MacNet.defaultInterface() {
            lock.withLock { storedInterface = iface }
        }
        _ = await serverPins(for: serverHosts)
    }

    public func enable(socksPort: Int, serverHosts: [String]) async -> String? {
        guard let iface = MacNet.defaultInterface() else {
            return "Не удалось определить сетевой интерфейс"
        }
        guard let service = MacNet.serviceForDevice(iface) else {
            return "Не удалось определить активную сеть для \(iface)"
        }
        guard let gateway = MacNet.defaultGateway() else {
            return "Не удалось определить шлюз — без него сервер окажется внутри туннеля"
        }

        // Every known server is pinned, not just the selected one, so switching
        // servers later is a core restart and nothing more — no route changes,
        // and therefore no second password prompt. Resolved here while the
        // tunnel is still down and DNS works normally. Only IPv4: the tunnel
        // captures IPv4 alone, so an IPv6 server needs no pin.
        let serverIPs = await serverPins(for: serverHosts).values.flatMap { $0 }.uniqued()

        guard let script = Paths.bundledResource("grkax-tun.sh") else {
            return "Не найден помощник grkax-tun.sh в бандле приложения"
        }
        guard let tun2socks = Paths.bundledResource("tun2socks") else {
            return "Не найден tun2socks в бандле приложения"
        }
        // Packaging does not carry the executable bit through reliably, so
        // restore it the same way the core binary did.
        for url in [script, tun2socks] {
            try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path)
        }

        let arguments = [
            "/bin/sh", script.path, "up",
            tun2socks.path, String(socksPort), iface, service,
            AppConfig.defaultRemoteDns, gateway,
        ] + serverIPs
        let command = arguments.map(Shell.quote).joined(separator: " ")

        guard let result = Shell.runAsAdmin(command), result.ok else {
            return "Не удалось поднять TUN"
        }

        lock.withLock {
            appliedService = service
            storedInterface = iface
            tunnelUp = true
        }
        log("TUN поднят (\(result.out.trimmingCharacters(in: .whitespacesAndNewlines))), интерфейс \(iface), сеть «\(service)»")

        if serverIPs.isEmpty {
            // Not fatal — an IPv6-only or unresolvable server may still work —
            // but it is the first thing to look at if nothing loads.
            log("Внимание: адреса серверов не закреплены за \(gateway), возможна петля")
        } else {
            log("Вне туннеля через \(gateway): \(serverIPs.joined(separator: ", "))")
        }
        return nil
    }

    /// hostname → IPv4 for every known server, for both the host routes and the
    /// core's static DNS table.
    ///
    /// Resolved only while the tunnel is down. Once it is up, this lookup would
    /// go through the tunnel like everything else — and the tunnel cannot carry
    /// it until the server connection it is meant to establish already exists.
    /// So the answers are taken before that door closes and cached for as long
    /// as the tunnel lives, which is also what lets a mode or server switch
    /// rebuild the config without asking anything of the network.
    public func serverPins(for hosts: [String]) async -> [String: [String]] {
        let (isUp, cached, key) = lock.withLock { (tunnelUp, pins, pinKey) }
        if isUp { return cached }

        let wanted = Set(hosts.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty })
        if wanted == key { return cached }

        let resolved = await Self.resolveIPv4(wanted)
        lock.withLock {
            pins = resolved
            pinKey = wanted
        }
        return resolved
    }

    /// Concurrent on purpose: a subscription can carry dozens of servers, and
    /// one after another a few unreachable names would stall the connect behind
    /// their DNS timeouts.
    private static func resolveIPv4(_ hosts: Set<String>) async -> [String: [String]] {
        await withTaskGroup(of: (String, [String]).self) { group in
            for host in hosts {
                group.addTask { (host, lookupIPv4(host)) }
            }
            var out: [String: [String]] = [:]
            for await (host, addresses) in group where !addresses.isEmpty {
                out[host] = addresses
            }
            return out
        }
    }

    private static func lookupIPv4(_ host: String) -> [String] {
        var hints = addrinfo(
            ai_flags: 0,
            ai_family: AF_INET,
            ai_socktype: SOCK_STREAM,
            ai_protocol: 0,
            ai_addrlen: 0,
            ai_canonname: nil,
            ai_addr: nil,
            ai_next: nil
        )
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &result) == 0, let head = result else { return [] }
        defer { freeaddrinfo(head) }

        var addresses: [String] = []
        var node: UnsafeMutablePointer<addrinfo>? = head
        while let current = node {
            if let sockaddr = current.pointee.ai_addr {
                var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                sockaddr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { pointer in
                    var address = pointer.pointee.sin_addr
                    inet_ntop(AF_INET, &address, &buffer, socklen_t(INET_ADDRSTRLEN))
                }
                let text = String(decoding: buffer.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
                if !text.isEmpty, !addresses.contains(text) { addresses.append(text) }
            }
            node = current.pointee.ai_next
        }
        return addresses
    }

    /// Tears down a tunnel left behind by a run that never cleaned up after
    /// itself.
    ///
    /// A normal exit is handled on shutdown, but a SIGKILL or a crash leaves the
    /// /1 routes and the DNS override in place with no tun2socks behind them —
    /// every packet then goes to a tunnel that no longer exists, and the machine
    /// stays offline until someone removes the routes by hand.
    ///
    /// Recognised by the state files outliving the process they describe. A live
    /// pid means a tunnel that is genuinely running, which is left alone.
    public func cleanStale() {
        guard FileManager.default.fileExists(atPath: Self.stateDir.appending(path: "tun.dev").path) else {
            return
        }
        let pidFile = Self.stateDir.appending(path: "tun2socks.pid")
        if let text = try? String(contentsOf: pidFile, encoding: .utf8),
           let pid = pid_t(text.trimmingCharacters(in: .whitespacesAndNewlines)),
           kill(pid, 0) == 0 {
            return // still alive
        }

        log("Найден туннель от прошлого запуска — снимаю, чтобы вернуть сеть")
        disable()
    }

    @discardableResult
    public func disable() -> String? {
        let service = lock.withLock { appliedService } ?? MacNet.activeService() ?? ""
        guard let script = Paths.bundledResource("grkax-tun.sh") else {
            return "Не найден помощник grkax-tun.sh — туннель снять нечем"
        }

        let command = ["/bin/sh", script.path, "down", service]
            .map(Shell.quote)
            .joined(separator: " ")

        // The prompt this raises can be dismissed, and `osascript` reports that
        // as a failure rather than raising it again. Treating it as success
        // used to leave the routes in place while the caller stopped the core.
        let result = Shell.runAsAdmin(command)
        guard let result, result.ok else {
            let reason = result?.message() ?? "не удалось запустить osascript"
            log("Не удалось снять TUN: \(reason)")
            return "Не удалось снять TUN — туннель всё ещё поднят (\(reason))"
        }

        // Cleared only now: while the tunnel is up these answer the config
        // builder, and wiping them early would send a restarted core back
        // through the tunnel it feeds.
        lock.withLock {
            appliedService = nil
            storedInterface = nil
            tunnelUp = false
            // Addresses can move between sessions; the next connect resolves afresh.
            pinKey = []
            pins = [:]
        }
        log("TUN снят")
        return nil
    }
}

extension Array where Element: Hashable {
    /// Order-preserving `distinct()`.
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}

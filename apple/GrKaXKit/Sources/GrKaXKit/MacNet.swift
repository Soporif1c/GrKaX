import Foundation

/// Discovery helpers for the active macOS network service and interface.
public enum MacNet {

    private static let networksetup = "/usr/sbin/networksetup"

    /// Physical interface carrying the default route, e.g. `en0`.
    public static func defaultInterface() -> String? {
        parseInterface(routeOutput())
    }

    /// Gateway of the default route, needed to keep the proxy server reachable.
    public static func defaultGateway() -> String? {
        parseGateway(routeOutput())
    }

    private static func routeOutput() -> String {
        Shell.capture("/sbin/route", "-n", "get", "default") ?? ""
    }

    /// Name of the network service (as System Settings shows it, e.g. "Wi-Fi")
    /// that owns `device`.
    ///
    /// `networksetup` only accepts service names while the routing table only
    /// knows device names, so the two have to be matched up through the service
    /// order listing.
    public static func serviceForDevice(_ device: String) -> String? {
        guard let out = Shell.capture(networksetup, "-listnetworkserviceorder") else { return nil }
        return parseService(forDevice: device, in: out)
    }

    // MARK: - Parsing
    //
    // Split out from the commands so it can be tested against captured output:
    // this is regex over the text of four different tools, and it is where a
    // wrong answer sends the proxy to the wrong network service.

    static func parseInterface(_ routeOutput: String) -> String? {
        firstMatch(in: routeOutput, pattern: #"interface:\s*(\S+)"#)
    }

    static func parseGateway(_ routeOutput: String) -> String? {
        firstMatch(in: routeOutput, pattern: #"gateway:\s*(\S+)"#)
    }

    static func parseService(forDevice device: String, in listing: String) -> String? {
        // Blocks look like:
        //   (1) Wi-Fi
        //   (Hardware Port: Wi-Fi, Device: en0)
        //
        // Other VPN clients register services with no device at all —
        // `(Hardware Port: su.ffg.happ, Device: )`. The character class rejects
        // those, which is what keeps an empty `device` from matching one of
        // them and pointing the proxy at a service that carries no traffic.
        var lastService: String?
        for line in listing.split(separator: "\n", omittingEmptySubsequences: false) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if let service = firstMatch(in: trimmed, pattern: #"^\(\d+\)\s+(.*)$"#) {
                lastService = service.trimmingCharacters(in: .whitespaces)
                continue
            }
            if let found = firstMatch(in: trimmed, pattern: #"Device:\s*([^)\s,]+)"#), found == device {
                return lastService
            }
        }
        return nil
    }

    /// Whether a `networksetup -getsocksfirewallproxy` block describes a proxy
    /// this app set up, on `port`.
    static func describesOurProxy(_ output: String, port: Int) -> Bool {
        output.contains("Enabled: Yes")
            && output.contains(AppConfig.loopback)
            && output.contains("Port: \(port)")
    }

    /// The service the system is actually routing through, if it can be found.
    public static func activeService() -> String? {
        defaultInterface().flatMap { serviceForDevice($0) }
    }

    /// First capture group of `pattern`, or nil.
    static func firstMatch(in text: String, pattern: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.anchorsMatchLines]) else {
            return nil
        }
        let range = NSRange(text.startIndex..., in: text)
        guard let match = regex.firstMatch(in: text, range: range), match.numberOfRanges > 1 else {
            return nil
        }
        guard let captured = Range(match.range(at: 1), in: text) else { return nil }
        return String(text[captured])
    }
}

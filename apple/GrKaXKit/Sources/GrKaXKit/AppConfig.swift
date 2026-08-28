import Foundation

public enum AppConfig {
    public static let appName = "GrKa X"

    public static let loopback = "127.0.0.1"
    public static let defaultSocksPort = 10808
    public static let defaultHttpPort = 10809
    /// dokodemo-door inbound the core exposes its StatsService on.
    public static let defaultApiPort = 10810

    public static let defaultMTU = 9000

    // Point-to-point TUN addressing used by the macOS helper.
    public static let tunIPv4Client = "198.18.0.1"
    public static let tunIPv4Gateway = "198.18.0.2"
    public static let tunIPv4Mask = "255.255.0.0"

    public static let delayTestURL = "https://www.gstatic.com/generate_204"
    public static let delayTestURL2 = "https://www.google.com/generate_204"

    public static let defaultRemoteDns = "1.1.1.1"
    public static let defaultDirectDns = "77.88.8.8"

    /// Traffic mode — the Karing-style switch that sits next to the power
    /// button. Per-app routing is deliberately absent: it needs process-name
    /// rules, which the Xray core does not implement (and Xray is required here
    /// for XHTTP obfuscation passthrough).
    ///
    /// On macOS a `NETransparentProxyProvider` could do the matching outside
    /// the core, which is the one route to per-app routing that does not give
    /// up XHTTP — it needs a paid Apple Developer account.
    public static let modeRule = "rule"
    public static let modeGlobal = "global"
    public static let modeDirect = "direct"

    /// Built-in rule sets used when `modeRule` has no template to follow.
    public static let routeBypassLan = "bypass_lan"
    public static let routeBypassRu = "bypass_ru"

    /// How the OS is pointed at the core.
    ///  - `netSystemProxy`: `networksetup` writes the SOCKS/HTTP proxy into the
    ///    active network service. No privileges, but only catches apps that
    ///    honour the system proxy.
    ///  - `netTun`: a privileged helper opens a utun device and pumps it into
    ///    our SOCKS inbound. Catches everything, costs one admin prompt.
    public static let netSystemProxy = "system_proxy"
    public static let netTun = "tun"

    // UI themes — same three as the Android client.
    public static let themeAurora = "aurora"
    public static let themeOcean = "ocean"
    public static let themePearl = "pearl"

    public static let releasesAPI = "https://api.github.com/repos/Soporif1c/GrKaX/releases"
    public static let releasesPage = "https://github.com/Soporif1c/GrKaX/releases/latest"
}

/// On-disk locations.
public enum Paths {

    /// Where the shipping Compose build keeps its data.
    public static let composeDataDir = URL(fileURLWithPath: NSHomeDirectory())
        .appending(path: "Library/Application Support/GrKaX")

    /// Where this build keeps its data.
    ///
    /// Deliberately *not* the Compose directory. The two apps are installed
    /// side by side while the port is unfinished, and a half-written Swift
    /// store would take the working client's profiles down with it. The format
    /// is identical, so the directory becomes the Compose one — no migration —
    /// once this build actually replaces it.
    nonisolated(unsafe) public static var dataDir = URL(fileURLWithPath: NSHomeDirectory())
        .appending(path: "Library/Application Support/GrKaX-swift")

    /// Working dir for the core: generated config, logs, runtime state.
    public static var runtimeDir: URL { dataDir.appending(path: "runtime") }

    static func ensure(_ directory: URL) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    /// A file shipped inside the app bundle — the tunnel helper script and the
    /// `tun2socks` binary.
    ///
    /// Nil outside a bundle, which is the case under `swift test`; callers turn
    /// that into a message naming the missing file rather than failing blankly.
    public static func bundledResource(_ name: String) -> URL? {
        guard let resources = Bundle.main.resourceURL else { return nil }
        let url = resources.appending(path: name)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }
}

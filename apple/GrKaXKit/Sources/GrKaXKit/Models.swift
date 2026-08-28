import Foundation

/// Mirrors the Android and Compose model field for field — the parsers and the
/// config builder are shared logic, and keeping the shape identical means a
/// profile exported from one client can be read by the other.
///
/// Decoding is deliberately lenient: kotlinx.serialization fills in defaults for
/// absent keys, and Swift's synthesised `init(from:)` would instead throw on
/// them. A profile written by an older client, or by Android, has to keep
/// loading here.
public struct Profile: Codable, Identifiable, Hashable, Sendable {
    public var id: String
    public var name: String
    /// vless | vmess | trojan | shadowsocks
    public var proto: String
    public var server: String
    public var port: Int
    /// uuid or password
    public var uuid: String
    /// ss cipher / vless encryption / vmess security
    public var method: String?
    public var flow: String?

    // transport
    /// tcp | ws | grpc | httpupgrade | xhttp | kcp | h2
    public var network: String
    public var host: String?
    public var path: String?
    public var headerType: String?
    public var seed: String?
    public var serviceName: String?
    public var authority: String?
    public var grpcMode: String?
    public var xhttpMode: String?
    public var xhttpExtra: String?

    // security
    /// tls | reality | nil
    public var security: String?
    public var sni: String?
    public var alpn: String?
    public var fingerprint: String?
    public var allowInsecure: Bool
    public var publicKey: String?
    public var shortId: String?
    public var spiderX: String?

    // meta
    public var subId: String?
    public var rawLink: String?
    /// Full outbound JSON when delivered by an xray-json subscription. Used
    /// verbatim so complex/non-standard transport fields (XHTTP obfuscation,
    /// extra, noSSEHeader, xmux, …) are preserved exactly instead of being lost
    /// in a flatten-and-rebuild round trip.
    public var rawOutbound: String?
    /// The entire xray-json config object this profile's outbound belongs to
    /// (outbounds + routing + dns). Kept so the panel's routing template is
    /// applied with its original outbound tags intact.
    public var fullConfig: String?
    /// Tag of this profile's proxy outbound inside `fullConfig`.
    public var proxyTag: String?

    public init(
        id: String = Profile.newID(),
        name: String = "",
        proto: String = "vless",
        server: String = "",
        port: Int = 443,
        uuid: String = "",
        method: String? = nil,
        flow: String? = nil,
        network: String = "tcp",
        host: String? = nil,
        path: String? = nil,
        headerType: String? = nil,
        seed: String? = nil,
        serviceName: String? = nil,
        authority: String? = nil,
        grpcMode: String? = nil,
        xhttpMode: String? = nil,
        xhttpExtra: String? = nil,
        security: String? = nil,
        sni: String? = nil,
        alpn: String? = nil,
        fingerprint: String? = nil,
        allowInsecure: Bool = false,
        publicKey: String? = nil,
        shortId: String? = nil,
        spiderX: String? = nil,
        subId: String? = nil,
        rawLink: String? = nil,
        rawOutbound: String? = nil,
        fullConfig: String? = nil,
        proxyTag: String? = nil
    ) {
        self.id = id
        self.name = name
        self.proto = proto
        self.server = server
        self.port = port
        self.uuid = uuid
        self.method = method
        self.flow = flow
        self.network = network
        self.host = host
        self.path = path
        self.headerType = headerType
        self.seed = seed
        self.serviceName = serviceName
        self.authority = authority
        self.grpcMode = grpcMode
        self.xhttpMode = xhttpMode
        self.xhttpExtra = xhttpExtra
        self.security = security
        self.sni = sni
        self.alpn = alpn
        self.fingerprint = fingerprint
        self.allowInsecure = allowInsecure
        self.publicKey = publicKey
        self.shortId = shortId
        self.spiderX = spiderX
        self.subId = subId
        self.rawLink = rawLink
        self.rawOutbound = rawOutbound
        self.fullConfig = fullConfig
        self.proxyTag = proxyTag
    }

    /// Kotlin's `UUID.randomUUID().toString()` is lower case; Foundation's is
    /// upper. Matched so ids stay byte-identical across the two clients.
    public static func newID() -> String {
        UUID().uuidString.lowercased()
    }

    /// `protocol` is a keyword in Swift, so the property is `proto` while the
    /// wire name stays what Android and Compose write.
    private enum CodingKeys: String, CodingKey {
        case id, name, server, port, uuid, method, flow
        case network, host, path, headerType, seed, serviceName, authority
        case grpcMode, xhttpMode, xhttpExtra
        case security, sni, alpn, fingerprint, allowInsecure, publicKey, shortId, spiderX
        case subId, rawLink, rawOutbound, fullConfig, proxyTag
        case proto = "protocol"
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? Profile.newID()
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        proto = try c.decodeIfPresent(String.self, forKey: .proto) ?? "vless"
        server = try c.decodeIfPresent(String.self, forKey: .server) ?? ""
        port = try c.decodeIfPresent(Int.self, forKey: .port) ?? 443
        uuid = try c.decodeIfPresent(String.self, forKey: .uuid) ?? ""
        method = try c.decodeIfPresent(String.self, forKey: .method)
        flow = try c.decodeIfPresent(String.self, forKey: .flow)
        network = try c.decodeIfPresent(String.self, forKey: .network) ?? "tcp"
        host = try c.decodeIfPresent(String.self, forKey: .host)
        path = try c.decodeIfPresent(String.self, forKey: .path)
        headerType = try c.decodeIfPresent(String.self, forKey: .headerType)
        seed = try c.decodeIfPresent(String.self, forKey: .seed)
        serviceName = try c.decodeIfPresent(String.self, forKey: .serviceName)
        authority = try c.decodeIfPresent(String.self, forKey: .authority)
        grpcMode = try c.decodeIfPresent(String.self, forKey: .grpcMode)
        xhttpMode = try c.decodeIfPresent(String.self, forKey: .xhttpMode)
        xhttpExtra = try c.decodeIfPresent(String.self, forKey: .xhttpExtra)
        security = try c.decodeIfPresent(String.self, forKey: .security)
        sni = try c.decodeIfPresent(String.self, forKey: .sni)
        alpn = try c.decodeIfPresent(String.self, forKey: .alpn)
        fingerprint = try c.decodeIfPresent(String.self, forKey: .fingerprint)
        allowInsecure = try c.decodeIfPresent(Bool.self, forKey: .allowInsecure) ?? false
        publicKey = try c.decodeIfPresent(String.self, forKey: .publicKey)
        shortId = try c.decodeIfPresent(String.self, forKey: .shortId)
        spiderX = try c.decodeIfPresent(String.self, forKey: .spiderX)
        subId = try c.decodeIfPresent(String.self, forKey: .subId)
        rawLink = try c.decodeIfPresent(String.self, forKey: .rawLink)
        rawOutbound = try c.decodeIfPresent(String.self, forKey: .rawOutbound)
        fullConfig = try c.decodeIfPresent(String.self, forKey: .fullConfig)
        proxyTag = try c.decodeIfPresent(String.self, forKey: .proxyTag)
    }

    // MARK: - Display

    public var protoLabel: String {
        switch proto {
        case "vless": "VLESS"
        case "vmess": "VMess"
        case "trojan": "Trojan"
        case "shadowsocks": "SS"
        default: proto.uppercased()
        }
    }

    public var transportLabel: String {
        let net = network.uppercased()
        let sec: String? = switch security {
        case "tls": "TLS"
        case "reality": "REALITY"
        default: nil
        }
        return sec.map { "\(net) · \($0)" } ?? net
    }

    /// Rough identity used to keep selection across subscription updates.
    public var identityKey: String {
        "\(proto)|\(server)|\(port)|\(uuid)|\(network)"
    }
}

public struct Subscription: Codable, Identifiable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var url: String
    public var lastUpdate: Int64
    /// From the subscription-userinfo header (Remnawave and others); -1 = unknown
    public var upload: Int64
    public var download: Int64
    public var total: Int64
    public var expire: Int64
    /// Routing template shipped by an xray-json subscription (Remnawave). When
    /// set, it replaces the app's routing preset for this sub's profiles.
    public var routingJson: String?
    /// Raw fetched subscription body, kept so the user can inspect it.
    public var rawBody: String?
    /// Custom User-Agent for fetching (some panels serve different formats per
    /// UA; e.g. Remnawave returns xray-json with routing to certain clients).
    public var userAgent: String?

    public init(
        id: String = Profile.newID(),
        name: String = "",
        url: String = "",
        lastUpdate: Int64 = 0,
        upload: Int64 = -1,
        download: Int64 = -1,
        total: Int64 = -1,
        expire: Int64 = -1,
        routingJson: String? = nil,
        rawBody: String? = nil,
        userAgent: String? = nil
    ) {
        self.id = id
        self.name = name
        self.url = url
        self.lastUpdate = lastUpdate
        self.upload = upload
        self.download = download
        self.total = total
        self.expire = expire
        self.routingJson = routingJson
        self.rawBody = rawBody
        self.userAgent = userAgent
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? Profile.newID()
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        url = try c.decodeIfPresent(String.self, forKey: .url) ?? ""
        lastUpdate = try c.decodeIfPresent(Int64.self, forKey: .lastUpdate) ?? 0
        upload = try c.decodeIfPresent(Int64.self, forKey: .upload) ?? -1
        download = try c.decodeIfPresent(Int64.self, forKey: .download) ?? -1
        total = try c.decodeIfPresent(Int64.self, forKey: .total) ?? -1
        expire = try c.decodeIfPresent(Int64.self, forKey: .expire) ?? -1
        routingJson = try c.decodeIfPresent(String.self, forKey: .routingJson)
        rawBody = try c.decodeIfPresent(String.self, forKey: .rawBody)
        userAgent = try c.decodeIfPresent(String.self, forKey: .userAgent)
    }
}

/// Immutable view of the settings handed to the config builder.
public struct SettingsSnapshot: Hashable, Sendable {
    public let socksPort: Int
    public let httpPort: Int
    public let apiPort: Int
    public let remoteDns: String
    public let directDns: String
    public let mode: String
    public let routingPreset: String
    public let blockQuic: Bool
    public let bypassTorrent: Bool
    public let sniffing: Bool
    public let routeOnly: Bool
    public let mux: Bool
    public let logLevel: String
    /// Physical interface (e.g. `en0`) every outbound is pinned to in TUN mode.
    /// Without it the core's own traffic is routed back into the tunnel it
    /// feeds, and the connection deadlocks. Nil in system-proxy mode.
    ///
    /// Both this and `serverPins` exist only because the tunnel is built by
    /// hand. A NEPacketTunnelProvider excludes the provider's own sockets from
    /// the tunnel itself, and both fields go away with it.
    public let bindInterface: String?
    /// Static hostname → IPv4 answers for the proxy servers, fed to the core's
    /// DNS as facts. In TUN mode the core cannot look these up itself: its
    /// resolver is reached through the tunnel, and the tunnel only carries
    /// traffic once the connection this lookup would establish already exists.
    /// A server given as a bare IP never needed this, which is why those were
    /// the only ones that worked. Empty in system-proxy mode.
    public let serverPins: [String: [String]]

    public init(
        socksPort: Int,
        httpPort: Int,
        apiPort: Int,
        remoteDns: String,
        directDns: String,
        mode: String,
        routingPreset: String,
        blockQuic: Bool,
        bypassTorrent: Bool,
        sniffing: Bool,
        routeOnly: Bool,
        mux: Bool,
        logLevel: String,
        bindInterface: String? = nil,
        serverPins: [String: [String]] = [:]
    ) {
        self.socksPort = socksPort
        self.httpPort = httpPort
        self.apiPort = apiPort
        self.remoteDns = remoteDns
        self.directDns = directDns
        self.mode = mode
        self.routingPreset = routingPreset
        self.blockQuic = blockQuic
        self.bypassTorrent = bypassTorrent
        self.sniffing = sniffing
        self.routeOnly = routeOnly
        self.mux = mux
        self.logLevel = logLevel
        self.bindInterface = bindInterface
        self.serverPins = serverPins
    }
}

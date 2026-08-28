import Foundation
import Testing
@testable import GrKaXKit

/// The config is the payload the core actually receives, so it is compared as
/// exact text — key order included.
///
/// A wrong config does not fail loudly. The core either refuses to start, which
/// surfaces as "core exited immediately", or it starts and quietly routes
/// traffic somewhere other than intended. Neither is debuggable from a user's
/// report, which is why these 23 cases exist.
struct GoldenConfigTests {

    private struct Settings: Decodable {
        let socksPort: Int
        let httpPort: Int
        let apiPort: Int
        let remoteDns: String
        let directDns: String
        let mode: String
        let routingPreset: String
        let blockQuic: Bool
        let bypassTorrent: Bool
        let sniffing: Bool
        let routeOnly: Bool
        let mux: Bool
        let logLevel: String
        let bindInterface: String?
        let serverPins: [String: [String]]

        var snapshot: SettingsSnapshot {
            SettingsSnapshot(
                socksPort: socksPort, httpPort: httpPort, apiPort: apiPort,
                remoteDns: remoteDns, directDns: directDns,
                mode: mode, routingPreset: routingPreset,
                blockQuic: blockQuic, bypassTorrent: bypassTorrent,
                sniffing: sniffing, routeOnly: routeOnly, mux: mux,
                logLevel: logLevel, bindInterface: bindInterface, serverPins: serverPins
            )
        }
    }

    private struct Case: Decodable {
        let name: String
        let profile: Profile
        let settings: Settings
        let routingJson: String?
        let useSubRouting: Bool
        let customTemplate: String?
        let config: String
    }

    private static func cases() throws -> [Case] {
        let url = try #require(
            Bundle.module.url(forResource: "Golden/configs", withExtension: "json"),
            "run ./gradlew golden"
        )
        return try JSONDecoder().decode([Case].self, from: Data(contentsOf: url))
    }

    @Test func matchesKotlinForEveryCase() throws {
        let cases = try Self.cases()
        #expect(cases.count >= 20)

        for testCase in cases {
            let actual = ConfigBuilder.build(
                profile: testCase.profile,
                settings: testCase.settings.snapshot,
                routingJson: testCase.routingJson,
                useSubRouting: testCase.useSubRouting,
                customTemplate: testCase.customTemplate
            )
            #expect(
                actual == testCase.config,
                """
                конфиг «\(testCase.name)» разошёлся с Kotlin
                Kotlin: \(testCase.config)
                Swift:  \(actual)
                """
            )
        }
    }

    /// Transports the shipping Compose client still emits and no current Xray
    /// accepts.
    ///
    /// Both were removed from the core, not from us: `http` transport migrated
    /// to XHTTP stream-one H2/H3, and mKCP's `header`/`seed` migrated to
    /// finalmask. The bundled 26.7.11 core rejects these configs exactly as
    /// 26.7.28 does, so a user with an h2 or mKCP server cannot connect today —
    /// this is a pre-existing bug the port inherited, not one it introduced.
    ///
    /// Listed rather than skipped so that fixing ConfigBuilder makes this test
    /// fail and forces the list to shrink.
    private static let rejectedByCurrentCore: Set<String> = ["h2", "kcp"]

    /// Every generated config has to be one the core will actually accept —
    /// byte-equality with Kotlin would not catch a shape both got wrong.
    ///
    /// Needs the geo data: the default rule set names `geoip:` and `geosite:`,
    /// and the core rejects the whole config when it cannot open the files.
    @Test func everyConfigStartsTheCore() async throws {
        try await withExclusiveCore {
        try requireGeoData()
        for testCase in try Self.cases() {
            let config = ConfigBuilder.build(
                profile: testCase.profile,
                settings: testCase.settings.snapshot,
                routingJson: testCase.routingJson,
                useSubRouting: testCase.useSubRouting,
                customTemplate: testCase.customTemplate
            )
            defer { try? XrayCore.stop() }

            if Self.rejectedByCurrentCore.contains(testCase.name) {
                #expect(throws: XrayCoreError.self, "«\(testCase.name)» снова принимается ядром — убери его из rejectedByCurrentCore") {
                    try XrayCore.run(configJSON: config)
                }
                continue
            }
            #expect(throws: Never.self, "ядро отвергло конфиг «\(testCase.name)»") {
                try XrayCore.run(configJSON: config)
            }
        }
        }
    }

    // MARK: - Named invariants
    //
    // These duplicate coverage the golden files already give, but they name the
    // failure. Each is a bug that shipped and was fixed in the Compose client.

    private func config(named name: String) throws -> String {
        let testCase = try #require(try Self.cases().first { $0.name == name })
        return ConfigBuilder.build(
            profile: testCase.profile,
            settings: testCase.settings.snapshot,
            routingJson: testCase.routingJson,
            useSubRouting: testCase.useSubRouting,
            customTemplate: testCase.customTemplate
        )
    }

    /// Without this the core dials its own server through the tunnel it feeds.
    @Test func tunModePinsOutboundsToThePhysicalInterface() throws {
        let tun = try config(named: "link-tun")
        #expect(tun.contains(#""interface":"en0""#))

        let proxy = try config(named: "link-rule")
        #expect(!proxy.contains(#""interface""#), "pinning leaked into system-proxy mode")
    }

    /// In TUN mode the core's resolver sits behind the connection this lookup
    /// would have to open first.
    @Test func tunModeFeedsResolvedServerAddressesToDns() throws {
        let tun = try config(named: "link-tun")
        #expect(tun.contains(#""example.com":["203.0.113.10"]"#))
        #expect(!(try config(named: "link-rule")).contains("203.0.113.10"))
    }

    /// The dial has to target the resolved address while the handshake still
    /// presents the hostname. Swap one and lose the other and TLS fails — and
    /// the verbatim subscription path is the one that shipped broken.
    @Test func addressSubstitutionKeepsTheSNI() throws {
        for name in ["link-tun", "sub-tun"] {
            let config = try config(named: name)
            #expect(config.contains(#""address":"203.0.113.10""#), "\(name) всё ещё набирает имя")
            #expect(config.contains(#""serverName":"www.microsoft.com""#), "\(name) потерял SNI")
        }
        #expect(try config(named: "link-rule").contains(#""address":"example.com""#))
    }

    /// The whole reason Xray is required rather than a lighter core.
    @Test func xhttpObfuscationSurvivesIntoTheConfig() throws {
        let config = try config(named: "sub-rule")
        for marker in ["noSSEHeader", "sessionIDLength", "packet-up", "xmux"] {
            #expect(config.contains(marker), "потеряно поле XHTTP: \(marker)")
        }
        // Legacy key renamed, old spelling gone.
        #expect(!config.contains("\"sessionLength\""))
    }

    /// The stats rule has to outrank whatever the panel shipped, or the traffic
    /// counters silently read zero.
    @Test func apiRuleComesFirstInEveryRoutingSet() throws {
        for name in ["link-rule", "sub-rule", "template", "link-global", "link-direct"] {
            let config = try config(named: name)
            let rules = try #require(JSON.parse(config)?["routing"]?["rules"]?.arrayValue)
            #expect(rules.first?["outboundTag"]?.stringValue == "api", "api-правило не первое в «\(name)»")
        }
    }

    /// Mux breaks xhttp, and the toggle must not override that.
    @Test func muxStaysOffForXhttp() throws {
        let xhttp = try config(named: "link-mux-routeonly")
        let mux = try #require(JSON.parse(xhttp)?["outbounds"]?.arrayValue?.first?["mux"])
        #expect(mux["enabled"]?.boolValue == false)

        let vmess = try config(named: "vmess-mux")
        let vmessMux = try #require(JSON.parse(vmess)?["outbounds"]?.arrayValue?.first?["mux"])
        #expect(vmessMux["enabled"]?.boolValue == true)
    }

    /// Global and Direct ignore the panel's routing on purpose — otherwise the
    /// switch beside the power button is a lie.
    @Test func globalAndDirectIgnoreSubscriptionRouting() throws {
        let global = try #require(JSON.parse(try config(named: "sub-global"))?["routing"])
        #expect(global["domainStrategy"]?.stringValue == "IPIfNonMatch")
        // The subscription's own rule set mentions geosite:category-ru; ours
        // in Global mode does not.
        #expect(!(try config(named: "sub-global")).contains("category-ru"))
    }
}

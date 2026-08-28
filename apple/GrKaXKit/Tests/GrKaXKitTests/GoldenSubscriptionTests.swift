import Foundation
import Testing
@testable import GrKaXKit

/// The subscription path is where the app's reason for existing lives: an
/// xray-json body carries a ready-made outbound with XHTTP obfuscation, and it
/// has to reach the core untouched. `rawOutbound` and `fullConfig` are compared
/// as text here, so any key the Swift JSON layer reordered — or any number it
/// respelled — fails the test rather than quietly changing a config.
struct GoldenSubscriptionTests {

    private struct Case: Decodable {
        let body: String
        let profiles: [Profile]?
        let routingJson: String?
    }

    @Test func matchesKotlinForEverySubscriptionBody() throws {
        let url = try #require(
            Bundle.module.url(forResource: "Golden/subscriptions", withExtension: "json"),
            "run ./gradlew golden"
        )
        let cases = try JSONDecoder().decode([Case].self, from: Data(contentsOf: url))
        #expect(cases.count >= 6)

        for testCase in cases {
            let result = JsonSubscriptionParser.parse(testCase.body)

            let actual = result?.profiles.map { profile -> Profile in
                var copy = profile
                copy.id = ""
                return copy
            }
            #expect(
                actual == testCase.profiles,
                """
                профили разошлись с Kotlin для тела:
                \(testCase.body.prefix(120))…
                """
            )
            #expect(
                result?.routingJson == testCase.routingJson,
                "routing разошёлся для тела: \(testCase.body.prefix(80))…"
            )
        }
    }

    /// Named separately because this is the regression that would be silent:
    /// the config still validates, the connection still opens, and the
    /// obfuscation is simply gone.
    @Test func keepsXhttpObfuscationVerbatim() throws {
        let body = """
        {"outbounds":[{"tag":"p","protocol":"vless","settings":{"vnext":[{"address":"e.example",\
        "port":443,"users":[{"id":"u","encryption":"none"}]}]},"streamSettings":{"network":"xhttp",\
        "security":"tls","xhttpSettings":{"mode":"packet-up","extra":{"noSSEHeader":true,\
        "sessionLength":8,"xmux":{"maxConcurrency":"16-32"}}}}}]}
        """
        let result = try #require(JsonSubscriptionParser.parse(body))
        let raw = try #require(result.profiles.first?.rawOutbound)

        for marker in ["noSSEHeader", "sessionLength", "xmux", "maxConcurrency", "packet-up"] {
            #expect(raw.contains(marker), "потеряно поле XHTTP: \(marker)")
        }
        #expect(result.profiles.first?.xhttpExtra?.contains("noSSEHeader") == true)
    }

    @Test func rejectsBodiesWithNoProxyOutbound() {
        #expect(JsonSubscriptionParser.parse(#"{"outbounds":[{"tag":"d","protocol":"freedom"}]}"#) == nil)
        #expect(JsonSubscriptionParser.parse(#"{"hello":"world"}"#) == nil)
        #expect(JsonSubscriptionParser.parse("not json") == nil)
    }

    @Test func recognisesJsonBodies() {
        #expect(JsonSubscriptionParser.looksLikeJson("  {\"a\":1}"))
        #expect(JsonSubscriptionParser.looksLikeJson("\n[1]"))
        #expect(!JsonSubscriptionParser.looksLikeJson("vless://x@y:443"))
    }
}

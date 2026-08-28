import Foundation
import Testing
@testable import GrKaXKit

/// Compares the Swift parser against fixtures dumped from the Kotlin one.
///
/// This is the guard that makes the rewrite checkable. A share link is parsed
/// by rules spread across `java.net.URI`, `URLDecoder` and a pile of panel
/// quirks; re-deriving them by reading the code is exactly how a port grows
/// bugs the original never had. Regenerate with `./gradlew golden`.
struct GoldenLinkTests {

    private struct Case: Decodable {
        let link: String
        let profile: Profile?
    }

    private struct Batch: Decodable {
        let encoded: String
        let profiles: [Profile]
    }

    private static func fixture<T: Decodable>(_ name: String, as type: T.Type) throws -> T {
        let url = try #require(
            Bundle.module.url(forResource: "Golden/\(name)", withExtension: "json"),
            "fixture Golden/\(name).json is missing — run ./gradlew golden"
        )
        return try JSONDecoder().decode(T.self, from: Data(contentsOf: url))
    }

    /// Ids are freshly generated on every parse, so they are blanked on both
    /// sides — the Kotlin dump does the same before writing.
    private func normalized(_ profile: Profile?) -> Profile? {
        guard var profile else { return nil }
        profile.id = ""
        return profile
    }

    @Test func matchesKotlinForEveryLink() throws {
        let cases = try Self.fixture("links", as: [Case].self)
        #expect(cases.count > 20, "fixture looks truncated")

        for testCase in cases {
            let actual = normalized(LinkParser.parse(testCase.link))
            #expect(
                actual == testCase.profile,
                """
                разошлось с Kotlin для ссылки:
                \(testCase.link)
                Kotlin: \(String(describing: testCase.profile))
                Swift:  \(String(describing: actual))
                """
            )
        }
    }

    @Test func matchesKotlinForBase64Batch() throws {
        let batch = try Self.fixture("batch", as: Batch.self)
        let actual = LinkParser.parseBatch(batch.encoded).map { normalized($0)! }
        #expect(actual == batch.profiles)
    }

    /// Spot checks for the quirks worth naming, so a regression says what broke
    /// rather than just pointing at a link.
    @Test func keepsPlusInFragmentButNotInQuery() throws {
        let named = try #require(LinkParser.parse("vless://u@h.example:443?encryption=none#Berlin+Fast"))
        #expect(named.name == "Berlin+Fast", "URI fragments are not form-encoded")

        let queried = try #require(LinkParser.parse("vless://u@h.example:443?encryption=none&sni=a+b#N"))
        #expect(queried.sni == "a b", "query values go through URLDecoder, where + is a space")
    }

    @Test func keepsBracketsAroundIPv6Host() throws {
        let profile = try #require(LinkParser.parse("vless://u@[2001:db8::1]:443?encryption=none#V6"))
        #expect(
            profile.server == "[2001:db8::1]",
            "java.net.URI reports IPv6 literals bracketed, and the config builder relies on it"
        )
    }

    @Test func rewritesSpacesAndPipesBeforeParsing() throws {
        let profile = try #require(LinkParser.parse("vless://u@h.example:443?encryption=none#Berlin 01 | fast"))
        #expect(profile.name == "Berlin 01 | fast")
    }
}

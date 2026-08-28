import Foundation
import Testing
@testable import GrKaXKit

/// The JSON type is load-bearing: whole outbounds travel through the app as
/// text and reach the core unmodified, so anything this layer rewrites is a
/// change to a config the user never asked for.
struct JSONTests {

    private struct RoundTrip: Decodable {
        let input: String
        let compact: String
    }

    @Test func matchesKotlinRoundTrips() throws {
        let url = try #require(
            Bundle.module.url(forResource: "Golden/json", withExtension: "json"),
            "run ./gradlew golden"
        )
        let cases = try JSONDecoder().decode([RoundTrip].self, from: Data(contentsOf: url))
        #expect(cases.count > 10)

        for testCase in cases {
            let parsed = try #require(JSON.parse(testCase.input), "не распарсилось: \(testCase.input)")
            #expect(
                parsed.compact == testCase.compact,
                """
                разошлось с kotlinx:
                вход:   \(testCase.input)
                Kotlin: \(testCase.compact)
                Swift:  \(parsed.compact)
                """
            )
        }
    }

    @Test func preservesKeyOrder() throws {
        let parsed = try #require(JSON.parse(#"{"zebra":1,"alpha":2,"middle":3}"#))
        #expect(parsed.objectValue?.keys == ["zebra", "alpha", "middle"])
        #expect(parsed.compact == #"{"zebra":1,"alpha":2,"middle":3}"#)
    }

    /// Reformatting a port as `443.0`, or an exponent as `1000`, changes the
    /// config text the core is given.
    @Test func preservesNumberLiterals() throws {
        let parsed = try #require(JSON.parse(#"{"a":443,"b":0.0,"c":1e3,"d":10000000000}"#))
        #expect(parsed.compact == #"{"a":443,"b":0.0,"c":1e3,"d":10000000000}"#)
        #expect(parsed["a"]?.intValue == 443)
    }

    /// Last value wins, and keeps the position the key first appeared at —
    /// LinkedHashMap semantics.
    @Test func lastDuplicateKeyWins() throws {
        let parsed = try #require(JSON.parse(#"{"a":1,"dup":1,"b":2,"dup":9}"#))
        #expect(parsed.compact == #"{"a":1,"dup":9,"b":2}"#)
    }

    @Test func reassignmentKeepsPosition() {
        var object = JSONObject()
        object["first"] = .int(1)
        object["second"] = .int(2)
        object["first"] = .int(99)
        #expect(object.keys == ["first", "second"])
        #expect(JSON.object(object).compact == #"{"first":99,"second":2}"#)
    }

    @Test func removingKeyDropsItFromOrder() {
        var object = JSONObject()
        object["a"] = .int(1)
        object["b"] = .int(2)
        object["a"] = nil
        #expect(object.keys == ["b"])
        #expect(!object.has("a"))
    }

    @Test func handlesEscapesAndSurrogatePairs() throws {
        let parsed = try #require(JSON.parse(#"{"s":"a\"b\\c\/d\teAf🚀"}"#))
        #expect(parsed["s"]?.stringValue == "a\"b\\c/d\te\u{41}f🚀")
        // `/` arrives escaped and leaves unescaped — kotlinx does not re-escape
        // it — and the surrogate pair comes back as one scalar.
        #expect(parsed.compact == #"{"s":"a\"b\\c/d\teAf🚀"}"#)
    }

    @Test func rejectsMalformedInput() {
        #expect(JSON.parse("") == nil)
        #expect(JSON.parse("{") == nil)
        #expect(JSON.parse(#"{"a":}"#) == nil)
        #expect(JSON.parse(#"{"a":1}trailing"#) == nil)
        #expect(JSON.parse("[1,2") == nil)
        #expect(JSON.parse("nul") == nil)
    }

    @Test func prettyPrintsForTheLogScreen() throws {
        let parsed = try #require(JSON.parse(#"{"a":[1,2],"b":{}}"#))
        #expect(parsed.pretty == """
        {
          "a": [
            1,
            2
          ],
          "b": {}
        }
        """)
    }

    @Test func blankStringsReadAsAbsent() throws {
        let parsed = try #require(JSON.parse(#"{"empty":"","spaces":"  ","real":"x"}"#))
        #expect(parsed["empty"]?.nonBlankString == nil)
        #expect(parsed["spaces"]?.nonBlankString == nil)
        #expect(parsed["real"]?.nonBlankString == "x")
    }
}

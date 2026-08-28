import Foundation
import Testing
@testable import GrKaXKit

struct ProfileCodingTests {

    /// The point of the hand-written decoder: a profile written by Android or
    /// by an older build carries only the keys it knows about, and everything
    /// missing has to fall back rather than fail the whole load.
    @Test func decodesSparseProfileWithDefaults() throws {
        let json = Data(#"{"name":"Node","server":"example.com"}"#.utf8)
        let profile = try JSONDecoder().decode(Profile.self, from: json)

        #expect(profile.name == "Node")
        #expect(profile.server == "example.com")
        #expect(profile.proto == "vless")
        #expect(profile.port == 443)
        #expect(profile.network == "tcp")
        #expect(profile.allowInsecure == false)
        #expect(!profile.id.isEmpty)
        #expect(profile.security == nil)
    }

    /// `protocol` is a Swift keyword, so the property is renamed — the wire key
    /// must not follow it, or the two clients stop understanding each other.
    @Test func keepsProtocolAsWireKey() throws {
        let profile = Profile(name: "n", proto: "trojan", server: "a.b", port: 8443)
        let data = try JSONEncoder().encode(profile)
        let object = try #require(
            try JSONSerialization.jsonObject(with: data) as? [String: Any]
        )

        #expect(object["protocol"] as? String == "trojan")
        #expect(object["proto"] == nil)

        let decoded = try JSONDecoder().decode(Profile.self, from: data)
        #expect(decoded.proto == "trojan")
        #expect(decoded == profile)
    }

    /// Unknown keys from a newer client must not blow up an older one.
    @Test func ignoresUnknownKeys() throws {
        let json = Data(#"{"name":"n","somethingNew":true,"port":8080}"#.utf8)
        let profile = try JSONDecoder().decode(Profile.self, from: json)
        #expect(profile.port == 8080)
    }

    /// Ids are compared across clients, and Kotlin writes them lower case.
    @Test func generatesLowercaseIDs() {
        let id = Profile.newID()
        #expect(id == id.lowercased())
        #expect(id.count == 36)
    }

    @Test func buildsDisplayLabels() {
        var profile = Profile(proto: "vless", network: "ws", security: "reality")
        #expect(profile.protoLabel == "VLESS")
        #expect(profile.transportLabel == "WS · REALITY")

        profile.security = nil
        #expect(profile.transportLabel == "WS")

        profile.proto = "shadowsocks"
        #expect(profile.protoLabel == "SS")
    }

    @Test func identityKeySurvivesRename() {
        let a = Profile(name: "Берлин", proto: "vless", server: "a.b", port: 443, uuid: "u", network: "ws")
        var b = a
        b.name = "Berlin (new)"
        b.id = Profile.newID()
        #expect(a.identityKey == b.identityKey)
    }
}

struct SubscriptionCodingTests {

    /// -1 means "the panel did not tell us", which is different from 0.
    @Test func defaultsUsageCountersToUnknown() throws {
        let json = Data(#"{"name":"sub","url":"https://example.com/s"}"#.utf8)
        let sub = try JSONDecoder().decode(Subscription.self, from: json)

        #expect(sub.upload == -1)
        #expect(sub.download == -1)
        #expect(sub.total == -1)
        #expect(sub.expire == -1)
        #expect(sub.lastUpdate == 0)
    }
}

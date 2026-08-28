import Foundation
import Testing
@testable import GrKaXKit

/// Header handling, which is where a subscription silently comes back wrong:
/// the panel decides what to serve from the User-Agent, and reports quota in a
/// header format nobody validates.
struct SubscriptionManagerTests {

    @Test func defaultsToOurOwnUserAgent() {
        let agent = SubscriptionManager.userAgent(for: Subscription(name: "s", url: "https://e.example"))
        #expect(agent.hasPrefix("GrKaX/"))
    }

    /// A bare "happ" is expanded, because panels match on the full UA string —
    /// a rule reading "user-agent contains happ" would still fire, but one
    /// matching the version would not.
    @Test func expandsBareHappUserAgent() {
        var sub = Subscription(name: "s", url: "https://e.example")
        sub.userAgent = "happ"
        #expect(SubscriptionManager.userAgent(for: sub) == "Happ/3.13.0")

        sub.userAgent = "HAPP"
        #expect(SubscriptionManager.userAgent(for: sub) == "Happ/3.13.0")

        sub.userAgent = "Happ/2.0.0"
        #expect(SubscriptionManager.userAgent(for: sub) == "Happ/2.0.0", "явную версию менять нельзя")

        sub.userAgent = "   "
        #expect(SubscriptionManager.userAgent(for: sub).hasPrefix("GrKaX/"))
    }

    @Test func parsesQuotaHeader() {
        var sub = Subscription(name: "s", url: "https://e.example")
        SubscriptionManager.applyUserInfo(
            &sub,
            "upload=1024; download=2048; total=1099511627776; expire=1767225600"
        )
        #expect(sub.upload == 1024)
        #expect(sub.download == 2048)
        #expect(sub.total == 1_099_511_627_776)
        #expect(sub.expire == 1_767_225_600)
    }

    /// Unknown or malformed parts must not wipe the values that did parse.
    @Test func ignoresJunkInQuotaHeader() {
        var sub = Subscription(name: "s", url: "https://e.example")
        sub.total = 999
        SubscriptionManager.applyUserInfo(&sub, "upload=10; nonsense; download=; total=abc; expire=1e9")

        #expect(sub.upload == 10)
        #expect(sub.download == -1, "пустое значение не должно засчитаться")
        #expect(sub.total == 999, "нечисловое значение не должно затирать прежнее")
        #expect(sub.expire == 1_000_000_000, "экспоненциальная запись встречается у панелей")
    }

    @Test func decodesBase64ProfileTitle() {
        let encoded = "base64:" + Data("Берлин".utf8).base64EncodedString()
        #expect(SubscriptionManager.decodeProfileTitle(encoded) == "Берлин")
        #expect(SubscriptionManager.decodeProfileTitle("Плоский заголовок") == "Плоский заголовок")
    }
}

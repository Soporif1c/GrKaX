import Testing
@testable import GrKaXKit

/// Proves the whole bridge is wired: Swift → cgo → Go → Xray → back.
/// If these pass, the linking, the modulemap and the JSON envelope are all
/// correct, and everything else is ordinary porting.
struct XrayCoreTests {

    @Test func reportsCoreVersion() throws {
        let version = try XrayCore.version()
        #expect(!version.isEmpty)
    }

    @Test func startsIdle() async throws {
        try await withExclusiveCore {
            let running = try XrayCore.isRunning()
            #expect(running == false)
        }
    }

    @Test func handsOutFreePorts() throws {
        let ports = try XrayCore.freePorts(count: 3)
        #expect(ports.count == 3)
        #expect(Set(ports).count == 3)
        #expect(ports.allSatisfy { $0 > 1024 && $0 < 65536 })
    }

    /// A malformed config has to come back as `.core`, carrying the core's own
    /// message — that message is what the log screen shows the user.
    @Test func surfacesCoreErrors() async throws {
        try await withExclusiveCore {
            #expect(throws: XrayCoreError.self) {
                try XrayCore.run(configJSON: #"{"inbounds":"not an array"}"#)
            }
        }
    }
}

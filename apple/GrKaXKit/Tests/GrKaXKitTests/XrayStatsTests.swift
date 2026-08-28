import Darwin
import Foundation
import Testing
@testable import GrKaXKit

/// The stats path is hand-rolled down to the HTTP/2 frames, so it is checked
/// against the real core rather than against a mock: traffic is pushed through
/// the core's own SOCKS inbound to a local echo server, and the counters have to
/// come back non-zero.
struct XrayStatsTests {

    @Test func parsesQueryStatsResponse() {
        // QueryStatsResponse{ stat: [ {name, value}, … ] }, built by hand.
        func stat(_ name: String, _ value: Int64) -> [UInt8] {
            var inner: [UInt8] = []
            let nameBytes = Array(name.utf8)
            inner += Protobuf.varint(UInt64(1 << 3 | 2))
            inner += Protobuf.varint(UInt64(nameBytes.count))
            inner += nameBytes
            inner += Protobuf.encodeVarintField(number: 2, value: UInt64(value))

            var out: [UInt8] = []
            out += Protobuf.varint(UInt64(1 << 3 | 2))
            out += Protobuf.varint(UInt64(inner.count))
            out += inner
            return out
        }

        let payload = stat("outbound>>>proxy>>>traffic>>>uplink", 100)
            + stat("outbound>>>proxy>>>traffic>>>downlink", 250)
            + stat("outbound>>>other>>>traffic>>>uplink", 7)
            // Inbound counters must not be summed into the totals.
            + stat("inbound>>>socks>>>traffic>>>uplink", 9999)

        let counters = XrayStats.parseCounters(payload)
        #expect(counters.up == 107, "все outbound складываются, inbound игнорируется")
        #expect(counters.down == 250)
    }

    @Test func handlesEmptyResponse() {
        let counters = XrayStats.parseCounters([])
        #expect(counters.up == 0)
        #expect(counters.down == 0)
    }

    /// End to end: real core, real traffic, real gRPC call.
    @Test func readsCountersFromTheRunningCore() async throws {
        try await withExclusiveCore {
        try requireGeoData()

        let echo = try EchoServer()
        echo.start()
        defer { echo.stop() }

        let ports = try XrayCore.freePorts(count: 2)
        let socksPort = ports[0]
        let apiPort = ports[1]

        // Direct mode: everything goes out through freedom, so no server is
        // needed — but the api inbound and the stats policy are the same ones
        // ConfigBuilder emits for a real profile.
        let profile = Profile(name: "stats", proto: "vless", server: "unused.example", port: 443, uuid: "u")
        let settings = SettingsSnapshot(
            socksPort: socksPort, httpPort: ports[0] + 1, apiPort: apiPort,
            remoteDns: "1.1.1.1", directDns: "77.88.8.8",
            mode: AppConfig.modeDirect, routingPreset: AppConfig.routeBypassLan,
            blockQuic: false, bypassTorrent: false, sniffing: false,
            routeOnly: false, mux: false, logLevel: "warning"
        )
        try XrayCore.run(configJSON: ConfigBuilder.build(profile: profile, settings: settings))
        defer { try? XrayCore.stop() }

        // Give the inbounds a moment to bind.
        try await Task.sleep(for: .milliseconds(300))

        let sent = 4096
        try pushThroughSocks(socksPort: socksPort, to: echo.port, bytes: sent)
        try await Task.sleep(for: .milliseconds(300))

        let counters = try XrayStats.queryDelta(apiPort: apiPort)
        #expect(counters.up >= Int64(sent), "uplink не досчитался: \(counters.up)")
        #expect(counters.down >= Int64(sent), "downlink не досчитался: \(counters.down)")

        // Counters reset on read, so a second call over an idle core is zero.
        let second = try XrayStats.queryDelta(apiPort: apiPort)
        #expect(second.up < Int64(sent), "счётчики не сбросились: \(second.up)")
        }
    }

    /// Minimal SOCKS5 client: greet, CONNECT to the echo server, send and read
    /// back `bytes` so both directions register on the core's counters.
    private func pushThroughSocks(socksPort: Int, to echoPort: UInt16, bytes: Int) throws {
        let handle = socket(AF_INET, SOCK_STREAM, 0)
        try #require(handle >= 0)
        defer { close(handle) }

        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = UInt16(socksPort).bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        var tv = timeval(tv_sec: 5, tv_usec: 0)
        setsockopt(handle, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        let connected = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(handle, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        try #require(connected == 0, "SOCKS-вход ядра не принял соединение")

        func send(_ payload: [UInt8]) throws {
            var offset = 0
            while offset < payload.count {
                let written = payload[offset...].withUnsafeBufferPointer {
                    Darwin.send(handle, $0.baseAddress, $0.count, 0)
                }
                try #require(written > 0)
                offset += written
            }
        }
        func receive(_ count: Int) throws -> [UInt8] {
            var out: [UInt8] = []
            var chunk = [UInt8](repeating: 0, count: count)
            while out.count < count {
                let read = chunk.withUnsafeMutableBufferPointer {
                    Darwin.recv(handle, $0.baseAddress, count - out.count, 0)
                }
                try #require(read > 0, "соединение оборвалось")
                out += chunk[0..<read]
            }
            return out
        }

        try send([0x05, 0x01, 0x00])
        let greeting = try receive(2)
        try #require(greeting == [0x05, 0x00], "SOCKS5 handshake не прошёл")

        var connect: [UInt8] = [0x05, 0x01, 0x00, 0x01]
        connect += [127, 0, 0, 1]
        connect += [UInt8(echoPort >> 8), UInt8(echoPort & 0xFF)]
        try send(connect)
        let reply = try receive(10)
        try #require(reply[1] == 0x00, "SOCKS5 CONNECT отклонён кодом \(reply[1])")

        let payload = [UInt8](repeating: 0x41, count: bytes)
        try send(payload)
        let echoed = try receive(bytes)
        #expect(echoed.count == bytes)
    }
}

/// Accepts one connection and echoes everything back.
private final class EchoServer {
    let port: UInt16
    private let handle: Int32
    private let queue = DispatchQueue(label: "echo")
    private nonisolated(unsafe) var stopped = false

    init() throws {
        // Everything runs against a local before any stored property is set:
        // touching `self.handle` inside these closures would count as capturing
        // a partly-initialised self.
        let listening = socket(AF_INET, SOCK_STREAM, 0)
        try #require(listening >= 0)

        var one: Int32 = 1
        setsockopt(listening, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))

        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0 // ephemeral
        address.sin_addr.s_addr = inet_addr("127.0.0.1")

        let bound = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(listening, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        try #require(bound == 0)
        try #require(listen(listening, 4) == 0)

        var boundAddress = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &boundAddress) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(listening, $0, &length)
            }
        }

        handle = listening
        port = boundAddress.sin_port.bigEndian
    }

    /// Separate from `init` so the accept loop never captures a half-built self.
    func start() {
        let listening = handle
        queue.async { [weak self] in
            let client = accept(listening, nil, nil)
            guard client >= 0 else { return }
            defer { close(client) }
            var buffer = [UInt8](repeating: 0, count: 8192)
            while self?.stopped == false {
                let read = buffer.withUnsafeMutableBufferPointer {
                    Darwin.recv(client, $0.baseAddress, $0.count, 0)
                }
                guard read > 0 else { return }
                var offset = 0
                while offset < read {
                    let written = buffer[offset..<read].withUnsafeBufferPointer {
                        Darwin.send(client, $0.baseAddress, $0.count, 0)
                    }
                    guard written > 0 else { return }
                    offset += written
                }
            }
        }
    }

    func stop() {
        stopped = true
        close(handle)
    }
}

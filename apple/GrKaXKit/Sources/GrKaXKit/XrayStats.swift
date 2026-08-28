import Foundation

/// Reads the core's traffic counters.
///
/// The Compose client shelled out to `xray api statsquery`. In-process there is
/// no CLI, and libXray exposes no stats method, so the counters have to come off
/// the core's own gRPC StatsService — served in cleartext on the dokodemo-door
/// `api` inbound that ConfigBuilder always adds.
///
/// That is served over HTTP/2, which rules out URLSession: it will not speak
/// HTTP/2 without TLS. Rather than take on grpc-swift and NIO for a single
/// unary call once a second, this is a one-shot client that opens a connection,
/// makes the call and closes it. It stays small by never decoding a response
/// header: HPACK decoding is the hard half of HTTP/2, and the answer is entirely
/// in the DATA frames.
public enum XrayStats {

    public struct Counters: Equatable, Sendable {
        public let up: Int64
        public let down: Int64
    }

    public enum StatsError: Error, LocalizedError {
        case connectionFailed
        case timedOut
        case refused(String)

        public var errorDescription: String? {
            switch self {
            case .connectionFailed: "Не удалось подключиться к API ядра"
            case .timedOut: "Ядро не ответило на запрос статистики"
            case .refused(let message): message
            }
        }
    }

    private static let servicePath = "/xray.app.stats.command.StatsService/QueryStats"

    /// Queries and resets the outbound counters, so each answer is the delta
    /// since the previous call — matching how the Android client reads its
    /// stats, and how the Compose build used `-reset`.
    public static func queryDelta(apiPort: Int, timeout: TimeInterval = 5) throws -> Counters {
        // QueryStatsRequest{ pattern: "" (omitted, proto3 default), reset: true }
        let request = Protobuf.encodeVarintField(number: 2, value: 1)
        let payload = try call(apiPort: apiPort, message: request, timeout: timeout)
        return parseCounters(payload)
    }

    /// Sums every outbound's uplink/downlink.
    ///
    /// Summed rather than filtered on a "proxy" tag: an xray-json subscription
    /// keeps its own outbound tags, and matching one name would silently report
    /// zero for those.
    static func parseCounters(_ payload: [UInt8]) -> Counters {
        var up: Int64 = 0
        var down: Int64 = 0
        for field in Protobuf.fields(in: payload) where field.number == 1 {
            guard case .bytes(let statBytes) = field.value else { continue }
            var name = ""
            var value: Int64 = 0
            for statField in Protobuf.fields(in: statBytes) {
                switch (statField.number, statField.value) {
                case (1, .bytes(let raw)): name = String(decoding: raw, as: UTF8.self)
                case (2, .varint(let raw)): value = Int64(bitPattern: raw)
                default: break
                }
            }
            guard name.hasPrefix("outbound>>>") else { continue }
            if name.hasSuffix("uplink") { up += value }
            if name.hasSuffix("downlink") { down += value }
        }
        return Counters(up: up, down: down)
    }

    // MARK: - One-shot gRPC

    private static func call(apiPort: Int, message: [UInt8], timeout: TimeInterval) throws -> [UInt8] {
        let socket = try connect(port: apiPort, timeout: timeout)
        defer { close(socket) }

        var out: [UInt8] = []
        out += Array("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".utf8)
        out += HTTP2.frame(type: 0x4, flags: 0, stream: 0, payload: [])   // SETTINGS

        var headers: [UInt8] = []
        headers += HPACK.literal(":method", "POST")
        headers += HPACK.literal(":scheme", "http")
        headers += HPACK.literal(":path", servicePath)
        headers += HPACK.literal(":authority", "\(AppConfig.loopback):\(apiPort)")
        headers += HPACK.literal("content-type", "application/grpc")
        headers += HPACK.literal("te", "trailers")
        out += HTTP2.frame(type: 0x1, flags: 0x4, stream: 1, payload: headers) // END_HEADERS

        // gRPC length-prefixed message: uncompressed flag, then a big-endian
        // length, then the protobuf body.
        var body: [UInt8] = [0]
        body += HTTP2.bigEndian32(UInt32(message.count))
        body += message
        out += HTTP2.frame(type: 0x0, flags: 0x1, stream: 1, payload: body)    // END_STREAM

        try write(socket, out)

        var buffer: [UInt8] = []
        var data: [UInt8] = []
        let deadline = Date().addingTimeInterval(timeout)

        while Date() < deadline {
            guard let chunk = try read(socket) else { break }
            buffer += chunk

            while let frame = HTTP2.parseFrame(&buffer) {
                switch frame.type {
                case 0x0 where frame.stream == 1:
                    data += frame.payload
                case 0x4 where frame.flags & 0x1 == 0:
                    // SETTINGS from the server; acknowledge and move on.
                    try write(socket, HTTP2.frame(type: 0x4, flags: 0x1, stream: 0, payload: []))
                case 0x3 where frame.stream == 1:
                    throw StatsError.refused("ядро сбросило поток статистики")
                case 0x7:
                    throw StatsError.refused("ядро закрыло соединение (GOAWAY)")
                default:
                    break
                }
                // The trailers close the stream; everything wanted has arrived.
                if frame.stream == 1, frame.flags & 0x1 != 0, frame.type == 0x1 {
                    return try grpcBody(data)
                }
            }
        }
        if data.isEmpty { throw StatsError.timedOut }
        return try grpcBody(data)
    }

    /// Strips the 5-byte gRPC message prefix.
    private static func grpcBody(_ data: [UInt8]) throws -> [UInt8] {
        guard data.count >= 5 else { throw StatsError.refused("пустой ответ статистики") }
        let length = Int(HTTP2.readBigEndian32(data, at: 1))
        let start = 5
        let end = min(start + length, data.count)
        guard end > start else { return [] }
        return Array(data[start..<end])
    }

    // MARK: - Socket

    private static func connect(port: Int, timeout: TimeInterval) throws -> Int32 {
        let handle = socket(AF_INET, SOCK_STREAM, 0)
        guard handle >= 0 else { throw StatsError.connectionFailed }

        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = UInt16(port).bigEndian
        address.sin_addr.s_addr = inet_addr(AppConfig.loopback)

        var tv = timeval(
            tv_sec: Int(timeout),
            tv_usec: Int32((timeout - Double(Int(timeout))) * 1_000_000)
        )
        setsockopt(handle, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(handle, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var one: Int32 = 1
        setsockopt(handle, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))

        let connected = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(handle, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard connected == 0 else {
            close(handle)
            throw StatsError.connectionFailed
        }
        return handle
    }

    private static func write(_ handle: Int32, _ bytes: [UInt8]) throws {
        var sent = 0
        while sent < bytes.count {
            let written = bytes[sent...].withUnsafeBufferPointer {
                Darwin.send(handle, $0.baseAddress, $0.count, 0)
            }
            guard written > 0 else { throw StatsError.connectionFailed }
            sent += written
        }
    }

    /// Nil once the peer is done; throws only on a real error.
    private static func read(_ handle: Int32) throws -> [UInt8]? {
        var chunk = [UInt8](repeating: 0, count: 8192)
        let count = chunk.withUnsafeMutableBufferPointer {
            Darwin.recv(handle, $0.baseAddress, $0.count, 0)
        }
        if count > 0 { return Array(chunk[0..<count]) }
        if count == 0 { return nil }
        if errno == EAGAIN || errno == EWOULDBLOCK { throw StatsError.timedOut }
        throw StatsError.connectionFailed
    }
}

// MARK: - HTTP/2 framing

enum HTTP2 {
    struct Frame {
        let type: UInt8
        let flags: UInt8
        let stream: UInt32
        let payload: [UInt8]
    }

    static func bigEndian32(_ value: UInt32) -> [UInt8] {
        [UInt8(value >> 24 & 0xFF), UInt8(value >> 16 & 0xFF), UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]
    }

    static func readBigEndian32(_ bytes: [UInt8], at offset: Int) -> UInt32 {
        (UInt32(bytes[offset]) << 24) | (UInt32(bytes[offset + 1]) << 16)
            | (UInt32(bytes[offset + 2]) << 8) | UInt32(bytes[offset + 3])
    }

    static func frame(type: UInt8, flags: UInt8, stream: UInt32, payload: [UInt8]) -> [UInt8] {
        var out: [UInt8] = []
        let length = UInt32(payload.count)
        out += [UInt8(length >> 16 & 0xFF), UInt8(length >> 8 & 0xFF), UInt8(length & 0xFF)]
        out.append(type)
        out.append(flags)
        out += bigEndian32(stream & 0x7FFF_FFFF)
        out += payload
        return out
    }

    /// Removes and returns the first complete frame, if the buffer holds one.
    static func parseFrame(_ buffer: inout [UInt8]) -> Frame? {
        guard buffer.count >= 9 else { return nil }
        let length = (Int(buffer[0]) << 16) | (Int(buffer[1]) << 8) | Int(buffer[2])
        guard buffer.count >= 9 + length else { return nil }

        let frame = Frame(
            type: buffer[3],
            flags: buffer[4],
            stream: readBigEndian32(buffer, at: 5) & 0x7FFF_FFFF,
            payload: Array(buffer[9..<(9 + length)])
        )
        buffer.removeFirst(9 + length)
        return frame
    }
}

/// Just enough HPACK to *write* headers.
///
/// Every field goes out as a literal with a new name and no indexing, which is
/// always legal and needs neither the static table nor Huffman coding. Reading
/// is not implemented because nothing here reads a header.
enum HPACK {
    static func literal(_ name: String, _ value: String) -> [UInt8] {
        var out: [UInt8] = [0x00]
        out += lengthPrefixed(name)
        out += lengthPrefixed(value)
        return out
    }

    private static func lengthPrefixed(_ text: String) -> [UInt8] {
        let bytes = Array(text.utf8)
        // 7-bit prefix integer, H (Huffman) bit clear.
        if bytes.count < 0x7F {
            return [UInt8(bytes.count)] + bytes
        }
        var out: [UInt8] = [0x7F]
        var remainder = bytes.count - 0x7F
        while remainder >= 0x80 {
            out.append(UInt8(remainder & 0x7F | 0x80))
            remainder >>= 7
        }
        out.append(UInt8(remainder))
        return out + bytes
    }
}

// MARK: - Protobuf

/// The two messages involved are three fields wide between them, so the wire
/// format is handled directly rather than pulling in swift-protobuf.
enum Protobuf {
    enum Value {
        case varint(UInt64)
        case bytes([UInt8])
    }

    struct Field {
        let number: Int
        let value: Value
    }

    static func encodeVarintField(number: Int, value: UInt64) -> [UInt8] {
        varint(UInt64(number << 3)) + varint(value)
    }

    static func varint(_ value: UInt64) -> [UInt8] {
        var out: [UInt8] = []
        var remainder = value
        repeat {
            var byte = UInt8(remainder & 0x7F)
            remainder >>= 7
            if remainder != 0 { byte |= 0x80 }
            out.append(byte)
        } while remainder != 0
        return out
    }

    /// Walks the top level of a message, skipping anything unrecognised.
    static func fields(in bytes: [UInt8]) -> [Field] {
        var out: [Field] = []
        var index = 0
        while index < bytes.count {
            guard let (key, afterKey) = readVarint(bytes, index) else { break }
            index = afterKey
            let number = Int(key >> 3)
            switch key & 0x7 {
            case 0:
                guard let (value, next) = readVarint(bytes, index) else { return out }
                out.append(Field(number: number, value: .varint(value)))
                index = next
            case 2:
                guard let (length, next) = readVarint(bytes, index) else { return out }
                let start = next
                let end = start + Int(length)
                guard end <= bytes.count else { return out }
                out.append(Field(number: number, value: .bytes(Array(bytes[start..<end]))))
                index = end
            case 5:
                index += 4
            case 1:
                index += 8
            default:
                return out
            }
        }
        return out
    }

    private static func readVarint(_ bytes: [UInt8], _ start: Int) -> (UInt64, Int)? {
        var value: UInt64 = 0
        var shift: UInt64 = 0
        var index = start
        while index < bytes.count {
            let byte = bytes[index]
            value |= UInt64(byte & 0x7F) << shift
            index += 1
            if byte & 0x80 == 0 { return (value, index) }
            shift += 7
            if shift > 63 { return nil }
        }
        return nil
    }
}

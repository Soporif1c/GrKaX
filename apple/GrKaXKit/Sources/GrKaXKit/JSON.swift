import Foundation

/// An order-preserving JSON value, standing in for kotlinx.serialization's
/// `JsonElement`.
///
/// Foundation is not usable for this job. `JSONSerialization` drops object key
/// order, because a Swift dictionary has none, and it reads every number into a
/// `Double`, so `443` comes back out as `443` or `443.0` depending on the path
/// it took. Both matter here: whole outbounds are carried through the app as
/// text and handed to the core verbatim, and a subscription's own config is
/// re-emitted with its structure intact. Numbers therefore keep their literal
/// spelling, exactly as `JsonPrimitive` does.
public enum JSON: Equatable, Sendable {
    case null
    case bool(Bool)
    /// The number as written. Never reformatted.
    case number(String)
    case string(String)
    case array([JSON])
    case object(JSONObject)

    // MARK: - Convenience accessors

    public var stringValue: String? {
        switch self {
        case .string(let value): value
        case .number(let value): value
        case .bool(let value): String(value)
        default: nil
        }
    }

    /// Blank strings read as absent, which is what `takeIf { isNotBlank() }`
    /// does on the Kotlin side of every one of these lookups.
    public var nonBlankString: String? {
        guard let value = stringValue, !value.trimmingCharacters(in: .whitespaces).isEmpty else {
            return nil
        }
        return value
    }

    public var intValue: Int? {
        switch self {
        case .number(let value): Int(value) ?? Double(value).flatMap { Int(exactly: $0.rounded(.towardZero)) }
        case .string(let value): Int(value)
        default: nil
        }
    }

    public var boolValue: Bool? {
        switch self {
        case .bool(let value): value
        case .string(let value): Bool(value)
        default: nil
        }
    }

    public var objectValue: JSONObject? {
        if case .object(let object) = self { return object }
        return nil
    }

    public var arrayValue: [JSON]? {
        if case .array(let array) = self { return array }
        return nil
    }

    public subscript(key: String) -> JSON? {
        objectValue?[key]
    }

    public static func int(_ value: Int) -> JSON { .number(String(value)) }
}

/// Insertion-ordered string→JSON map, mirroring the `LinkedHashMap` kotlinx
/// parses into. Re-assigning an existing key keeps its original position.
public struct JSONObject: Equatable, Sendable {
    public private(set) var keys: [String] = []
    private var storage: [String: JSON] = [:]

    public init() {}

    public init(_ pairs: [(String, JSON)]) {
        for (key, value) in pairs { self[key] = value }
    }

    public subscript(key: String) -> JSON? {
        get { storage[key] }
        set {
            if let newValue {
                if storage[key] == nil { keys.append(key) }
                storage[key] = newValue
            } else if storage.removeValue(forKey: key) != nil {
                keys.removeAll { $0 == key }
            }
        }
    }

    public var isEmpty: Bool { keys.isEmpty }
    public var count: Int { keys.count }

    public func has(_ key: String) -> Bool { storage[key] != nil }

    public var pairs: [(String, JSON)] { keys.map { ($0, storage[$0]!) } }

    /// Adds `value` only when it is there — the `?.let { put(...) }` shape that
    /// runs through the whole config builder.
    public mutating func putIfPresent(_ key: String, _ value: JSON?) {
        if let value { self[key] = value }
    }

    public mutating func putIfPresent(_ key: String, _ value: String?) {
        if let value, !value.isEmpty { self[key] = .string(value) }
    }

    public static func == (lhs: JSONObject, rhs: JSONObject) -> Bool {
        lhs.keys == rhs.keys && lhs.storage == rhs.storage
    }
}

// MARK: - Serialising

public extension JSON {

    /// Compact form, matching `JsonElement.toString()`.
    var compact: String {
        var out = ""
        write(into: &out, indent: nil, level: 0)
        return out
    }

    /// Indented form for the config actually handed to the core — it is shown
    /// on the log screen, and a wall of one-line JSON is unreadable there.
    var pretty: String {
        var out = ""
        write(into: &out, indent: "  ", level: 0)
        return out
    }

    private func write(into out: inout String, indent: String?, level: Int) {
        switch self {
        case .null:
            out += "null"
        case .bool(let value):
            out += value ? "true" : "false"
        case .number(let value):
            out += value
        case .string(let value):
            JSON.writeString(value, into: &out)
        case .array(let items):
            guard !items.isEmpty else { out += "[]"; return }
            out += "["
            for (index, item) in items.enumerated() {
                if index > 0 { out += "," }
                newline(&out, indent: indent, level: level + 1)
                item.write(into: &out, indent: indent, level: level + 1)
            }
            newline(&out, indent: indent, level: level)
            out += "]"
        case .object(let object):
            guard !object.isEmpty else { out += "{}"; return }
            out += "{"
            for (index, pair) in object.pairs.enumerated() {
                if index > 0 { out += "," }
                newline(&out, indent: indent, level: level + 1)
                JSON.writeString(pair.0, into: &out)
                out += indent == nil ? ":" : ": "
                pair.1.write(into: &out, indent: indent, level: level + 1)
            }
            newline(&out, indent: indent, level: level)
            out += "}"
        }
    }

    private func newline(_ out: inout String, indent: String?, level: Int) {
        guard let indent else { return }
        out += "\n"
        out += String(repeating: indent, count: level)
    }

    /// Escapes the way kotlinx does: quotes, backslash, the named control
    /// escapes, and `\uXXXX` for the rest below 0x20. Non-ASCII is emitted as
    /// UTF-8 rather than escaped, so Cyrillic node names stay readable.
    private static func writeString(_ value: String, into out: inout String) {
        out += "\""
        for character in value.unicodeScalars {
            switch character {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if character.value < 0x20 {
                    out += String(format: "\\u%04x", character.value)
                } else {
                    out.unicodeScalars.append(character)
                }
            }
        }
        out += "\""
    }
}

// MARK: - Parsing

public extension JSON {

    /// Parses `text`, or returns nil when it is not JSON.
    static func parse(_ text: String) -> JSON? {
        var parser = JSONParser(Array(text.utf8))
        guard let value = parser.parseValue() else { return nil }
        parser.skipWhitespace()
        return parser.atEnd ? value : nil
    }
}

private struct JSONParser {
    private let bytes: [UInt8]
    private var index = 0

    init(_ bytes: [UInt8]) {
        self.bytes = bytes
    }

    var atEnd: Bool { index >= bytes.count }

    mutating func skipWhitespace() {
        while index < bytes.count {
            switch bytes[index] {
            case 0x20, 0x09, 0x0A, 0x0D: index += 1
            default: return
            }
        }
    }

    mutating func parseValue() -> JSON? {
        skipWhitespace()
        guard index < bytes.count else { return nil }
        switch bytes[index] {
        case UInt8(ascii: "{"): return parseObject()
        case UInt8(ascii: "["): return parseArray()
        case UInt8(ascii: "\""): return parseString().map { .string($0) }
        case UInt8(ascii: "t"): return literal("true") ? .bool(true) : nil
        case UInt8(ascii: "f"): return literal("false") ? .bool(false) : nil
        case UInt8(ascii: "n"): return literal("null") ? .null : nil
        default: return parseNumber()
        }
    }

    private mutating func literal(_ text: String) -> Bool {
        let expected = Array(text.utf8)
        guard index + expected.count <= bytes.count else { return false }
        guard Array(bytes[index..<(index + expected.count)]) == expected else { return false }
        index += expected.count
        return true
    }

    private mutating func parseObject() -> JSON? {
        index += 1 // {
        var object = JSONObject()
        skipWhitespace()
        if index < bytes.count, bytes[index] == UInt8(ascii: "}") {
            index += 1
            return .object(object)
        }
        while true {
            skipWhitespace()
            guard let key = parseString() else { return nil }
            skipWhitespace()
            guard index < bytes.count, bytes[index] == UInt8(ascii: ":") else { return nil }
            index += 1
            guard let value = parseValue() else { return nil }
            object[key] = value
            skipWhitespace()
            guard index < bytes.count else { return nil }
            if bytes[index] == UInt8(ascii: ",") { index += 1; continue }
            if bytes[index] == UInt8(ascii: "}") { index += 1; return .object(object) }
            return nil
        }
    }

    private mutating func parseArray() -> JSON? {
        index += 1 // [
        var items: [JSON] = []
        skipWhitespace()
        if index < bytes.count, bytes[index] == UInt8(ascii: "]") {
            index += 1
            return .array(items)
        }
        while true {
            guard let value = parseValue() else { return nil }
            items.append(value)
            skipWhitespace()
            guard index < bytes.count else { return nil }
            if bytes[index] == UInt8(ascii: ",") { index += 1; continue }
            if bytes[index] == UInt8(ascii: "]") { index += 1; return .array(items) }
            return nil
        }
    }

    private mutating func parseString() -> String? {
        guard index < bytes.count, bytes[index] == UInt8(ascii: "\"") else { return nil }
        index += 1
        var scalars = String.UnicodeScalarView()
        var raw: [UInt8] = []

        func flushRaw() {
            guard !raw.isEmpty else { return }
            scalars.append(contentsOf: String(decoding: raw, as: UTF8.self).unicodeScalars)
            raw.removeAll(keepingCapacity: true)
        }

        while index < bytes.count {
            let byte = bytes[index]
            if byte == UInt8(ascii: "\"") {
                index += 1
                flushRaw()
                return String(scalars)
            }
            if byte == UInt8(ascii: "\\") {
                flushRaw()
                index += 1
                guard index < bytes.count else { return nil }
                let escape = bytes[index]
                index += 1
                switch escape {
                case UInt8(ascii: "\""): scalars.append("\"")
                case UInt8(ascii: "\\"): scalars.append("\\")
                case UInt8(ascii: "/"): scalars.append("/")
                case UInt8(ascii: "b"): scalars.append("\u{08}")
                case UInt8(ascii: "f"): scalars.append("\u{0C}")
                case UInt8(ascii: "n"): scalars.append("\n")
                case UInt8(ascii: "r"): scalars.append("\r")
                case UInt8(ascii: "t"): scalars.append("\t")
                case UInt8(ascii: "u"):
                    guard let scalar = parseUnicodeEscape() else { return nil }
                    scalars.append(scalar)
                default: return nil
                }
                continue
            }
            raw.append(byte)
            index += 1
        }
        return nil
    }

    /// `\uXXXX`, joining a surrogate pair when one follows.
    private mutating func parseUnicodeEscape() -> Unicode.Scalar? {
        guard let high = readHex4() else { return nil }
        if high >= 0xD800, high <= 0xDBFF,
           index + 1 < bytes.count,
           bytes[index] == UInt8(ascii: "\\"), bytes[index + 1] == UInt8(ascii: "u") {
            let save = index
            index += 2
            if let low = readHex4(), low >= 0xDC00, low <= 0xDFFF {
                let combined = 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00)
                return Unicode.Scalar(combined)
            }
            index = save
        }
        return Unicode.Scalar(high)
    }

    private mutating func readHex4() -> UInt32? {
        guard index + 4 <= bytes.count else { return nil }
        var value: UInt32 = 0
        for offset in 0..<4 {
            let byte = bytes[index + offset]
            let digit: UInt32
            switch byte {
            case UInt8(ascii: "0")...UInt8(ascii: "9"): digit = UInt32(byte - UInt8(ascii: "0"))
            case UInt8(ascii: "a")...UInt8(ascii: "f"): digit = UInt32(byte - UInt8(ascii: "a")) + 10
            case UInt8(ascii: "A")...UInt8(ascii: "F"): digit = UInt32(byte - UInt8(ascii: "A")) + 10
            default: return nil
            }
            value = value << 4 | digit
        }
        index += 4
        return value
    }

    /// Kept as written — the literal is what gets re-emitted.
    private mutating func parseNumber() -> JSON? {
        let start = index
        if index < bytes.count, bytes[index] == UInt8(ascii: "-") { index += 1 }
        var sawDigit = false
        while index < bytes.count {
            switch bytes[index] {
            case UInt8(ascii: "0")...UInt8(ascii: "9"):
                sawDigit = true
                index += 1
            case UInt8(ascii: "."), UInt8(ascii: "e"), UInt8(ascii: "E"),
                 UInt8(ascii: "+"), UInt8(ascii: "-"):
                index += 1
            default:
                guard sawDigit else { return nil }
                return .number(String(decoding: bytes[start..<index], as: UTF8.self))
            }
        }
        guard sawDigit else { return nil }
        return .number(String(decoding: bytes[start..<index], as: UTF8.self))
    }
}

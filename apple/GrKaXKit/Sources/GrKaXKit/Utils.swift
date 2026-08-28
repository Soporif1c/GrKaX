import CryptoKit
import Foundation

public enum Utils {

    // MARK: - Base64

    /// Tries the base64 flavours subscriptions show up in (std/url-safe, padded
    /// or not).
    ///
    /// The lenient pass mirrors Java's MIME decoder, which skips anything
    /// outside the alphabet instead of failing — several panels wrap their
    /// payload in whitespace or stray punctuation and rely on that.
    ///
    /// Invalid UTF-8 is replaced rather than rejected, again matching the JVM:
    /// `String(bytes, UTF_8)` substitutes U+FFFD where Swift's
    /// `String(data:encoding:)` would hand back nil and lose a payload that
    /// Android accepts.
    public static func tryDecodeBase64(_ text: String) -> String? {
        let cleaned = text
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\n", with: "")
            .replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: " ", with: "")
        guard !cleaned.isEmpty else { return nil }

        let padded = cleaned.padding(
            toLength: (cleaned.count + 3) / 4 * 4,
            withPad: "=",
            startingAt: 0
        )

        for candidate in [cleaned, padded] {
            for decoded in [
                Data(base64Encoded: candidate),
                Data(base64Encoded: urlSafeToStandard(candidate)),
                Data(base64Encoded: candidate, options: .ignoreUnknownCharacters),
            ] {
                if let decoded, !decoded.isEmpty {
                    return String(decoding: decoded, as: UTF8.self)
                }
            }
        }
        return nil
    }

    private static func urlSafeToStandard(_ text: String) -> String {
        text.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
    }

    // MARK: - URL

    /// Percent-decoding that matches `java.net.URLDecoder`.
    ///
    /// Note the `+` → space step: that is form-encoding behaviour, and it is
    /// what both the Android and Compose clients do. It is wrong for the
    /// shadowsocks user-info segment — a `+` inside base64 is destroyed there —
    /// but the two clients have to agree on how a link parses, so the quirk is
    /// reproduced rather than quietly fixed here.
    public static func urlDecode(_ text: String) -> String {
        let spaced = text.replacingOccurrences(of: "+", with: " ")
        return spaced.removingPercentEncoding ?? text
    }

    // MARK: - Addresses

    public static func isIPAddress(_ value: String) -> Bool {
        let v = value.trimmingCharacters(in: .whitespaces)
        guard !v.isEmpty else { return false }

        let parts = v.split(separator: ".", omittingEmptySubsequences: false)
        if parts.count == 4,
           parts.allSatisfy({ part in Int(part).map { (0...255).contains($0) } ?? false }) {
            return true
        }
        if v.contains(":"),
           v.allSatisfy({ $0.isHexDigit || $0 == ":" }) {
            return true
        }
        return false
    }

    // MARK: - Machine identity

    /// Stable machine identifier sent as `x-hwid` to panels like Remnawave.
    ///
    /// Must stay identical to what the Compose build produces, or a panel sees
    /// the Swift client as a new device and may refuse the subscription. That
    /// means the same seed *and* Java's `UUID.nameUUIDFromBytes`, which is a
    /// version-3 (MD5) UUID rather than anything Foundation offers.
    public static let hwid: String = nameUUIDFromBytes(Array("grkax:\(machineSeed())".utf8))

    private static func machineSeed() -> String {
        if let out = Shell.capture("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice"),
           let match = out.range(of: #""IOPlatformUUID"\s*=\s*"([^"]+)""#, options: .regularExpression) {
            let fragment = String(out[match])
            if let quoted = fragment.range(of: #""[^"]+"$"#, options: .regularExpression) {
                let uuid = fragment[quoted].trimmingCharacters(in: CharacterSet(charactersIn: "\""))
                if !uuid.isEmpty { return uuid }
            }
        }
        let user = NSUserName()
        let host = ProcessInfo.processInfo.hostName
        return "\(user)@\(host)"
    }

    /// Port of `java.util.UUID.nameUUIDFromBytes`: MD5, then stamp version 3
    /// and the IETF variant into the same two bytes the JDK does.
    static func nameUUIDFromBytes(_ bytes: [UInt8]) -> String {
        var digest = Array(Insecure.MD5.hash(data: Data(bytes)))
        digest[6] = (digest[6] & 0x0f) | 0x30
        digest[8] = (digest[8] & 0x3f) | 0x80

        let hex = digest.map { String(format: "%02x", $0) }.joined()
        let groups = [
            hex.prefix(8),
            hex.dropFirst(8).prefix(4),
            hex.dropFirst(12).prefix(4),
            hex.dropFirst(16).prefix(4),
            hex.dropFirst(20).prefix(12),
        ]
        return groups.joined(separator: "-")
    }

    // MARK: - Formatting

    public static func formatBytes(_ bytes: Int64) -> String {
        if bytes < 0 { return "—" }
        let units = ["B", "KB", "MB", "GB", "TB"]
        var value = Double(bytes)
        var unit = 0
        while value >= 1024, unit < units.count - 1 {
            value /= 1024
            unit += 1
        }
        return unit == 0
            ? "\(bytes) \(units[0])"
            : String(format: "%.1f %@", locale: Locale(identifier: "en_US_POSIX"), value, units[unit])
    }

    public static func formatSpeed(_ bytesPerSecond: Int64) -> String {
        formatBytes(bytesPerSecond) + "/s"
    }

    public static func formatDuration(millis: Int64) -> String {
        if millis <= 0 { return "00:00" }
        let totalSeconds = millis / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, seconds)
            : String(format: "%02d:%02d", minutes, seconds)
    }

    public static func formatDate(epochSeconds: Int64) -> String {
        if epochSeconds <= 0 { return "—" }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.timeZone = .current
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epochSeconds)))
    }
}

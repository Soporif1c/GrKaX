import Foundation

/// Thin wrapper over `Process` for the few places that still need a command
/// line — reading the hardware UUID, and the network plumbing that shells out
/// to `networksetup` and friends.
///
/// The list shrinks as the port moves onto system APIs, and the network half
/// disappears entirely if the tunnel ever becomes a NEPacketTunnelProvider.
public enum Shell {

    public struct Result: Sendable {
        public let status: Int32
        public let out: String
        public let err: String

        public var ok: Bool { status == 0 }

        /// What to show a user when the command failed.
        public func message() -> String {
            let text = err.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { return text }
            let fallback = out.trimmingCharacters(in: .whitespacesAndNewlines)
            return fallback.isEmpty ? "код выхода \(status)" : fallback
        }
    }

    /// Runs a command and returns its output, or nil if it could not be run.
    public static func capture(_ path: String, _ arguments: String...) -> String? {
        let result = run(path, arguments)
        return result?.ok == true ? result?.out : nil
    }

    /// Runs `script` through `osascript` so macOS raises one authorization
    /// prompt for the whole thing.
    ///
    /// Everything that needs root is batched into a single script on purpose: a
    /// per-command prompt would ask the user for a password four times to raise
    /// one tunnel.
    @discardableResult
    public static func runAsAdmin(_ script: String, timeout: TimeInterval = 120) -> Result? {
        // osascript needs the inner double quotes and backslashes escaped.
        let escaped = script
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
        return run(
            "/usr/bin/osascript",
            ["-e", "do shell script \"\(escaped)\" with administrator privileges"],
            timeout: timeout
        )
    }

    /// Quotes a value for inclusion in a shell script built by hand.
    public static func quote(_ value: String) -> String {
        guard value.isEmpty || value.contains(where: \.isWhitespace) else { return value }
        return "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    @discardableResult
    public static func run(_ path: String, _ arguments: [String], timeout: TimeInterval = 15) -> Result? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = arguments

        let outPipe = Pipe()
        let errPipe = Pipe()
        process.standardOutput = outPipe
        process.standardError = errPipe

        do {
            try process.run()
        } catch {
            return nil
        }

        // Read before waiting: a command that fills the 64 KB pipe buffer would
        // otherwise block forever on write while we block on exit.
        let outData = outPipe.fileHandleForReading.readDataToEndOfFile()
        let errData = errPipe.fileHandleForReading.readDataToEndOfFile()

        let deadline = Date().addingTimeInterval(timeout)
        while process.isRunning, Date() < deadline {
            usleep(20_000)
        }
        if process.isRunning {
            process.terminate()
            return Result(status: -1, out: "", err: "команда не уложилась в \(Int(timeout)) с")
        }

        return Result(
            status: process.terminationStatus,
            out: String(decoding: outData, as: UTF8.self),
            err: String(decoding: errData, as: UTF8.self)
        )
    }
}

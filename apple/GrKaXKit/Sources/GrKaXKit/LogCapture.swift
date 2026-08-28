import Foundation

/// Captures what the core writes to stderr.
///
/// The Compose client read the log off a child process's pipe. In-process the
/// core logs into our own stderr, so the descriptor is redirected into a pipe
/// and read from a background thread.
///
/// The alternative — pointing `log.error` at a file in the config — was
/// rejected on purpose: it would change the config text, and the whole config
/// layer is verified by comparing that text against the Kotlin original. This
/// way the config stays identical and the capture catches more besides, panics
/// from the Go runtime included.
///
/// Everything read is written back out to the real stderr, so running under
/// Xcode still shows the log where it is expected.
public final class LogCapture: @unchecked Sendable {

    public static let shared = LogCapture()

    private let lock = NSLock()
    private var sink: (@Sendable (String) -> Void)?
    private var originalStderr: Int32 = -1
    private var started = false
    private var pending = ""

    private init() {}

    /// Starts redirecting. Safe to call more than once.
    public func start(_ sink: @escaping @Sendable (String) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        self.sink = sink
        guard !started else { return }
        started = true

        let pipe = Pipe()
        originalStderr = dup(STDERR_FILENO)
        dup2(pipe.fileHandleForWriting.fileDescriptor, STDERR_FILENO)

        let saved = originalStderr
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty else { return }
            // Pass it through so the real stderr still sees everything.
            data.withUnsafeBytes { raw in
                if let base = raw.baseAddress { _ = Darwin.write(saved, base, raw.count) }
            }
            self?.consume(String(decoding: data, as: UTF8.self))
        }
    }

    /// Splits on newlines, holding a partial line until the rest arrives — the
    /// core does not always write a whole line in one go.
    private func consume(_ text: String) {
        lock.lock()
        pending += text
        var lines: [String] = []
        while let newline = pending.firstIndex(of: "\n") {
            lines.append(String(pending[..<newline]))
            pending = String(pending[pending.index(after: newline)...])
        }
        let sink = self.sink
        lock.unlock()

        guard let sink else { return }
        for line in lines where !line.trimmingCharacters(in: .whitespaces).isEmpty {
            sink(line)
        }
    }
}

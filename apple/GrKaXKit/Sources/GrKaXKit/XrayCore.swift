import Foundation
import LibXray

/// Errors coming out of the core bridge.
public enum XrayCoreError: Error, LocalizedError, Equatable {
    /// The request could not be turned into JSON — a programming error here.
    case encodingFailed(String)
    /// The response was not the envelope we expect.
    case malformedResponse(String)
    /// The core ran and refused: its own message, passed through verbatim.
    case core(String)

    public var errorDescription: String? {
        switch self {
        case .encodingFailed(let message):
            "Не удалось собрать запрос к ядру: \(message)"
        case .malformedResponse(let message):
            "Ядро вернуло неожиданный ответ: \(message)"
        case .core(let message):
            message
        }
    }
}

/// Swift face of libXray's single `CGoInvoke(requestJSON) -> responseJSON`
/// entry point.
///
/// The core is one global instance inside the Go runtime, so every call is
/// serialised here. Calls are synchronous and some of them block for a while
/// (`ping`, and `run` while the config is validated), so this type is
/// deliberately not tied to an actor — callers hop off the main thread
/// themselves.
public enum XrayCore {

    /// libXray rejects anything other than 0 or 1 today.
    private static let apiVersion = 1

    /// Go's core is a process-wide singleton; overlapping start/stop calls
    /// would race inside it.
    private static let lock = NSLock()

    // MARK: - Public surface

    /// Where the core will look for `geoip.dat` and `geosite.dat`.
    ///
    /// Any routing rule naming `geoip:` or `geosite:` fails config parsing when
    /// these are missing, and the app's default rule set uses both — so getting
    /// this wrong means nothing connects at all.
    ///
    /// There is deliberately no setter. The core reads `XRAY_LOCATION_ASSET`
    /// through Go's `os.Getenv`, and the Go runtime snapshots the environment
    /// when it starts — which, for a static c-archive, is before `main` runs.
    /// Calling `setenv` from Swift updates the C environment and the Go side
    /// never sees it; the Compose build got away with it only because it passed
    /// the variable to a child process.
    ///
    /// So the files go next to the executable, which is where the core falls
    /// back to when the variable is unset. In a bundle that is
    /// `Contents/MacOS/`, filled by the "Copy geo data" build phase. Tests get
    /// there by having the variable exported before the process starts — see
    /// `apple/scripts/test.sh`.
    public static var assetDirectory: URL {
        if let configured = ProcessInfo.processInfo.environment["XRAY_LOCATION_ASSET"] {
            return URL(fileURLWithPath: configured)
        }
        return URL(fileURLWithPath: Bundle.main.executablePath ?? ".")
            .deletingLastPathComponent()
    }

    /// Whether both geo files are actually readable where the core will look.
    public static var geoDataPresent: Bool {
        ["geoip.dat", "geosite.dat"].allSatisfy {
            FileManager.default.fileExists(atPath: assetDirectory.appending(path: $0).path)
        }
    }

    /// Version string of the linked Xray core.
    public static func version() throws -> String {
        let response: VersionResponse = try invoke(method: "xrayVersion")
        return response.version
    }

    /// Whether the core is currently running.
    public static func isRunning() throws -> Bool {
        let response: StateResponse = try invoke(method: "getXrayState")
        return response.running
    }

    /// Starts the core with a config passed as JSON.
    ///
    /// The Compose client wrote the config to disk first because it drove the
    /// `xray` CLI; in-process there is no reason to touch the filesystem.
    public static func run(configJSON: String) throws {
        let _: EmptyData = try invoke(
            method: "runXrayFromJson",
            payload: RunFromJSONRequest(configJSON: configJSON)
        )
    }

    /// Validates a config without starting the core.
    public static func test(configPath: String) throws {
        let _: EmptyData = try invoke(
            method: "testXray",
            payload: ConfigPathRequest(configPath: configPath)
        )
    }

    /// Stops the core. Succeeds when it was not running.
    public static func stop() throws {
        let _: EmptyData = try invoke(method: "stopXray")
    }

    /// Asks the core for `count` free local ports.
    public static func freePorts(count: Int) throws -> [Int] {
        let response: FreePortsResponse = try invoke(
            method: "getFreePorts",
            payload: FreePortsRequest(count: count)
        )
        return response.ports
    }

    // MARK: - Envelope

    private struct Request<Payload: Encodable>: Encodable {
        let apiVersion: Int
        let method: String
        let payload: Payload?
    }

    private struct Response<Data: Decodable>: Decodable {
        let success: Bool
        let data: Data?
        let error: String?
    }

    /// Stand-in for methods that take or return nothing. libXray answers those
    /// with `{}`, which decodes into an empty struct.
    struct EmptyData: Codable {}

    private struct VersionResponse: Decodable { let version: String }
    private struct StateResponse: Decodable { let running: Bool }
    private struct FreePortsResponse: Decodable { let ports: [Int] }
    private struct FreePortsRequest: Encodable { let count: Int }
    private struct RunFromJSONRequest: Encodable { let configJSON: String }
    private struct ConfigPathRequest: Encodable { let configPath: String }

    // MARK: - Bridge

    private static func invoke<Data: Decodable>(method: String) throws -> Data {
        try invoke(method: method, payload: Optional<EmptyData>.none)
    }

    private static func invoke<Payload: Encodable, Data: Decodable>(
        method: String,
        payload: Payload?
    ) throws -> Data {
        let request = Request(apiVersion: apiVersion, method: method, payload: payload)
        let requestData: Foundation.Data
        do {
            requestData = try JSONEncoder().encode(request)
        } catch {
            throw XrayCoreError.encodingFailed(error.localizedDescription)
        }
        guard let requestJSON = String(data: requestData, encoding: .utf8) else {
            throw XrayCoreError.encodingFailed("request is not valid UTF-8")
        }

        let responseJSON = callCore(requestJSON)

        guard let responseData = responseJSON.data(using: .utf8) else {
            throw XrayCoreError.malformedResponse("response is not valid UTF-8")
        }
        let response: Response<Data>
        do {
            response = try JSONDecoder().decode(Response<Data>.self, from: responseData)
        } catch {
            throw XrayCoreError.malformedResponse(responseJSON)
        }
        guard response.success else {
            let message = response.error.flatMap { $0.isEmpty ? nil : $0 }
            throw XrayCoreError.core(message ?? "ядро отклонило запрос без объяснения")
        }
        guard let data = response.data else {
            throw XrayCoreError.malformedResponse("успех без данных для метода \(method)")
        }
        return data
    }

    /// The raw C hop.
    ///
    /// `CGoInvoke` copies its argument on the Go side, so the buffer we hand it
    /// is ours to free. The returned string is `malloc`ed by Go and is only
    /// valid until `CGoFree`, so it is copied into a Swift `String` first.
    private static func callCore(_ requestJSON: String) -> String {
        lock.lock()
        defer { lock.unlock() }

        return requestJSON.withCString { source in
            guard let owned = strdup(source) else { return "" }
            defer { free(owned) }
            guard let raw = CGoInvoke(owned) else { return "" }
            defer { CGoFree(raw) }
            return String(cString: raw)
        }
    }
}

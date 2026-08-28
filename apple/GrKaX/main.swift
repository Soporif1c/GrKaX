import AppKit
import Foundation
import GrKaXKit

/// Explicit entry point, because one thing has to happen before the app exists.
///
/// libXray reads `XRAY_LOCATION_ASSET` through Go's `os.Getenv`, and the Go
/// runtime copies the environment when it starts — which, for a static
/// c-archive, is before any Swift code runs. `setenv` from Swift updates the C
/// environment and the core never sees the change. This build of libXray offers
/// no way round it either: there is no dispatcher method to set the asset
/// directory, and the `datDir` field its requests carry is ignored — the core
/// still looks beside the executable.
///
/// The geo data cannot simply be placed where the core looks by default either:
/// that is the executable's own directory, and `codesign` treats every file in
/// `Contents/MacOS` as a code object, refusing to seal a bundle that holds an
/// unsignable `.dat`.
///
/// So the files live in `Contents/Resources`, and the variable has to reach a
/// process that has not started yet. Handing it to a fresh instance through
/// LaunchServices does that — and unlike `execv`, which did the same job by
/// replacing this process's image, it keeps the menu bar item: a re-executed
/// process keeps its windows but never gets its status item inserted.
GeoAssets.pointCoreAtBundledData()
GrKaXApp.main()

enum GeoAssets {

    /// Box for the launch result: the completion handler is `@Sendable`, so the
    /// flags it sets cannot be plain locals.
    private final class Handover: @unchecked Sendable {
        var finished = false
        var launched = false
    }

    static func pointCoreAtBundledData() {
        guard ProcessInfo.processInfo.environment["XRAY_LOCATION_ASSET"] == nil else { return }
        guard let resources = Bundle.main.resourceURL else { return }

        // Nothing to point at — a dev build without the fetch step. Better to
        // start and say so on screen than to hand over in a loop.
        let present = ["geoip.dat", "geosite.dat"].allSatisfy {
            FileManager.default.fileExists(atPath: resources.appending(path: $0).path)
        }
        guard present else { return }

        let configuration = NSWorkspace.OpenConfiguration()
        configuration.environment = ["XRAY_LOCATION_ASSET": resources.path]
        // Without this LaunchServices would see this very process as the
        // running instance and just activate it, and the variable would reach
        // nobody.
        configuration.createsNewApplicationInstance = true

        let handover = Handover()
        NSWorkspace.shared.openApplication(
            at: Bundle.main.bundleURL,
            configuration: configuration
        ) { app, _ in
            handover.launched = app != nil
            handover.finished = true
        }

        // Pump the run loop rather than blocking on a semaphore: the completion
        // handler is delivered through it, and waiting on the main thread would
        // never let it arrive.
        let deadline = Date().addingTimeInterval(10)
        while !handover.finished, Date() < deadline {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.05))
        }

        // Only step aside once the replacement exists. If the hand-over failed,
        // carry on without geo rules — an app that starts and says so beats no
        // app at all.
        if handover.launched { exit(0) }
    }
}

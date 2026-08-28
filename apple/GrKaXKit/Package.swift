// swift-tools-version: 6.0
import PackageDescription

// Everything portable — config building, link parsing, storage, the core
// wrapper — lives here rather than in the app target, so `swift test` can
// exercise it without launching a UI. The Xcode app depends on this package.
let package = Package(
    name: "GrKaXKit",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "GrKaXKit", targets: ["GrKaXKit"]),
    ],
    targets: [
        // Fetched by apple/scripts/fetch-libxray.sh, not committed.
        .binaryTarget(
            name: "LibXray",
            path: "../vendor/LibXray.xcframework"
        ),
        .target(
            name: "GrKaXKit",
            dependencies: ["LibXray"],
            linkerSettings: [
                // The Go runtime's net and os/user packages call into the
                // system resolver and directory services; a static cgo archive
                // does not carry those references itself.
                .linkedLibrary("resolv"),
                .linkedFramework("CoreFoundation"),
                .linkedFramework("Security"),
            ]
        ),
        .testTarget(
            name: "GrKaXKitTests",
            dependencies: ["GrKaXKit"],
            // Produced by `./gradlew golden` in the Compose project: what the
            // Kotlin implementation really outputs, so the port is compared
            // against the original rather than against an assumption.
            resources: [.copy("Golden")]
        ),
    ]
)

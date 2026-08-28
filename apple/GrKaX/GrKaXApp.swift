import GrKaXKit
import SwiftUI

/// Started from `main.swift`, not `@main` — see the note there about the one
/// thing that must happen before the app exists.
struct GrKaXApp: App {
    @State private var environment = AppEnvironment()

    var body: some Scene {
        Window("GrKa X", id: "main") {
            RootView()
                .environment(environment)
                .frame(minWidth: 720, minHeight: 520)
        }
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(replacing: .newItem) {}
        }

        // Where the app is actually used from once it is set up: the window is
        // for adding servers and reading the log, this is for everything else.
        MenuBarExtra {
            MenuBarContent()
                .environment(environment)
        } label: {
            Image(systemName: environment.runtime.state == .connected
                  ? "shield.lefthalf.filled"
                  : "shield")
        }
    }
}

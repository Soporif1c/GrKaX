import AppKit
import GrKaXKit
import SwiftUI

/// The menu bar item's menu.
///
/// Deliberately plain buttons and submenus rather than a custom panel: this is
/// reached mid-task, from another app, and a native menu costs no learning and
/// no waiting. Everything here is something you would otherwise open the window
/// for — connect, change server, change routing.
struct MenuBarContent: View {
    @Environment(AppEnvironment.self) private var app
    @Environment(\.openWindow) private var openWindow

    private var runtime: CoreRuntime { app.runtime }

    var body: some View {
        Text(statusLine)

        Divider()

        Button(runtime.state == .connected ? "Отключить" : "Подключить") {
            Task { await app.toggleConnection() }
        }
        .disabled(app.store.selectedProfile() == nil || busy)

        Menu("Сервер") {
            ForEach(app.store.profiles) { profile in
                Button {
                    Task { await app.select(profile) }
                } label: {
                    // A checkmark rather than a disabled row: the selected
                    // server stays clickable, which is how you reconnect to it.
                    Text(profile.id == app.store.selectedID
                         ? "✓ \(profile.name)"
                         : "   \(profile.name)")
                }
            }
        }
        .disabled(app.store.profiles.isEmpty)

        Menu("Трафик") {
            ForEach(Self.modes, id: \.0) { mode, title in
                Button(app.store.mode == mode ? "✓ \(title)" : "   \(title)") {
                    Task { await runtime.applyMode(mode) }
                }
            }
        }

        Divider()

        Button("Открыть GrKa X") {
            openWindow(id: "main")
            NSApp.activate(ignoringOtherApps: true)
        }

        Button("Выйти") {
            // Through the app's own teardown, never a bare terminate: quitting
            // with a tunnel up is what leaves a machine with no network and no
            // window left to fix it from.
            Task {
                await runtime.shutdown()
                NSApp.terminate(nil)
            }
        }
    }

    private static let modes: [(String, String)] = [
        (AppConfig.modeRule, "Правила"),
        (AppConfig.modeGlobal, "Глобально"),
        (AppConfig.modeDirect, "Прямое"),
    ]

    private var busy: Bool {
        runtime.state == .connecting || runtime.state == .stopping
    }

    private var statusLine: String {
        switch runtime.state {
        case .connected:
            let name = app.store.selectedProfile()?.name ?? "сервер"
            return "Подключено · \(name)"
        case .connecting: return "Подключение…"
        case .stopping: return "Отключение…"
        case .disconnected: return "Отключено"
        }
    }
}

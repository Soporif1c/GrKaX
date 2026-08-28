import GrKaXKit
import SwiftUI

enum Screen: String, CaseIterable, Identifiable {
    case home, servers, settings, log

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: "Главная"
        case .servers: "Серверы"
        case .settings: "Настройки"
        case .log: "Журнал"
        }
    }

    var icon: String {
        switch self {
        case .home: "bolt.horizontal"
        case .servers: "server.rack"
        case .settings: "gearshape"
        case .log: "text.alignleft"
        }
    }
}

struct RootView: View {
    @Environment(AppEnvironment.self) private var app

    var body: some View {
        @Bindable var app = app
        return NavigationSplitView {
            List(Screen.allCases, selection: $app.screen) { item in
                Label(item.title, systemImage: item.icon).tag(item)
            }
            .navigationSplitViewColumnWidth(min: 160, ideal: 180, max: 220)
            .safeAreaInset(edge: .bottom) { statusFooter }
        } detail: {
            Group {
                switch app.screen {
                case .home: HomeScreen()
                case .servers: ServersScreen()
                case .settings: SettingsScreen()
                case .log: LogScreen()
                }
            }
            .navigationTitle(app.screen.title)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    /// Connection state stays visible from every screen — it is the one thing
    /// the user is here to check. Naming the server too, because with a list of
    /// dozens "connected" on its own does not say connected to what.
    private var statusFooter: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(footerTint)
                .frame(width: 8, height: 8)

            VStack(alignment: .leading, spacing: 1) {
                Text(footerTitle)
                    .font(.caption.weight(.medium))
                if app.runtime.state == .connected,
                   let name = app.store.selectedProfile()?.name {
                    Text(name)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .animation(.easeInOut(duration: 0.2), value: app.runtime.state)
    }

    private var footerTint: Color {
        switch app.runtime.state {
        case .connected: .green
        case .connecting, .stopping: .orange
        case .disconnected: .secondary
        }
    }

    private var footerTitle: String {
        switch app.runtime.state {
        case .connected: "Подключено"
        case .connecting: "Подключение…"
        case .stopping: "Отключение…"
        case .disconnected: "Отключено"
        }
    }
}

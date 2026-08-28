import GrKaXKit
import SwiftUI

struct HomeScreen: View {
    @Environment(AppEnvironment.self) private var app

    private var runtime: CoreRuntime { app.runtime }
    private var connected: Bool { runtime.state == .connected }
    private var busy: Bool { runtime.state == .connecting || runtime.state == .stopping }
    private var profile: Profile? { app.store.selectedProfile() }

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 12)

            powerButton
                .padding(.bottom, 20)

            statusText
            serverLine

            if let error = runtime.lastError, !busy {
                errorBanner(error)
            }

            Spacer(minLength: 20)

            if connected { trafficTiles }

            modePicker
                .padding(.top, 20)
        }
        .padding(28)
        .animation(.easeInOut(duration: 0.2), value: runtime.state)
    }

    // MARK: - Power

    /// The one control on the screen, so it is allowed to be large. The ring
    /// carries the state on its own — colour when settled, motion while the
    /// answer is still unknown — which is what lets the label below stay a
    /// plain word instead of a spinner with a caption.
    private var powerButton: some View {
        Button {
            Task { await app.toggleConnection() }
        } label: {
            ZStack {
                Circle()
                    .fill(ringTint.opacity(0.10))
                    .frame(width: 156, height: 156)

                Circle()
                    .stroke(ringTint.opacity(0.25), lineWidth: 3)
                    .frame(width: 156, height: 156)

                if busy {
                    Circle()
                        .trim(from: 0, to: 0.22)
                        .stroke(ringTint, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .frame(width: 156, height: 156)
                        .rotationEffect(.degrees(spin ? 360 : 0))
                        .animation(
                            .linear(duration: 1.1).repeatForever(autoreverses: false),
                            value: spin
                        )
                        .onAppear { spin = true }
                        .onDisappear { spin = false }
                }

                Image(systemName: "power")
                    .font(.system(size: 52, weight: .thin))
                    .foregroundStyle(ringTint)
            }
        }
        .buttonStyle(.plain)
        .disabled(profile == nil || busy)
        .help(profile == nil ? "Сначала добавьте сервер" : "")
    }

    @State private var spin = false

    private var ringTint: Color {
        switch runtime.state {
        case .connected: .green
        case .connecting, .stopping: .orange
        case .disconnected: profile == nil ? .secondary : .accentColor
        }
    }

    // MARK: - Status

    private var statusText: some View {
        Text(statusTitle)
            .font(.title2.weight(.semibold))
            .foregroundStyle(connected ? Color.green : .primary)
            .contentTransition(.opacity)
    }

    private var statusTitle: String {
        switch runtime.state {
        case .connected: "Подключено"
        case .connecting: "Подключение…"
        case .stopping: "Отключение…"
        case .disconnected: profile == nil ? "Нет серверов" : "Отключено"
        }
    }

    /// Name, then what it actually is, then how long it has been up. The
    /// address itself is deliberately not here: this screen is the one most
    /// likely to be looked at over a shoulder or caught in a screenshot.
    private var serverLine: some View {
        VStack(spacing: 6) {
            if let profile {
                Text(profile.name)
                    .font(.headline)
                    .padding(.top, 10)

                HStack(spacing: 6) {
                    Text(profile.protoLabel)
                    Text("·")
                    Text(profile.transportLabel)
                    if connected, let since = runtime.connectedAt {
                        Text("·")
                        TimelineView(.periodic(from: .now, by: 1)) { _ in
                            Text(uptime(since)).monospacedDigit()
                        }
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            } else {
                Button("Добавить сервер") { app.screen = .servers }
                    .buttonStyle(.link)
                    .padding(.top, 10)
            }
        }
    }

    private func errorBanner(_ error: String) -> some View {
        Text(error)
            .font(.caption)
            .foregroundStyle(.red)
            .multilineTextAlignment(.center)
            .textSelection(.enabled)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
            .padding(.top, 14)
            .padding(.horizontal, 20)
    }

    // MARK: - Traffic

    private var trafficTiles: some View {
        HStack(spacing: 12) {
            trafficTile(
                icon: "arrow.down",
                tint: .blue,
                speed: runtime.traffic.downSpeed,
                total: runtime.traffic.downTotal
            )
            trafficTile(
                icon: "arrow.up",
                tint: .purple,
                speed: runtime.traffic.upSpeed,
                total: runtime.traffic.upTotal
            )
        }
        .frame(maxWidth: 340)
    }

    /// Speed large, session total under it: the first answers "is it moving",
    /// the second "how much have I used" — both asked of this screen, and
    /// previously only the first was answered.
    private func trafficTile(icon: String, tint: Color, speed: Int64, total: Int64) -> some View {
        VStack(spacing: 4) {
            HStack(spacing: 5) {
                Image(systemName: icon).font(.caption2).foregroundStyle(tint)
                Text(Utils.formatSpeed(speed))
                    .font(.callout.weight(.medium).monospacedDigit())
            }
            Text(Utils.formatBytes(total))
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - Mode

    private var modePicker: some View {
        VStack(spacing: 6) {
            Picker("", selection: modeBinding) {
                Text("Правила").tag(AppConfig.modeRule)
                Text("Глобально").tag(AppConfig.modeGlobal)
                Text("Прямое").tag(AppConfig.modeDirect)
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            Text(modeHint)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: 340)
    }

    private var modeHint: String {
        switch app.store.mode {
        case AppConfig.modeGlobal: "Весь трафик через сервер"
        case AppConfig.modeDirect: "Всё напрямую, в обход сервера"
        default: "По правилам маршрутизации"
        }
    }

    private var modeBinding: Binding<String> {
        Binding(
            get: { app.store.mode },
            // Switching while connected restarts the core but leaves the
            // tunnel in place, so this is cheap even mid-session.
            set: { mode in Task { await app.runtime.applyMode(mode) } }
        )
    }

    private func uptime(_ since: Date) -> String {
        Utils.formatDuration(millis: Int64(Date().timeIntervalSince(since) * 1000))
    }
}

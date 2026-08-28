import GrKaXKit
import SwiftUI

struct SettingsScreen: View {
    @Environment(AppEnvironment.self) private var app

    var body: some View {
        Form {
            Section("Сеть") {
                Picker("Режим сети", selection: binding(\.networkMode)) {
                    Text("Системный прокси").tag(AppConfig.netSystemProxy)
                    Text("TUN (весь трафик)").tag(AppConfig.netTun)
                }
                Text(app.store.networkMode == AppConfig.netTun
                     ? "Перехватывает весь трафик, включая приложения, игнорирующие системный прокси. Требует пароль администратора при подключении."
                     : "Не требует прав, но действует только на приложения, которые уважают системный прокси.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Маршрутизация") {
                Picker("Набор правил", selection: binding(\.routingPreset)) {
                    Text("Обход локальной сети").tag(AppConfig.routeBypassLan)
                    Text("Обход RU").tag(AppConfig.routeBypassRu)
                }
                Toggle("Блокировать QUIC", isOn: binding(\.blockQuic))
                Toggle("BitTorrent напрямую", isOn: binding(\.bypassTorrent))
                Toggle("Использовать маршрутизацию из подписки", isOn: binding(\.useSubscriptionRouting))
            }

            Section("DNS") {
                TextField("Внешний DNS", text: binding(\.remoteDns))
                TextField("Прямой DNS", text: binding(\.directDns))
            }

            Section("Порты") {
                portField("SOCKS", binding(\.socksPort))
                portField("HTTP", binding(\.httpPort))
                portField("API (статистика)", binding(\.apiPort))
                Text("Изменения портов вступят в силу при следующем подключении.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Ядро") {
                Toggle("Определять протокол (sniffing)", isOn: binding(\.sniffing))
                Toggle("Только для маршрутизации (routeOnly)", isOn: binding(\.routeOnly))
                Toggle("Mux", isOn: binding(\.mux))
                Picker("Уровень журнала", selection: binding(\.logLevel)) {
                    ForEach(["debug", "info", "warning", "error", "none"], id: \.self) { level in
                        Text(level).tag(level)
                    }
                }
            }

            Section("Подписки") {
                Toggle("Отправлять идентификатор устройства (x-hwid)", isOn: binding(\.hwidEnabled))
                LabeledContent("Идентификатор", value: Utils.hwid)
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
            }

            Section("О программе") {
                LabeledContent("Ядро Xray", value: coreVersion)
                LabeledContent("geo-данные", value: XrayCore.geoDataPresent ? "на месте" : "не найдены")
                LabeledContent("Каталог данных", value: Paths.dataDir.path)
                    .font(.caption)
                    .textSelection(.enabled)
            }
        }
        .formStyle(.grouped)
    }

    private var coreVersion: String {
        (try? XrayCore.version()) ?? "недоступно"
    }

    private func portField(_ label: String, _ value: Binding<Int>) -> some View {
        TextField(label, value: value, format: .number.grouping(.never))
    }

    /// Settings live on the store, which persists on every write — so the
    /// bindings go straight there rather than through local state.
    private func binding<Value>(_ path: ReferenceWritableKeyPath<Store, Value>) -> Binding<Value> {
        Binding(
            get: { app.store[keyPath: path] },
            set: { app.store[keyPath: path] = $0 }
        )
    }
}

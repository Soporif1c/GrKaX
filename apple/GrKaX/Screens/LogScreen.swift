import GrKaXKit
import SwiftUI

struct LogScreen: View {
    @Environment(AppEnvironment.self) private var app
    @State private var showingConfig = false

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 3) {
                        ForEach(Array(app.runtime.logs.enumerated()), id: \.offset) { index, line in
                            Text(line)
                                .font(.system(.caption, design: .monospaced))
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .id(index)
                        }
                    }
                    .padding(12)
                }
                .onChange(of: app.runtime.logs.count) { _, count in
                    guard count > 0 else { return }
                    withAnimation { proxy.scrollTo(count - 1, anchor: .bottom) }
                }
            }
        }
        .toolbar {
            ToolbarItemGroup {
                Button {
                    showingConfig = true
                } label: {
                    Label("Показать конфиг", systemImage: "doc.text")
                }
                .disabled(app.runtime.lastConfig.isEmpty)

                Button {
                    copyToPasteboard(app.runtime.logs.joined(separator: "\n"))
                } label: {
                    Label("Скопировать", systemImage: "doc.on.doc")
                }
                .disabled(app.runtime.logs.isEmpty)

                Button {
                    app.runtime.clearLogs()
                } label: {
                    Label("Очистить", systemImage: "trash")
                }
                .disabled(app.runtime.logs.isEmpty)
            }
        }
        .sheet(isPresented: $showingConfig) { configSheet }
    }

    /// The config the core was last given — the first thing worth looking at
    /// when a connection fails for no visible reason.
    private var configSheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Конфиг последнего запуска").font(.headline)
            ScrollView {
                Text(prettyConfig)
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
            }
            .frame(minWidth: 620, minHeight: 420)
            .background(.quaternary.opacity(0.4), in: .rect(cornerRadius: 8))

            HStack {
                Button("Скопировать") { copyToPasteboard(prettyConfig) }
                Spacer()
                Button("Закрыть") { showingConfig = false }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
    }

    private var prettyConfig: String {
        JSON.parse(app.runtime.lastConfig)?.pretty ?? app.runtime.lastConfig
    }

    private func copyToPasteboard(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }
}

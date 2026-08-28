import GrKaXKit
import SwiftUI

struct ServersScreen: View {
    @Environment(AppEnvironment.self) private var app

    @State private var showingLinkImport = false
    @State private var showingSubscription = false

    var body: some View {
        Group {
            if app.store.profiles.isEmpty && app.store.subscriptions.isEmpty {
                empty
            } else {
                list
            }
        }
        .toolbar {
            ToolbarItemGroup {
                if app.busy { ProgressView().controlSize(.small) }
                Button { showingLinkImport = true } label: {
                    Label("Добавить ссылки", systemImage: "link.badge.plus")
                }
                Button { showingSubscription = true } label: {
                    Label("Добавить подписку", systemImage: "arrow.down.circle")
                }
                Button {
                    Task { await app.updateAll() }
                } label: {
                    Label("Обновить всё", systemImage: "arrow.clockwise")
                }
                .disabled(app.store.subscriptions.isEmpty || app.busy)
            }
        }
        .sheet(isPresented: $showingLinkImport) { LinkImportSheet() }
        .sheet(isPresented: $showingSubscription) { SubscriptionSheet() }
    }

    private var empty: some View {
        ContentUnavailableView {
            Label("Серверов пока нет", systemImage: "server.rack")
        } description: {
            Text("Вставьте ссылки vless:// vmess:// trojan:// ss:// или добавьте подписку.")
        } actions: {
            Button("Вставить ссылки") { showingLinkImport = true }
            Button("Добавить подписку") { showingSubscription = true }
        }
    }

    private var list: some View {
        List {
            ForEach(app.store.subscriptions) { sub in
                Section {
                    profileRows(app.store.profiles.filter { $0.subId == sub.id })
                } header: {
                    subscriptionHeader(sub)
                }
            }

            let loose = app.store.profiles.filter { profile in
                profile.subId == nil
                    || !app.store.subscriptions.contains { $0.id == profile.subId }
            }
            if !loose.isEmpty {
                Section("Добавлены вручную") {
                    profileRows(loose)
                }
            }
        }
    }

    @ViewBuilder
    private func profileRows(_ profiles: [Profile]) -> some View {
        ForEach(profiles) { profile in
            ProfileRow(profile: profile, selected: profile.id == app.store.selectedID)
                .contentShape(.rect)
                .onTapGesture { Task { await app.select(profile) } }
                .contextMenu {
                    Button("Выбрать") { Task { await app.select(profile) } }
                    Button("Удалить", role: .destructive) { app.deleteProfile(profile) }
                }
        }
    }

    private func subscriptionHeader(_ sub: Subscription) -> some View {
        HStack {
            Text(sub.name.isEmpty ? sub.url : sub.name)
            Spacer()
            if sub.total > 0 {
                Text("\(Utils.formatBytes(sub.upload + sub.download)) из \(Utils.formatBytes(sub.total))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if sub.expire > 0 {
                Text("до \(Utils.formatDate(epochSeconds: sub.expire))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Button {
                Task { await app.update(sub) }
            } label: {
                Image(systemName: "arrow.clockwise")
            }
            .buttonStyle(.borderless)
            .disabled(app.busy)

            Button {
                app.deleteSubscription(sub)
            } label: {
                Image(systemName: "trash")
            }
            .buttonStyle(.borderless)
        }
    }
}

private struct ProfileRow: View {
    let profile: Profile
    let selected: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                .foregroundStyle(selected ? Color.accentColor : .secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(profile.name)
                Text("\(profile.server):\(profile.port) · \(profile.protoLabel) · \(profile.transportLabel)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.vertical, 2)
    }
}

private struct LinkImportSheet: View {
    @Environment(AppEnvironment.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Вставьте ссылки").font(.headline)
            Text("По одной в строке. Base64-содержимое подписки тоже подойдёт.")
                .font(.caption)
                .foregroundStyle(.secondary)
            TextEditor(text: $text)
                .font(.system(.caption, design: .monospaced))
                .frame(minWidth: 460, minHeight: 200)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(.quaternary))
            HStack {
                Spacer()
                Button("Отмена") { dismiss() }
                Button("Добавить") {
                    app.importLinks(text)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(20)
    }
}

private struct SubscriptionSheet: View {
    @Environment(AppEnvironment.self) private var app
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var url = ""
    /// Prefilled, as in the Compose client: panels commonly gate the useful
    /// response — xray-json with routing and XHTTP obfuscation — behind a Happ
    /// User-Agent, and an empty field quietly gets you the plain list instead.
    @State private var userAgent = "happ"

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Новая подписка").font(.headline)
            Form {
                TextField("Название", text: $name, prompt: Text("необязательно"))
                TextField("Адрес", text: $url, prompt: Text("https://…"))
                TextField("User-Agent", text: $userAgent, prompt: Text("необязательно, например happ"))
                Text("Некоторые панели отдают разный формат в зависимости от User-Agent. «happ» разворачивается в полную строку Happ.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .formStyle(.grouped)
            .frame(minWidth: 460)

            HStack {
                Spacer()
                Button("Отмена") { dismiss() }
                Button("Добавить") {
                    let sheetName = name
                    let sheetURL = url
                    let sheetAgent = userAgent
                    dismiss()
                    Task { await app.addSubscription(name: sheetName, url: sheetURL, userAgent: sheetAgent) }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(url.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(20)
    }
}

import SwiftUI

struct RootView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        Group {
            if store.session == nil {
                AuthenticationView()
            } else {
                MainTabView()
            }
        }
        .alert("智悟本", isPresented: Binding(
            get: { store.statusMessage != nil },
            set: { if !$0 { store.statusMessage = nil } }
        )) {
            Button("知道了", role: .cancel) { store.statusMessage = nil }
        } message: {
            Text(store.statusMessage ?? "")
        }
    }
}

struct BrandMark: View {
    var size: CGFloat = 44

    var body: some View {
        Image("BrandIcon")
            .resizable()
            .scaledToFill()
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: size * 0.24, style: .continuous))
            .accessibilityLabel("智悟本")
    }
}

private struct AuthenticationView: View {
    @EnvironmentObject private var store: AppStore
    @State private var isRegistering = false
    @State private var endpoint = ""
    @State private var username = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    Spacer(minLength: 42)
                    BrandMark(size: 72)
                    VStack(spacing: 8) {
                        Text("智悟本")
                            .font(.largeTitle.bold())
                        Text("智记所言，悟行所止。")
                            .font(.headline)
                            .foregroundStyle(.secondary)
                        Text("智能体 · 小Woo")
                            .font(.subheadline)
                            .foregroundStyle(.tint)
                    }
                    .multilineTextAlignment(.center)

                    VStack(spacing: 14) {
                        TextField("账户服务地址（以 /api 结尾）", text: $endpoint)
                            .textInputAutocapitalization(.never)
                            .keyboardType(.URL)
                            .textContentType(.URL)
                            .textFieldStyle(.roundedBorder)
                        TextField("用户名", text: $username)
                            .textInputAutocapitalization(.never)
                            .textContentType(.username)
                            .textFieldStyle(.roundedBorder)
                        SecureField("密码", text: $password)
                            .textContentType(isRegistering ? .newPassword : .password)
                            .textFieldStyle(.roundedBorder)
                    }
                    .padding(.top, 8)

                    Button {
                        store.saveConfiguration(AppConfiguration(
                            accountEndpoint: endpoint,
                            sttEndpoint: store.configuration.sttEndpoint,
                            agentProvider: store.configuration.agentProvider,
                            reasoningEffort: store.configuration.reasoningEffort,
                            selectedTemplateName: store.configuration.selectedTemplateName
                        ))
                        Task { await store.authenticate(username: username, password: password, registering: isRegistering) }
                    } label: {
                        HStack {
                            if store.isWorking { ProgressView().tint(.white) }
                            Text(isRegistering ? "创建账号" : "登录")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(store.isWorking || endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || username.isEmpty || password.isEmpty)

                    Button(isRegistering ? "已有账号，去登录" : "新用户，创建账号") {
                        isRegistering.toggle()
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.tint)
                }
                .padding(.horizontal, 24)
            }
            .onAppear { endpoint = store.configuration.accountEndpoint }
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct MainTabView: View {
    var body: some View {
        TabView {
            HomeView()
                .tabItem { Label("首页", systemImage: "square.grid.2x2.fill") }
            MeetingListView()
                .tabItem { Label("会议", systemImage: "list.bullet.rectangle") }
            ProfileView()
                .tabItem { Label("我的", systemImage: "person.crop.circle") }
        }
    }
}

private struct HomeView: View {
    @EnvironmentObject private var store: AppStore
    @State private var showNewMeeting = false

    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(Date.now.formatted(.dateTime.year().month().day().weekday()))
                            .font(.subheadline)
                            .foregroundStyle(.tint)
                        Text("智记所言，悟行所止。")
                            .font(.title2.bold())
                        Text("智慧  领悟  本源")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }

                    LazyVGrid(columns: columns, spacing: 10) {
                        QuickAction(title: "快速录音", subtitle: "小Woo 实时记录", icon: "mic.fill", tint: .green) { showNewMeeting = true }
                        QuickAction(title: "新建会议", subtitle: "建立会议档案", icon: "plus.circle.fill", tint: .orange) { showNewMeeting = true }
                        NavigationLink {
                            MeetingListView()
                        } label: {
                            QuickActionLabel(title: "最近记录", subtitle: "查看全部会议", icon: "clock.arrow.circlepath", tint: .blue)
                        }
                        NavigationLink {
                            SettingsView()
                        } label: {
                            QuickActionLabel(title: "服务设置", subtitle: "小Woo 与模型", icon: "slider.horizontal.3", tint: .purple)
                        }
                    }

                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("最近记录").font(.headline)
                            Text("\(store.meetings.count) 条会议").font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer()
                        NavigationLink("查看全部") { MeetingListView() }
                    }

                    if store.meetings.isEmpty {
                        ContentUnavailableView(
                            "还没有会议记录",
                            systemImage: "waveform",
                            description: Text("从一次录音或一份文字开始。")
                        )
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                    } else {
                        ForEach(Array(store.meetings.prefix(3))) { meeting in
                            NavigationLink {
                                MeetingEditorView(meetingID: meeting.id)
                            } label: {
                                MeetingRow(meeting: meeting)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("智悟本")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { BrandMark(size: 34) }
                ToolbarItem(placement: .topBarTrailing) {
                    Text("智能体 · 小Woo").font(.caption).foregroundStyle(.secondary)
                }
            }
            .sheet(isPresented: $showNewMeeting) { NewMeetingSheet() }
        }
    }
}

private struct QuickAction: View {
    let title: String
    let subtitle: String
    let icon: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) { QuickActionLabel(title: title, subtitle: subtitle, icon: icon, tint: tint) }
            .buttonStyle(.plain)
    }
}

private struct QuickActionLabel: View {
    let title: String
    let subtitle: String
    let icon: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(tint)
            Spacer(minLength: 0)
            Text(title).font(.headline).foregroundStyle(.primary)
            Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .frame(maxWidth: .infinity, minHeight: 106, alignment: .leading)
        .padding(14)
        .background(tint.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
    }
}

struct MeetingRow: View {
    let meeting: Meeting

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: meeting.report == nil ? "waveform" : "doc.text.fill")
                .foregroundStyle(meeting.report == nil ? Color.accentColor : Color.green)
                .frame(width: 30, height: 30)
                .background((meeting.report == nil ? Color.accentColor : .green).opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 4) {
                Text(meeting.title).font(.headline).foregroundStyle(.primary).lineLimit(1)
                Text("\(meeting.templateName) · \(meeting.updatedAt.formatted(date: .abbreviated, time: .shortened))")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
        }
        .padding(12)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct MeetingListView: View {
    @EnvironmentObject private var store: AppStore
    @State private var showNewMeeting = false

    var body: some View {
        NavigationStack {
            List {
                if store.meetings.isEmpty {
                    ContentUnavailableView("还没有会议记录", systemImage: "list.bullet.rectangle")
                        .listRowBackground(Color.clear)
                } else {
                    ForEach(store.meetings) { meeting in
                        NavigationLink { MeetingEditorView(meetingID: meeting.id) } label: { MeetingRow(meeting: meeting) }
                            .listRowSeparator(.hidden)
                    }
                    .onDelete { offsets in
                        offsets.map { store.meetings[$0].id }.forEach(store.deleteMeeting)
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle("会议记录")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showNewMeeting = true } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $showNewMeeting) { NewMeetingSheet() }
        }
    }
}

private struct NewMeetingSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: AppStore
    @State private var title = ""
    @State private var templateName = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("会议信息") {
                    TextField("会议名称", text: $title)
                    Picker("纪要模板", selection: $templateName) {
                        ForEach(MeetingTemplate.all) { template in
                            Text("\(template.name) · \(template.subtitle)").tag(template.name)
                        }
                    }
                }
            }
            .navigationTitle("新建会议")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") {
                        _ = store.createMeeting(title: title, templateName: templateName)
                        dismiss()
                    }
                }
            }
            .onAppear { templateName = store.configuration.selectedTemplateName }
        }
    }
}

private struct ProfileView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 12) {
                        BrandMark(size: 48)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(store.profileName).font(.headline)
                            Text("智能体 · 小Woo").font(.subheadline).foregroundStyle(.tint)
                            if let profile = store.session?.user {
                                Text("\(profile.planName) · 剩余 \(profile.quota.requestsRemaining) 次")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Section("账户与能力") {
                    NavigationLink { SettingsView() } label: { Label("服务设置", systemImage: "slider.horizontal.3") }
                    NavigationLink { TemplateLibraryView() } label: { Label("纪要模板", systemImage: "doc.text") }
                }
                Section {
                    Button("退出登录", role: .destructive) { store.logout() }
                }
            }
            .navigationTitle("我的")
        }
    }
}

private struct TemplateLibraryView: View {
    var body: some View {
        List(MeetingTemplate.all) { template in
            VStack(alignment: .leading, spacing: 5) {
                Text(template.name).font(.headline)
                Text(template.subtitle).font(.subheadline).foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)
        }
        .navigationTitle("纪要模板")
    }
}

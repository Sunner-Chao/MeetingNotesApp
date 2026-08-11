import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var configuration = AppConfiguration()

    var body: some View {
        Form {
            Section {
                TextField("账户服务地址", text: $configuration.accountEndpoint)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                TextField("STT 服务地址", text: $configuration.sttEndpoint)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
            } header: {
                Text("服务连接")
            } footer: {
                Text("账户服务地址用于登录和小Woo纪要；STT 服务地址用于录音结束后的最终转写。所有地址均由你在运行时配置。")
            }

            Section("智能体 · 小Woo") {
                Picker("智能体", selection: $configuration.agentProvider) {
                    Text("小Woo").tag("codex-cli")
                    Text("智能体").tag("claude-cli")
                }
                Picker("推理强度", selection: $configuration.reasoningEffort) {
                    Text("低").tag("low")
                    Text("中").tag("medium")
                    Text("高").tag("high")
                    Text("超高").tag("xhigh")
                }
            }

            Section("默认纪要模板") {
                Picker("模板", selection: $configuration.selectedTemplateName) {
                    ForEach(MeetingTemplate.all) { template in
                        Text(template.name).tag(template.name)
                    }
                }
                ForEach(MeetingTemplate.all) { template in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(template.name)
                        Text(template.subtitle).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }

            if let profile = store.session?.user {
                Section("当前账号") {
                    LabeledContent("用户名", value: profile.username)
                    LabeledContent("套餐", value: profile.planName)
                    LabeledContent("AI 处理额度", value: "剩余 \(profile.quota.requestsRemaining) 次")
                    Button("刷新账号会话") { Task { await store.refreshSession() } }
                }
            }
        }
        .navigationTitle("服务设置")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") {
                    store.saveConfiguration(configuration)
                    dismiss()
                }
            }
        }
        .onAppear { configuration = store.configuration }
    }
}

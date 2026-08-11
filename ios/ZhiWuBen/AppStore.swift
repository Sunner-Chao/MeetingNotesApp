import Foundation
import Combine

@MainActor
final class AppStore: ObservableObject {
    @Published private(set) var configuration: AppConfiguration
    @Published private(set) var session: AuthSession?
    @Published private(set) var meetings: [Meeting]
    @Published var isWorking = false
    @Published var statusMessage: String?

    private let apiClient = APIClient()
    private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let packagedConfiguration = AppConfiguration.buildDefaults()
        var resolvedConfiguration = Self.load(
            AppConfiguration.self,
            key: LocalStorage.configurationKey,
            from: defaults
        ) ?? packagedConfiguration
        if !resolvedConfiguration.hasAccountEndpoint {
            resolvedConfiguration.accountEndpoint = packagedConfiguration.accountEndpoint
        }
        if !resolvedConfiguration.hasSttEndpoint {
            resolvedConfiguration.sttEndpoint = packagedConfiguration.sttEndpoint
        }
        self.configuration = resolvedConfiguration
        self.session = Self.load(AuthSession.self, key: LocalStorage.sessionKey, from: defaults)
        self.meetings = Self.load([Meeting].self, key: LocalStorage.meetingsKey, from: defaults) ?? []
        self.meetings.sort { $0.updatedAt > $1.updatedAt }
    }

    var profileName: String {
        let displayName = session?.user.displayName.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return displayName.isEmpty ? (session?.user.username ?? "访客") : displayName
    }

    func saveConfiguration(_ value: AppConfiguration) {
        configuration = value
        persist(value, key: LocalStorage.configurationKey)
    }

    func authenticate(username: String, password: String, registering: Bool) async {
        guard configuration.hasAccountEndpoint else {
            statusMessage = "请先填写账户服务地址"
            return
        }
        isWorking = true
        defer { isWorking = false }
        do {
            let authenticated = try await (registering
                ? apiClient.register(endpoint: configuration.accountEndpoint, username: username, password: password)
                : apiClient.login(endpoint: configuration.accountEndpoint, username: username, password: password))
            session = authenticated
            persist(authenticated, key: LocalStorage.sessionKey)
            statusMessage = registering ? "注册成功，已启用 Free 试用套餐" : "登录成功"
        } catch {
            statusMessage = error.localizedDescription
        }
    }

    func refreshSession() async {
        guard let current = session, configuration.hasAccountEndpoint else { return }
        do {
            let refreshed = try await apiClient.refreshSession(
                endpoint: configuration.accountEndpoint,
                accessToken: current.accessToken
            )
            let updated = AuthSession(
                accessToken: current.accessToken,
                agentAccessToken: refreshed.agentAccessToken,
                sttAccessToken: refreshed.sttAccessToken,
                expiresAt: refreshed.expiresAt,
                user: refreshed.user
            )
            session = updated
            persist(updated, key: LocalStorage.sessionKey)
        } catch {
            statusMessage = "会话刷新失败：\(error.localizedDescription)"
        }
    }

    func logout() {
        session = nil
        defaults.removeObject(forKey: LocalStorage.sessionKey)
        statusMessage = "已退出当前账号"
    }

    @discardableResult
    func createMeeting(title: String, templateName: String? = nil) -> Meeting {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let meeting = Meeting(
            title: cleanTitle.isEmpty ? "会议记录 \(Self.meetingDateFormatter.string(from: Date()))" : cleanTitle,
            templateName: templateName ?? configuration.selectedTemplateName
        )
        meetings.insert(meeting, at: 0)
        persistMeetings()
        return meeting
    }

    func meeting(id: String) -> Meeting? {
        meetings.first(where: { $0.id == id })
    }

    func saveMeeting(_ meeting: Meeting) {
        guard let index = meetings.firstIndex(where: { $0.id == meeting.id }) else { return }
        var updated = meeting
        updated.updatedAt = Date()
        meetings[index] = updated
        meetings.sort { $0.updatedAt > $1.updatedAt }
        persistMeetings()
    }

    func deleteMeeting(id: String) {
        guard let existing = meeting(id: id) else { return }
        if let fileName = existing.audioFileName, let url = LocalStorage.audioURL(fileName: fileName) {
            try? FileManager.default.removeItem(at: url)
        }
        meetings.removeAll { $0.id == id }
        persistMeetings()
        statusMessage = "会议已删除"
    }

    func transcribe(meetingID: String) async {
        guard configuration.hasSttEndpoint else {
            statusMessage = "请在服务设置中填写 STT 服务地址"
            return
        }
        guard let token = session?.sttAccessToken, !token.isEmpty else {
            statusMessage = "STT 短令牌不可用，请先刷新账号会话"
            return
        }
        guard let meeting = meeting(id: meetingID),
              let fileName = meeting.audioFileName,
              let audioURL = LocalStorage.audioURL(fileName: fileName),
              FileManager.default.fileExists(atPath: audioURL.path) else {
            statusMessage = "请先录制会议音频"
            return
        }
        isWorking = true
        defer { isWorking = false }
        do {
            let text = try await apiClient.transcribe(
                endpoint: configuration.sttEndpoint,
                sttToken: token,
                meetingID: meeting.id,
                audioURL: audioURL
            )
            var updated = meeting
            updated.transcript = text
            saveMeeting(updated)
            statusMessage = "最终转录已生成"
        } catch {
            statusMessage = "转录失败：\(error.localizedDescription)"
        }
    }

    func generateReport(meetingID: String) async {
        guard let currentSession = session else {
            statusMessage = "请先登录"
            return
        }
        guard configuration.hasAccountEndpoint else {
            statusMessage = "请在服务设置中填写账户服务地址"
            return
        }
        guard let meeting = meeting(id: meetingID),
              !meeting.transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            statusMessage = "请先录入或生成转写内容"
            return
        }
        isWorking = true
        defer { isWorking = false }
        do {
            let output = try await apiClient.generateReport(
                endpoint: configuration.accountEndpoint,
                agentToken: currentSession.agentAccessToken,
                provider: configuration.agentProvider,
                reasoningEffort: configuration.reasoningEffort,
                transcript: meeting.transcript,
                template: MeetingTemplate.named(meeting.templateName)
            )
            var updated = meeting
            updated.report = output
            saveMeeting(updated)
            statusMessage = "会议纪要已生成"
        } catch {
            statusMessage = "纪要生成失败：\(error.localizedDescription)"
        }
    }

    private func persistMeetings() {
        persist(meetings, key: LocalStorage.meetingsKey)
    }

    private func persist<T: Encodable>(_ value: T, key: String) {
        if let data = try? encoder.encode(value) {
            defaults.set(data, forKey: key)
        }
    }

    private static func load<T: Decodable>(_ type: T.Type, key: String, from defaults: UserDefaults) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private static let meetingDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm"
        return formatter
    }()
}

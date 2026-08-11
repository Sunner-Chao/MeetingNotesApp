import Foundation

struct AccountQuota: Codable, Hashable {
    var requestLimit: Int = 0
    var requestsUsed: Int = 0
    var requestsRemaining: Int = 0

    enum CodingKeys: String, CodingKey {
        case requestLimit = "request_limit"
        case requestsUsed = "requests_used"
        case requestsRemaining = "requests_remaining"
    }
}

struct AccountProfile: Codable, Hashable {
    var id: String
    var username: String
    var displayName: String = ""
    var role: String = "user"
    var isAdmin: Bool = false
    var vipEnabled: Bool = false
    var planName: String = "Free"
    var quota: AccountQuota = AccountQuota()

    enum CodingKeys: String, CodingKey {
        case id, username, role, quota
        case displayName = "display_name"
        case isAdmin = "is_admin"
        case vipEnabled = "vip_enabled"
        case planName = "plan_name"
    }
}

struct AuthSession: Codable, Hashable {
    var accessToken: String
    var agentAccessToken: String
    var sttAccessToken: String?
    var expiresAt: Int
    var user: AccountProfile

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case agentAccessToken = "agent_access_token"
        case sttAccessToken = "stt_access_token"
        case expiresAt = "expires_at"
        case user
    }
}

struct AppConfiguration: Codable, Hashable {
    var accountEndpoint: String = ""
    var sttEndpoint: String = ""
    var agentProvider: String = "codex-cli"
    var reasoningEffort: String = "medium"
    var selectedTemplateName: String = MeetingTemplate.projectManagement.name

    var hasAccountEndpoint: Bool { !accountEndpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var hasSttEndpoint: Bool { !sttEndpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    static func buildDefaults(bundle: Bundle = .main) -> AppConfiguration {
        AppConfiguration(
            accountEndpoint: packagedEndpoint(for: "ZhiWuBenDefaultAccountEndpoint", bundle: bundle),
            sttEndpoint: packagedEndpoint(for: "ZhiWuBenDefaultSTTEndpoint", bundle: bundle)
        )
    }

    private static func packagedEndpoint(for key: String, bundle: Bundle) -> String {
        let value = (bundle.object(forInfoDictionaryKey: key) as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.contains("$(") ? "" : value
    }
}

struct Meeting: Identifiable, Codable, Hashable {
    var id: String
    var title: String
    var createdAt: Date
    var updatedAt: Date
    var templateName: String
    var transcript: String
    var report: String?
    var audioFileName: String?
    var durationSeconds: TimeInterval

    init(
        id: String = UUID().uuidString.lowercased(),
        title: String,
        templateName: String = MeetingTemplate.projectManagement.name
    ) {
        self.id = id
        self.title = title
        self.createdAt = Date()
        self.updatedAt = Date()
        self.templateName = templateName
        self.transcript = ""
        self.report = nil
        self.audioFileName = nil
        self.durationSeconds = 0
    }
}

struct MeetingTemplate: Identifiable, Hashable {
    let name: String
    let subtitle: String
    let content: String

    var id: String { name }

    static let projectManagement = MeetingTemplate(
        name: "项目管理",
        subtitle: "推演节点、风险与行动项",
        content: """
        # 【会议主题】

        ## 核心结论摘要
        - ...

        ## 重点讨论议题
        - ...

        ## 已达成共识
        | 编号 | 共识事项 | 说明 |
        | --- | --- | --- |
        | C-01 | ... | ... |

        ## 行动项跟踪表
        | ActionID | 事项 | 负责人 | 当前状态 | 截止时间 |
        | --- | --- | --- | --- | --- |
        | ACT-001 | ... | 待确认 | 待执行 | 待确认 |

        ## 风险提醒
        | 风险编号 | 风险内容 | 说明 |
        | --- | --- | --- |
        | R-01 | ... | ... |

        输出约束：不编造事实；负责人和时间未提及时写“待确认”；先交代节点和风险，再呈现行动项。
        """
    )

    static let administrative = MeetingTemplate(
        name: "行政会议",
        subtitle: "宣贯决定、时间节点与执行",
        content: """
        # 【行政会议主题】

        ## 会议信息
        | 项目 | 内容 |
        | --- | --- |
        | 会议日期 | 待确认 |
        | 主持人 | 待确认 |
        | 参会人员 | 待确认 |

        ## 核心决定
        | 决定编号 | 决定事项 | 生效时间 | 责任部门/人 |
        | --- | --- | --- | --- |
        | D-01 | ... | 待确认 | 待确认 |

        ## 时间节点总表
        | 节点编号 | 事项 | 责任部门/人 | 启动时间 | 阶段检查时间 | 最终截止时间 | 当前状态 |
        | --- | --- | --- | --- | --- | --- | --- |
        | T-01 | ... | 待确认 | 待确认 | 待确认 | 待确认 | 待执行 |

        ## 行动项跟踪表
        | ActionID | 任务 | 负责人 | 截止时间 | 验收标准 | 状态 |
        | --- | --- | --- | --- | --- | --- |
        | ACT-001 | ... | 待确认 | 待确认 | ... | 待执行 |

        输出约束：以时间节点为主线，严格区分会议时间、检查时间、截止时间和汇报时间；未知内容写“待确认”。
        """
    )

    static let brainstorming = MeetingTemplate(
        name: "头脑风暴",
        subtitle: "观点聚类、洞察与验证",
        content: """
        # 【头脑风暴/沙龙主题】

        ## 活动信息
        | 项目 | 内容 |
        | --- | --- |
        | 主题 | ... |
        | 时间 | 待确认 |
        | 主持/引导人 | 待确认 |

        ## 嘉宾观点与灵感触发
        | 发言人 | 核心观点 | 案例/依据 | 触发的追问 | 是否存在分歧 |
        | --- | --- | --- | --- |
        | ... | ... | ... | ... | 待确认 |

        ## 创意池
        | IdeaID | 创意名称 | 提出者 | 核心设想 | 关键假设 |
        | --- | --- | --- | --- |
        | IDEA-001 | ... | 待确认 | ... | ... |

        ## 主题聚类与优先验证
        | 聚类编号 | 主题 | 包含的 IdeaID | 可组合方向 |
        | --- | --- | --- | --- |
        | CL-01 | ... | IDEA-001 | ... |

        | ExperimentID | 对应 IdeaID | 最小验证动作 | 负责人 | 验证截止时间 |
        | --- | --- | --- | --- |
        | EXP-001 | IDEA-001 | ... | 待确认 | 待确认 |

        输出约束：保留有价值的发散想法和少数观点；先归纳观点脉络，再呈现验证方向，不把讨论误写成正式立项。
        """
    )

    static let all = [projectManagement, administrative, brainstorming]

    static func named(_ name: String) -> MeetingTemplate {
        all.first(where: { $0.name == name }) ?? projectManagement
    }
}

enum LocalStorage {
    static let configurationKey = "zhiwuben.ios.configuration"
    static let sessionKey = "zhiwuben.ios.session"
    static let meetingsKey = "zhiwuben.ios.meetings"

    static func audioDirectory() throws -> URL {
        let documents = try FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = documents.appendingPathComponent("MeetingAudio", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    static func audioURL(fileName: String) -> URL? {
        try? audioDirectory().appendingPathComponent(fileName)
    }
}

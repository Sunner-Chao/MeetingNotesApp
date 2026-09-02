package com.oa.automation.ui.screen.recording

/**
 * A short, user-facing explanation of how a meeting template turns a recording
 * into a structured report. The model intentionally has no Compose dependency
 * so it can later be loaded from a versioned JSON registry.
 */
internal data class TemplateWorkflowStep(
    val title: String,
    val detail: String,
    val iconKey: String
)

internal data class TemplateWorkflow(
    val templateName: String,
    val goal: String,
    val aiFocus: String,
    val steps: List<TemplateWorkflowStep>,
    val output: String,
    val confirmation: String
)

internal fun templateWorkflowFor(templateName: String): TemplateWorkflow {
    val normalized = templateName.trim()
    return when {
        normalized.contains("宣贯") || normalized.contains("行政") -> TemplateWorkflow(
            templateName = normalized,
            goal = "把要求讲清楚，并落到责任人和时间点",
            aiFocus = "识别指令、责任归属与截止时间",
            steps = listOf(
                TemplateWorkflowStep("听清要求", "提炼政策、任务和背景", "listen"),
                TemplateWorkflowStep("拆解任务", "匹配负责人、节点和依赖", "split"),
                TemplateWorkflowStep("确认口径", "标记分歧与待确认信息", "check"),
                TemplateWorkflowStep("形成落实表", "输出可追踪的行动项", "output")
            ),
            output = "落实清单 · 责任人 · 时间节点",
            confirmation = "会后只需核对责任人和日期"
        )
        normalized.contains("推演") || normalized.contains("进度") || normalized.contains("项目") -> TemplateWorkflow(
            templateName = normalized,
            goal = "看清里程碑、风险和下一步动作",
            aiFocus = "追踪进度变化，发现阻塞与风险信号",
            steps = listOf(
                TemplateWorkflowStep("回看节点", "对齐已完成与进行中事项", "timeline"),
                TemplateWorkflowStep("定位风险", "聚合问题、依赖和阻塞项", "risk"),
                TemplateWorkflowStep("推演方案", "记录不同方案与取舍依据", "branch"),
                TemplateWorkflowStep("锁定动作", "生成负责人和截止时间", "output")
            ),
            output = "里程碑看板 · 风险清单 · 行动项",
            confirmation = "会后确认风险等级和下一检查点"
        )
        normalized.contains("启迪") || normalized.contains("共创") || normalized.contains("头脑") -> TemplateWorkflow(
            templateName = normalized,
            goal = "让发散的想法变成可验证的创意池",
            aiFocus = "聚类观点、保留原话并识别共识",
            steps = listOf(
                TemplateWorkflowStep("自由发言", "完整保留灵感和例子", "spark"),
                TemplateWorkflowStep("聚类观点", "把相近想法放到一起", "cluster"),
                TemplateWorkflowStep("筛选方向", "记录共识、分歧和优先级", "filter"),
                TemplateWorkflowStep("安排验证", "输出实验与反馈入口", "output")
            ),
            output = "创意地图 · 共识点 · 验证计划",
            confirmation = "会后挑选一个最小验证动作"
        )
        normalized.contains("博弈") || normalized.contains("洽谈") || normalized.contains("客户") -> TemplateWorkflow(
            templateName = normalized,
            goal = "把双方立场、条款和可交换空间记录清楚",
            aiFocus = "区分事实、条件、让步和情绪信号",
            steps = listOf(
                TemplateWorkflowStep("识别立场", "记录双方目标和底线", "people"),
                TemplateWorkflowStep("拆看条款", "捕捉价格、范围与条件", "terms"),
                TemplateWorkflowStep("识别信号", "提示犹豫、共识和升级点", "signal"),
                TemplateWorkflowStep("形成共识", "列出承诺与待补材料", "output")
            ),
            output = "谈判摘要 · 条款表 · 待办材料",
            confirmation = "会后复核敏感表述和对外口径"
        )
        normalized.contains("复盘") || normalized.contains("分析") -> TemplateWorkflow(
            templateName = normalized,
            goal = "从事实回到根因，形成下一轮改进",
            aiFocus = "建立时间线，区分现象、原因和措施",
            steps = listOf(
                TemplateWorkflowStep("还原事实", "按时间整理关键事件", "timeline"),
                TemplateWorkflowStep("追问根因", "区分直接原因与系统原因", "search"),
                TemplateWorkflowStep("总结经验", "保留有效做法与教训", "insight"),
                TemplateWorkflowStep("制定预防", "生成下一轮改进动作", "output")
            ),
            output = "事件时间线 · 根因树 · 改进清单",
            confirmation = "会后确认哪些措施进入下个周期"
        )
        normalized.contains("敏捷") || normalized.contains("站会") -> TemplateWorkflow(
            templateName = normalized,
            goal = "用几分钟对齐昨天、今天和阻塞项",
            aiFocus = "保持简洁，优先识别阻塞与承诺",
            steps = listOf(
                TemplateWorkflowStep("昨日完成", "记录已交付和进展", "done"),
                TemplateWorkflowStep("今日计划", "明确今天要完成什么", "today"),
                TemplateWorkflowStep("暴露阻塞", "标记需要协作的事项", "block"),
                TemplateWorkflowStep("带走承诺", "输出短小的跟进列表", "output")
            ),
            output = "站会摘要 · 阻塞项 · 今日承诺",
            confirmation = "会后只检查阻塞项是否有人接手"
        )
        else -> TemplateWorkflow(
            templateName = normalized.ifBlank { "通用会议" },
            goal = "把讨论整理成可阅读、可执行的纪要",
            aiFocus = "识别议题、事实、观点、结论与行动项",
            steps = listOf(
                TemplateWorkflowStep("记录讨论", "保留关键发言和证据", "listen"),
                TemplateWorkflowStep("整理议题", "按主题合并相关内容", "cluster"),
                TemplateWorkflowStep("提炼结论", "区分已确认与待确认", "check"),
                TemplateWorkflowStep("生成纪要", "输出结构化会议文档", "output")
            ),
            output = "议题摘要 · 结论 · 行动项",
            confirmation = "会后快速浏览高亮的待确认项"
        )
    }
}

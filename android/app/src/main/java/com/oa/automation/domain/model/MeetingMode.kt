package com.oa.automation.domain.model

/**
 * Meeting-purpose catalog used by the light edition. Template names remain the
 * persisted compatibility key so existing Room and DataStore records keep working.
 */
enum class MeetingMode(
    val templateName: String,
    val displayName: String
) {
    GENERAL("通用会议", "通用会议"),
    DIRECTIVE("宣贯·落实会", "宣贯·落实会"),
    /** Persisted as the legacy key “项目管理” for Room/DataStore compatibility. */
    PROGRESS("项目管理", "推演·进度会"),
    CO_CREATE("启迪·共创会", "启迪·共创会"),
    NEGOTIATION("博弈·洽谈会", "博弈·洽谈会"),
    RETROSPECTIVE("复盘·分析会", "复盘·分析会"),
    STANDUP("敏捷·站会", "敏捷·站会"),
    /** Persisted as the legacy key “论坛会议” so existing forum reports retain their behavior. */
    FORUM("论坛会议", "论坛·共识会"),
    CUSTOM("自定义会议", "自定义会议"),
    /** Dedicated listening/planning workflow; distinct from the user-composed custom template. */
    LISTENING_PLANNING("聆听·策划会", "聆听·策划会"),
    STUDY("研学考察", "研学考察");

    companion object {
        fun fromTemplateName(templateName: String): MeetingMode {
            val normalized = templateName.trim()
            return when {
                normalized == DIRECTIVE.templateName || normalized == "行政会议" -> DIRECTIVE
                normalized == PROGRESS.templateName || normalized == "推演·进度会" -> PROGRESS
                normalized == CO_CREATE.templateName || normalized == "头脑风暴" -> CO_CREATE
                normalized == NEGOTIATION.templateName -> NEGOTIATION
                normalized == RETROSPECTIVE.templateName -> RETROSPECTIVE
                normalized == STANDUP.templateName -> STANDUP
                normalized == FORUM.templateName ||
                    normalized == FORUM.displayName ||
                    normalized == "论坛共识会" ||
                    normalized == "聚智·论道会" ||
                    normalized == "聚智论道会" -> FORUM
                normalized == CUSTOM.templateName -> CUSTOM
                normalized == LISTENING_PLANNING.templateName ||
                    normalized == "聆听策划会" ||
                    normalized == "听策划会" -> LISTENING_PLANNING
                normalized == STUDY.templateName ||
                    normalized.contains("参观考察") ||
                    normalized.contains("游记") -> STUDY
                else -> GENERAL
            }
        }
    }
}

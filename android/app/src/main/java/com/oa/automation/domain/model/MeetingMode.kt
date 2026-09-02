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
    FORUM("论坛会议", "论坛会议"),
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
                normalized == FORUM.templateName -> FORUM
                normalized == STUDY.templateName -> STUDY
                else -> GENERAL
            }
        }
    }
}

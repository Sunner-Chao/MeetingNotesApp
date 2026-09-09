package com.oa.automation.domain.model

/**
 * A module that can be dragged into the 自定义会议 template.
 *
 * The declaration order is the factory default order shipped in
 * `assets/自定义会议.md`; users may reorder and disable modules and the saved
 * arrangement drives report generation.
 */
enum class CustomTemplateModule(
    val title: String,
    val hint: String,
    /** Section body written into the generated template for this module. */
    val promptBody: String
) {
    MEETING_INFO(
        title = "会议信息",
        hint = "主题、时间地点与参会人员",
        promptBody = "- 会议主题：\n- 时间与地点：\n- 参会人员："
    ),
    CORE_SUMMARY(
        title = "核心摘要",
        hint = "背景、重点与结果的概述",
        promptBody = "用简洁文字概括本次讨论的背景、重点与结果。"
    ),
    TOPICS(
        title = "议题与讨论",
        hint = "关键事实、观点、依据与分歧",
        promptBody = "按议题记录关键事实、主要观点、依据与分歧。"
    ),
    DECISIONS(
        title = "决策与共识",
        hint = "已明确确认的决定与适用范围",
        promptBody = "仅记录已经明确确认的决定、共识与适用范围。"
    ),
    OPEN_QUESTIONS(
        title = "观点与问题",
        hint = "待进一步讨论与待确认事项",
        promptBody = "保留需要进一步讨论的观点、问题和待确认事项。"
    ),
    ACTIONS(
        title = "行动项",
        hint = "事项、负责人、截止时间与验收",
        promptBody = "| 事项 | 负责人 | 协同人 | 截止时间 | 验收方式 |\n|---|---|---|---|---|\n| | | | | |"
    ),
    RISKS(
        title = "风险与待确认",
        hint = "已出现的风险、依赖与待核实信息",
        promptBody = "记录已出现的风险、依赖和待核实信息，不推测不存在的问题。"
    ),
    ATTACHMENTS(
        title = "资料与图片",
        hint = "实际提供的资料来源与关联议题",
        promptBody = "仅在实际提供资料或图片时列出来源、说明与关联议题。"
    );

    companion object {
        fun fromKeyOrNull(key: String): CustomTemplateModule? =
            entries.firstOrNull { it.name == key.trim() }
    }
}

/**
 * The user's saved arrangement of [CustomTemplateModule]s.
 *
 * [order] always contains every module exactly once so the editor can show
 * disabled modules in place; [enabled] decides which ones reach the report.
 */
data class CustomTemplateLayout(
    val order: List<CustomTemplateModule> = CustomTemplateModule.entries.toList(),
    val enabled: Set<CustomTemplateModule> = CustomTemplateModule.entries.toSet()
) {
    /** Modules in user order, restricted to the enabled ones. */
    val activeModules: List<CustomTemplateModule>
        get() = order.filter { it in enabled }

    fun isEnabled(module: CustomTemplateModule): Boolean = module in enabled

    fun toggled(module: CustomTemplateModule): CustomTemplateLayout {
        val next = if (module in enabled) enabled - module else enabled + module
        // At least one module must survive, otherwise there is nothing to write.
        if (next.isEmpty()) return this
        return copy(enabled = next)
    }

    fun moved(fromIndex: Int, toIndex: Int): CustomTemplateLayout {
        if (fromIndex !in order.indices) return this
        val target = toIndex.coerceIn(0, order.lastIndex)
        if (target == fromIndex) return this
        val next = order.toMutableList()
        next.add(target, next.removeAt(fromIndex))
        return copy(order = next)
    }

    /** Compact `KEY:1|KEY:0` form so the layout survives as a single string. */
    fun serialize(): String = order.joinToString("|") { module ->
        "${module.name}:${if (module in enabled) 1 else 0}"
    }

    /**
     * Renders the complete 自定义会议 template for the report prompt. Only
     * enabled modules appear, numbered in the user's own order.
     */
    fun toPromptDocument(): String {
        val active = activeModules.ifEmpty { CustomTemplateModule.entries.toList() }
        val sections = active.mapIndexed { index, module ->
            "## ${index + 1}. ${module.title}\n\n${module.promptBody}"
        }.joinToString("\n\n")
        return buildString {
            appendLine("# 自定义会议纪要")
            appendLine()
            appendLine("> 本模板由用户拖拽编排，共启用 ${active.size} 个模块。")
            appendLine()
            appendLine("## 模板组合说明（仅用于生成）")
            appendLine()
            appendLine(
                "严格按下列模块顺序输出，不要新增、重排或重命名模块。" +
                    "仅保留转写内容能够支撑的模块；没有对应事实、讨论或结论的模块直接省略，不得补造内容。"
            )
            appendLine()
            append(sections)
            appendLine()
        }
    }

    companion object {
        val DEFAULT = CustomTemplateLayout()

        fun deserialize(raw: String?): CustomTemplateLayout {
            if (raw.isNullOrBlank()) return DEFAULT
            val seen = LinkedHashMap<CustomTemplateModule, Boolean>()
            raw.split('|').forEach { entry ->
                val parts = entry.split(':')
                val module = CustomTemplateModule.fromKeyOrNull(parts.firstOrNull().orEmpty())
                    ?: return@forEach
                // A malformed flag is treated as enabled rather than dropping
                // the module, so a partial write can never hide content.
                seen.putIfAbsent(module, parts.getOrNull(1)?.trim() != "0")
            }
            if (seen.isEmpty()) return DEFAULT
            // Modules added by a later app version are appended, enabled.
            CustomTemplateModule.entries.forEach { module -> seen.putIfAbsent(module, true) }
            val enabled = seen.filterValues { it }.keys.toSet()
            return CustomTemplateLayout(
                order = seen.keys.toList(),
                enabled = enabled.ifEmpty { CustomTemplateModule.entries.toSet() }
            )
        }
    }
}

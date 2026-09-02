package com.oa.automation.domain.model

data class Report(
    val id: String = "",
    val meetingId: String,
    val summary: String = "",
    val keyPoints: List<String> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val decisions: List<String> = emptyList(),
    val actionItems: List<String> = emptyList(),
    val participants: List<ForumParticipant> = emptyList(),
    val rawContent: String = "",
    val templateName: String = "",
    /** Stable ids for the report workspace blocks, persisted with the report. */
    val workspaceBlockOrder: List<String> = emptyList(),
    val generatedAt: Long = System.currentTimeMillis()
)

object ReportWorkspaceBlocks {
    const val PARTICIPANTS = "participants"
    const val AUDIO = "audio"
    const val IMAGES = "images"
    const val REPORT = "report"
    const val RISKS = "risks"
    const val INTERACTION_SIGNALS = "interaction-signals"
    const val TRANSCRIPT = "transcript"

    val DEFAULT_ORDER = listOf(PARTICIPANTS, AUDIO, IMAGES, REPORT, RISKS, INTERACTION_SIGNALS, TRANSCRIPT)
}

fun normalizeReportWorkspaceOrder(
    order: List<String>,
    available: List<String> = ReportWorkspaceBlocks.DEFAULT_ORDER
): List<String> = order.distinct().filter { it in available } + available.filterNot { it in order }

data class Task(
    val content: String,
    val assignee: String? = null,
    val due: String? = null,
    val completed: Boolean = false,
    /** Optional priority parsed from the fourth task-table column. */
    val priority: String? = null
)

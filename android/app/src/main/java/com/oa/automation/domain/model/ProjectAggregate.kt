package com.oa.automation.domain.model

/**
 * Deterministically rebuilds the small project overview read model from user-selected refs.
 * It deliberately does not deduplicate task text or infer project membership.
 */
fun buildProjectAggregateSnapshot(
    snapshotId: String,
    projectId: String,
    meetingLinks: List<ProjectMeetingLink>,
    tasks: List<ProjectTaskRef>,
    risks: List<ProjectRiskRef>,
    decisions: List<ProjectDecisionRef>,
    generatedAt: Long = System.currentTimeMillis()
): ProjectAggregateSnapshot = ProjectAggregateSnapshot(
    id = snapshotId,
    projectId = projectId,
    sourceMeetingCount = meetingLinks.count { it.removedAt == null },
    openTaskCount = tasks.count { !it.completed },
    openRiskCount = risks.count { it.status.isOpenProjectRisk() },
    pendingDecisionCount = decisions.count { !it.confirmed },
    generatedAt = generatedAt
)

private fun String?.isOpenProjectRisk(): Boolean = when (this?.trim()?.lowercase()) {
    "closed", "resolved", "done", "已关闭", "已解决", "完成" -> false
    else -> true
}

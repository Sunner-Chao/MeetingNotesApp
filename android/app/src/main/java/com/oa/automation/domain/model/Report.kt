package com.oa.automation.domain.model

data class Report(
    val id: String = "",
    val meetingId: String,
    val summary: String = "",
    val keyPoints: List<String> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val decisions: List<String> = emptyList(),
    val actionItems: List<String> = emptyList(),
    val rawContent: String = "",
    val templateName: String = "",
    val generatedAt: Long = System.currentTimeMillis()
)

data class Task(
    val content: String,
    val assignee: String? = null,
    val due: String? = null,
    val completed: Boolean = false
)

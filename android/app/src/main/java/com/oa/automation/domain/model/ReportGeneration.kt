package com.oa.automation.domain.model

/** The durable identity of the only report request allowed to publish for a meeting. */
data class ReportGeneration(
    val requestId: String,
    val templateName: String,
    val reportId: String,
    val cancelled: Boolean = false,
    val completed: Boolean = false
)

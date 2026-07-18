package com.oa.automation.domain.model

data class Meeting(
    val id: String = "",
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val audioFilePath: String? = null
)

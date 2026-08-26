package com.oa.automation.infrastructure.stt

private const val MAX_STT_CONTEXT_HINT_CHARS = 240

internal fun buildSttContextHint(
    meetingTitle: String?,
    templateName: String? = null
): String = listOfNotNull(
    meetingTitle.cleanSttContextPart(),
    templateName.cleanSttContextPart()
)
    .distinct()
    .joinToString("；")
    .take(MAX_STT_CONTEXT_HINT_CHARS)

private fun String?.cleanSttContextPart(): String? = this
    ?.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.takeIf { it.isNotBlank() }

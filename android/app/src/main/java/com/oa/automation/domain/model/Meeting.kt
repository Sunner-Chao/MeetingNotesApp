package com.oa.automation.domain.model

data class Meeting(
    val id: String = "",
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val audioFilePath: String? = null,
    val origin: MeetingOrigin = MeetingOrigin.QUICK
)

enum class MeetingOrigin {
    QUICK,
    SCHEDULED,
    FILE_IMPORT;

    companion object {
        fun fromPersisted(value: String): MeetingOrigin =
            entries.firstOrNull { it.name == value } ?: QUICK
    }
}

private val LEGACY_FILE_IMPORT_TITLE = Regex("^资料导入(?:\\s+\\d{2}-\\d{2}\\s+\\d{2}:\\d{2})?$")

/**
 * Keeps system-generated titles consistent after the import entry was renamed.
 * Custom titles are intentionally left untouched.
 */
fun Meeting.displayTitle(): String = if (
    origin == MeetingOrigin.FILE_IMPORT && LEGACY_FILE_IMPORT_TITLE.matches(title.trim())
) {
    title.trim().replaceFirst("资料导入", "文件导入")
} else {
    title
}

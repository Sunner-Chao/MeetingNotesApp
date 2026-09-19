package com.oa.automation.domain.model

data class Meeting(
    val id: String = "",
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val audioFilePath: String? = null,
    val origin: MeetingOrigin = MeetingOrigin.QUICK,
    /**
     * The report template chosen while this meeting was being prepared.
     * Kept on the meeting so reopening an unfinished record restores its workflow.
     */
    val selectedTemplateName: String? = null,
    /**
     * The realtime speech engine chosen for this meeting. Persisted separately from
     * the app default so a resumed meeting keeps its original local/cloud route.
     */
    val selectedSttEngineName: String? = null,
    /** Actual audio source used by the meeting session. */
    val audioSource: MeetingAudioSource = when (origin) {
        MeetingOrigin.FILE_IMPORT -> MeetingAudioSource.IMPORTED_FILE
        else -> MeetingAudioSource.MICROPHONE
    },
    /** Human-readable route/device detail, never a credential. */
    val captureDeviceName: String? = null,
    /** Provider session id when the source supplies one. */
    val externalSessionId: String? = null,
    /** Number of anonymous speaker labels observed in the session. */
    val detectedSpeakerCount: Int = 0,
    val preferredCaptureInput: CaptureInput = CaptureInput.PHONE,
    /** Local account owner; populated by account-aware repositories. */
    val ownerId: String? = null,
    /** First-party 聆听·策划会 room bound to this recording, if any. */
    val meetingRoomId: String? = null
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

private val LEGACY_FILE_IMPORT_TITLE = Regex("^(资料导入|文件导入)(?:\\s+\\d{2}-\\d{2}\\s+\\d{2}:\\d{2})?$")

/**
 * Keeps system-generated titles consistent after the import entry was renamed.
 * Custom titles are intentionally left untouched.
 */
fun Meeting.displayTitle(): String = if (
    origin == MeetingOrigin.FILE_IMPORT && LEGACY_FILE_IMPORT_TITLE.matches(title.trim())
) {
    title.trim().replaceFirst(Regex("^(资料导入|文件导入)"), "顷刻成稿")
} else {
    title
}

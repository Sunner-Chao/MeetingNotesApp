package com.oa.automation.application.usecase

import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingOrigin
import com.oa.automation.domain.repository.MeetingRepository
import java.util.UUID

class StartRecordingUseCase(
    private val meetingRepository: MeetingRepository
) {
    suspend operator fun invoke(
        title: String,
        origin: MeetingOrigin = MeetingOrigin.QUICK
    ): Result<Meeting> {
        return try {
            val meeting = Meeting(
                id = UUID.randomUUID().toString(),
                title = title,
                origin = origin
            )
            meetingRepository.save(meeting).getOrThrow()
            Result.success(meeting)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

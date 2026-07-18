package com.oa.automation.application.usecase

import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.repository.MeetingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class StartRecordingUseCase(
    private val meetingRepository: MeetingRepository
) {
    suspend operator fun invoke(title: String): Result<Meeting> {
        return try {
            val meeting = Meeting(
                id = UUID.randomUUID().toString(),
                title = title
            )
            meetingRepository.save(meeting).getOrThrow()
            Result.success(meeting)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

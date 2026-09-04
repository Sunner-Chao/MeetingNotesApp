package com.oa.automation.infrastructure.stt

import com.oa.automation.domain.model.ProcessingProgress
import com.oa.automation.domain.model.STTEngineType
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal class FallbackSpeechToTextEngine(
    private val primary: SpeechToTextEngine,
    private val fallback: SpeechToTextEngine
) : SpeechToTextEngine {
    override suspend fun transcribe(
        audioFile: File,
        onProgress: (ProcessingProgress) -> Unit,
        meetingId: String?,
        archiveKey: String?,
        contextHint: String?
    ): Result<String> {
        var lastProgress = 0
        val primaryProgress: (ProcessingProgress) -> Unit = { progress ->
            lastProgress = maxOf(lastProgress, progress.percent)
            onProgress(progress)
        }
        val primaryResult = execute {
            primary.transcribe(audioFile, primaryProgress, meetingId, archiveKey, contextHint)
        }
        if (primaryResult.isSuccess) return primaryResult

        val fallbackStart = maxOf(lastProgress, 45).coerceAtMost(FALLBACK_END_PERCENT)
        onProgress(
            ProcessingProgress(
                percent = fallbackStart,
                stage = "本地识别暂不可用，正在启用云端兜底",
                isIndeterminate = true
            )
        )
        val fallbackProgress: (ProcessingProgress) -> Unit = { progress ->
            val mappedPercent = fallbackStart +
                progress.percent * (FALLBACK_END_PERCENT - fallbackStart) / 100
            onProgress(progress.copy(percent = mappedPercent))
        }
        val fallbackResult = execute {
            fallback.transcribe(audioFile, fallbackProgress, meetingId, archiveKey, contextHint)
        }
        if (fallbackResult.isSuccess) return fallbackResult

        val primaryError = primaryResult.exceptionOrNull()
            ?: IOException("本地识别失败")
        val fallbackError = fallbackResult.exceptionOrNull()
            ?: IOException("云端兜底失败")
        val combined = IOException(
            "本地识别失败，云端兜底也失败：${fallbackError.message ?: "未知错误"}",
            fallbackError
        )
        combined.addSuppressed(primaryError)
        return Result.failure(combined)
    }

    override suspend fun transcribeStreamSession(
        sessionId: String,
        onProgress: (ProcessingProgress) -> Unit
    ): Result<String> {
        // A live socket may have moved from the local node to Tencent after a
        // local connection failure. The session id is owned by whichever
        // service accepted the socket, so retry finalization through the cloud
        // engine when the preferred/local endpoint cannot see it.
        var lastProgress = 0
        val primaryResult = execute {
            primary.transcribeStreamSession(sessionId) { progress ->
                lastProgress = maxOf(lastProgress, progress.percent)
                onProgress(progress)
            }
        }
        if (primaryResult.isSuccess) return primaryResult

        val fallbackStart = maxOf(lastProgress, 45).coerceAtMost(FALLBACK_END_PERCENT)
        onProgress(
            ProcessingProgress(
                percent = fallbackStart,
                stage = "本地流式会话不可用，正在启用云端兜底",
                isIndeterminate = true
            )
        )
        val fallbackResult = execute {
            fallback.transcribeStreamSession(sessionId) { progress ->
                val mappedPercent = fallbackStart +
                    progress.percent * (FALLBACK_END_PERCENT - fallbackStart) / 100
                onProgress(progress.copy(percent = mappedPercent))
            }
        }
        if (fallbackResult.isSuccess) return fallbackResult

        val primaryError = primaryResult.exceptionOrNull()
            ?: IOException("本地流式会话最终化失败")
        val fallbackError = fallbackResult.exceptionOrNull()
            ?: IOException("云端流式会话最终化失败")
        val combined = IOException(
            "本地流式会话最终化失败，云端兜底也失败：${fallbackError.message ?: "未知错误"}",
            fallbackError
        )
        combined.addSuppressed(primaryError)
        return Result.failure(combined)
    }

    override fun getEngineType(): STTEngineType = primary.getEngineType()

    override fun getDisplayName(): String = primary.getDisplayName()

    override fun isAvailable(): Boolean = primary.isAvailable() || fallback.isAvailable()

    private suspend fun execute(block: suspend () -> Result<String>): Result<String> =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    private companion object {
        const val FALLBACK_END_PERCENT = 85
    }
}

package com.oa.automation.infrastructure.audio

import android.content.Context
import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.infrastructure.service.RecordingSessionController
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Restores stale recorder files that were never attached to a meeting row. */
class OrphanedMeetingAudioRecovery(
    private val context: Context,
    private val meetingRepository: MeetingRepository,
    private val recordingController: RecordingSessionController
) {
    suspend fun recover(): Int = withContext(Dispatchers.IO) {
        val session = recordingController.state.value
        if (session.isRecording || session.isStarting || session.isStopping) return@withContext 0

        var recoveredCount = 0
        val meetings = meetingRepository.getAllMeetings()
        val knownPaths = meetings
            .flatMap { meeting ->
                meetingRepository.findAudioSegmentsByMeetingId(meeting.id)
                    .getOrNull()
                    .orEmpty()
                    .map { it.localPath }
            }
            .toMutableSet()
        knownPaths += meetings.mapNotNull { it.audioFilePath }

        // Releases before the durable-files change wrote the final WAV under
        // cache/. Move those files first so sharing and later app launches do
        // not depend on cache retention.
        meetings.forEach { meeting ->
            val source = meeting.audioFilePath?.let(::File)
                ?.takeIf(::isUsableWav)
                ?: return@forEach
            if (!isCacheFile(source)) return@forEach

            val destination = persistentDestination(meeting.id, source)
            if (copyAtomically(source, destination)) {
                meetingRepository.save(meeting.copy(audioFilePath = destination.absolutePath))
                    .onSuccess {
                        knownPaths.remove(source.absolutePath)
                        knownPaths.add(destination.absolutePath)
                        source.delete()
                        recoveredCount++
                    }
            }
        }

        val refreshedMeetings = meetingRepository.getAllMeetings()
        knownPaths.clear()
        refreshedMeetings.forEach { meeting ->
            knownPaths += meetingRepository.findAudioSegmentsByMeetingId(meeting.id)
                .getOrNull()
                .orEmpty()
                .map { it.localPath }
        }
        knownPaths += refreshedMeetings.mapNotNull { it.audioFilePath }
        val cutoff = System.currentTimeMillis() - STALE_RECORDING_GRACE_MS
        recordingRoots()
            .flatMap { root -> root.listFiles().orEmpty().asList() }
            .filter { file ->
                    file.isFile &&
                    file.name.startsWith(RECORDING_PREFIX) &&
                    file.extension.equals("wav", ignoreCase = true) &&
                    // Older recorder builds sometimes left raw PCM bytes in a
                    // .wav-named cache file. restoreToPersistentStorage adds a
                    // canonical WAV header for that legacy format.
                    file.length() > WAV_HEADER_SIZE &&
                    file.lastModified() < cutoff &&
                    file.absolutePath !in knownPaths &&
                    recoveryDestination(file).absolutePath !in knownPaths
            }
            .distinctBy { it.absolutePath }
            .forEach { source ->
            runCatching {
                val restored = restoreToPersistentStorage(source)
                val createdAt = source.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                val durationMs = ((restored.length() - WAV_HEADER_SIZE) * 1_000L / PCM_BYTE_RATE)
                    .coerceAtLeast(0L)
                val candidate = findOrphanMeeting(refreshedMeetings, createdAt)
                val restoredMeeting = candidate?.copy(
                    durationMs = maxOf(candidate.durationMs, durationMs),
                    audioFilePath = restored.absolutePath
                ) ?: Meeting(
                    id = UUID.randomUUID().toString(),
                    title = recoveredTitle(createdAt),
                    createdAt = createdAt,
                    durationMs = durationMs,
                    audioFilePath = restored.absolutePath
                )
                meetingRepository.save(restoredMeeting).getOrThrow()
                knownPaths.add(restored.absolutePath)
                source.delete()
                recoveredCount++
            }
        }
        recoveredCount
    }

    private fun restoreToPersistentStorage(source: File): File {
        val destination = recoveryDestination(source).apply { parentFile?.mkdirs() }
        val expectedLength = source.length()
        if (isUsableWav(destination) && destination.length() == expectedLength) return destination

        val partial = File(destination.parentFile, ".${destination.name}.part")
        partial.delete()
        val headerIsValid = source.inputStream().use(::isWavHeader)
        try {
            if (headerIsValid) {
                source.copyTo(partial, overwrite = true)
            } else {
                val dataBytes = expectedLength - WAV_HEADER_SIZE
                require(dataBytes > 0L) { "录音文件为空" }
                FileInputStream(source).use { input ->
                    input.skipFully(WAV_HEADER_SIZE)
                    FileOutputStream(partial).use { output ->
                        output.write(wavHeader(dataBytes))
                        input.copyTo(output)
                    }
                }
            }
            check(partial.length() == expectedLength) { "恢复后的录音长度不完整" }
            check(isUsableWav(partial)) { "恢复后的录音文件无效" }
            if (destination.exists() && !destination.delete()) error("无法替换未完成的恢复文件")
            check(partial.renameTo(destination)) { "无法完成录音恢复" }
        } finally {
            partial.delete()
        }
        return destination
    }

    private fun recoveryDestination(source: File): File =
        File(context.filesDir, "recordings/recovered_${source.name}")

    private fun persistentDestination(meetingId: String, source: File): File =
        File(context.filesDir, "recordings/meeting_${meetingId}.${source.extension.ifBlank { "wav" }}")

    private fun recordingRoots(): List<File> = listOf(
        context.cacheDir,
        File(context.filesDir, "recordings")
    ).distinctBy { it.absolutePath }

    private fun isCacheFile(file: File): Boolean {
        val cachePath = context.cacheDir.toPath().toAbsolutePath().normalize().toString() +
            File.separator
        val filePath = file.toPath().toAbsolutePath().normalize().toString()
        return filePath.startsWith(cachePath)
    }

    private fun isUsableWav(file: File): Boolean =
        file.isFile && file.length() > WAV_HEADER_SIZE && file.inputStream().use(::isWavHeader)

    private fun copyAtomically(source: File, destination: File): Boolean {
        if (source.absolutePath == destination.absolutePath) return isUsableWav(source)
        destination.parentFile?.mkdirs()
        if (isUsableWav(destination) && destination.length() == source.length()) return true
        val partial = File(destination.parentFile, ".${destination.name}.part")
        return runCatching {
            source.copyTo(partial, overwrite = true)
            check(partial.length() == source.length()) { "复制后的录音长度不完整" }
            check(isUsableWav(partial)) { "复制后的录音文件无效" }
            if (destination.exists() && !destination.delete()) error("无法替换旧录音文件")
            check(partial.renameTo(destination)) { "无法完成录音文件迁移" }
            true
        }.getOrElse {
            partial.delete()
            false
        }
    }

    private fun findOrphanMeeting(meetings: List<Meeting>, createdAt: Long): Meeting? = meetings
        .asSequence()
        .filter { meeting ->
            val path = meeting.audioFilePath?.let(::File)
            path == null || !isUsableWav(path)
        }
        .filter { meeting -> abs(meeting.createdAt - createdAt) <= ORPHAN_MEETING_MATCH_WINDOW_MS }
        .minByOrNull { meeting -> abs(meeting.createdAt - createdAt) }

    private fun recoveredTitle(createdAt: Long): String =
        "恢复的会议录音 " + SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
            .format(Date(createdAt))

    private fun isWavHeader(input: java.io.InputStream): Boolean {
        val header = ByteArray(WAV_HEADER_SIZE)
        if (input.read(header) != WAV_HEADER_SIZE) return false
        return header.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            header.copyOfRange(8, 12).decodeToString() == "WAVE" &&
            header.copyOfRange(12, 16).decodeToString() == "fmt " &&
            header.copyOfRange(36, 40).decodeToString() == "data"
    }

    private fun wavHeader(dataBytes: Long): ByteArray {
        require(dataBytes <= UInt.MAX_VALUE.toLong()) { "录音文件过大，无法恢复" }
        val bytes = ByteArray(WAV_HEADER_SIZE)
        bytes.writeAscii(0, "RIFF")
        bytes.writeLeInt(4, dataBytes + 36L)
        bytes.writeAscii(8, "WAVEfmt ")
        bytes.writeLeInt(16, 16L)
        bytes.writeLeShort(20, 1)
        bytes.writeLeShort(22, 1)
        bytes.writeLeInt(24, SAMPLE_RATE.toLong())
        bytes.writeLeInt(28, PCM_BYTE_RATE)
        bytes.writeLeShort(32, 2)
        bytes.writeLeShort(34, 16)
        bytes.writeAscii(36, "data")
        bytes.writeLeInt(40, dataBytes)
        return bytes
    }

    private fun java.io.InputStream.skipFully(bytes: Int) {
        var remaining = bytes.toLong()
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() == -1) {
                error("录音文件不完整")
            } else {
                remaining--
            }
        }
    }

    private fun ByteArray.writeAscii(offset: Int, value: String) {
        value.encodeToByteArray().copyInto(this, destinationOffset = offset)
    }

    private fun ByteArray.writeLeShort(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.writeLeInt(offset: Int, value: Long) {
        repeat(4) { index ->
            this[offset + index] = ((value shr (index * 8)) and 0xFF).toByte()
        }
    }

    private companion object {
        const val RECORDING_PREFIX = "oa_recording_"
        const val WAV_HEADER_SIZE = 44
        const val SAMPLE_RATE = 16_000
        const val PCM_BYTE_RATE = 32_000L
        const val STALE_RECORDING_GRACE_MS = 2 * 60 * 1_000L
        const val ORPHAN_MEETING_MATCH_WINDOW_MS = 24 * 60 * 60 * 1_000L
    }
}

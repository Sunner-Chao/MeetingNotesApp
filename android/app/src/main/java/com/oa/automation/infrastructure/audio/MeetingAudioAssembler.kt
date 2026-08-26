package com.oa.automation.infrastructure.audio

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Combines ordered PCM WAV segments into one stable meeting playback file. */
class MeetingAudioAssembler(private val context: Context) {
    suspend fun assemble(meetingId: String, segmentPaths: List<String>): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sources = segmentPaths
                    .map(::File)
                    .filter { it.isFile && it.length() > WAV_HEADER_SIZE }
                require(sources.isNotEmpty()) { "没有可合并的会议音频片段" }

                val directory = File(context.filesDir, "recordings").apply { mkdirs() }
                val target = File(directory, "meeting_$meetingId.wav")
                val partial = File(directory, ".meeting_$meetingId.wav.part")
                partial.delete()

                var dataBytes = 0L
                FileInputStream(sources.first()).use { input ->
                    val header = ByteArray(WAV_HEADER_SIZE)
                    require(input.read(header) == WAV_HEADER_SIZE) { "WAV 头无效" }
                    FileOutputStream(partial).use { output ->
                        output.write(header)
                        sources.forEach { source ->
                            FileInputStream(source).use { segmentInput ->
                                segmentInput.skipFully(WAV_HEADER_SIZE.toLong())
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val read = segmentInput.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    dataBytes += read
                                }
                            }
                        }
                        output.flush()
                    }
                }

                RandomAccessFile(partial, "rw").use { wav ->
                    wav.seek(4)
                    wav.writeIntLE((WAV_HEADER_SIZE - 8L + dataBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    wav.seek(40)
                    wav.writeIntLE(dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }
                if (target.exists() && !target.delete()) {
                    error("无法替换会议合并音频")
                }
                check(partial.renameTo(target)) { "无法完成会议音频合并" }
                target
            }.onFailure {
                File(context.filesDir, "recordings/.meeting_$meetingId.wav.part").delete()
            }
        }

    private fun FileInputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                error("WAV 片段数据不完整")
            }
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private companion object {
        const val WAV_HEADER_SIZE = 44
        const val BUFFER_SIZE = 64 * 1024
    }
}

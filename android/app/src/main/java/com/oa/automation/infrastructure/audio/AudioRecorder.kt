package com.oa.automation.infrastructure.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.max

/**
 * WAV recorder backed by AudioRecord so we can emit short audio chunks while recording.
 */
class AudioRecorder(private val context: android.content.Context) {

    private var audioRecord: AudioRecord? = null
    private var outputFile: File? = null
    private var outputStream: FileOutputStream? = null
    private var recordingThread: Thread? = null
    private var chunkListener: ((File) -> Unit)? = null
    private var pcmListener: ((ByteArray, Int) -> Unit)? = null

    @Volatile
    private var isRecording = false

    @Volatile
    private var isPaused = false

    /**
     * Persisted recording flag to survive AudioRecord instance recreation.
     * Set to true when start() succeeds, set to false when stop()/cancel() is called.
     * Use this instead of the instance field isRecording to check global recording state.
     */
    private val prefs: android.content.SharedPreferences by lazy {
        context.getSharedPreferences("AudioRecorderState", android.content.Context.MODE_PRIVATE)
    }

    private val chunkBuffer = ByteArrayOutputStream()
    private val chunkLock = Any()
    private val fileLock = Any()

    private var totalAudioBytes: Long = 0

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BITS_PER_SAMPLE = 16
        const val CHANNEL_COUNT = 1
        private const val CHUNK_DURATION_MS = 1000
        private const val WAV_HEADER_SIZE = 44
        private const val PREFS_RECORDING_KEY = "is_recording_globally"
    }

    fun setOnChunkAvailableListener(listener: ((File) -> Unit)?) {
        chunkListener = listener
    }

    fun setOnPcmDataListener(listener: ((ByteArray, Int) -> Unit)?) {
        pcmListener = listener
    }

    /** True when the native AudioRecord instance is alive and initialized. */
    fun isAudioRecordReady(): Boolean = audioRecord != null

    /**
     * Start recording audio and return the final WAV file that will be completed on stop().
     */
    fun start(): File? {
        // If prefs says "recording" but the AudioRecord instance is gone (app killed/restored),
        // recover by resetting the stale flag so a fresh recording can start.
        if (isRecording && audioRecord == null) {
            Log.w(TAG, "Stale recording flag detected, resetting")
            isRecording = false
            prefs.edit().putBoolean(PREFS_RECORDING_KEY, false).apply()
        }
        if (isRecording) {
            Log.w(TAG, "start: already recording")
            return outputFile
        }

        try {
            cleanupStaleChunks()
            Log.d(TAG, "Initializing AudioRecord at $SAMPLE_RATE Hz")
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid AudioRecord buffer size: $minBufferSize")
                return null
            }

            val bufferSize = max(minBufferSize, 4096)

            outputFile = File.createTempFile("oa_recording_", ".wav", context.cacheDir)
            outputStream = FileOutputStream(outputFile).apply {
                write(ByteArray(WAV_HEADER_SIZE))
                flush()
            }

            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AUDIO_FORMAT)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                releaseRecorder()
                return null
            }

            synchronized(chunkLock) {
                chunkBuffer.reset()
            }
            totalAudioBytes = 0
            isPaused = false
            isRecording = true
            prefs.edit().putBoolean(PREFS_RECORDING_KEY, true).apply()
            audioRecord?.startRecording()
            val recState = audioRecord?.recordingState ?: -1
            if (recState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord did not enter recording state: $recState")
                releaseRecorder()
                return null
            }
            recordingThread = Thread(
                { captureLoop(bufferSize) },
                "MeetingAudioRecorder"
            ).apply { start() }

            Log.d(TAG, "Recording thread started")

            return outputFile
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing audio permission", e)
            releaseRecorder()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            releaseRecorder()
            return null
        }
    }

    fun stop(): File? {
        if (!isRecording) {
            return null
        }

        isRecording = false
        isPaused = false
        prefs.edit().putBoolean(PREFS_RECORDING_KEY, false).apply()

        try {
            recordingThread?.join(1500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recordingThread = null

        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord stop failed", e)
        } finally {
            audioRecord?.release()
            audioRecord = null
        }

        flushChunk(force = true)

        synchronized(fileLock) {
            outputStream?.flush()
            outputStream?.close()
            outputStream = null
        }

        outputFile?.let { file ->
            writeWavHeader(file, totalAudioBytes)
        }

        return outputFile
    }

    /** Temporarily suspends microphone capture while keeping the current WAV open. */
    @Synchronized
    fun pause(): Boolean {
        if (!isRecording || isPaused) return false
        val recorder = audioRecord ?: return false
        return runCatching {
            recorder.stop()
            isPaused = true
        }.isSuccess
    }

    /** Resumes capture into the same WAV file after a pause. */
    @Synchronized
    fun resume(): Boolean {
        if (!isRecording || !isPaused) return false
        return runCatching {
            audioRecord?.startRecording()
            check(audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            isPaused = false
        }.isSuccess
    }

    fun isPaused(): Boolean = isPaused

    fun cancel(deleteFile: Boolean = true) {
        if (!isRecording && audioRecord == null && outputStream == null) {
            if (deleteFile) {
                outputFile?.delete()
                outputFile = null
            }
            return
        }

        isRecording = false
        isPaused = false
        prefs.edit().putBoolean(PREFS_RECORDING_KEY, false).apply()

        try {
            recordingThread?.join(1500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recordingThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        } finally {
            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }
            audioRecord = null
        }

        synchronized(chunkLock) {
            chunkBuffer.reset()
        }

        synchronized(fileLock) {
            try {
                outputStream?.flush()
            } catch (_: Exception) {
            }
            try {
                outputStream?.close()
            } catch (_: Exception) {
            }
            outputStream = null
        }

        if (deleteFile) {
            outputFile?.delete()
            outputFile = null
        }

        pcmListener = null
        chunkListener = null
        totalAudioBytes = 0
    }

    fun isRecording(): Boolean {
        if (isRecording) return true

        val persistedRecording = prefs.getBoolean(PREFS_RECORDING_KEY, false)
        if (persistedRecording && audioRecord == null) {
            Log.w(TAG, "Clearing stale persisted recording state")
            prefs.edit().putBoolean(PREFS_RECORDING_KEY, false).apply()
            return false
        }

        return persistedRecording
    }

    private fun captureLoop(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        val chunkSizeBytes = SAMPLE_RATE * CHANNEL_COUNT * (BITS_PER_SAMPLE / 8) * CHUNK_DURATION_MS / 1000

        while (isRecording) {
            if (isPaused) {
                try {
                    Thread.sleep(20)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            }
            val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: AudioRecord.ERROR_INVALID_OPERATION
            if (readBytes <= 0) {
                continue
            }

            synchronized(fileLock) {
                outputStream?.write(buffer, 0, readBytes)
                totalAudioBytes += readBytes
            }

            pcmListener?.invoke(buffer.copyOf(readBytes), readBytes)

            val listener = chunkListener
            if (listener != null) {
                synchronized(chunkLock) {
                    chunkBuffer.write(buffer, 0, readBytes)
                    if (chunkBuffer.size() >= chunkSizeBytes) {
                        emitChunk(chunkBuffer.toByteArray(), listener)
                        chunkBuffer.reset()
                    }
                }
            }
        }
    }

    private fun flushChunk(force: Boolean) {
        val listener = chunkListener ?: return
        synchronized(chunkLock) {
            if (!force || chunkBuffer.size() == 0) {
                return
            }
            emitChunk(chunkBuffer.toByteArray(), listener)
            chunkBuffer.reset()
        }
    }

    private fun emitChunk(audioBytes: ByteArray, listener: (File) -> Unit) {
        if (audioBytes.isEmpty()) {
            return
        }

        runCatching {
            val chunkFile = File.createTempFile("oa_chunk_", ".wav", context.cacheDir)
            FileOutputStream(chunkFile).use { stream ->
                stream.write(ByteArray(WAV_HEADER_SIZE))
                stream.write(audioBytes)
            }
            writeWavHeader(chunkFile, audioBytes.size.toLong())
            listener(chunkFile)
        }.onFailure { error ->
            Log.w(TAG, "Failed to emit audio chunk", error)
        }
    }

    private fun cleanupStaleChunks() {
        context.cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("oa_chunk_") && file.extension == "wav"
        }?.forEach { file ->
            if (!file.delete()) Log.w(TAG, "Could not delete stale chunk: ${file.name}")
        }
    }

    private fun releaseRecorder() {
        isRecording = false
        isPaused = false
        prefs.edit().putBoolean(PREFS_RECORDING_KEY, false).apply()
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        pcmListener = null
        chunkListener = null
        try {
            outputStream?.close()
        } catch (_: Exception) {
        }
        outputStream = null
        recordingThread = null
        outputFile?.delete()
        outputFile = null
    }

    private fun writeWavHeader(file: File, audioLength: Long) {
        runCatching {
            RandomAccessFile(file, "rw").use { wavFile ->
                val byteRate = SAMPLE_RATE * CHANNEL_COUNT * (BITS_PER_SAMPLE / 8)
                val dataLength = audioLength + 36

                wavFile.seek(0)
                wavFile.writeBytes("RIFF")
                wavFile.writeInt(Integer.reverseBytes(dataLength.toInt()))
                wavFile.writeBytes("WAVE")
                wavFile.writeBytes("fmt ")
                wavFile.writeInt(Integer.reverseBytes(16))
                wavFile.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
                wavFile.writeShort(java.lang.Short.reverseBytes(CHANNEL_COUNT.toShort()).toInt())
                wavFile.writeInt(Integer.reverseBytes(SAMPLE_RATE))
                wavFile.writeInt(Integer.reverseBytes(byteRate))
                wavFile.writeShort(
                    java.lang.Short.reverseBytes((CHANNEL_COUNT * (BITS_PER_SAMPLE / 8)).toShort()).toInt()
                )
                wavFile.writeShort(java.lang.Short.reverseBytes(BITS_PER_SAMPLE.toShort()).toInt())
                wavFile.writeBytes("data")
                wavFile.writeInt(Integer.reverseBytes(audioLength.toInt()))
            }
        }.onFailure { error ->
            throw IOException("Failed to write WAV header", error)
        }
    }
}

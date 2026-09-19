package com.oa.automation.infrastructure.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import com.oa.automation.domain.model.CaptureInput
import com.oa.automation.domain.model.MeetingAudioSource

/** Scoped to one recording; never leaves the system in communication mode. */
internal class AudioCaptureRouter(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var priorMode: Int? = null
    private var ownsBluetoothRoute = false
    private var input = CaptureInput.PHONE

    fun prepare(requested: CaptureInput) {
        release()
        input = requested
        if (requested == CaptureInput.PHONE) return
        val audio = checkNotNull(manager) { "此设备无法切换麦克风" }
        val bluetooth = if (Build.VERSION.SDK_INT >= 31) {
            audio.availableCommunicationDevices.firstOrNull { isBluetoothCaptureDevice(it.type) }
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isBluetoothCaptureDevice(it.type) }
        }
        checkNotNull(bluetooth) { "请先连接支持通话的蓝牙耳机，或选择手机麦克风" }
        priorMode = audio.mode
        ownsBluetoothRoute = true
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                check(audio.setCommunicationDevice(bluetooth)) { "蓝牙麦克风未就绪，请重新连接耳机" }
            } else {
                @Suppress("DEPRECATION")
                audio.startBluetoothSco()
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = true
            }
        } catch (error: Exception) {
            release()
            throw error
        }
    }

    fun apply(recorder: AudioRecord) {
        val device = manager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull {
            if (input == CaptureInput.BLUETOOTH) isBluetoothCaptureDevice(it.type)
            else it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        }
        if (device != null) recorder.setPreferredDevice(device)
    }

    fun release() {
        if (!ownsBluetoothRoute) return
        val audio = manager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audio.stopBluetoothSco()
            }
        }
        priorMode?.let { mode -> runCatching { audio.mode = mode } }
        priorMode = null
        ownsBluetoothRoute = false
    }
}

internal fun isBluetoothCaptureDevice(type: Int): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

internal fun captureSourceForDevice(type: Int?): MeetingAudioSource = when {
    type == null -> MeetingAudioSource.UNKNOWN
    isBluetoothCaptureDevice(type) -> MeetingAudioSource.BLUETOOTH_MICROPHONE
    type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> MeetingAudioSource.MICROPHONE
    type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY) -> MeetingAudioSource.EXTERNAL_MICROPHONE
    else -> MeetingAudioSource.UNKNOWN
}

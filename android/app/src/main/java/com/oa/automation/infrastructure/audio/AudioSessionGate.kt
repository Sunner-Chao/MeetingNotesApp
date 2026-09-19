package com.oa.automation.infrastructure.audio

/** One microphone owner across local recording and RTC, including connection setup. */
class AudioSessionGate {
    private var owner: String? = null

    @Synchronized fun acquire(candidate: String): Boolean {
        if (owner != null && owner != candidate) return false
        owner = candidate
        return true
    }

    @Synchronized fun release(candidate: String) {
        if (owner == candidate) owner = null
    }
}

package com.oa.automation.infrastructure.location

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val capturedAt: Long,
    val source: String
)

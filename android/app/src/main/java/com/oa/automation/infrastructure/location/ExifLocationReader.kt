package com.oa.automation.infrastructure.location

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Reads optional capture coordinates from an imported image without treating them as authoritative. */
internal object ExifLocationReader {
    fun read(file: File, fallbackCapturedAt: Long): LocationSnapshot? = runCatching {
        if (!file.isFile) return@runCatching null
        val exif = ExifInterface(file)
        val coordinates = exif.latLong ?: return@runCatching null
        if (coordinates.size < 2) return@runCatching null

        val latitude = coordinates[0]
        val longitude = coordinates[1]
        if (!latitude.isFinite() || !longitude.isFinite()) return@runCatching null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return@runCatching null
        }

        LocationSnapshot(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = null,
            capturedAt = readGpsTimestamp(exif) ?: fallbackCapturedAt,
            source = "exif"
        )
    }.getOrNull()

    private fun readGpsTimestamp(exif: ExifInterface): Long? = runCatching {
        val date = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)?.trim().orEmpty()
        val time = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP)?.trim().orEmpty()
        if (date.isBlank() || time.isBlank()) return@runCatching null

        val separator = if (time.contains(':')) ':' else ','
        val parts = time.split(separator).map { component ->
            val rational = component.trim().split('/')
            val value = if (rational.size == 2) {
                rational[0].toDouble() / rational[1].toDouble()
            } else {
                component.trim().toDouble()
            }
            value.toInt()
        }
        if (
            parts.size != 3 ||
            parts[0] !in 0..23 ||
            parts.drop(1).any { it !in 0..59 }
        ) {
            return@runCatching null
        }

        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse("$date %02d:%02d:%02d".format(Locale.US, parts[0], parts[1], parts[2]))?.time
    }.getOrNull()
}

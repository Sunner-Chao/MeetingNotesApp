package com.oa.automation.infrastructure.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class DeviceLocationProvider(private val context: Context) {
    suspend fun capture(): LocationSnapshot? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return@withContext null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
        if (providers.isEmpty()) return@withContext null

        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            providers.mapNotNull { provider ->
                runCatching { requestCurrentLocation(manager, provider) }.getOrNull()?.let { location ->
                    location to provider
                }
            }.minByOrNull { (location, _) -> location.accuracy.coerceAtLeast(0f) }
        } else {
            null
        }
        if (current != null) {
            return@withContext current.first.toSnapshot(source = current.second)
        }

        providers.mapNotNull { provider ->
            readLastKnownLocation(manager, provider)?.let { location ->
                location to "last_known_$provider"
            }
        }.minByOrNull { (location, _) -> location.accuracy.coerceAtLeast(0f) }
            ?.let { (location, source) -> location.toSnapshot(source) }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun readLastKnownLocation(manager: LocationManager, provider: String): Location? =
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestCurrentLocation(
        manager: LocationManager,
        provider: String
    ): Location? = withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            manager.getCurrentLocation(
                provider,
                cancellationSignal,
                context.mainExecutor
            ) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }
    }

    private fun Location.toSnapshot(source: String): LocationSnapshot = LocationSnapshot(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.takeIf { it >= 0f },
        capturedAt = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
        source = source
    )

    private companion object {
        const val CURRENT_LOCATION_TIMEOUT_MS = 2_500L
    }
}

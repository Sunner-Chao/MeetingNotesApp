package com.oa.automation.ui.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permission required only on Android 9 and older. */
object MeetingGalleryPermission {
    val requestedPermissions: Array<String> = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        emptyArray()
    }

    fun isGranted(context: Context): Boolean = requestedPermissions.isEmpty() ||
        requestedPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
}

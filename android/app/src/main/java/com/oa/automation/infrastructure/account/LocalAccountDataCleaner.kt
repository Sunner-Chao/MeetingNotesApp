package com.oa.automation.infrastructure.account

import android.content.Context
import com.oa.automation.infrastructure.db.AppDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clears the device-local workspace when a different account signs in.
 * Account data is persisted in the server account database; this local Room
 * file is a working cache and must never be reused across identities.
 */
class LocalAccountDataCleaner(
    private val context: Context,
    private val database: AppDatabase
) {
    suspend fun clearForAccountSwitch() = withContext(Dispatchers.IO) {
        database.clearAllTables()
        listOf(
            "recordings",
            "imported-audio",
            "meeting-attachments",
            "community-media",
            "debug-study-tour-media-v2"
        ).forEach { directory ->
            File(context.filesDir, directory).deleteRecursively()
        }
        // Generated report/audio exports are cached outside filesDir.
        File(context.cacheDir, "exports").deleteRecursively()
    }
}

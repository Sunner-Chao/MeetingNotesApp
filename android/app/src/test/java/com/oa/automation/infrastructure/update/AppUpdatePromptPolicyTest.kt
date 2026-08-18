package com.oa.automation.infrastructure.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePromptPolicyTest {
    private fun update(versionCode: Int, mandatory: Boolean = false) = AndroidAppUpdate(
        versionCode = versionCode,
        versionName = "1.2.$versionCode",
        mandatory = mandatory,
        releaseNotes = "",
        publishedAt = "",
        downloadUrl = "https://example.invalid/app.apk",
        sha256 = null
    )

    @Test
    fun `new optional version is prompted until that exact version is ignored`() {
        assertTrue(shouldPromptForUpdate(update(14), ignoredVersionCode = null, currentVersionCode = 13))
        assertFalse(shouldPromptForUpdate(update(14), ignoredVersionCode = 14, currentVersionCode = 13))
        assertTrue(shouldPromptForUpdate(update(15), ignoredVersionCode = 14, currentVersionCode = 13))
    }

    @Test
    fun `mandatory version cannot be suppressed by ignore preference`() {
        assertTrue(
            shouldPromptForUpdate(
                update = update(14, mandatory = true),
                ignoredVersionCode = 14,
                currentVersionCode = 13
            )
        )
    }

    @Test
    fun `installed or older version is not prompted`() {
        assertFalse(shouldPromptForUpdate(update(13), ignoredVersionCode = null, currentVersionCode = 13))
        assertFalse(shouldPromptForUpdate(update(12), ignoredVersionCode = null, currentVersionCode = 13))
    }

    @Test
    fun `a higher server version replaces a pending update prompt`() {
        val pending = update(14)
        val latest = update(17)

        assertSame(latest, newerAppUpdate(pending, latest))
        assertSame(pending, newerAppUpdate(pending, update(13)))
        assertSame(pending, newerAppUpdate(pending, update(14)))
    }
}

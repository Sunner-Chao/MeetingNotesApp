package com.oa.automation.infrastructure.textimport

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedTextImportCoordinatorInstrumentedTest {
    @Test
    fun acceptsTextLargerThanLegacyClipboardAndGatewayLimits() = runBlocking {
        val coordinator = SharedTextImportCoordinator(ApplicationProvider.getApplicationContext())
        val text = "会".repeat(1_200_000)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

        assertTrue(coordinator.accept(intent))
        assertEquals(text.length, coordinator.pending.value?.text?.length)
    }
}

package com.oa.automation.infrastructure.attachment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingAttachmentStoreTest {
    @Test
    fun `large image selection is processed sequentially with exact progress`() = runBlocking {
        val sources = (1..250).toList()
        val processed = mutableListOf<Int>()
        val progress = mutableListOf<Pair<Int, Int>>()

        val results = processImageImportsSequentially(
            sources = sources,
            importer = { source ->
                processed += source
                Result.success(source)
            },
            onProgress = { completed, total -> progress += completed to total }
        )

        assertEquals(sources, processed)
        assertEquals(250, results.size)
        assertTrue(results.all { it.isSuccess })
        assertEquals(1 to 250, progress.first())
        assertEquals(250 to 250, progress.last())
        assertEquals((1..250).toList(), progress.map { it.first })
    }

    @Test
    fun `failed image does not stop remaining selection`() = runBlocking {
        val processed = mutableListOf<Int>()

        val results = processImageImportsSequentially(
            sources = listOf(1, 2, 3),
            importer = { source ->
                processed += source
                if (source == 2) Result.failure(IllegalStateException("broken image"))
                else Result.success(source)
            }
        )

        assertEquals(listOf(1, 2, 3), processed)
        assertTrue(results[0].isSuccess)
        assertTrue(results[1].isFailure)
        assertTrue(results[2].isSuccess)
    }
}

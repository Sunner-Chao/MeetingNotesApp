package com.oa.automation.infrastructure.llm

import com.oa.automation.infrastructure.image.AgentImageCompressor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUploadImagesTest {
    @Test
    fun preparesImagesSequentiallyAndCleansCopiesOnClose() = runBlocking {
        val originals = (1..3).map { File.createTempFile("agent-original-", ".jpg") }
        val copies = mutableListOf<File>()
        try {
            val upload = prepareAgentUploadImages(originals.mapIndexed { index, file ->
                AgentAttachment(file, "image/jpeg", "图$index.jpg")
            }) { attachment ->
                val copy = File.createTempFile("agent-copy-", ".jpg")
                copy.writeBytes(byteArrayOf(1, 2, 3))
                copies += copy
                AgentImageCompressor.Prepared(attachment.copy(file = copy), copy)
            }
            assertEquals(3, upload.attachments.size)
            assertTrue(copies.all(File::isFile))
            upload.close()
            assertTrue(copies.none(File::exists))
        } finally {
            originals.forEach(File::delete)
            copies.forEach(File::delete)
        }
    }

    @Test
    fun rejectsCompressedBatchOverThirtyTwoMegabytes() = runBlocking {
        val original = File.createTempFile("agent-original-", ".jpg")
        val copy = File.createTempFile("agent-copy-", ".jpg")
        try {
            copy.writeBytes(ByteArray(AgentImageCompressor.TARGET_BYTES))
            val failure = runCatching {
                prepareAgentUploadImages((1..34).map { index ->
                    AgentAttachment(original, "image/jpeg", "图$index.jpg")
                }) {
                    AgentImageCompressor.Prepared(
                        AgentAttachment(copy, "image/jpeg", "compressed.jpg"),
                        null
                    )
                }
            }.exceptionOrNull()
            assertFalse(failure == null)
            assertTrue(failure!!.message.orEmpty().contains("32 MB"))
        } finally {
            original.delete()
            copy.delete()
        }
    }
}

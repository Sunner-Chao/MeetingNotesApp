package com.oa.automation.infrastructure.llm

import com.oa.automation.infrastructure.image.AgentImageCompressor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.IOException

/** Owns only request copies; never deletes local meeting originals. */
internal class AgentUploadImages(
    private val prepared: List<AgentImageCompressor.Prepared>
) : Closeable {
    val attachments: List<AgentAttachment> = prepared.map { it.attachment }

    override fun close() {
        prepared.forEach { it.temporaryFile?.delete() }
    }
}

/** Sequential preparation bounds bitmap memory, and failure rolls back every upload copy. */
internal suspend fun prepareAgentUploadImages(
    attachments: List<AgentAttachment>,
    prepareImage: suspend (AgentAttachment) -> AgentImageCompressor.Prepared = {
        AgentImageCompressor.prepare(it)
    }
): AgentUploadImages {
    val prepared = mutableListOf<AgentImageCompressor.Prepared>()
    var totalBytes = 0L
    try {
        for (attachment in attachments) {
            currentCoroutineContext().ensureActive()
            val item = prepareImage(attachment)
            prepared += item
            val size = item.attachment.file.length()
            if (size !in 1..AgentImageCompressor.TARGET_BYTES.toLong()) {
                throw IOException("图片压缩失败，请重新选择：${attachment.displayName}")
            }
            totalBytes += size
            if (totalBytes > 32L * 1024 * 1024) {
                throw IOException("图片压缩后总量仍超过 32 MB，请减少本次生成使用的图片或分段生成纪要。本地原图已保留。")
            }
        }
        currentCoroutineContext().ensureActive()
        return AgentUploadImages(prepared)
    } catch (error: Throwable) {
        AgentUploadImages(prepared).close()
        throw error
    }
}

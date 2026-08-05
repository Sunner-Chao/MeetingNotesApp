package com.oa.automation.infrastructure.llm

import com.oa.automation.domain.model.AgentProvider
import com.oa.automation.domain.model.ClaudeReasoningEffort
import com.oa.automation.domain.model.CodexReasoningEffort
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.ReportTemplateConfig
import java.io.File
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGatewayEngineTest {
    @Test
    fun defaultsToHighForBothProviderSpecificEffortFields() {
        assertEquals(CodexReasoningEffort.HIGH, LLMConfig.DEFAULT.codexReasoningEffort)
        assertEquals(ClaudeReasoningEffort.HIGH, LLMConfig.DEFAULT.claudeReasoningEffort)
    }

    @Test
    fun sendsCodexAndClaudeEffortFieldsSeparately() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"text\":\"ok\"}"))
            val config = LLMConfig(
                agentEndpoint = server.url("/").toString(),
                agentAccessToken = "token",
                agentProvider = AgentProvider.CODEX_CLI,
                codexReasoningEffort = CodexReasoningEffort.HIGH,
                claudeReasoningEffort = ClaudeReasoningEffort.LOW
            )

            val result = AgentGatewayEngine(config).generateReport(
                transcript = "会议内容",
                template = ReportTemplateConfig()
            )

            assertTrue(result.isSuccess)
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            val body = request?.body?.readUtf8().orEmpty()
            assertTrue(body.contains("\"model_reasoning_effort\":\"high\""))
            assertTrue(body.contains("\"effort\":\"low\""))
        }
    }

    @Test
    fun sendsOrderedAttachmentManifestWithCaptureMetadata() = runBlocking {
        val image = File.createTempFile("visit-entrance", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("{\"text\":\"ok\"}"))
                val config = LLMConfig(
                    agentEndpoint = server.url("/").toString(),
                    agentAccessToken = "token",
                    agentProvider = AgentProvider.CODEX_CLI
                )

                val result = AgentGatewayEngine(config).generateReport(
                    transcript = "参观记录",
                    template = ReportTemplateConfig(selectedName = "参观考察（游记）"),
                    attachments = listOf(
                        AgentAttachment(
                            file = image,
                            mimeType = "image/jpeg",
                            displayName = "entrance.jpg",
                            capturedAt = 1_754_274_400_000,
                            latitude = 30.7521,
                            longitude = 120.7582,
                            accuracyMeters = 18.5f,
                            locationCapturedAt = 1_754_274_401_000,
                            locationSource = "gps"
                        )
                    )
                )

                assertTrue(result.isSuccess)
                val request = server.takeRequest(5, TimeUnit.SECONDS)
                val body = request?.body?.readUtf8().orEmpty()
                assertTrue(body.contains("\"attachmentManifest\":[{"))
                assertTrue(body.contains("\"index\":1"))
                assertTrue(body.contains("\"displayName\":\"entrance.jpg\""))
                assertTrue(body.contains("\"capturedAt\":1754274400000"))
                assertTrue(body.contains("\"latitude\":30.7521"))
                assertTrue(body.contains("\"longitude\":120.7582"))
                assertTrue(body.contains("\"accuracyMeters\":18.5"))
                assertTrue(body.contains("\"locationCapturedAt\":1754274401000"))
                assertTrue(body.contains("\"locationSource\":\"gps\""))
            }
        } finally {
            image.delete()
        }
    }

    @Test
    fun cancellingCoroutineCancelsInFlightAgentRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val config = LLMConfig(
                agentEndpoint = server.url("/").toString(),
                agentAccessToken = "token"
            )
            val job = launch {
                AgentGatewayEngine(config).generateReport(
                    transcript = "会议内容",
                    template = ReportTemplateConfig()
                )
            }

            server.takeRequest(5, TimeUnit.SECONDS)
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
        }
    }
}

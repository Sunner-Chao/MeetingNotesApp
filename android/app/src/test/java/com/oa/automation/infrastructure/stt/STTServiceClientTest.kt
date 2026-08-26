package com.oa.automation.infrastructure.stt

import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTLanguage
import com.oa.automation.domain.model.TencentAsrQuotaWarningLevel
import com.oa.automation.domain.model.TencentAsrUsage
import com.oa.automation.domain.model.TencentAsrUsageService
import com.oa.automation.domain.model.formatTencentAsrDuration
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class STTServiceClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `connection test validates health and bearer token`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"events\":[]}"))

        val result = STTServiceClient.testConnection(endpoint(), "valid-token")

        assertTrue(result.isSuccess)
        assertEquals("/health", server.takeRequest().path)
        val authRequest = server.takeRequest()
        assertEquals("/debug/stream-events?limit=1", authRequest.path)
        assertEquals("Bearer valid-token", authRequest.getHeader("Authorization"))
    }

    @Test
    fun `connection test reports an invalid bearer token`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"detail\":\"Unauthorized\"}"))

        val result = STTServiceClient.testConnection(endpoint(), "wrong-token")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SttAuthorizationException)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("令牌无效"))
    }

    @Test
    fun `usage query exposes authorization failures for account token renewal`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{\"detail\":\"Forbidden\"}"))

        val result = STTServiceClient.fetchTencentAsrUsage(endpoint(), "expired-account-token")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SttAuthorizationException)
    }

    @Test
    fun `connection test rejects a missing token before network access`() {
        val result = STTServiceClient.testConnection(endpoint(), null)

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `usage query parses account quota and sends bearer token`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "month":"2026-07",
                  "timezone":"Asia/Shanghai",
                  "next_reset_at":"2026-08-01T00:00:00+08:00",
                  "hybrid_remaining_seconds":800,
                  "warning_level":"critical",
                  "source":"tencent-cloud-api+server-ledger",
                  "updated_at":"2026-07-22T12:00:00Z",
                  "is_estimated":true,
                  "services":[
                    {
                      "id":"realtime",
                      "display_name":"实时语音识别",
                      "used_seconds":17200,
                      "free_seconds":18000,
                      "remaining_seconds":800,
                      "usage_ratio":0.955556,
                      "request_count":10
                    },
                    {
                      "id":"flash",
                      "display_name":"录音文件识别极速版",
                      "used_seconds":2000,
                      "free_seconds":18000,
                      "remaining_seconds":16000,
                      "usage_ratio":0.111111,
                      "request_count":3,
                      "pending_local_seconds":182,
                      "pending_local_request_count":2
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val result = STTServiceClient.fetchTencentAsrUsage(
            endpoint(),
            "account-token",
            forceRefresh = true
        )

        assertTrue(result.isSuccess)
        val usage = result.getOrThrow()
        assertEquals("2026-07", usage.month)
        assertEquals(TencentAsrQuotaWarningLevel.CRITICAL, usage.warningLevel)
        assertEquals(800L, usage.hybridRemainingSeconds)
        assertEquals(17_200L, usage.services.first().usedSeconds)
        assertEquals(182L, usage.services.last().pendingLocalSeconds)
        assertEquals(2L, usage.services.last().pendingLocalRequestCount)
        assertTrue(usage.isEstimated)
        val request = server.takeRequest()
        assertEquals("/cloud-asr/usage?force=true", request.path)
        assertEquals("Bearer account-token", request.getHeader("Authorization"))
    }

    @Test
    fun `tier policy parses server budget rather than claiming a cloud free balance`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "month":"2026-07",
                  "source":"standard-usage-reference; precision-budget-ledger",
                  "tiers":[
                    {
                      "id":"standard",
                      "display_name":"腾讯标准云端（免费额度）",
                      "paid":false,
                      "flash_enabled":true,
                      "realtime_enabled":true,
                      "monthly_limit_sec":18000,
                      "services":[
                        {"business_name":"asr_rt","display_name":"实时语音识别","used_seconds":120,"reserved_seconds":60,"limit_seconds":18000,"remaining_seconds":17820}
                      ]
                    },
                    {
                      "id":"precision",
                      "display_name":"腾讯高精度云端（付费）",
                      "paid":true,
                      "flash_enabled":false,
                      "realtime_enabled":false,
                      "monthly_limit_sec":0,
                      "services":[]
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val result = STTServiceClient.fetchTencentAsrPolicy(endpoint(), "account-token")

        assertTrue(result.isSuccess)
        val policy = result.getOrThrow()
        assertEquals("standard-usage-reference; precision-budget-ledger", policy.source)
        assertTrue(policy.tiers.first().isAvailable)
        assertFalse(policy.tiers.last().isAvailable)
        assertEquals(60L, policy.tiers.first().services.single().reservedSeconds)
        val request = server.takeRequest()
        assertEquals("/cloud-asr/policy", request.path)
        assertEquals("Bearer account-token", request.getHeader("Authorization"))
    }

    @Test
    fun `duration formatting and quota warning messages are concise`() {
        assertEquals("0 分钟", formatTencentAsrDuration(0))
        assertEquals("不足 1 分钟", formatTencentAsrDuration(42))
        assertEquals("1 小时 15 分钟", formatTencentAsrDuration(4_500))
        assertEquals("5 小时", formatTencentAsrDuration(18_000))

        val service = TencentAsrUsageService(
            id = "realtime",
            displayName = "实时语音识别",
            usedSeconds = 17_500,
            freeSeconds = 18_000,
            remainingSeconds = 500,
            usageRatio = 0.97f,
            requestCount = 2
        )
        fun usage(level: TencentAsrQuotaWarningLevel) = TencentAsrUsage(
            month = "2026-07",
            timezone = "Asia/Shanghai",
            nextResetAt = "2026-08-01T00:00:00+08:00",
            hybridRemainingSeconds = service.remainingSeconds,
            warningLevel = level,
            services = listOf(service)
        )

        assertEquals(null, usage(TencentAsrQuotaWarningLevel.NORMAL).warningMessage())
        assertTrue(usage(TencentAsrQuotaWarningLevel.LOW).warningMessage().orEmpty().contains("1 小时"))
        assertTrue(usage(TencentAsrQuotaWarningLevel.CRITICAL).warningMessage().orEmpty().contains("15 分钟"))
        assertTrue(usage(TencentAsrQuotaWarningLevel.EXHAUSTED).warningMessage().orEmpty().contains("已用完"))
    }

    @Test
    fun `transcription maps unauthorized response to actionable error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"detail\":\"Unauthorized\"}"))
        val audio = File.createTempFile("meetingnotes-test-", ".wav").apply {
            writeBytes(ByteArray(64))
        }
        try {
            val engine = FasterWhisperEngine(
                STTConfig(localEndpoint = endpoint(), apiToken = "wrong-token")
            )

            val result = engine.transcribe(audio)

            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("令牌无效"))
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `local transcription sends selected language`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "{\"text\":\"English transcript.\",\"language\":\"en\"}"
            )
        )
        val audio = File.createTempFile("meetingnotes-language-", ".wav").apply {
            writeBytes(ByteArray(64))
        }
        try {
            val engine = FasterWhisperEngine(
                STTConfig(
                    language = STTLanguage.ENGLISH,
                    localEndpoint = endpoint(),
                    apiToken = "valid-token"
                )
            )

            val result = engine.transcribe(
                audio,
                meetingId = "meeting-1",
                archiveKey = "transcript-1",
                contextHint = "大佛寺 研学考察"
            )

            assertEquals("English transcript.", result.getOrThrow())
            val request = server.takeRequest()
            assertEquals("meeting-1", request.getHeader("X-Meeting-Id"))
            assertEquals("transcript-1", request.getHeader("X-Archive-Key"))
            val multipart = request.body.readUtf8()
            assertTrue(multipart.contains("name=\"language\""))
            assertTrue(multipart.contains("en"))
            assertTrue(multipart.contains("name=\"context_hint\""))
            assertTrue(multipart.contains("大佛寺 研学考察"))
        } finally {
            audio.delete()
        }
    }

    private fun endpoint(): String = server.url("/").toString().trimEnd('/')
}

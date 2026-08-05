package com.oa.automation.infrastructure.llm

import com.oa.automation.domain.model.LLMConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentQuotaServiceTest {
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
    fun fetchUsesConfiguredEndpointAndToken() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"label":"Pro","request_limit":100,"requests_used":25,"requests_remaining":75,"allowed_providers":["codex-cli","claude-cli"],"expires_at":1893456000}"""
                )
        )

        val quota = AgentQuotaService().fetch(
            LLMConfig(
                agentEndpoint = server.url("/api/agent").toString().trimEnd('/'),
                agentAccessToken = "scoped-token"
            )
        ).getOrThrow()

        val request = server.takeRequest()
        assertEquals("/api/agent/quota", request.path)
        assertEquals("Bearer scoped-token", request.getHeader("Authorization"))
        assertEquals(75, quota.requestsRemaining)
        assertEquals(0.25f, quota.usedFraction)
        assertEquals(listOf("codex-cli", "claude-cli"), quota.allowedProviders)
    }

    @Test
    fun fetchMapsInvalidTokenError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"detail\":\"invalid\"}"))

        val result = AgentQuotaService().fetch(
            LLMConfig(
                agentEndpoint = server.url("/api/agent").toString().trimEnd('/'),
                agentAccessToken = "expired-token"
            )
        )

        assertTrue(result.isFailure)
        assertEquals("访问令牌无效或已过期", result.exceptionOrNull()?.message)
    }
}

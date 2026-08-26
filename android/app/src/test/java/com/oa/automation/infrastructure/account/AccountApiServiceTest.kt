package com.oa.automation.infrastructure.account

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountApiServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: AccountApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = AccountApiService()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loginParsesServerSessionAndUserEntitlements() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{
                      "access_token":"user-token",
                      "agent_access_token":"agent-token",
                      "stt_access_token":"stt-token",
                      "token_type":"bearer",
                      "expires_at":1893456000,
                      "user":{
                        "id":"u1","username":"admin","role":"admin",
                        "is_admin":true,"enabled":true,"vip_enabled":true,
                        "construction_logs_unlocked":true,
                        "plan_code":"vip_professional","plan_name":"专业月卡","created_at":1,
                        "quota":{"request_limit":1000,"requests_used":5,"requests_remaining":995}
                      }
                    }"""
                )
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        val session = service.login(endpoint, "admin", "password123").getOrThrow()

        val request = server.takeRequest()
        assertEquals("/api/auth/login", request.path)
        assertTrue(request.body.readUtf8().contains("\"username\":\"admin\""))
        assertEquals("agent-token", session.agentAccessToken)
        assertEquals("stt-token", session.sttAccessToken)
        assertEquals("专业月卡", session.user.planName)
        assertTrue(session.user.isAdmin)
        assertTrue(session.user.constructionLogsUnlocked)
        assertEquals(995, session.user.quota?.requestsRemaining)
    }

    @Test
    fun authProvidersExposeOnlyRuntimeConfiguredLoginUrls() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """[
                      {"id":"wechat","name":"微信","enabled":true,"authorization_url":"https://login.example/wechat"},
                      {"id":"qq","name":"QQ","enabled":false,"authorization_url":""},
                      {"id":"feishu","name":"飞书","enabled":false,"authorization_url":""}
                    ]"""
                )
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        val providers = service.authProviders(endpoint).getOrThrow()

        assertEquals("/api/auth/providers", server.takeRequest().path)
        assertEquals(listOf("wechat", "qq", "feishu"), providers.map { it.id })
        assertTrue(providers.first().enabled)
        assertEquals("https://login.example/wechat", providers.first().authorizationUrl)
    }

    @Test
    fun refreshSessionReturnsCurrentProfileAndRuntimeTokens() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{
                      "agent_access_token":"agent-refreshed",
                      "stt_access_token":"stt-refreshed",
                      "expires_at":1893456000,
                      "user":{
                        "id":"u2","username":"member","role":"user",
                        "is_admin":false,"enabled":true,"vip_enabled":true,
                        "construction_logs_unlocked":true,
                        "plan_code":"vip_starter","plan_name":"轻享月卡","created_at":1,
                        "quota":{"request_limit":40,"requests_used":2,"requests_remaining":38}
                      }
                    }"""
                )
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        val credentials = service.refreshSession(endpoint, "user-session").getOrThrow()

        val request = server.takeRequest()
        assertEquals("/api/account/session", request.path)
        assertEquals("Bearer user-session", request.getHeader("Authorization"))
        assertEquals("stt-refreshed", credentials.sttAccessToken)
        assertEquals("轻享月卡", credentials.user.planName)
        assertEquals(38, credentials.user.quota?.requestsRemaining)
    }

    @Test
    fun refreshSessionAcceptsExplicitNullUsageAndQuotaFromLegacyServer() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{
                      "agent_access_token":"agent-refreshed",
                      "stt_access_token":"stt-refreshed",
                      "expires_at":1893456000,
                      "user":{
                        "id":"legacy-user","username":"legacy","role":"user",
                        "is_admin":false,"enabled":true,"vip_enabled":false,
                        "construction_logs_unlocked":false,
                        "plan_code":"free","plan_name":"Free","created_at":1,
                        "usage":null,"quota":null
                      }
                    }"""
                )
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        val credentials = service.refreshSession(endpoint, "user-session").getOrThrow()

        assertEquals(null, credentials.user.usage)
        assertEquals(null, credentials.user.quota)
    }

    @Test
    fun createOrderUsesUserBearerAndMapsConflict() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"已有待处理的充值订单\"}")
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        val result = service.createOrder(endpoint, "user-session", "vip_starter")

        val request = server.takeRequest()
        assertEquals("/api/account/orders", request.path)
        assertEquals("Bearer user-session", request.getHeader("Authorization"))
        assertEquals("已有待处理的充值订单", result.exceptionOrNull()?.message)
    }

    @Test
    fun plansAndOrdersExposePointBasedEntitlements() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """[
                      {"code":"points_starter","name":"轻享积分包","description":"日常记录","price_cents":3900,"quota_amount":10000,"points":10000,"duration_days":30,"team_seats":1,"construction_logs_unlocked":true}
                    ]"""
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """[
                      {"id":"order-1","user_id":"u1","username":"member","plan_code":"points_starter","plan_name":"轻享积分包","amount_cents":3900,"quota_amount":10000,"points":10000,"duration_days":30,"team_seats":1,"construction_logs_unlocked":true,"status":"pending","created_at":1700000000}
                    ]"""
                )
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        val plans = service.plans(endpoint, "user-session").getOrThrow()
        val orders = service.orders(endpoint, "user-session").getOrThrow()

        assertEquals("/api/account/plans", server.takeRequest().path)
        assertEquals("Bearer user-session", server.takeRequest().getHeader("Authorization"))
        assertEquals(10000, plans.single().points)
        assertEquals(10000, orders.single().points)
        assertEquals("pending", orders.single().status)
    }

    @Test
    fun deleteUserUsesAdminBearerAndDeleteMethod() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"deleted\"}")
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        service.deleteUser(endpoint, "admin-session", "user-123").getOrThrow()

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/admin/accounts/users/user-123", request.path)
        assertEquals("Bearer admin-session", request.getHeader("Authorization"))
    }

    @Test
    fun passwordResetUsesDedicatedPurposeAndPublicEndpoint() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"ok\"}")
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        service.resetPassword(
            endpoint = endpoint,
            channel = "phone",
            identifier = "13800138000",
            code = "123456",
            newPassword = "new-password"
        ).getOrThrow()

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/password/reset", request.path)
        assertTrue(body.contains("\"purpose\":\"reset_password\""))
        assertTrue(body.contains("\"new_password\":\"new-password\""))
    }

    @Test
    fun authCodeRequestCarriesResetPasswordPurpose() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"sent","channel":"email","masked_identifier":"a***@example.com","expires_in":300,"retry_after":60}"""
                )
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        service.requestAuthCode(endpoint, "email", "a@example.com", "reset_password").getOrThrow()

        val request = server.takeRequest()
        assertEquals("/api/auth/code/request", request.path)
        assertTrue(request.body.readUtf8().contains("\"purpose\":\"reset_password\""))
    }
}

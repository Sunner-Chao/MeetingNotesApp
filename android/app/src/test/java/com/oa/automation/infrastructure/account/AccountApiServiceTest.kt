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
    fun createAlipayPaymentUsesOrderEndpointAndParsesOrderString() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"provider":"alipay","product":"app_pay","environment":"sandbox","order_id":"order-1","out_trade_no":"ZW-1","amount_cents":990,"orderStr":"app_id=test&sign=server-signature","payment_status":"created"}"""
                )
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        val payment = service.createAlipayPayment(endpoint, "user-session", "order-1").getOrThrow()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/account/orders/order-1/alipay/pay", request.path)
        assertEquals("Bearer user-session", request.getHeader("Authorization"))
        assertEquals("sandbox", payment.environment)
        assertEquals("app_id=test&sign=server-signature", payment.orderString)
        assertEquals(990, payment.amountCents)
    }

    @Test
    fun queryAlipayPaymentParsesAuthoritativeServerStatus() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"payment":{"id":"tx-1","order_id":"order-1","out_trade_no":"ZW-1","trade_no":"20260828001","status":"paid","last_trade_status":"TRADE_SUCCESS","amount_cents":990,"environment":"sandbox","paid_at":1893456000},"processed":{"result":"success"}}"""
                )
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        val query = service.queryAlipayPayment(endpoint, "user-session", "order-1").getOrThrow()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/account/orders/order-1/alipay/query", request.path)
        assertEquals("Bearer user-session", request.getHeader("Authorization"))
        assertEquals("paid", query.payment.status)
        assertEquals("TRADE_SUCCESS", query.payment.lastTradeStatus)
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

    @Test
    fun verifiedRegistrationCarriesOptionalReferralCode() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                """{"access_token":"user-token","agent_access_token":"agent-token","stt_access_token":"stt-token","token_type":"bearer","expires_at":1893456000,"user":{"id":"u3","username":"new-user","role":"user","is_admin":false,"enabled":true,"vip_enabled":false,"construction_logs_unlocked":false,"created_at":1}}"""
            )
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        service.registerWithCode(
            endpoint,
            "email",
            "new@example.com",
            "123456",
            "new-user",
            "password123",
            "zw-ab12"
        ).getOrThrow()

        val request = server.takeRequest()
        assertEquals("/api/auth/register/verify", request.path)
        assertTrue(request.body.readUtf8().contains("\"referral_code\":\"ZW-AB12\""))
    }

    @Test
    fun growthOverviewAndCampaignDetailMapAndroidFields() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                """{
                  "referral":{"code":"ZW-1234","successful_invites":2,"pending_rewards":0,"reward_points":300,"share_path":"/app/?ref=ZW-1234"},
                  "rewards":{"points":120},
                  "campaigns":[{"id":"daily-quiz","title":"每日答题兑好礼","campaign_type":"quiz","summary":"答题得积分","rules":{"checkin_reward":10,"answer_reward":20,"questions":[{"key":"q1","question":"浙江省省会是哪里？","options":["杭州","宁波"]}]},"reward_pool":{"ranks":{"1":500}},"starts_at":1700000000,"ends_at":1800000000,"status":"active"}],
                  "private_channel":{"id":"default-welfare-group","name":"智悟本福利7群","qr_image_url":"/api/growth/private-channel/default-qr","join_url":"","short_url":"","slogan":"扫码入群","reward_type":"points","reward":{"quantity":200},"enabled":true}
                }"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                """{"id":"daily-quiz","title":"每日答题兑好礼","campaign_type":"quiz","summary":"答题得积分","rules":{"checkin_reward":10,"answer_reward":20,"questions":[{"key":"q1","question":"浙江省省会是哪里？","options":["杭州","宁波"]}]},"reward_pool":{"ranks":{"1":500}},"starts_at":1700000000,"ends_at":1800000000,"status":"active","joined":true,"my_score":3,"my_rank":1,"actions":[],"leaderboard":[{"user_id":"u1","display_name":"高老师","score":3,"rank":1}]}"""
            )
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        val overview = service.growthOverview(endpoint, "user-session").getOrThrow()
        val detail = service.growthCampaignDetail(endpoint, "user-session", "daily-quiz").getOrThrow()

        assertEquals("ZW-1234", overview.referral.code)
        assertEquals(300, overview.referral.rewardPoints)
        assertEquals(20, overview.campaigns.single().rules.answerReward)
        assertEquals("智悟本福利7群", overview.privateChannel?.name)
        assertEquals(200, overview.privateChannel?.reward?.quantity)
        assertEquals(3, detail.myScore)
        assertEquals("高老师", detail.leaderboard.single().displayName)
        assertEquals("Bearer user-session", server.takeRequest().getHeader("Authorization"))
        assertEquals("/api/growth/campaigns/daily-quiz", server.takeRequest().path)
    }

    @Test
    fun publicGrowthPrivateChannelDoesNotRequireSession() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                """{"id":"default-welfare-group","name":"智悟本福利7群","qr_image_url":"/api/growth/private-channel/default-qr","slogan":"扫码加入福利群","reward":{"quantity":200},"enabled":true}"""
            )
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        val channel = service.publicGrowthPrivateChannel(endpoint).getOrThrow()
        val request = server.takeRequest()

        assertEquals("智悟本福利7群", channel.name)
        assertEquals("/api/growth/private-channel/default-qr", channel.qrImageUrl)
        assertEquals(null, request.getHeader("Authorization"))
        assertEquals("/api/growth/private-channel", request.path)
    }

    @Test
    fun growthSystemMessagesCanBeListedAndMarkedRead() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                """[{"id":"campaign:1:announcement","message_type":"campaign_announcement","title":"获奖公告","body":"活动已结算","campaign_id":"campaign-1","action_path":"/growth/campaigns/campaign-1","created_at":1800000000,"read_at":null}]"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                """{"status":"read","id":"campaign:1:announcement","read_at":1800000010}"""
            )
        )

        val endpoint = server.url("/api").toString().trimEnd('/')
        val messages = service.growthSystemMessages(endpoint, "user-session").getOrThrow()
        service.markGrowthSystemMessageRead(
            endpoint,
            "user-session",
            messages.single().id
        ).getOrThrow()

        assertEquals("获奖公告", messages.single().title)
        assertEquals(null, messages.single().readAt)
        assertEquals("/api/account/growth/messages", server.takeRequest().path)
        assertEquals(
            "/api/account/growth/messages/campaign%3A1%3Aannouncement/read",
            server.takeRequest().path
        )
    }
}

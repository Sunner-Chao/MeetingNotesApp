package com.oa.automation.domain.model

import com.google.gson.annotations.SerializedName

data class AccountQuotaSummary(
    @SerializedName("request_limit") val requestLimit: Int = 0,
    @SerializedName("requests_used") val requestsUsed: Int = 0,
    @SerializedName("requests_remaining") val requestsRemaining: Int = 0
)

data class AccountUsageSummary(
    @SerializedName("included_minutes") val includedMinutes: Int = 0,
    @SerializedName("stt_seconds_used") val sttSecondsUsed: Int = 0,
    @SerializedName("stt_minutes_used") val sttMinutesUsed: Double = 0.0,
    @SerializedName("stt_seconds_remaining") val sttSecondsRemaining: Int = 0,
    @SerializedName("stt_minutes_remaining") val sttMinutesRemaining: Double = 0.0,
    @SerializedName("ai_credits_granted") val aiCreditsGranted: Int = 0,
    @SerializedName("ai_credits_used") val aiCreditsUsed: Int = 0,
    @SerializedName("ai_credits_remaining") val aiCreditsRemaining: Int = 0,
    @SerializedName("points_granted") val pointsGranted: Int = 1000,
    @SerializedName("points_used") val pointsUsed: Int = 0,
    @SerializedName("points_remaining") val pointsRemaining: Int = 1000,
    @SerializedName("team_seats") val teamSeats: Int = 1,
    @SerializedName("period_start") val periodStart: Long = 0,
    @SerializedName("period_end") val periodEnd: Long = 0
)

data class AccountProfile(
    val id: String,
    val username: String,
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("avatar_data_url") val avatarDataUrl: String? = null,
    val role: String,
    @SerializedName("is_admin") val isAdmin: Boolean,
    val enabled: Boolean,
    @SerializedName("vip_enabled") val vipEnabled: Boolean,
    @SerializedName("vip_expires_at") val vipExpiresAt: Long? = null,
    @SerializedName("construction_logs_unlocked") val constructionLogsUnlocked: Boolean,
    @SerializedName("plan_code") val planCode: String = "free",
    @SerializedName("plan_name") val planName: String = "免费账户",
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("identity_providers") val identityProviders: List<String> = emptyList(),
    val usage: AccountUsageSummary? = AccountUsageSummary(),
    val quota: AccountQuotaSummary? = AccountQuotaSummary()
)

data class AuthSession(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("agent_access_token") val agentAccessToken: String = "",
    @SerializedName("stt_access_token") val sttAccessToken: String? = null,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_at") val expiresAt: Long,
    val user: AccountProfile
)

data class SocialAuthProvider(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val status: String = "not_configured",
    @SerializedName("unavailable_reason") val unavailableReason: String = "",
    @SerializedName("authorization_url") val authorizationUrl: String = "",
    @SerializedName("start_path") val startPath: String = "",
    val tier: String = "consumer"
)

data class AuthCodeRequestResult(
    val status: String,
    val channel: String,
    @SerializedName("masked_identifier") val maskedIdentifier: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("retry_after") val retryAfter: Int
)

data class AccountSessionCredentials(
    @SerializedName("agent_access_token") val agentAccessToken: String = "",
    @SerializedName("stt_access_token") val sttAccessToken: String = "",
    @SerializedName("expires_at") val expiresAt: Long,
    val user: AccountProfile
)

data class AccountPlan(
    val code: String,
    val name: String,
    val description: String,
    @SerializedName("price_cents") val priceCents: Int,
    @SerializedName("quota_amount") val quotaAmount: Int,
    val points: Int = quotaAmount,
    @SerializedName("included_minutes") val includedMinutes: Int = 0,
    @SerializedName("ai_credits") val aiCredits: Int = quotaAmount,
    @SerializedName("team_seats") val teamSeats: Int = 1,
    @SerializedName("duration_days") val durationDays: Int = 30,
    @SerializedName("construction_logs_unlocked") val constructionLogsUnlocked: Boolean
)

data class RechargeOrder(
    val id: String,
    @SerializedName("user_id") val userId: String,
    val username: String,
    @SerializedName("plan_code") val planCode: String,
    @SerializedName("plan_name") val planName: String,
    @SerializedName("amount_cents") val amountCents: Int,
    @SerializedName("quota_amount") val quotaAmount: Int,
    val points: Int = quotaAmount,
    @SerializedName("included_minutes") val includedMinutes: Int = 0,
    @SerializedName("ai_credits") val aiCredits: Int = quotaAmount,
    @SerializedName("team_seats") val teamSeats: Int = 1,
    @SerializedName("duration_days") val durationDays: Int = 30,
    @SerializedName("subscription_started_at") val subscriptionStartedAt: Long? = null,
    @SerializedName("subscription_expires_at") val subscriptionExpiresAt: Long? = null,
    @SerializedName("construction_logs_unlocked") val constructionLogsUnlocked: Boolean,
    val status: String,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("decided_at") val decidedAt: Long? = null
)

data class AlipayAppPayment(
    val provider: String = "alipay",
    val product: String = "app_pay",
    val environment: String = "sandbox",
    @SerializedName("order_id") val orderId: String = "",
    @SerializedName("out_trade_no") val outTradeNo: String = "",
    @SerializedName("amount_cents") val amountCents: Int = 0,
    @SerializedName("orderStr") val orderString: String = "",
    @SerializedName("payment_status") val paymentStatus: String = "created"
)

data class AlipayPaymentState(
    val id: String = "",
    @SerializedName("order_id") val orderId: String = "",
    @SerializedName("out_trade_no") val outTradeNo: String = "",
    @SerializedName("trade_no") val tradeNo: String? = null,
    val status: String = "created",
    @SerializedName("last_trade_status") val lastTradeStatus: String? = null,
    @SerializedName("amount_cents") val amountCents: Int = 0,
    val environment: String = "sandbox",
    @SerializedName("paid_at") val paidAt: Long? = null
)

data class AlipayPaymentQuery(
    val payment: AlipayPaymentState = AlipayPaymentState(),
    val processed: Map<String, Any?>? = null
)

data class GrowthQuestion(
    val key: String = "",
    val question: String = "",
    val options: List<String> = emptyList()
)

data class GrowthCampaignRules(
    @SerializedName("checkin_reward") val checkinReward: Int = 0,
    @SerializedName("answer_reward") val answerReward: Int = 0,
    @SerializedName("draw_reward") val drawReward: Int = 0,
    @SerializedName("win_probability") val winProbability: Int = 0,
    val questions: List<GrowthQuestion> = emptyList()
)

data class GrowthCampaignRewardPool(
    val ranks: Map<String, Int> = emptyMap()
)

data class GrowthCampaign(
    val id: String = "",
    val title: String = "",
    @SerializedName("campaign_type") val campaignType: String = "",
    val summary: String = "",
    val rules: GrowthCampaignRules = GrowthCampaignRules(),
    @SerializedName("reward_pool") val rewardPool: GrowthCampaignRewardPool = GrowthCampaignRewardPool(),
    @SerializedName("starts_at") val startsAt: Long = 0,
    @SerializedName("ends_at") val endsAt: Long = 0,
    val status: String = ""
)

data class GrowthCampaignAction(
    @SerializedName("action_type") val actionType: String = "",
    @SerializedName("action_key") val actionKey: String = "",
    val score: Int = 0,
    val status: String = "",
    @SerializedName("created_at") val createdAt: Long = 0
)

data class GrowthLeaderboardEntry(
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("display_name") val displayName: String = "",
    val score: Int = 0,
    val rank: Int? = null
)

data class GrowthCampaignDetail(
    val id: String = "",
    val title: String = "",
    @SerializedName("campaign_type") val campaignType: String = "",
    val summary: String = "",
    val rules: GrowthCampaignRules = GrowthCampaignRules(),
    @SerializedName("reward_pool") val rewardPool: GrowthCampaignRewardPool = GrowthCampaignRewardPool(),
    @SerializedName("starts_at") val startsAt: Long = 0,
    @SerializedName("ends_at") val endsAt: Long = 0,
    val status: String = "",
    val joined: Boolean = false,
    @SerializedName("my_score") val myScore: Int = 0,
    @SerializedName("my_rank") val myRank: Int? = null,
    val actions: List<GrowthCampaignAction> = emptyList(),
    val leaderboard: List<GrowthLeaderboardEntry> = emptyList()
)

data class GrowthReferral(
    val code: String = "",
    @SerializedName("successful_invites") val successfulInvites: Int = 0,
    @SerializedName("pending_rewards") val pendingRewards: Int = 0,
    @SerializedName("reward_points") val rewardPoints: Int = 300,
    @SerializedName("share_path") val sharePath: String = ""
)

data class GrowthReward(
    val quantity: Int = 0
)

data class GrowthChannelApplication(
    val id: String = "",
    @SerializedName("channel_id") val channelId: String = "",
    @SerializedName("user_id") val userId: String = "",
    val answers: Map<String, String> = emptyMap(),
    val status: String = "",
    @SerializedName("review_note") val reviewNote: String = "",
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("updated_at") val updatedAt: Long = 0,
    @SerializedName("reviewed_at") val reviewedAt: Long? = null,
    @SerializedName("channel_name") val channelName: String = "",
    @SerializedName("user_name") val userName: String = ""
)

data class GrowthPrivateChannel(
    val id: String = "",
    val name: String = "",
    @SerializedName("qr_image_url") val qrImageUrl: String = "",
    @SerializedName("manager_card_image_url") val managerCardImageUrl: String = "",
    @SerializedName("join_url") val joinUrl: String = "",
    @SerializedName("short_url") val shortUrl: String = "",
    val slogan: String = "",
    @SerializedName("reward_type") val rewardType: String = "points",
    val reward: GrowthReward = GrowthReward(),
    val enabled: Boolean = false,
    @SerializedName("requires_application") val requiresApplication: Boolean = true,
    val application: GrowthChannelApplication? = null
)

data class GrowthOverview(
    val referral: GrowthReferral = GrowthReferral(),
    val rewards: Map<String, Int> = emptyMap(),
    val campaigns: List<GrowthCampaign> = emptyList(),
    @SerializedName("private_channel") val privateChannel: GrowthPrivateChannel? = null
)

data class GrowthActionResult(
    val status: String = "",
    val message: String = "",
    val quantity: Int = 0,
    val correct: Boolean? = null,
    val won: Boolean? = null,
    val probability: Int = 0
)

data class GrowthRedeemResult(
    val status: String = "",
    val message: String = "",
    @SerializedName("reward_type") val rewardType: String = "",
    val quantity: Int = 0,
    val profile: AccountProfile,
    @SerializedName("private_channel") val privateChannel: GrowthPrivateChannel? = null
)

data class GrowthSystemMessage(
    val id: String = "",
    @SerializedName("message_type") val messageType: String = "system",
    val title: String = "",
    val body: String = "",
    @SerializedName("campaign_id") val campaignId: String? = null,
    @SerializedName("action_path") val actionPath: String = "",
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("read_at") val readAt: Long? = null
)

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
    @SerializedName("plan_name") val planName: String = "Free",
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("identity_providers") val identityProviders: List<String> = emptyList(),
    val usage: AccountUsageSummary? = AccountUsageSummary(),
    val quota: AccountQuotaSummary? = AccountQuotaSummary()
)

data class AuthSession(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("agent_access_token") val agentAccessToken: String,
    @SerializedName("stt_access_token") val sttAccessToken: String? = null,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_at") val expiresAt: Long,
    val user: AccountProfile
)

data class SocialAuthProvider(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    @SerializedName("authorization_url") val authorizationUrl: String = "",
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
    @SerializedName("agent_access_token") val agentAccessToken: String,
    @SerializedName("stt_access_token") val sttAccessToken: String,
    @SerializedName("expires_at") val expiresAt: Long,
    val user: AccountProfile
)

data class AccountPlan(
    val code: String,
    val name: String,
    val description: String,
    @SerializedName("price_cents") val priceCents: Int,
    @SerializedName("quota_amount") val quotaAmount: Int,
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

package com.oa.automation.domain.model

import com.google.gson.annotations.SerializedName

data class AccountQuotaSummary(
    @SerializedName("request_limit") val requestLimit: Int = 0,
    @SerializedName("requests_used") val requestsUsed: Int = 0,
    @SerializedName("requests_remaining") val requestsRemaining: Int = 0
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
    @SerializedName("construction_logs_unlocked") val constructionLogsUnlocked: Boolean,
    @SerializedName("plan_code") val planCode: String = "free",
    @SerializedName("plan_name") val planName: String = "Free",
    @SerializedName("created_at") val createdAt: Long,
    val quota: AccountQuotaSummary = AccountQuotaSummary()
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
    @SerializedName("authorization_url") val authorizationUrl: String = ""
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
    @SerializedName("construction_logs_unlocked") val constructionLogsUnlocked: Boolean,
    val status: String,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("decided_at") val decidedAt: Long? = null
)

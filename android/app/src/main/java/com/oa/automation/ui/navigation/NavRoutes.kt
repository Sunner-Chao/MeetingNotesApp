package com.oa.automation.ui.navigation

import kotlinx.serialization.Serializable

// ── Splash ──────────────────────────────────────────────────
@Serializable
object Splash

// ── Auth Graph ──────────────────────────────────────────────
@Serializable
object AuthGraph

@Serializable
object Login

@Serializable
object Register

@Serializable
object ForgotPassword

// ── Main Graph (post-login) ────────────────────────────────
@Serializable
object MainGraph

@Serializable
object Home

@Serializable
object Settings

@Serializable
object Notifications

@Serializable
object AccountProfile

@Serializable
object AccountQuota

@Serializable
object AccountPointsPlans

@Serializable
object AccountUsers

@Serializable
object AccountCommunityModeration

@Serializable
data class CommunityPost(val postId: String)

@Serializable
data class CommunityCollection(val collectionId: String)

@Serializable
data class Recording(
    val meetingId: String,
    val launchAction: String = "STANDARD"
)

@Serializable
data class Report(val meetingId: String)

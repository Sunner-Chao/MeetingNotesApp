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
object Vip

@Serializable
data class Recording(val meetingId: String)

@Serializable
data class Report(val meetingId: String)

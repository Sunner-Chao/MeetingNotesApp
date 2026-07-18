package com.oa.automation.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.ui.screen.home.HomeScreen
import com.oa.automation.ui.screen.login.LoginScreen
import com.oa.automation.ui.screen.login.LoginViewModel
import com.oa.automation.ui.screen.recording.RecordingScreen
import com.oa.automation.ui.screen.report.ReportScreen
import com.oa.automation.ui.screen.settings.SettingsScreen
import com.oa.automation.ui.screen.splash.SplashScreen
import com.oa.automation.ui.screen.vip.VipScreen
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

private const val TRANSITION_DURATION = 400

/**
 * Root-level NavHost with two nested graphs:
 *
 *  - [Splash]     → Entrance animation (always shown first)
 *  - [AuthGraph]  → Login / Register / ForgotPassword
 *  - [MainGraph]  → Home / Settings / Vip / Recording / Report
 */
@Composable
fun OAAutomationNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    configDataStore: ConfigDataStore = koinInject()
) {
    val savedUsername by configDataStore.usernameFlow.collectAsStateWithLifecycle(initialValue = null)

    NavHost(
        navController = navController,
        startDestination = Splash,
        modifier = modifier,
        enterTransition = {
            fadeIn(tween(TRANSITION_DURATION))
        },
        exitTransition = {
            fadeOut(tween(TRANSITION_DURATION))
        },
        popEnterTransition = {
            fadeIn(tween(TRANSITION_DURATION))
        },
        popExitTransition = {
            fadeOut(tween(TRANSITION_DURATION))
        }
    ) {
        // ── Splash Screen ─────────────────────────────────
        composable<Splash> {
            SplashScreen(
                onSplashFinished = {
                    val isLoggedIn = !savedUsername.isNullOrBlank()
                    if (isLoggedIn) {
                        navController.navigate(MainGraph) {
                            popUpTo<Splash> { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        navController.navigate(AuthGraph) {
                            popUpTo<Splash> { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        // ── Auth Nested Graph ──────────────────────────────
        navigation<AuthGraph>(startDestination = Login) {

            composable<Login> {
                val loginViewModel: LoginViewModel = koinViewModel()
                LoginScreen(
                    viewModel = loginViewModel,
                    onEvent = loginViewModel::onEvent,
                    onNavigateToRegister = {
                        navController.navigate(Register)
                    },
                    onNavigateToForgotPassword = {
                        navController.navigate(ForgotPassword)
                    },
                    onLoginSuccess = {
                        navController.navigate(MainGraph) {
                            popUpTo<AuthGraph> { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable<Register> {
                // TODO: Replace with real RegisterScreen
                PlaceholderScreen(title = "注册") {
                    navController.popBackStack()
                }
            }

            composable<ForgotPassword> {
                // TODO: Replace with real ForgotPasswordScreen
                PlaceholderScreen(title = "忘记密码") {
                    navController.popBackStack()
                }
            }
        }

        // ── Main Nested Graph ──────────────────────────────
        navigation<MainGraph>(startDestination = Home) {

            composable<Home> {
                HomeScreen(
                    onNavigateToRecording = { meetingId ->
                        navController.navigate(Recording(meetingId))
                    },
                    onNavigateToReport = { meetingId ->
                        navController.navigate(Report(meetingId))
                    },
                    onNavigateToSettings = {
                        navController.navigate(Settings)
                    },
                    onNavigateToVip = {
                        navController.navigate(Vip)
                    }
                )
            }

            composable<Settings> {
                SettingsScreen(
                    viewModel = koinViewModel(),
                    onNavigateBack = { navController.popBackStack() },
                    onLogout = {
                        navController.navigate(AuthGraph) {
                            popUpTo<MainGraph> { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable<Vip> {
                VipScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToRecording = { meetingId ->
                        navController.navigate(Recording(meetingId))
                    }
                )
            }

            composable<Recording> { backStackEntry ->
                val route: Recording = backStackEntry.toRoute()
                RecordingScreen(
                    meetingId = route.meetingId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToReport = { id ->
                        navController.navigate(Report(id))
                    }
                )
            }

            composable<Report> { backStackEntry ->
                val route: Report = backStackEntry.toRoute()
                ReportScreen(
                    meetingId = route.meetingId,
                    onNavigateBack = { navController.popBackStack() },
                    onContinueRecording = { id ->
                        navController.navigate(Recording(id))
                    }
                )
            }
        }
    }
}

/**
 * Temporary placeholder for screens not yet implemented.
 */
@Composable
private fun PlaceholderScreen(
    title: String,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium
            )
            Button(onClick = onBack) {
                Text("返回")
            }
        }
    }
}

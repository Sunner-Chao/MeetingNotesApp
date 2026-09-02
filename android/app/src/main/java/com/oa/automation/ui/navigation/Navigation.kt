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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.oa.automation.ui.screen.account.AccountProfileScreen
import com.oa.automation.ui.screen.account.AccountQuotaDetailsScreen
import com.oa.automation.ui.screen.account.PointsPlansScreen
import com.oa.automation.ui.screen.account.RechargeOrdersScreen
import com.oa.automation.ui.screen.account.AccountUserManagementScreen
import com.oa.automation.ui.screen.account.CommunityModerationScreen
import com.oa.automation.ui.screen.account.GrowthCenterScreen
import com.oa.automation.ui.screen.account.GrowthCenterSection
import com.oa.automation.ui.screen.community.CommunityPostDetailScreen
import com.oa.automation.ui.screen.community.CommunityCollectionDetailScreen
import com.oa.automation.ui.screen.login.LoginScreen
import com.oa.automation.ui.screen.login.LoginViewModel
import com.oa.automation.ui.screen.login.ForgotPasswordScreen
import com.oa.automation.ui.screen.login.RegisterScreen
import com.oa.automation.ui.screen.main.MainWorkspaceScreen
import com.oa.automation.ui.screen.notifications.NotificationCenterScreen
import com.oa.automation.ui.screen.recording.RecordingScreen
import com.oa.automation.ui.screen.report.ReportScreen
import com.oa.automation.ui.screen.settings.SettingsScreen
import com.oa.automation.ui.screen.splash.SplashScreen
import com.oa.automation.domain.model.ProductEdition
import org.koin.androidx.compose.koinViewModel

private const val TRANSITION_DURATION = 400

/**
 * Root-level NavHost with two nested graphs:
 *
 *  - [Splash]     → Entrance animation (always shown first)
 *  - [AuthGraph]  → Login / Register / ForgotPassword
 *  - [MainGraph]  → Home workspace tabs / Settings / Recording / Report
 */
@Composable
fun OAAutomationNavHost(
    socialAuthLoginVersion: Int = 0,
    openRecordingMeetingId: String? = null,
    onRecordingMeetingOpened: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    LaunchedEffect(socialAuthLoginVersion) {
        if (socialAuthLoginVersion <= 0) return@LaunchedEffect
        navController.navigate(MainGraph) {
            popUpTo(navController.graph.id) { inclusive = false }
            launchSingleTop = true
        }
    }
    LaunchedEffect(openRecordingMeetingId) {
        val meetingId = openRecordingMeetingId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        navController.navigate(Recording(meetingId)) {
            launchSingleTop = true
        }
        onRecordingMeetingOpened(meetingId)
    }
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
                    navController.navigate(MainGraph) {
                        popUpTo<Splash> { inclusive = true }
                        launchSingleTop = true
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
                            popUpTo<MainGraph> { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onContinueAsGuest = {
                        navController.navigate(MainGraph) {
                            popUpTo<MainGraph> { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable<Register> {
                RegisterScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onRegisterSuccess = {
                        navController.navigate(MainGraph) {
                            popUpTo<MainGraph> { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onContinueAsGuest = {
                        navController.navigate(MainGraph) {
                            popUpTo<MainGraph> { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable<ForgotPassword> {
                ForgotPasswordScreen(onNavigateBack = { navController.popBackStack() })
            }
        }

        // ── Main Nested Graph ──────────────────────────────
        navigation<MainGraph>(startDestination = Home) {

            composable<Home> {
                MainWorkspaceScreen(
                    productEdition = ProductEdition.current,
                    onNavigateToRecording = { meetingId, action ->
                        navController.navigate(Recording(meetingId, action.name))
                    },
                    onNavigateToReport = { meetingId ->
                        navController.navigate(Report(meetingId))
                    },
                    onNavigateToSettings = {
                        navController.navigate(Settings)
                    },
                    onNavigateToNotifications = {
                        navController.navigate(Notifications())
                    },
                    onNavigateToAccountProfile = {
                        navController.navigate(AccountProfile)
                    },
                    onNavigateToAccountQuota = {
                        navController.navigate(AccountQuota)
                    },
                    onNavigateToAccountPointsPlans = {
                        navController.navigate(AccountPointsPlans)
                    },
                    onNavigateToAccountRechargeOrders = {
                        navController.navigate(AccountRechargeOrders)
                    },
                    onNavigateToInvitation = {
                        navController.navigate(AccountInvitation)
                    },
                    onNavigateToAccountUsers = {
                        navController.navigate(AccountUsers)
                    },
                    onNavigateToCommunityModeration = {
                        navController.navigate(AccountCommunityModeration)
                    },
                    onNavigateToCommunityPost = { postId ->
                        navController.navigate(CommunityPost(postId))
                    },
                    onNavigateToCommunityCollection = { collectionId ->
                        navController.navigate(CommunityCollection(collectionId))
                    },
                    onLogout = {
                        navController.navigate(AuthGraph) {
                            popUpTo<MainGraph> { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onLogin = {
                        navController.navigate(AuthGraph) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable<Settings> {
                SettingsScreen(
                    viewModel = koinViewModel(),
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable<Notifications> { backStackEntry ->
                val route: Notifications = backStackEntry.toRoute()
                NotificationCenterScreen(
                    onNavigateBack = { navController.popBackStack() },
                    initialTab = route.initialTab,
                    productEdition = ProductEdition.current,
                    onOpenMeeting = { meetingId, hasReport ->
                        if (hasReport) {
                            navController.navigate(Report(meetingId))
                        } else {
                            navController.navigate(Recording(meetingId))
                        }
                    }
                )
            }

            composable<AccountProfile> {
                AccountProfileScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable<AccountQuota> {
                AccountQuotaDetailsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToSettings = { navController.navigate(Settings) }
                )
            }

            composable<AccountPointsPlans> { entry ->
                // Both payment screens share one ViewModel scoped to the main graph,
                // so a payment made on either screen updates the other immediately.
                val mainGraphEntry = remember(entry) { navController.getBackStackEntry(MainGraph) }
                PointsPlansScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToOrders = { navController.navigate(AccountRechargeOrders) },
                    viewModel = koinViewModel(viewModelStoreOwner = mainGraphEntry)
                )
            }

            composable<AccountRechargeOrders> { entry ->
                val mainGraphEntry = remember(entry) { navController.getBackStackEntry(MainGraph) }
                RechargeOrdersScreen(
                    onNavigateBack = { navController.popBackStack() },
                    viewModel = koinViewModel(viewModelStoreOwner = mainGraphEntry)
                )
            }

            composable<AccountInvitation> {
                GrowthCenterScreen(
                    onNavigateBack = { navController.popBackStack() },
                    section = GrowthCenterSection.BENEFITS
                )
            }

            composable<AccountUsers> {
                AccountUserManagementScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable<AccountCommunityModeration> {
                CommunityModerationScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable<CommunityPost> { backStackEntry ->
                val route: CommunityPost = backStackEntry.toRoute()
                CommunityPostDetailScreen(
                    postId = route.postId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable<CommunityCollection> { backStackEntry ->
                val route: CommunityCollection = backStackEntry.toRoute()
                CommunityCollectionDetailScreen(
                    collectionId = route.collectionId,
                    onOpenPost = { postId -> navController.navigate(CommunityPost(postId)) },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable<Recording> { backStackEntry ->
                val route: Recording = backStackEntry.toRoute()
                RecordingScreen(
                    meetingId = route.meetingId,
                    launchAction = com.oa.automation.ui.screen.recording.RecordingLaunchAction.from(
                        route.launchAction
                    ),
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToReport = { id ->
                        navController.navigate(Report(id))
                    },
                    onRequireLogin = {
                        navController.navigate(AuthGraph) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable<Report> { backStackEntry ->
                val route: Report = backStackEntry.toRoute()
                ReportScreen(
                    meetingId = route.meetingId,
                    onNavigateBack = { navController.popBackStack() }
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

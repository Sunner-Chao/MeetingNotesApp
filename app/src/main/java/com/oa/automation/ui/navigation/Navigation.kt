package com.oa.automation.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.oa.automation.ui.screen.home.HomeScreen
import com.oa.automation.ui.screen.recording.RecordingScreen
import com.oa.automation.ui.screen.report.ReportScreen
import com.oa.automation.ui.screen.settings.SettingsScreen
import com.oa.automation.ui.screen.vip.VipScreen
import org.koin.androidx.compose.koinViewModel

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val VIP = "vip"
    const val RECORDING = "recording/{meetingId}"
    const val REPORT = "report/{meetingId}"

    fun recording(meetingId: String) = "recording/$meetingId"
    fun report(meetingId: String) = "report/$meetingId"
}

@Composable
fun OAAutomationNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToRecording = { meetingId ->
                    navController.navigate(Routes.recording(meetingId))
                },
                onNavigateToReport = { meetingId ->
                    navController.navigate(Routes.report(meetingId))
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onNavigateToVip = {
                    navController.navigate(Routes.VIP)
                }
            )
        }

        composable(Routes.VIP) {
            VipScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToRecording = { meetingId ->
                    navController.navigate(Routes.recording(meetingId))
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = koinViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.RECORDING,
            arguments = listOf(navArgument("meetingId") { type = NavType.StringType })
        ) { backStackEntry ->
            val meetingId = backStackEntry.arguments?.getString("meetingId") ?: ""
            RecordingScreen(
                meetingId = meetingId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToReport = { id ->
                    navController.navigate(Routes.report(id))
                }
            )
        }

        composable(
            route = Routes.REPORT,
            arguments = listOf(navArgument("meetingId") { type = NavType.StringType })
        ) { backStackEntry ->
            val meetingId = backStackEntry.arguments?.getString("meetingId") ?: ""
            ReportScreen(
                meetingId = meetingId,
                onNavigateBack = { navController.popBackStack() },
                onContinueRecording = { id ->
                    navController.navigate(Routes.recording(id))
                }
            )
        }
    }
}

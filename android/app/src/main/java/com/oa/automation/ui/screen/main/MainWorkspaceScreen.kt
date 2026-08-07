package com.oa.automation.ui.screen.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oa.automation.ui.screen.account.AccountScreen
import com.oa.automation.ui.screen.community.CommunityScreen
import com.oa.automation.ui.screen.home.HomeLaunchAction
import com.oa.automation.ui.screen.home.HomeScreen
import com.oa.automation.ui.theme.BrandBlue

private enum class MainDestination {
    HOME,
    COMMUNITY,
    ACCOUNT
}

@Composable
fun MainWorkspaceScreen(
    onNavigateToRecording: (String, HomeLaunchAction) -> Unit,
    onNavigateToReport: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToAccountProfile: () -> Unit,
    onNavigateToAccountQuota: () -> Unit,
    onNavigateToAccountUsers: () -> Unit,
    onNavigateToCommunityModeration: () -> Unit,
    onNavigateToCommunityPost: (String) -> Unit,
    onNavigateToCommunityCollection: (String) -> Unit,
    onLogout: () -> Unit
) {
    var destination by rememberSaveable { mutableStateOf(MainDestination.HOME) }
    val stateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = destination != MainDestination.HOME) {
        destination = MainDestination.HOME
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            MainNavigationBar(
                selected = destination,
                onSelect = { destination = it }
            )
        }
    ) { paddingValues ->
        AnimatedContent(
            targetState = destination,
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            transitionSpec = {
                fadeIn(tween(durationMillis = 160)) togetherWith
                    fadeOut(tween(durationMillis = 110))
            },
            label = "mainDestinationContent"
        ) { current ->
            stateHolder.SaveableStateProvider(current.name) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (current) {
                        MainDestination.HOME -> HomeScreen(
                            onNavigateToRecording = onNavigateToRecording,
                            onNavigateToReport = onNavigateToReport,
                            onNavigateToSettings = onNavigateToSettings,
                            onNavigateToNotifications = onNavigateToNotifications
                        )

                        MainDestination.COMMUNITY -> CommunityScreen(
                            onOpenPost = onNavigateToCommunityPost,
                            onOpenCollection = onNavigateToCommunityCollection
                        )

                        MainDestination.ACCOUNT -> AccountScreen(
                            onNavigateToProfile = onNavigateToAccountProfile,
                            onNavigateToQuotaDetails = onNavigateToAccountQuota,
                            onNavigateToUserManagement = onNavigateToAccountUsers,
                            onNavigateToCommunityModeration = onNavigateToCommunityModeration,
                            onNavigateToSettings = onNavigateToSettings,
                            onLogout = onLogout
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainNavigationBar(
    selected: MainDestination,
    onSelect: (MainDestination) -> Unit
) {
    val selectedTint = if (selected == MainDestination.HOME) BrandBlue else MaterialTheme.colorScheme.primary
    val unselectedTint = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp
    ) {
      NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
        NavigationBarItem(
            selected = selected == MainDestination.HOME,
            onClick = { onSelect(MainDestination.HOME) },
            icon = { MeetingNavigationIcon(selected = selected == MainDestination.HOME) },
            label = { Text("会议") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = selectedTint,
                selectedTextColor = selectedTint,
                unselectedIconColor = unselectedTint,
                unselectedTextColor = unselectedTint,
                indicatorColor = Color.Transparent
            )
        )
        NavigationBarItem(
            selected = selected == MainDestination.COMMUNITY,
            onClick = { onSelect(MainDestination.COMMUNITY) },
            icon = {
                Icon(
                    if (selected == MainDestination.COMMUNITY) Icons.Filled.Explore else Icons.Outlined.Explore,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp)
                )
            },
            label = { Text("研学") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = selectedTint,
                selectedTextColor = selectedTint,
                unselectedIconColor = unselectedTint,
                unselectedTextColor = unselectedTint,
                indicatorColor = Color.Transparent
            )
        )
        NavigationBarItem(
            selected = selected == MainDestination.ACCOUNT,
            onClick = { onSelect(MainDestination.ACCOUNT) },
            icon = {
                Icon(
                    if (selected == MainDestination.ACCOUNT) Icons.Filled.Person else Icons.Outlined.Person,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp)
                )
            },
            label = { Text("我的") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = selectedTint,
                selectedTextColor = selectedTint,
                unselectedIconColor = unselectedTint,
                unselectedTextColor = unselectedTint,
                indicatorColor = Color.Transparent
            )
        )
      }
    }
}

@Composable
private fun MeetingNavigationIcon(selected: Boolean) {
    if (selected) {
        Canvas(modifier = Modifier.size(32.dp)) {
            val unit = size.minDimension / 32f
            drawRoundRect(
                color = BrandBlue,
                topLeft = androidx.compose.ui.geometry.Offset(3f * unit, 6f * unit),
                size = androidx.compose.ui.geometry.Size(26f * unit, 24f * unit),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f * unit)
            )
            drawRoundRect(
                color = BrandBlue,
                topLeft = androidx.compose.ui.geometry.Offset(9f * unit, 2f * unit),
                size = androidx.compose.ui.geometry.Size(3f * unit, 8f * unit),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * unit)
            )
            drawRoundRect(
                color = BrandBlue,
                topLeft = androidx.compose.ui.geometry.Offset(20f * unit, 2f * unit),
                size = androidx.compose.ui.geometry.Size(3f * unit, 8f * unit),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * unit)
            )
            val centerX = 16f * unit
            val centerY = 19f * unit
            val radius = 6f * unit
            val inner = 1.7f * unit
            val sparkle = Path().apply {
                moveTo(centerX, centerY - radius)
                lineTo(centerX + inner, centerY - inner)
                lineTo(centerX + radius, centerY)
                lineTo(centerX + inner, centerY + inner)
                lineTo(centerX, centerY + radius)
                lineTo(centerX - inner, centerY + inner)
                lineTo(centerX - radius, centerY)
                lineTo(centerX - inner, centerY - inner)
                close()
            }
            drawPath(sparkle, color = Color.White)
        }
    } else {
        Icon(
            imageVector = Icons.Default.CalendarMonth,
            contentDescription = null,
            modifier = Modifier.size(30.dp)
        )
    }
}

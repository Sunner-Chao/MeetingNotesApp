package com.oa.automation.ui.screen.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.LocalActivity
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.GrowthSystemMessage
import com.oa.automation.ui.component.FirebaseUiTokens
import com.oa.automation.ui.component.ZhiWuScreenBackground
import com.oa.automation.ui.screen.account.GrowthCenterViewModel
import com.oa.automation.ui.screen.account.GrowthCenterScreen
import com.oa.automation.ui.screen.account.GrowthCenterSection
import com.oa.automation.ui.screen.home.HomeViewModel
import com.oa.automation.ui.screen.home.MeetingWithReport
import com.oa.automation.ui.theme.BrandBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.koin.androidx.compose.koinViewModel

private val NotificationGreen = Color(0xFF087A55)

private enum class NotificationCenterTab(val route: String, val label: String) {
    MESSAGES("messages", "通知"),
    ACTIVITIES("activities", "活动"),
    BENEFITS("benefits", "福利群");

    companion object {
        fun fromRoute(value: String): NotificationCenterTab =
            entries.firstOrNull { it.route == value } ?: MESSAGES
    }
}

private enum class NotificationFilter(val label: String) {
    ALL("全部"),
    PENDING("待处理")
}

@Composable
fun NotificationCenterScreen(
    onNavigateBack: () -> Unit,
    onOpenMeeting: (String, Boolean) -> Unit,
    initialTab: String = "messages",
    viewModel: HomeViewModel = koinViewModel(),
    growthViewModel: GrowthCenterViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val growthState by growthViewModel.uiState.collectAsStateWithLifecycle()
    var selectedFilter by rememberSaveable { mutableStateOf(NotificationFilter.ALL) }
    var selectedTab by rememberSaveable(initialTab) {
        mutableStateOf(NotificationCenterTab.fromRoute(initialTab))
    }

    val activeCampaigns = growthState.overview?.campaigns.orEmpty().filter {
        it.status == "active" || it.status == "running"
    }
    val unreadSystemMessages = growthState.systemMessages.filter { it.readAt == null }
    val unreadMeetings = uiState.meetings.filterNot { item ->
        meetingNotificationId(item) in uiState.seenNotificationEvents
    }
    val messageUnreadCount = unreadSystemMessages.size + unreadMeetings.size
    val visibleSystemMessages = growthState.systemMessages.filter { item ->
        selectedFilter == NotificationFilter.ALL || item.readAt == null
    }
    val visibleMeetings = uiState.meetings.take(20).filter { item ->
        selectedFilter == NotificationFilter.ALL ||
            meetingNotificationId(item) !in uiState.seenNotificationEvents
    }
    val activityUnreadCount = activeCampaigns.count { it.id !in growthState.seenCampaignIds }

    LaunchedEffect(selectedTab, activeCampaigns.map { it.id }) {
        if (selectedTab == NotificationCenterTab.ACTIVITIES) {
            growthViewModel.markCampaignsRead(activeCampaigns.map { it.id })
        }
    }

    ZhiWuScreenBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("通知中心", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (selectedTab == NotificationCenterTab.MESSAGES && messageUnreadCount > 0) {
                            TextButton(
                                onClick = {
                                    viewModel.markNotificationsRead()
                                    growthViewModel.markCampaignsRead(activeCampaigns.map { it.id })
                                    growthViewModel.markSystemMessagesRead(
                                        unreadSystemMessages.map { it.id }
                                    )
                                }
                            ) {
                                Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("全部已读")
                            }
                        } else if (selectedTab != NotificationCenterTab.MESSAGES) {
                            IconButton(
                                onClick = growthViewModel::refresh,
                                enabled = !growthState.isLoading
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新活动")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                NotificationCenterTabs(
                    selected = selectedTab,
                    activityUnreadCount = activityUnreadCount,
                    onSelected = { selectedTab = it }
                )
                when (selectedTab) {
                    NotificationCenterTab.MESSAGES -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = FirebaseUiTokens.ScreenPadding,
                            end = FirebaseUiTokens.ScreenPadding,
                            top = 12.dp,
                            bottom = 28.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (visibleMeetings.isNotEmpty()) {
                            item { NotificationSectionTitle("会议动态") }
                            items(visibleMeetings, key = { meetingNotificationId(it) }) { item ->
                                val isUnread = meetingNotificationId(item) !in uiState.seenNotificationEvents
                                NotificationRow(
                                    item = item,
                                    isUnread = isUnread,
                                    onClick = {
                                        viewModel.markNotificationRead(item.meeting.id, item.hasReport)
                                        onOpenMeeting(item.meeting.id, item.hasReport)
                                    }
                                )
                            }
                        }
                        item {
                            NotificationFilterBar(
                                selected = selectedFilter,
                                unreadCount = messageUnreadCount,
                                onSelected = { selectedFilter = it }
                            )
                        }
                        if (visibleSystemMessages.isNotEmpty()) {
                            item { NotificationSectionTitle("系统通知") }
                            items(visibleSystemMessages, key = { it.id }) { item ->
                                GrowthSystemMessageRow(
                                    item = item,
                                    onClick = {
                                        growthViewModel.markSystemMessagesRead(listOf(item.id))
                                        if (!item.campaignId.isNullOrBlank()) {
                                            growthViewModel.openCampaign(item.campaignId)
                                            selectedTab = NotificationCenterTab.ACTIVITIES
                                        }
                                    }
                                )
                            }
                        }
                        if (visibleSystemMessages.isEmpty() && visibleMeetings.isEmpty()) {
                            item {
                                EmptyNotifications(
                                    title = if (selectedFilter == NotificationFilter.PENDING) {
                                        "没有待处理通知"
                                    } else {
                                        "暂无通知"
                                    },
                                    subtitle = "新的会议结果和活动通知会显示在这里"
                                )
                            }
                        }
                    }
                    NotificationCenterTab.ACTIVITIES -> GrowthCenterScreen(
                        section = GrowthCenterSection.ACTIVITIES,
                        embedded = true,
                        viewModel = growthViewModel
                    )
                    NotificationCenterTab.BENEFITS -> GrowthCenterScreen(
                        section = GrowthCenterSection.BENEFITS,
                        embedded = true,
                        viewModel = growthViewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationCenterTabs(
    selected: NotificationCenterTab,
    activityUnreadCount: Int,
    onSelected: (NotificationCenterTab) -> Unit
) {
    TabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = BrandBlue
    ) {
        NotificationCenterTab.entries.forEach { tab ->
            Tab(
                selected = selected == tab,
                onClick = { onSelected(tab) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tab.label, fontWeight = FontWeight.SemiBold)
                        if (tab == NotificationCenterTab.ACTIVITIES && activityUnreadCount > 0) {
                            Spacer(Modifier.width(5.dp))
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.error
                            ) {
                                Text(
                                    activityUnreadCount.coerceAtMost(99).toString(),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    color = MaterialTheme.colorScheme.onError,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun GrowthSystemMessageRow(item: GrowthSystemMessage, onClick: () -> Unit) {
    val isUnread = item.readAt == null
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnread) Color(0xFFF1FAF5) else MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isUnread) Color(0xFF9AD8C4) else MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = NotificationGreen.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.LocalActivity,
                        contentDescription = null,
                        tint = NotificationGreen,
                        modifier = Modifier.size(23.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isUnread) {
                        Spacer(Modifier.width(7.dp))
                        Surface(modifier = Modifier.size(7.dp), shape = CircleShape, color = BrandBlue) {}
                    }
                }
                Text(
                    item.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatBeijingTime(item.createdAt * 1000),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "查看活动",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun NotificationFilterBar(
    selected: NotificationFilter,
    unreadCount: Int,
    onSelected: (NotificationFilter) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NotificationFilter.values().forEach { filter ->
            val isSelected = filter == selected
            Surface(
                onClick = { onSelected(filter) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) BrandBlue else MaterialTheme.colorScheme.surface,
                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Text(
                    text = if (filter == NotificationFilter.PENDING && unreadCount > 0) {
                        "${filter.label} $unreadCount"
                    } else {
                        filter.label
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun NotificationSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 5.dp, bottom = 1.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun NotificationRow(item: MeetingWithReport, isUnread: Boolean, onClick: () -> Unit) {
    val accent = if (item.hasReport) Color(0xFF20AE79) else BrandBlue
    val icon = if (item.hasReport) Icons.Default.CheckCircle else Icons.Default.PendingActions
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnread) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(modifier = Modifier.size(46.dp), shape = CircleShape, color = accent.copy(alpha = 0.12f)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(25.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (item.hasReport) "纪要已生成" else "会议待完善",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium
                    )
                    if (isUnread) {
                        Spacer(Modifier.width(7.dp))
                        Surface(modifier = Modifier.size(7.dp), shape = CircleShape, color = BrandBlue) {}
                    }
                }
                Text(
                    text = item.meeting.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatBeijingTime(item.meeting.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "查看",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun EmptyNotifications(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(modifier = Modifier.size(72.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DoneAll, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun meetingNotificationId(item: MeetingWithReport): String =
    "${item.meeting.id}:${if (item.hasReport) "report" else "meeting"}"

private fun formatBeijingTime(timestamp: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.SIMPLIFIED_CHINESE).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(timestamp))

package com.oa.automation.ui.screen.home

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.R
import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingOrigin
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ScheduledMeeting
import com.oa.automation.domain.model.displayTitle
import com.oa.automation.ui.component.AppLauncherIcon
import com.oa.automation.ui.component.FirebaseUiTokens
import com.oa.automation.ui.component.MeetingCard
import com.oa.automation.ui.component.ZhiWuScreenBackground
import com.oa.automation.ui.theme.BrandBlue
import com.oa.automation.ui.theme.LocalAppIsDarkTheme
import com.oa.automation.infrastructure.notification.requestNotificationPermissionIfNeeded
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

private data class HomeColors(
    val ink: Color,
    val mutedInk: Color,
    val blueTile: Color,
    val mintTile: Color,
    val lilacTile: Color,
    val peachTile: Color,
    val arrowSurface: Color,
    val meetingSurface: Color,
    val completedContainer: Color,
    val pendingContainer: Color,
    val completedContent: Color,
    val pendingContent: Color,
    val emptyIconContainer: Color
)

@Composable
private fun homeColors(): HomeColors {
    val scheme = MaterialTheme.colorScheme
    return if (LocalAppIsDarkTheme.current) {
        HomeColors(
            ink = scheme.onBackground,
            mutedInk = scheme.onSurfaceVariant,
            blueTile = Color(0xFF213547),
            mintTile = Color(0xFF253947),
            lilacTile = Color(0xFF293B49),
            peachTile = Color(0xFF303B44),
            arrowSurface = scheme.surfaceVariant,
            meetingSurface = scheme.surface,
            completedContainer = Color(0xFF193B2B),
            pendingContainer = Color(0xFF403A1A),
            completedContent = Color(0xFF92C9A6),
            pendingContent = Color(0xFFF8D86A),
            emptyIconContainer = Color(0xFF1C3048)
        )
    } else {
        HomeColors(
            ink = Color(0xFF172139),
            mutedInk = Color(0xFF858D9B),
            blueTile = Color(0xFFE5F1FB),
            mintTile = Color(0xFFEAF3F8),
            lilacTile = Color(0xFFEDF3F8),
            peachTile = Color(0xFFF1F3F5),
            arrowSurface = Color.White.copy(alpha = 0.92f),
            meetingSurface = Color.White.copy(alpha = 0.95f),
            completedContainer = Color(0xFFDFF6DD),
            pendingContainer = Color(0xFFFFF4CE),
            completedContent = Color(0xFF0E700E),
            pendingContent = Color(0xFF8A6A00),
            emptyIconContainer = Color(0xFFEDF5FF)
        )
    }
}

private data class HomeLayoutSpec(
    val compact: Boolean,
    val sectionSpacing: Dp,
    val tileHeight: Dp,
    val tileTitleSize: Int,
    val artworkWidth: Dp,
    val artworkHeight: Dp,
    val brandIconSize: Dp,
    val brandTitleSize: Int,
    val greetingSize: Int,
    val recordHeight: Dp,
    val recordIconSize: Dp,
    val recordSpacing: Dp
)

private fun homeLayoutSpec(maxWidth: Dp, maxHeight: Dp): HomeLayoutSpec {
    val compact = maxWidth < 400.dp || maxHeight < 730.dp
    return if (compact) {
        HomeLayoutSpec(
            compact = true,
            sectionSpacing = 8.dp,
            tileHeight = 124.dp,
            tileTitleSize = 18,
            artworkWidth = 62.dp,
            artworkHeight = 78.dp,
            brandIconSize = 34.dp,
            brandTitleSize = 25,
            greetingSize = 24,
            recordHeight = 58.dp,
            recordIconSize = 38.dp,
            recordSpacing = 6.dp
        )
    } else {
        HomeLayoutSpec(
            compact = false,
            sectionSpacing = 12.dp,
            tileHeight = 140.dp,
            tileTitleSize = 19,
            artworkWidth = 68.dp,
            artworkHeight = 86.dp,
            brandIconSize = 38.dp,
            brandTitleSize = 27,
            greetingSize = 26,
            recordHeight = 62.dp,
            recordIconSize = 42.dp,
            recordSpacing = 7.dp
        )
    }
}

@Composable
fun HomeScreen(
    onNavigateToRecording: (String, HomeLaunchAction) -> Unit,
    onNavigateToReport: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    viewModel: HomeViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showMeetingDialog by remember { mutableStateOf(false) }
    var showAllMeetings by remember { mutableStateOf(false) }
    var showClearMeetingsDialog by remember { mutableStateOf(false) }
    var draftMeetingTitle by remember { mutableStateOf("") }
    var draftScheduledAt by remember { mutableStateOf(viewModel.suggestScheduledTime()) }
    val context = LocalContext.current

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(uiState.pendingNavigation) {
        uiState.pendingNavigation?.let { target ->
            onNavigateToRecording(target.meetingId, target.action)
            viewModel.clearPendingMeeting()
        }
    }

    if (showMeetingDialog) {
        ScheduledMeetingDialog(
            title = draftMeetingTitle,
            scheduledAt = draftScheduledAt,
            templates = uiState.reportTemplates,
            onTitleChange = { draftMeetingTitle = it },
            onScheduledAtChange = { draftScheduledAt = it },
            onDismiss = { showMeetingDialog = false },
            onConfirm = { reminderMinutes, templateName ->
                requestNotificationPermissionIfNeeded(context)
                viewModel.scheduleMeeting(
                    title = draftMeetingTitle.trim().ifBlank { viewModel.suggestMeetingTitle("预定会议") },
                    scheduledAt = draftScheduledAt,
                    reminderMinutes = reminderMinutes,
                    templateName = templateName
                )
                showMeetingDialog = false
            }
        )
    }
    if (showClearMeetingsDialog) {
        AlertDialog(
            onDismissRequest = { showClearMeetingsDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            title = { Text("清空会议记录") },
            text = { Text("全部会议、转写和纪要都会被删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearMeetingsDialog = false
                    viewModel.clearAllMeetings()
                }) { Text("全部删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearMeetingsDialog = false }) { Text("取消") } }
        )
    }
    uiState.editingMeetingId?.let {
        EditTitleDialog(
            currentTitle = uiState.editingTitle,
            onDismiss = viewModel::cancelEditTitle,
            onSave = viewModel::saveTitle,
            onTitleChange = viewModel::onTitleEditChange
        )
    }
    if (showAllMeetings) {
        AllMeetingsSheet(
            meetings = uiState.meetings,
            regeneratingMeetingId = uiState.regeneratingMeetingId,
            onDismiss = { showAllMeetings = false },
            onOpen = { item ->
                showAllMeetings = false
                if (item.hasReport) onNavigateToReport(item.meeting.id)
                else onNavigateToRecording(item.meeting.id, item.meeting.resumeLaunchAction())
            },
            onReportClick = { onNavigateToReport(it) },
            onContinueRecording = { meetingId ->
                val meeting = uiState.meetings.firstOrNull { it.meeting.id == meetingId }?.meeting
                onNavigateToRecording(meetingId, meeting?.resumeLaunchAction() ?: HomeLaunchAction.STANDARD)
            },
            onRegenerateReport = viewModel::regenerateReport,
            onDelete = viewModel::deleteMeeting,
            onEdit = viewModel::startEditTitle
        )
    }

    ZhiWuScreenBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { innerPadding ->
                if (!uiState.configLoaded) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                    return@Scaffold
                }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    val layout = homeLayoutSpec(maxWidth, maxHeight)
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxHeight()
                            .fillMaxWidth()
                            .widthIn(max = 560.dp)
                            .padding(
                                start = FirebaseUiTokens.ScreenPadding,
                                end = FirebaseUiTokens.ScreenPadding,
                                top = if (layout.compact) 8.dp else 12.dp,
                                bottom = 8.dp
                            )
                    ) {
                        HomeHeader(
                            showNotificationDot = uiState.hasUnreadNotifications,
                            onNavigateToNotifications = onNavigateToNotifications,
                            layout = layout
                        )
                        Spacer(Modifier.height(layout.sectionSpacing))
                        HomeGreeting(displayName = uiState.displayName, layout = layout)
                        Spacer(Modifier.height(layout.sectionSpacing))
                        QuickActionGrid(
                            layout = layout,
                            onQuickRecording = {
                                viewModel.startNewMeeting(
                                    viewModel.suggestMeetingTitle("快速会议"),
                                    HomeLaunchAction.START_RECORDING
                                )
                            },
                            onImportFile = {
                                viewModel.startNewMeeting(
                                    viewModel.suggestMeetingTitle("文件导入"),
                                    HomeLaunchAction.OPEN_IMPORT
                                )
                            },
                            onCreateMeeting = {
                                draftMeetingTitle = viewModel.suggestMeetingTitle("预定会议")
                                draftScheduledAt = viewModel.suggestScheduledTime()
                                showMeetingDialog = true
                            },
                            onSettings = onNavigateToSettings
                        )
                        if (uiState.scheduledMeetings.isNotEmpty()) {
                            Spacer(Modifier.height(if (layout.compact) 6.dp else 10.dp))
                            UpcomingMeetings(
                                meetings = uiState.scheduledMeetings,
                                onDelete = viewModel::deleteScheduledMeeting,
                                layout = layout
                            )
                        }
                        Spacer(Modifier.height(layout.sectionSpacing))
                        RecentMeetingsHeader(
                            meetingCount = uiState.meetings.size,
                            hasMeetings = uiState.meetings.isNotEmpty(),
                            onShowAll = { showAllMeetings = true },
                            onClearAll = { showClearMeetingsDialog = true },
                            layout = layout
                        )
                        Spacer(Modifier.height(if (layout.compact) 4.dp else 6.dp))
                        if (uiState.meetings.isEmpty()) {
                            EmptyHistory {
                                viewModel.startNewMeeting(
                                    viewModel.suggestMeetingTitle("快速会议"),
                                    HomeLaunchAction.START_RECORDING
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(layout.recordSpacing),
                                contentPadding = PaddingValues(bottom = 4.dp)
                            ) {
                                items(
                                    items = uiState.meetings,
                                    key = { item -> item.meeting.id }
                                ) { item ->
                                        HomeMeetingRow(
                                            item = item,
                                            layout = layout,
                                            onClick = {
                                            if (item.hasReport) onNavigateToReport(item.meeting.id)
                                            else onNavigateToRecording(
                                                item.meeting.id,
                                                item.meeting.resumeLaunchAction()
                                            )
                                        },
                                        onEdit = {
                                            viewModel.startEditTitle(item.meeting.id, item.meeting.displayTitle())
                                        },
                                        onDelete = { viewModel.deleteMeeting(item.meeting.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    showNotificationDot: Boolean,
    onNavigateToNotifications: () -> Unit,
    layout: HomeLayoutSpec
) {
    val colors = homeColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppLauncherIcon(modifier = Modifier.size(layout.brandIconSize), contentDescription = "智悟本")
            Spacer(Modifier.width(11.dp))
            Text(
                text = "智悟本",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = layout.brandTitleSize.sp,
                    lineHeight = (layout.brandTitleSize + 6).sp
                ),
                fontWeight = FontWeight.Bold,
                color = colors.ink
            )
        }
        Box {
            IconButton(onClick = onNavigateToNotifications) {
                Icon(
                    imageVector = Icons.Default.NotificationsNone,
                    contentDescription = "消息通知",
                    tint = colors.ink,
                    modifier = Modifier.size(27.dp)
                )
            }
            if (showNotificationDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-3).dp, y = 4.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(BrandBlue)
                )
            }
        }
    }
}

@Composable
private fun HomeGreeting(displayName: String, layout: HomeLayoutSpec) {
    val colors = homeColors()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = "您好，${displayName.ifBlank { "朋友" }}",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = layout.greetingSize.sp,
                lineHeight = (layout.greetingSize + 8).sp
            ),
            fontWeight = FontWeight.Bold,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formattedToday(),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = colors.mutedInk
        )
    }
}

@Composable
private fun QuickActionGrid(
    layout: HomeLayoutSpec,
    onQuickRecording: () -> Unit,
    onImportFile: () -> Unit,
    onCreateMeeting: () -> Unit,
    onSettings: () -> Unit
) {
    val colors = homeColors()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureTile(
                title = "快速会议",
                subtitle = "一键开始，\n智能转写",
                kind = HomeActionArt.MIC,
                containerColor = colors.blueTile,
                accent = BrandBlue,
                layout = layout,
                onClick = onQuickRecording,
                modifier = Modifier.weight(1f)
            )
            FeatureTile(
                title = "文件导入",
                subtitle = "导入音视频\n智能解析",
                kind = HomeActionArt.FOLDER,
                containerColor = colors.mintTile,
                accent = Color(0xFF106EBE),
                layout = layout,
                onClick = onImportFile,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureTile(
                title = "预定会议",
                subtitle = "安排时间，\n提前提醒",
                kind = HomeActionArt.ADD,
                containerColor = colors.lilacTile,
                accent = Color(0xFF2B88B9),
                layout = layout,
                onClick = onCreateMeeting,
                modifier = Modifier.weight(1f)
            )
            FeatureTile(
                title = "常用设置",
                subtitle = "个性设置，\n专属体验",
                kind = HomeActionArt.SETTINGS,
                containerColor = colors.peachTile,
                accent = Color(0xFF486A8A),
                layout = layout,
                onClick = onSettings,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private enum class HomeActionArt {
    MIC,
    FOLDER,
    ADD,
    SETTINGS
}

@Composable
private fun FeatureTile(
    title: String,
    subtitle: String,
    kind: HomeActionArt,
    containerColor: Color,
    accent: Color,
    layout: HomeLayoutSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = homeColors()
    Card(
        onClick = onClick,
        modifier = modifier.height(layout.tileHeight),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(15.dp)) {
            ActionArtwork(
                kind = kind,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-2).dp, y = (-3).dp)
                    .width(layout.artworkWidth)
                    .height(layout.artworkHeight)
            )
            Column(Modifier.align(Alignment.TopStart)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = layout.tileTitleSize.sp,
                        lineHeight = (layout.tileTitleSize + 6).sp
                    ),
                    fontWeight = FontWeight.Bold,
                    color = colors.ink
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = subtitle,
                    modifier = Modifier.width(76.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = colors.mutedInk,
                    maxLines = 2
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = if (layout.compact) 9.dp else 5.dp)
                    .size(if (layout.compact) 34.dp else 38.dp),
                shape = CircleShape,
                color = colors.arrowSurface
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(if (layout.compact) 20.dp else 22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionArtwork(kind: HomeActionArt, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.graphicsLayer {
            // Restore the slight side perspective of the original artwork while
            // keeping the geometry generated in Compose and free of bitmap debris.
            rotationY = -12f
            rotationX = 4f
            rotationZ = when (kind) {
                HomeActionArt.MIC -> -1.5f
                HomeActionArt.FOLDER -> 1.0f
                HomeActionArt.ADD -> -1.0f
                HomeActionArt.SETTINGS -> 1.5f
            }
            cameraDistance = 12f * density
        }
    ) {
        // Keep every edge inside the canvas. This avoids the isolated alpha pixels
        // that were visible when the old transparent 3D bitmaps met dark cards.
        when (kind) {
            HomeActionArt.MIC -> drawMicArtwork()
            HomeActionArt.FOLDER -> drawFolderArtwork()
            HomeActionArt.ADD -> drawAddArtwork()
            HomeActionArt.SETTINGS -> drawSettingsArtwork()
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMicArtwork() {
    val w = size.width
    val h = size.height
    val centerX = w * 0.50f
    val bodyTop = h * 0.10f
    val bodyWidth = w * 0.42f
    val bodyHeight = h * 0.55f
    val bodyLeft = centerX - bodyWidth / 2f

    drawOval(
        color = Color.Black.copy(alpha = 0.16f),
        topLeft = Offset(w * 0.10f, h * 0.83f),
        size = Size(w * 0.80f, h * 0.11f)
    )
    drawRoundRect(
        color = Color(0xFF83A9E0),
        topLeft = Offset(bodyLeft + w * 0.015f, bodyTop + h * 0.025f),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyWidth / 2f)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFFF5FAFF), Color(0xFFBEDAFF), Color(0xFF8FB8F0))
        ),
        topLeft = Offset(bodyLeft, bodyTop),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyWidth / 2f)
    )
    drawRoundRect(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.48f), Color.Transparent)
        ),
        topLeft = Offset(bodyLeft + bodyWidth * 0.17f, bodyTop + bodyHeight * 0.08f),
        size = Size(bodyWidth * 0.23f, bodyHeight * 0.74f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyWidth * 0.12f)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.34f),
        topLeft = Offset(bodyLeft + bodyWidth * 0.18f, bodyTop + bodyHeight * 0.05f),
        size = Size(bodyWidth * 0.50f, bodyHeight * 0.11f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyWidth * 0.06f)
    )

    val stroke = w * 0.065f
    val frameLeft = centerX - w * 0.33f
    val frameTop = bodyTop + bodyHeight * 0.35f
    val frameWidth = w * 0.66f
    val frameHeight = h * 0.36f
    drawRoundRect(
        color = Color(0xFF9FC4F0),
        topLeft = Offset(frameLeft + w * 0.012f, frameTop + h * 0.018f),
        size = Size(frameWidth, frameHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(frameWidth * 0.25f),
        style = Stroke(width = stroke * 1.12f)
    )
    drawRoundRect(
        color = Color(0xFFF0F7FF),
        topLeft = Offset(frameLeft, frameTop),
        size = Size(frameWidth, frameHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(frameWidth * 0.25f),
        style = Stroke(width = stroke)
    )
    drawLine(
        color = Color(0xFFB8D5F5),
        start = Offset(centerX, frameTop + frameHeight),
        end = Offset(centerX, h * 0.80f),
        strokeWidth = stroke,
        cap = androidx.compose.ui.graphics.StrokeCap.Round
    )
    drawLine(
        color = Color.White.copy(alpha = 0.74f),
        start = Offset(centerX - w * 0.24f, frameTop + frameHeight * 0.28f),
        end = Offset(centerX - w * 0.24f, frameTop + frameHeight * 0.58f),
        strokeWidth = w * 0.025f,
        cap = androidx.compose.ui.graphics.StrokeCap.Round
    )
    drawRoundRect(
        color = Color(0xFF8FAFDC),
        topLeft = Offset(w * 0.23f, h * 0.795f),
        size = Size(w * 0.54f, h * 0.10f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.05f)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFBBD6F6))),
        topLeft = Offset(w * 0.23f, h * 0.78f),
        size = Size(w * 0.54f, h * 0.10f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.05f)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFolderArtwork() {
    val w = size.width
    val h = size.height
    val left = w * 0.12f
    val top = h * 0.26f
    val folderWidth = w * 0.76f
    val folderHeight = h * 0.45f

    drawOval(
        color = Color.Black.copy(alpha = 0.14f),
        topLeft = Offset(w * 0.08f, h * 0.78f),
        size = Size(w * 0.84f, h * 0.10f)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF4F9BC5), Color(0xFF2B6F9B))),
        topLeft = Offset(left + w * 0.025f, top + h * 0.085f),
        size = Size(folderWidth, folderHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFFF0F8FD), Color(0xFFB9DDF5), Color(0xFF72B7DF))),
        topLeft = Offset(left, top + h * 0.04f),
        size = Size(folderWidth, folderHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFFF5FAFD), Color(0xFFCBE7F7))),
        topLeft = Offset(left + w * 0.03f, top),
        size = Size(w * 0.36f, h * 0.18f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
    )
    drawRoundRect(
        color = Color(0xFFB9DDF5).copy(alpha = 0.72f),
        topLeft = Offset(left + w * 0.07f, top + h * 0.18f),
        size = Size(folderWidth * 0.84f, folderHeight * 0.64f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.25f),
        topLeft = Offset(left + w * 0.10f, top + h * 0.24f),
        size = Size(folderWidth * 0.72f, h * 0.055f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.027f)
    )
    drawRoundRect(
        brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.46f), Color.Transparent)),
        topLeft = Offset(left + w * 0.16f, top + h * 0.08f),
        size = Size(w * 0.11f, folderHeight * 0.62f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f)
    )
    drawRoundRect(
        color = Color(0xFF4F96C2),
        topLeft = Offset(w * 0.08f, h * 0.745f),
        size = Size(w * 0.84f, h * 0.10f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.05f)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFCBE7F7))),
        topLeft = Offset(w * 0.08f, h * 0.72f),
        size = Size(w * 0.84f, h * 0.10f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.05f)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAddArtwork() {
    val w = size.width
    val h = size.height
    val tile = w * 0.64f
    val left = (w - tile) / 2f
    val top = h * 0.10f

    drawOval(
        color = Color.Black.copy(alpha = 0.14f),
        topLeft = Offset(w * 0.08f, h * 0.82f),
        size = Size(w * 0.84f, h * 0.09f)
    )
    drawRoundRect(
        color = Color(0xFF005A9E),
        topLeft = Offset(left + w * 0.035f, top + h * 0.035f),
        size = Size(tile, tile),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.13f)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFFF1F8FD), Color(0xFF9FD5F5), Color(0xFF3A96DD))),
        topLeft = Offset(left, top),
        size = Size(tile, tile),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.13f)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.30f),
        topLeft = Offset(left + tile * 0.12f, top + tile * 0.11f),
        size = Size(tile * 0.21f, tile * 0.68f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(tile * 0.10f)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.38f),
        topLeft = Offset(left + tile * 0.12f, top + tile * 0.11f),
        size = Size(tile * 0.70f, tile * 0.11f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(tile * 0.055f)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.92f),
        topLeft = Offset(w * 0.31f, h * 0.30f),
        size = Size(w * 0.38f, h * 0.07f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.035f)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.92f),
        topLeft = Offset(w * 0.465f, h * 0.185f),
        size = Size(w * 0.07f, h * 0.30f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.035f)
    )
    drawRoundRect(
        color = Color(0xFF106EBE),
        topLeft = Offset(w * 0.08f, h * 0.795f),
        size = Size(w * 0.84f, h * 0.10f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.05f)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFB9DDF5))),
        topLeft = Offset(w * 0.08f, h * 0.765f),
        size = Size(w * 0.84f, h * 0.10f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.05f)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSettingsArtwork() {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.50f, h * 0.45f)
    val outerRadius = w * 0.31f

    drawOval(
        color = Color.Black.copy(alpha = 0.14f),
        topLeft = Offset(w * 0.08f, h * 0.82f),
        size = Size(w * 0.84f, h * 0.09f)
    )
    fun drawGearLayer(colors: List<Color>, yOffset: Float) {
        repeat(8) { index ->
            rotate(index * 45f, pivot = center) {
                drawRoundRect(
                    brush = Brush.verticalGradient(colors),
                    topLeft = Offset(center.x - w * 0.075f, center.y - outerRadius - h * 0.05f + yOffset),
                    size = Size(w * 0.15f, h * 0.16f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.07f)
                )
            }
        }
        drawCircle(
            brush = Brush.radialGradient(colors),
            radius = outerRadius,
            center = Offset(center.x, center.y + yOffset)
        )
    }
    drawGearLayer(listOf(Color(0xFF005A9E), Color(0xFF00395D)), h * 0.035f)
    drawGearLayer(listOf(Color(0xFFD9EEF8), Color(0xFF60CDFF)), 0f)
    drawCircle(
        brush = Brush.radialGradient(listOf(Color(0xFFE5F1FB), Color(0xFF60CDFF))),
        radius = outerRadius,
        center = center
    )
    drawCircle(color = Color(0xFF0078D4), radius = w * 0.12f, center = center)
    drawCircle(color = Color(0xFF00395D), radius = w * 0.075f, center = center)
    drawArc(
        color = Color.White.copy(alpha = 0.52f),
        startAngle = 205f,
        sweepAngle = 125f,
        useCenter = false,
        topLeft = Offset(center.x - outerRadius * 0.70f, center.y - outerRadius * 0.70f),
        size = Size(outerRadius * 1.40f, outerRadius * 1.40f),
        style = Stroke(width = w * 0.035f)
    )
    drawRoundRect(
        color = Color(0xFF106EBE),
        topLeft = Offset(w * 0.08f, h * 0.795f),
        size = Size(w * 0.84f, h * 0.10f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.05f)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFB9DDF5))),
        topLeft = Offset(w * 0.08f, h * 0.765f),
        size = Size(w * 0.84f, h * 0.10f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.05f)
    )
}

@Composable
private fun RecentMeetingsHeader(
    meetingCount: Int,
    hasMeetings: Boolean,
    onShowAll: () -> Unit,
    onClearAll: () -> Unit,
    layout: HomeLayoutSpec
) {
    val colors = homeColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "最近记录",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = if (layout.compact) 19.sp else 21.sp
            ),
            fontWeight = FontWeight.Bold,
            color = colors.ink
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasMeetings) {
                IconButton(onClick = onClearAll, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "清空会议记录",
                        tint = colors.mutedInk,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
            if (meetingCount > 3) {
                TextButton(onClick = onShowAll) {
                    Text("查看全部", color = colors.mutedInk)
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = colors.mutedInk,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeMeetingRow(
    item: MeetingWithReport,
    layout: HomeLayoutSpec,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = homeColors()
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val source = item.meeting.origin
    val displayTitle = item.meeting.displayTitle()
    val icon = when (source) {
        MeetingOrigin.QUICK -> Icons.Default.Mic
        MeetingOrigin.SCHEDULED -> Icons.Default.EventAvailable
        MeetingOrigin.FILE_IMPORT -> Icons.Default.FolderOpen
    }
    val iconTint = when (source) {
        MeetingOrigin.QUICK -> BrandBlue
        MeetingOrigin.SCHEDULED -> Color(0xFF2B88B9)
        MeetingOrigin.FILE_IMPORT -> Color(0xFF106EBE)
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除会议") },
            text = { Text("“$displayTitle”及其录音、转写和纪要将被永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "会议操作",
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.meetingSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.recordHeight)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(layout.recordIconSize),
                shape = RoundedCornerShape(12.dp),
                color = iconTint.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = meetingOriginLabel(source),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = iconTint,
                        maxLines = 1
                    )
                    Text(
                        text = meetingMeta(item.meeting),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            val statusContainer = if (item.hasReport) colors.completedContainer else colors.pendingContainer
            val statusContent = if (item.hasReport) colors.completedContent else colors.pendingContent
            Surface(
                shape = RoundedCornerShape(50),
                color = statusContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(statusContent, CircleShape)
                    )
                    Text(
                        text = if (item.hasReport) "已完成" else "待完善",
                        style = MaterialTheme.typography.labelMedium,
                        color = statusContent
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "打开会议",
                tint = colors.mutedInk.copy(alpha = 0.62f),
                modifier = Modifier.size(25.dp)
            )
            Box(modifier = Modifier.size(1.dp)) {
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("修改名称") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory(onStart: () -> Unit) {
    val colors = homeColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = colors.meetingSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = colors.emptyIconContainer) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = BrandBlue,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("还没有会议记录", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                Text("从一段录音开始整理想法", style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
            }
            TextButton(onClick = onStart) { Text("开始") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllMeetingsSheet(
    meetings: List<MeetingWithReport>,
    regeneratingMeetingId: String?,
    onDismiss: () -> Unit,
    onOpen: (MeetingWithReport) -> Unit,
    onReportClick: (String) -> Unit,
    onContinueRecording: (String) -> Unit,
    onRegenerateReport: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (String, String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "全部会议",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = FirebaseUiTokens.ScreenPadding)
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            contentPadding = PaddingValues(
                start = FirebaseUiTokens.ScreenPadding,
                end = FirebaseUiTokens.ScreenPadding,
                bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(meetings, key = { it.meeting.id }) { item ->
                MeetingCard(
                    meeting = item.meeting.copy(title = item.meeting.displayTitle()),
                    hasReport = item.hasReport,
                    isRegenerating = regeneratingMeetingId == item.meeting.id,
                    onClick = { onOpen(item) },
                    onReportClick = { onReportClick(item.meeting.id) },
                    onContinueRecording = { onContinueRecording(item.meeting.id) },
                    onRegenerateReport = { onRegenerateReport(item.meeting.id) },
                    onDelete = { onDelete(item.meeting.id) },
                    onEdit = { onEdit(item.meeting.id, item.meeting.displayTitle()) }
                )
            }
        }
    }
}

@Composable
private fun UpcomingMeetings(
    meetings: List<ScheduledMeeting>,
    onDelete: (String) -> Unit,
    layout: HomeLayoutSpec
) {
    val colors = homeColors()
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.EventAvailable,
                    contentDescription = null,
                    tint = BrandBlue,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "即将开始",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink
                )
            }
            Text(
                text = "已安排 ${meetings.size} 场",
                style = MaterialTheme.typography.labelMedium,
                color = colors.mutedInk
            )
        }
        meetings.take(2).forEach { meeting ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colors.meetingSurface
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 13.dp,
                        vertical = if (layout.compact) 8.dp else 10.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = BrandBlue.copy(alpha = 0.12f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = null,
                            tint = BrandBlue,
                            modifier = Modifier.padding(8.dp).size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = meeting.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = scheduledMeetingMeta(meeting),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.mutedInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { onDelete(meeting.id) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "取消预定",
                            tint = colors.mutedInk
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduledMeetingDialog(
    title: String,
    scheduledAt: Long,
    templates: List<PresetReportTemplate>,
    onTitleChange: (String) -> Unit,
    onScheduledAtChange: (Long) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (reminderMinutes: Int, templateName: String?) -> Unit
) {
    val context = LocalContext.current
    var reminderExpanded by remember { mutableStateOf(false) }
    var templateExpanded by remember { mutableStateOf(false) }
    var reminderMinutes by remember { mutableStateOf(15) }
    var templateName by remember { mutableStateOf(templates.firstOrNull()?.name) }
    val calendar = Calendar.getInstance().apply { timeInMillis = scheduledAt }
    val dateText = SimpleDateFormat("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE).format(calendar.time)
    val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(calendar.time)
    val canConfirm = title.isNotBlank() && scheduledAt > System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Event, contentDescription = null) },
        title = { Text("预定会议") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("会议标题") },
                    singleLine = true
                )
                Text(
                    text = "会议时间",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    val next = Calendar.getInstance().apply {
                                        timeInMillis = scheduledAt
                                        set(Calendar.YEAR, year)
                                        set(Calendar.MONTH, month)
                                        set(Calendar.DAY_OF_MONTH, day)
                                    }
                                    onScheduledAtChange(next.timeInMillis)
                                },
                                calendar.get(Calendar.YEAR),
                                calendar.get(Calendar.MONTH),
                                calendar.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(dateText, maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    val next = Calendar.getInstance().apply {
                                        timeInMillis = scheduledAt
                                        set(Calendar.HOUR_OF_DAY, hour)
                                        set(Calendar.MINUTE, minute)
                                        set(Calendar.SECOND, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }
                                    onScheduledAtChange(next.timeInMillis)
                                },
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                                true
                            ).show()
                        },
                        modifier = Modifier.weight(0.72f)
                    ) { Text(timeText) }
                }
                Box {
                    OutlinedButton(
                        onClick = { reminderExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("提醒：${reminderLabel(reminderMinutes)}")
                    }
                    DropdownMenu(
                        expanded = reminderExpanded,
                        onDismissRequest = { reminderExpanded = false }
                    ) {
                        listOf(5, 15, 30, 60, 0).forEach { minutes ->
                            DropdownMenuItem(
                                text = { Text(reminderLabel(minutes)) },
                                onClick = {
                                    reminderMinutes = minutes
                                    reminderExpanded = false
                                }
                            )
                        }
                    }
                }
                if (templates.isNotEmpty()) {
                    Box {
                        OutlinedButton(
                            onClick = { templateExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "纪要模板：${templateName ?: "跟随默认"}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = templateExpanded,
                            onDismissRequest = { templateExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("跟随默认") },
                                onClick = {
                                    templateName = null
                                    templateExpanded = false
                                }
                            )
                            templates.forEach { template ->
                                DropdownMenuItem(
                                    text = { Text(template.name) },
                                    onClick = {
                                        templateName = template.name
                                        templateExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "到点后可直接进入快速会议，纪要模板会沿用本次预定设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reminderMinutes, templateName) },
                enabled = canConfirm
            ) { Text("保存预定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun EditTitleDialog(currentTitle: String, onDismiss: () -> Unit, onSave: () -> Unit, onTitleChange: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改会议名称") },
        text = { OutlinedTextField(currentTitle, onTitleChange, Modifier.fillMaxWidth(), label = { Text("会议名称") }, singleLine = true) },
        confirmButton = { Button(onClick = onSave, enabled = currentTitle.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun formattedToday(): String = SimpleDateFormat("yyyy年M月d日  EEEE", Locale.SIMPLIFIED_CHINESE).format(Date())

private fun scheduledMeetingMeta(meeting: ScheduledMeeting): String {
    val date = SimpleDateFormat("M月d日  HH:mm", Locale.SIMPLIFIED_CHINESE)
        .format(Date(meeting.scheduledAt))
    val reminder = reminderLabel(meeting.reminderMinutes)
    return if (meeting.templateName.isNullOrBlank()) "$date · $reminder" else "$date · $reminder · ${meeting.templateName}"
}

private fun reminderLabel(minutes: Int): String = when (minutes) {
    0 -> "不提醒"
    60 -> "提前 1 小时"
    else -> "提前 $minutes 分钟"
}

private fun meetingMeta(meeting: Meeting): String {
    val date = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(meeting.createdAt))
    return if (meeting.durationMs > 0L) "$date  |  ${formatDuration(meeting.durationMs)}" else date
}

internal fun meetingOriginLabel(origin: MeetingOrigin): String = when (origin) {
    MeetingOrigin.QUICK -> "快速"
    MeetingOrigin.SCHEDULED -> "预定"
    MeetingOrigin.FILE_IMPORT -> "导入"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

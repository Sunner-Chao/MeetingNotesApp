package com.oa.automation.ui.screen.report

import android.app.Activity
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Expand
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.ReportTitleResolver
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudio
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudioPlaybackSource
import com.oa.automation.infrastructure.image.OrientedImageDecoder
import com.oa.automation.ui.component.FlowingProgressBorder
import com.oa.automation.ui.theme.LocalAppIsDarkTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The coordinates below are calibrated from redesigned_meeting_summary_ui.png
 * after removing the device frame. On a 360 x 796 reference viewport the
 * sequence is: title 95, audio 140, images 250, report 445, controls 702.
 */
private val ReferenceInk = Color(0xFFF7F8FF)
private val ReferenceMuted = Color(0xFFD6DBEA)
private val ReferenceBorder = Color.White.copy(alpha = 0.36f)
private val ReferenceGlassTop = Color.White.copy(alpha = 0.22f)
private val ReferenceGlassBottom = Color.White.copy(alpha = 0.105f)
private val ReferenceLavender = Color(0xFFC8A4FF)
private val ReferenceSky = Color(0xFF78B8FF)
private val ReferencePink = Color(0xFFFFA9D9)
private val ReferenceMint = Color(0xFF9DE7D5)
private val ReferenceStatusBar = Color(0xFF153850)

private data class ReferenceMetrics(
    val horizontalInset: Dp,
    val topGap: Dp,
    val itemGap: Dp,
    val headerHeight: Dp,
    val audioHeight: Dp,
    val imageHeight: Dp,
    val reportHeight: Dp,
    val controlHeight: Dp,
    val controlBottom: Dp
)

private fun referenceMetrics(maxHeight: Dp) = if (maxHeight < 700.dp) {
    ReferenceMetrics(10.dp, 4.dp, 6.dp, 44.dp, 94.dp, 178.dp, 224.dp, 68.dp, 12.dp)
} else {
    ReferenceMetrics(10.dp, 5.dp, 7.dp, 47.dp, 104.dp, 204.dp, 249.dp, 74.dp, 18.dp)
}

@Composable
internal fun ReportReferenceFrame(content: @Composable () -> Unit) {
    ReferenceSystemBars()
    Box(modifier = Modifier.fillMaxSize()) {
        ReferenceBackdrop(modifier = Modifier.fillMaxSize())
        content()
    }
}

@Composable
private fun ReferenceSystemBars() {
    val view = LocalView.current
    val restoreLightIcons = !LocalAppIsDarkTheme.current
    DisposableEffect(view, restoreLightIcons) {
        val window = (view.context as? Activity)?.window
        val previousStatus = window?.statusBarColor
        val previousNavigation = window?.navigationBarColor
        if (window != null) {
            window.statusBarColor = ReferenceStatusBar.toArgb()
            window.navigationBarColor = Color(0xFF0E1D41).toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        onDispose {
            if (window != null && previousStatus != null && previousNavigation != null) {
                window.statusBarColor = previousStatus
                window.navigationBarColor = previousNavigation
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = restoreLightIcons
                    isAppearanceLightNavigationBars = restoreLightIcons
                }
            }
        }
    }
}

@Composable
private fun ReferenceBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF123655),
                    Color(0xFF335F8B),
                    Color(0xFF253C78),
                    Color(0xFF331E60),
                    Color(0xFF112A50)
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF9CE7DA).copy(alpha = .58f), Color.Transparent),
                center = Offset(size.width * .47f, size.height * .22f),
                radius = size.width * .58f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFFFB6D8).copy(alpha = .56f), Color.Transparent),
                center = Offset(size.width * 1.02f, size.height * .60f),
                radius = size.width * .72f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF83C6F0).copy(alpha = .42f), Color.Transparent),
                center = Offset(size.width * .09f, size.height * .79f),
                radius = size.width * .68f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFAC84F5).copy(alpha = .42f), Color.Transparent),
                center = Offset(size.width * .52f, size.height * .93f),
                radius = size.width * .64f
            )
        )
    }
}

@Composable
internal fun ReportReferenceTopBar(
    title: String,
    reportAvailable: Boolean,
    optimizeActive: Boolean,
    onNavigateBack: () -> Unit,
    onSave: () -> Unit,
    onOptimize: () -> Unit,
    onShare: () -> Unit,
    shareMenu: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        ReferenceCircleButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回上一页",
            modifier = Modifier.align(Alignment.CenterStart),
            onClick = onNavigateBack
        )
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            ReferenceCircleButton(
                icon = Icons.Default.MoreHoriz,
                contentDescription = "更多操作",
                onClick = onShare
            )
            shareMenu()
        }
    }
}

@Composable
private fun ReferenceCircleButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.size(34.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = .18f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .22f))
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription, tint = ReferenceInk, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
internal fun ReportReferenceContent(
    uiState: ReportUiState,
    onContinueRecording: () -> Unit,
    onToggleTranscript: () -> Unit,
    onExportTranscript: () -> Unit,
    onSelectTemplate: (PresetReportTemplate) -> Unit,
    onRegenerateWithTemplate: () -> Unit,
    onDeleteAttachment: (MeetingAttachment) -> Unit,
    onRefreshAudio: () -> Unit,
    onPrepareAudioPlayback: suspend (ArchivedMeetingAudio) -> Result<ArchivedMeetingAudioPlaybackSource>,
    onShareAudio: (ArchivedMeetingAudio) -> Unit,
    onDeleteAudio: (ArchivedMeetingAudio) -> Unit,
    onShare: () -> Unit,
    showTemplatePicker: Boolean,
    onShowTemplatePicker: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val report = uiState.report ?: return
    var showTemplatePreview by remember { mutableStateOf(false) }
    var previewTemplate by remember { mutableStateOf<PresetReportTemplate?>(null) }

    if (showTemplatePicker) {
        TemplatePickerDialog(
            templates = uiState.presetTemplates,
            selectedTemplateName = uiState.reportTemplate.selectedName,
            onSelect = {
                onSelectTemplate(it)
                onShowTemplatePicker(false)
            },
            onPreview = {
                previewTemplate = it
                showTemplatePreview = true
            },
            onDismiss = { onShowTemplatePicker(false) }
        )
    }
    if (showTemplatePreview) {
        previewTemplate?.let { TemplatePreviewDialog(template = it, onDismiss = { showTemplatePreview = false }) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val metrics = referenceMetrics(maxHeight)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = metrics.horizontalInset,
                top = metrics.topGap,
                end = metrics.horizontalInset,
                bottom = metrics.controlHeight + metrics.controlBottom + 18.dp
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.itemGap)
        ) {
            item {
                ReferenceSummaryHeader(
                    title = ReportTitleResolver.resolve(report, uiState.meetingTitle),
                    createdAt = uiState.meetingCreatedAt.takeIf { it > 0L } ?: report.generatedAt,
                    durationMs = uiState.archivedAudio.firstOrNull()?.durationSec
                            ?.times(1_000.0)
                            ?.toLong()
                            ?.takeIf { it > 0L }
                        ?: uiState.meetingDurationMs,
                    height = metrics.headerHeight
                )
            }
            item {
                ReferenceAudioCard(
                    audio = uiState.archivedAudio.firstOrNull(),
                    isLoading = uiState.isLoadingAudio,
                    height = metrics.audioHeight,
                    onRefresh = onRefreshAudio,
                    onPreparePlayback = onPrepareAudioPlayback,
                    onShare = { uiState.archivedAudio.firstOrNull()?.let(onShareAudio) },
                    onDelete = { uiState.archivedAudio.firstOrNull()?.let(onDeleteAudio) }
                )
            }
            item {
                ReferenceImagesCard(
                    attachments = uiState.attachments,
                    height = metrics.imageHeight,
                    onDelete = onDeleteAttachment
                )
            }
            item {
                ReferenceReportCard(
                    report = report,
                    initiatorName = uiState.initiatorName,
                    initiatorAvatarDataUrl = uiState.initiatorAvatarDataUrl,
                    isProcessing = uiState.isGenerating,
                    height = metrics.reportHeight
                )
            }
            if (uiState.showTranscript && uiState.transcriptText.isNotBlank()) {
                item {
                    ReferenceTranscriptCard(
                        text = uiState.transcriptText,
                        onCollapse = onToggleTranscript,
                        onExport = onExportTranscript
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = metrics.controlBottom),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReferenceBottomAction(
                icon = Icons.Default.Mic,
                label = "继续录制",
                height = metrics.controlHeight,
                onClick = onContinueRecording
            )
            Spacer(Modifier.width(42.dp))
            ReferenceBottomAction(
                icon = Icons.Default.Share,
                label = "分享",
                height = metrics.controlHeight,
                onClick = onShare
            )
        }
    }
}

@Composable
private fun ReferenceSummaryHeader(title: String, createdAt: Long, durationMs: Long, height: Dp) {
    Column(
        modifier = Modifier.fillMaxWidth().height(height),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = ReferenceInk,
                fontSize = 20.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = .15f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .28f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = ReferenceInk, modifier = Modifier.size(13.dp))
                    Text("生成完成", color = ReferenceInk, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Text(
            text = formatMeetingMeta(createdAt, durationMs),
            color = ReferenceMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReferenceAudioCard(
    audio: ArchivedMeetingAudio?,
    isLoading: Boolean,
    height: Dp,
    onRefresh: () -> Unit,
    onPreparePlayback: suspend (ArchivedMeetingAudio) -> Result<ArchivedMeetingAudioPlaybackSource>,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var moreExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPreparing by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableStateOf(0L) }
    var playbackDurationMs by remember { mutableStateOf(0L) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    fun releasePlayer() {
        mediaPlayer?.let { player -> runCatching { player.release() } }
        mediaPlayer = null
        isPreparing = false
        isPrepared = false
        isPlaying = false
        playbackPositionMs = 0L
        playbackDurationMs = 0L
    }

    fun togglePlayback() {
        val selectedAudio = audio ?: return
        val currentPlayer = mediaPlayer
        if (currentPlayer != null && isPrepared) {
            if (isPlaying) {
                runCatching { currentPlayer.pause() }
                isPlaying = false
            } else {
                runCatching { currentPlayer.start() }
                    .onSuccess { isPlaying = true }
                    .onFailure { playbackError = it.message ?: "播放失败" }
            }
            return
        }
        if (isPreparing) return

        isPreparing = true
        playbackError = null
        scope.launch {
            onPreparePlayback(selectedAudio).fold(
                onSuccess = { source ->
                    val player = MediaPlayer()
                    runCatching {
                        player.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        player.setDataSource(context, source.uri, source.headers)
                        player.setOnPreparedListener { prepared ->
                            isPreparing = false
                            isPrepared = true
                            playbackDurationMs = prepared.duration.toLong().coerceAtLeast(0L)
                            prepared.start()
                            isPlaying = true
                        }
                        player.setOnCompletionListener { completed ->
                            isPlaying = false
                            runCatching { completed.seekTo(0) }
                            playbackPositionMs = 0L
                        }
                        player.setOnErrorListener { failed, _, _ ->
                            if (mediaPlayer === failed) releasePlayer()
                            playbackError = "音频播放失败，请重试"
                            true
                        }
                        mediaPlayer = player
                        player.prepareAsync()
                    }.onFailure { error ->
                        runCatching { player.release() }
                        if (mediaPlayer === player) mediaPlayer = null
                        isPreparing = false
                        isPrepared = false
                        playbackError = error.message ?: "音频播放失败"
                    }
                },
                onFailure = { error ->
                    isPreparing = false
                    playbackError = error.message ?: "无法获取会议音频"
                }
            )
        }
    }

    DisposableEffect(audio?.id) {
        onDispose { releasePlayer() }
    }
    LaunchedEffect(mediaPlayer, isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let { player ->
                playbackPositionMs = runCatching { player.currentPosition.toLong() }
                    .getOrDefault(playbackPositionMs)
                val measuredDuration = runCatching { player.duration.toLong() }.getOrDefault(0L)
                if (measuredDuration > 0L) playbackDurationMs = measuredDuration
            }
            delay(250)
        }
    }

    val knownDurationMs = playbackDurationMs.takeIf { it > 0L }
        ?: audio?.durationSec?.times(1_000.0)?.toLong()?.coerceAtLeast(0L)
        ?: 0L
    val playbackProgress = if (knownDurationMs > 0L) {
        (playbackPositionMs.toFloat() / knownDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("删除会议音频") },
            text = { Text("音频将从服务器永久删除，确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("取消") } }
        )
    }
    ReferenceGlassCard(modifier = Modifier.fillMaxWidth().height(height)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, null, tint = ReferenceInk, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(7.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("会议音频", color = ReferenceInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = when {
                        playbackError != null -> playbackError.orEmpty()
                        isPreparing -> "正在准备播放"
                        isPrepared && knownDurationMs > 0L -> {
                            "${formatAudioDuration(playbackPositionMs / 1_000.0)} / " +
                                formatAudioDuration(knownDurationMs / 1_000.0)
                        }
                        audio != null -> audio.durationSec?.let(::formatAudioDuration) ?: "时长读取中"
                        isLoading -> "正在加载"
                        else -> "暂无音频"
                    },
                    color = if (playbackError == null) ReferenceMuted else ReferencePink,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            Box {
                IconButton(onClick = { moreExpanded = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Expand, "音频操作", tint = ReferenceInk, modifier = Modifier.size(17.dp))
                }
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("刷新音频") },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        enabled = !isLoading,
                        onClick = { moreExpanded = false; onRefresh() }
                    )
                    if (audio != null) {
                        DropdownMenuItem(
                            text = { Text("分享音频") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = { moreExpanded = false; onShare() }
                        )
                        DropdownMenuItem(
                            text = { Text("删除音频", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { moreExpanded = false; showDeleteConfirmation = true }
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = .96f),
                modifier = Modifier
                    .size(32.dp)
                    .clickable(enabled = audio != null && !isLoading, onClick = ::togglePlayback)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        isPreparing -> CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            color = Color(0xFF4D5E85),
                            strokeWidth = 2.dp
                        )
                        isPlaying -> Icon(
                            Icons.Default.Pause,
                            "暂停会议音频",
                            tint = Color(0xFF4D5E85),
                            modifier = Modifier.size(19.dp)
                        )
                        else -> Icon(
                            Icons.Default.PlayArrow,
                            "播放会议音频",
                            tint = Color(0xFF4D5E85),
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(9.dp))
            ReferenceWaveform(
                progress = playbackProgress,
                playbackActive = isPrepared || isPreparing,
                modifier = Modifier.weight(1f).height(42.dp)
            )
        }
    }
}

@Composable
private fun ReferenceWaveform(
    progress: Float,
    playbackActive: Boolean,
    modifier: Modifier = Modifier
) {
    val bars = remember {
        listOf(10, 16, 12, 24, 18, 30, 16, 38, 23, 15, 31, 18, 40, 25, 15, 34, 20, 42, 28, 16, 37, 25, 44, 22, 18, 32, 27, 20, 38, 17, 31, 22, 28, 18, 34, 20, 26, 17, 30, 20)
    }
    Canvas(modifier = modifier) {
        val step = size.width / bars.size
        bars.forEachIndexed { index, halfHeight ->
            val barColor = when {
                index < bars.size / 3 -> ReferenceSky
                index < bars.size * 2 / 3 -> ReferenceLavender
                else -> ReferencePink
            }
            val x = step * index + step / 2f
            val played = index.toFloat() / bars.lastIndex.coerceAtLeast(1) <= progress
            drawLine(
                color = barColor.copy(alpha = if (!playbackActive || played) 1f else .24f),
                start = Offset(x, size.height / 2f - halfHeight),
                end = Offset(x, size.height / 2f + halfHeight),
                strokeWidth = 2.4f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun ReferenceImagesCard(
    attachments: List<MeetingAttachment>,
    height: Dp,
    onDelete: (MeetingAttachment) -> Unit
) {
    var galleryIndex by remember { mutableStateOf<Int?>(null) }
    galleryIndex?.let { selectedIndex ->
        ReferenceImageGalleryDialog(
            attachments = attachments,
            initialIndex = selectedIndex,
            onDelete = onDelete,
            onDismiss = { galleryIndex = null }
        )
    }
    ReferenceGlassCard(modifier = Modifier.fillMaxWidth().height(height)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Image, null, tint = ReferenceInk, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("会议图片", color = ReferenceInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                text = "查看全部  ›",
                color = if (attachments.isEmpty()) ReferenceMuted.copy(alpha = .55f) else ReferenceMuted,
                fontSize = 10.sp,
                modifier = Modifier.clickable(enabled = attachments.isNotEmpty()) { galleryIndex = 0 }
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            repeat(2) { rowIndex ->
                Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(3) { columnIndex ->
                        val attachment = attachments.getOrNull(rowIndex * 3 + columnIndex)
                        if (attachment == null) {
                            ReferenceImagePlaceholder(modifier = Modifier.weight(1f).fillMaxHeight())
                        } else {
                            ReferenceAttachmentCell(
                                attachment = attachment,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onOpen = { galleryIndex = rowIndex * 3 + columnIndex },
                                onDelete = { onDelete(attachment) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceImagePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = .075f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Image, null, tint = ReferenceInk.copy(alpha = .34f), modifier = Modifier.size(16.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReferenceAttachmentCell(
    attachment: MeetingAttachment,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val bitmap = remember(attachment.localPath) {
        OrientedImageDecoder.decode(File(attachment.localPath), maximumDimension = 720)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onOpen, onLongClickLabel = "删除图片", onLongClick = onDelete)
    ) {
        if (bitmap == null) {
            ReferenceImagePlaceholder(Modifier.fillMaxSize())
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ReferenceImageGalleryDialog(
    attachments: List<MeetingAttachment>,
    initialIndex: Int,
    onDelete: (MeetingAttachment) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIndex by remember(initialIndex, attachments) {
        mutableStateOf(initialIndex.coerceIn(0, (attachments.lastIndex).coerceAtLeast(0)))
    }
    val selected = attachments.getOrNull(selectedIndex)
    val selectedBitmap = remember(selected?.localPath) {
        selected?.let { OrientedImageDecoder.decode(File(it.localPath), maximumDimension = 1_600) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(.94f)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF17284C),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .28f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("会议图片", color = ReferenceInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${selectedIndex + 1}/${attachments.size}", color = ReferenceMuted, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Close, "关闭图片浏览", tint = ReferenceInk, modifier = Modifier.size(19.dp))
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(370.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = .22f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedBitmap != null) {
                        Image(
                            bitmap = selectedBitmap.asImageBitmap(),
                            contentDescription = selected?.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        ReferenceImagePlaceholder(Modifier.fillMaxSize())
                    }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(attachments, key = { _, item -> item.id }) { index, item ->
                        val thumbnail = remember(item.localPath) {
                            OrientedImageDecoder.decode(File(item.localPath), maximumDimension = 240)
                        }
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color.White.copy(alpha = .1f))
                                .border(
                                    width = if (index == selectedIndex) 2.dp else 1.dp,
                                    color = if (index == selectedIndex) ReferencePink else Color.White.copy(alpha = .2f),
                                    shape = RoundedCornerShape(9.dp)
                                )
                                .clickable { selectedIndex = index }
                        ) {
                            if (thumbnail != null) {
                                Image(
                                    bitmap = thumbnail.asImageBitmap(),
                                    contentDescription = item.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
                if (selected != null) {
                    TextButton(
                        onClick = {
                            onDelete(selected)
                            if (attachments.size <= 1) onDismiss()
                            else selectedIndex = selectedIndex.coerceAtMost(attachments.lastIndex - 1)
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.DeleteOutline, null, tint = ReferencePink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除图片", color = ReferencePink, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceReportCard(
    report: Report,
    initiatorName: String,
    initiatorAvatarDataUrl: String?,
    isProcessing: Boolean,
    height: Dp
) {
    val points = remember(report) { reportPreviewPoints(report) }
    val attendees = remember(initiatorName) { listOf(initiatorName.trim()).filter(String::isNotBlank) }
    var showAttendees by remember { mutableStateOf(false) }
    if (showAttendees) {
        ReferenceAttendeeDialog(
            attendees = attendees,
            avatarDataUrl = initiatorAvatarDataUrl,
            onDismiss = { showAttendees = false }
        )
    }
    FlowingProgressBorder(
        active = isProcessing,
        modifier = Modifier.fillMaxWidth().height(height),
        cornerRadius = 22.dp,
        inset = 2.dp,
        strokeWidth = 2.dp,
        colors = listOf(ReferenceMint, ReferencePink, ReferenceLavender, ReferenceSky)
    ) {
    ReferenceGlassCard(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, null, tint = ReferenceInk, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("会议纪要", color = ReferenceInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
        }
        val contentScrollState = rememberScrollState()
        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 7.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 8.dp)
                    .verticalScroll(contentScrollState),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ReferenceSectionLabel("会议主题")
                Text(
                    text = reportPreviewSummary(report),
                    color = ReferenceInk.copy(alpha = .91f),
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                ReferenceSectionLabel("关键要点")
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    points.forEachIndexed { index, point ->
                        ReferencePointRow(point = point, index = index)
                    }
                    if (points.isEmpty()) ReferencePointRow(point = "暂无关键要点", index = 0)
                }
            }
            ReferenceVerticalScrollIndicator(
                scrollState = contentScrollState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("参会人员（${attendees.size}人）", color = ReferenceMuted, fontSize = 10.sp)
            if (attendees.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                ReferenceAttendeeStrip(attendees, initiatorAvatarDataUrl)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "查看全部  ›",
                color = ReferenceMuted,
                fontSize = 10.sp,
                modifier = Modifier.clickable { showAttendees = true }
            )
        }
    }
    }
}

@Composable
private fun ReferenceSectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ReferenceLavender)
        )
        Text(text, color = ReferencePink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ReferencePointRow(point: String, index: Int) {
    val pointColor = listOf(ReferencePink, Color(0xFFFFE6A6), ReferenceMint, Color(0xFFAEBBFF))[index % 4]
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(pointColor)
        )
        Text(
            text = point.cleanReportText(),
            color = ReferenceMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ReferenceAttendeeStrip(attendees: List<String>, avatarDataUrl: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        attendees.forEach { attendee ->
            ReferenceAttendeeAvatar(
                name = attendee,
                avatarDataUrl = avatarDataUrl,
                size = 20.dp,
                showBorder = true
            )
        }
    }
}

@Composable
private fun ReferenceAttendeeDialog(
    attendees: List<String>,
    avatarDataUrl: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("参会人员") },
        text = {
            if (attendees.isEmpty()) {
                Text("暂未记录参会人员信息")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    attendees.forEach { attendee ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ReferenceAttendeeAvatar(
                                name = attendee,
                                avatarDataUrl = avatarDataUrl,
                                size = 32.dp,
                                showBorder = false
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(attendee)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ReferenceAttendeeAvatar(
    name: String,
    avatarDataUrl: String?,
    size: Dp,
    showBorder: Boolean
) {
    val image = remember(avatarDataUrl) {
        runCatching {
            val encoded = avatarDataUrl
                ?.substringAfter("base64,", "")
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = ReferenceSky,
        border = if (showBorder) BorderStroke(1.dp, Color.White.copy(alpha = .62f)) else null
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "${name}的头像",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    name.take(1),
                    color = Color.White,
                    fontSize = if (size <= 20.dp) 8.sp else 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ReferenceVerticalScrollIndicator(scrollState: ScrollState, modifier: Modifier = Modifier) {
    if (scrollState.maxValue <= 0) return
    Canvas(modifier = modifier.width(3.dp).fillMaxHeight()) {
        val viewport = size.height
        val contentHeight = viewport + scrollState.maxValue
        val minThumb = 24.dp.toPx()
        val thumbHeight = (viewport * viewport / contentHeight).coerceIn(minThumb, viewport)
        val top = (viewport - thumbHeight) * scrollState.value / scrollState.maxValue
        drawRoundRect(
            color = Color.White.copy(alpha = .7f),
            topLeft = Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(size.width, thumbHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f)
        )
    }
}

@Composable
private fun ReferenceTranscriptCard(text: String, onCollapse: () -> Unit, onExport: () -> Unit) {
    ReferenceGlassCard(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("转写原文", color = ReferenceInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onExport, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("分享", color = ReferencePink, fontSize = 11.sp)
            }
            IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "收起转写原文", tint = ReferenceMuted, modifier = Modifier.size(16.dp))
            }
        }
        SelectionContainer {
            Text(
                text = text,
                color = ReferenceMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.heightIn(max = 280.dp).padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ReferenceGlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(ReferenceGlassTop, ReferenceGlassBottom),
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            )
            .border(1.dp, ReferenceBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize(), content = content)
    }
}

@Composable
private fun ReferenceBottomAction(
    icon: ImageVector,
    label: String,
    height: Dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .width(102.dp)
            .height(height),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = 2.dp.toPx()
            drawRoundRect(
                brush = Brush.linearGradient(listOf(ReferenceSky.copy(alpha = .95f), ReferencePink.copy(alpha = .95f))),
                topLeft = Offset(inset, inset),
                size = size.copy(width = size.width - inset * 2, height = size.height - inset * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                style = Stroke(width = 2.dp.toPx())
            )
            drawRoundRect(
                color = ReferencePink.copy(alpha = .22f),
                topLeft = Offset(-8.dp.toPx(), -8.dp.toPx()),
                size = size.copy(width = size.width + 16.dp.toPx(), height = size.height + 16.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f + 10.dp.toPx()),
                style = Stroke(width = 8.dp.toPx())
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            shape = shape,
            color = Color(0xFF536AC7).copy(alpha = .43f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .70f))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, label, tint = ReferenceInk, modifier = Modifier.size(27.dp))
                Spacer(Modifier.height(2.dp))
                Text(label, color = ReferenceInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun reportPreviewSummary(report: Report): String = report.summary.cleanReportText().ifBlank {
    report.rawContent.lineSequence()
        .map(String::cleanReportText)
        .firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("|") }
        .orEmpty()
}.ifBlank { "本次会议已完成记录，暂无主题摘要。" }

private fun reportPreviewPoints(report: Report): List<String> {
    val structured = (report.keyPoints + report.decisions + report.actionItems + report.tasks.map { it.content })
        .map(String::cleanReportText)
        .filter(String::isNotBlank)
    if (structured.isNotEmpty()) return structured.distinct()
    return report.rawContent.lineSequence()
        .map(String::cleanReportText)
        .filter { it.isNotBlank() && it.length > 3 && !it.startsWith("#") && !it.startsWith("|") }
        .distinct()
        .take(4)
        .toList()
}

private fun String.cleanReportText(): String = trim()
    .removePrefix("-")
    .removePrefix("*")
    .replace("**", "")
    .replace("__", "")
    .trim()

private fun formatMeetingMeta(createdAt: Long, durationMs: Long): String {
    val date = Date(createdAt)
    val today = Date()
    val isToday = SimpleDateFormat("yyyyMMdd", Locale.SIMPLIFIED_CHINESE).format(date) ==
        SimpleDateFormat("yyyyMMdd", Locale.SIMPLIFIED_CHINESE).format(today)
    val dateLabel = if (isToday) "今天" else SimpleDateFormat("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE).format(date)
    val includeSeconds = durationMs in 1L until 60_000L
    val timeFormat = if (includeSeconds) "HH:mm:ss" else "HH:mm"
    val start = SimpleDateFormat(timeFormat, Locale.SIMPLIFIED_CHINESE).format(date)
    val end = durationMs.takeIf { it > 0L }?.let {
        SimpleDateFormat(timeFormat, Locale.SIMPLIFIED_CHINESE).format(Date(createdAt + it))
    }
    val duration = durationMs.takeIf { it > 0L }?.let {
        val totalSeconds = it / 1_000L
        if (totalSeconds >= 60L) " · ${totalSeconds / 60L} 分钟"
        else " · ${totalSeconds.coerceAtLeast(1L)} 秒"
    }.orEmpty()
    return buildString {
        append(dateLabel).append(' ').append(start)
        end?.let { append(" - ").append(it) }
        append(duration)
    }
}

private fun Long?.orZero(): Long = this ?: 0L

private fun formatAudioDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val remaining = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(Locale.US, hours, minutes, remaining)
    else "%02d:%02d".format(Locale.US, minutes, remaining)
}

package com.oa.automation.ui.screen.recording

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.STTLanguage
import com.oa.automation.domain.model.TencentAsrQuotaWarningLevel
import com.oa.automation.infrastructure.audio.ArchivedMeetingAudio
import com.oa.automation.infrastructure.image.OrientedImageDecoder
import com.oa.automation.ui.location.ImageLocationPermission
import com.oa.automation.ui.location.MeetingGalleryPermission
import com.oa.automation.domain.model.ProductEdition
import com.oa.automation.ui.navigation.ProductEntryPolicy
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collect
import org.koin.androidx.compose.koinViewModel

/**
 * RecordingScreen - 精简版录音页面
 * 设计原则：核心操作突出，次要信息折叠
 */
enum class RecordingLaunchAction {
    STANDARD,
    START_RECORDING,
    OPEN_IMPORT;

    companion object {
        fun from(value: String): RecordingLaunchAction = when (value) {
            "IMPORT_TEXT_FILE", "IMPORT_AUDIO_FILE" -> OPEN_IMPORT
            else -> entries.firstOrNull { it.name == value } ?: STANDARD
        }
    }
}

private fun isAudioDocument(context: android.content.Context, uri: Uri): Boolean {
    val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
    if (mime.startsWith("audio/")) return true
    val name = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
    }.orEmpty()
    return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf(
        "aac", "amr", "flac", "m4a", "mp3", "ogg", "opus", "wav", "webm"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    meetingId: String,
    launchAction: RecordingLaunchAction = RecordingLaunchAction.STANDARD,
    onNavigateBack: () -> Unit,
    onNavigateToReport: (String) -> Unit,
    onRequireLogin: () -> Unit,
    viewModel: RecordingViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val productPolicy = remember { ProductEntryPolicy.forEdition(ProductEdition.current) }
    val context = LocalContext.current
    var pendingCameraUriString by rememberSaveable(meetingId) { mutableStateOf<String?>(null) }
    var pendingCameraMarkerId by rememberSaveable(meetingId) { mutableStateOf<String?>(null) }
    var pendingGalleryMarkerId by rememberSaveable(meetingId) { mutableStateOf<String?>(null) }
    var pendingImageImportUriStrings by rememberSaveable(meetingId) {
        mutableStateOf(arrayListOf<String>())
    }
    var pendingImageImportMarkerId by rememberSaveable(meetingId) { mutableStateOf<String?>(null) }
    var markerMediaChooserVisible by rememberSaveable(meetingId) { mutableStateOf(false) }
    var markerMediaChooserMarkerId by rememberSaveable(meetingId) { mutableStateOf<String?>(null) }
    var showBackgroundRecordingNotice by rememberSaveable(meetingId) { mutableStateOf(false) }
    var backgroundRecordingNoticePending by rememberSaveable(meetingId) { mutableStateOf(false) }
    var launchActionConsumed by rememberSaveable(meetingId, launchAction) {
        mutableStateOf(false)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel, meetingId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val recording = viewModel.uiState.value
                if (recording.isRecording || recording.isRecordingActionPending || recording.isFinalizingRecording) {
                    backgroundRecordingNoticePending = true
                }
            } else if (event == Lifecycle.Event.ON_RESUME && backgroundRecordingNoticePending) {
                val recording = viewModel.uiState.value
                showBackgroundRecordingNotice = recording.isRecording || recording.isRecordingActionPending || recording.isFinalizingRecording
                backgroundRecordingNoticePending = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val uris = pendingImageImportUriStrings.map(Uri::parse)
        val markerId = pendingImageImportMarkerId
        pendingImageImportUriStrings = arrayListOf()
        pendingImageImportMarkerId = null
        if (uris.isNotEmpty()) {
            viewModel.importImages(
                uris = uris,
                captureLocation = ImageLocationPermission.isGranted(context),
                recordingMarkerId = markerId
            )
        }
    }

    fun importImagesWithOptionalLocation(uris: List<Uri>, recordingMarkerId: String?) {
        if (uris.isEmpty()) return
        if (ImageLocationPermission.isGranted(context) && MeetingGalleryPermission.isGranted(context)) {
            viewModel.importImages(
                uris = uris,
                captureLocation = true,
                recordingMarkerId = recordingMarkerId
            )
        } else {
            pendingImageImportUriStrings = ArrayList(uris.map(Uri::toString))
            pendingImageImportMarkerId = recordingMarkerId
            locationPermissionLauncher.launch(
                (ImageLocationPermission.requestedPermissions + MeetingGalleryPermission.requestedPermissions)
                    .distinct()
                    .toTypedArray()
            )
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val markerId = pendingGalleryMarkerId
        pendingGalleryMarkerId = null
        if (uris.isEmpty()) {
            viewModel.onMarkerMediaPickerCancelled(markerId)
        } else {
            importImagesWithOptionalLocation(uris, markerId)
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        val markerId = pendingCameraMarkerId
        if (saved) {
            pendingCameraUriString
                ?.let(Uri::parse)
                ?.let { importImagesWithOptionalLocation(listOf(it), markerId) }
        } else {
            viewModel.onMarkerMediaPickerCancelled(markerId)
        }
        pendingCameraUriString = null
        pendingCameraMarkerId = null
    }
    val externalDocumentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (isAudioDocument(context, uri)) {
            viewModel.importAudioDocument(uri)
        } else {
            viewModel.importTextDocument(uri)
        }
    }

    fun launchCamera(recordingMarkerId: String?) {
        val directory = File(context.filesDir, "meeting-attachments/$meetingId/camera").apply { mkdirs() }
        val target = File(directory, "meeting_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target
        )
        pendingCameraUriString = uri.toString()
        pendingCameraMarkerId = recordingMarkerId
        cameraLauncher.launch(uri)
    }

    fun launchGallery(recordingMarkerId: String?) {
        pendingGalleryMarkerId = recordingMarkerId
        galleryLauncher.launch("image/*")
    }

    fun navigateBackToWorkspace() {
        onNavigateBack()
    }

    BackHandler(onBack = ::navigateBackToWorkspace)

    LaunchedEffect(meetingId) {
        viewModel.loadMeeting(meetingId)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect.mediaRequest) {
                RecordingMediaRequest.CHOOSE_SOURCE -> {
                    markerMediaChooserMarkerId = effect.recordingMarkerId
                    markerMediaChooserVisible = true
                }

                RecordingMediaRequest.CAMERA -> launchCamera(effect.recordingMarkerId)
                RecordingMediaRequest.GALLERY -> launchGallery(effect.recordingMarkerId)
            }
        }
    }

    LaunchedEffect(uiState.activePhotoMarker?.id) {
        if (markerMediaChooserVisible && uiState.activePhotoMarker == null) {
            markerMediaChooserVisible = false
            markerMediaChooserMarkerId = null
        }
    }

    if (markerMediaChooserVisible) {
        val activeMarker = uiState.activePhotoMarker
        MarkerMediaSourceDialog(
            transcriptAnchor = activeMarker?.transcriptAnchor.orEmpty(),
            markerTimestampMs = activeMarker?.timestampMs,
            markerAttachments = activeMarker?.let { marker ->
                uiState.attachments.filter { attachment ->
                    attachment.recordingMarkerId == marker.id
                }
            }.orEmpty(),
            onTakePhoto = {
                markerMediaChooserVisible = false
                launchCamera(markerMediaChooserMarkerId)
            },
            onPickImages = {
                markerMediaChooserVisible = false
                launchGallery(markerMediaChooserMarkerId)
            },
            onKeepTextMarker = {
                markerMediaChooserVisible = false
                markerMediaChooserMarkerId = null
                viewModel.closeActivePhotoMarker()
            },
            onDismiss = {
                markerMediaChooserVisible = false
                markerMediaChooserMarkerId = null
            }
        )
    }

    LaunchedEffect(uiState.reportReadyToOpen) {
        if (uiState.reportReadyToOpen) {
            viewModel.consumeReportNavigation()
            onNavigateToReport(meetingId)
        }
    }

    LaunchedEffect(uiState.requiresLogin) {
        if (uiState.requiresLogin) {
            viewModel.consumeLoginRequest()
            onRequireLogin()
        }
    }

    LaunchedEffect(uiState.pendingAudioExport) {
        uiState.pendingAudioExport?.let { export ->
            when (export.action) {
                MeetingAudioExportAction.SAVE -> viewModel.consumeAudioExport()

                MeetingAudioExportAction.SHARE -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = export.prepared.mimeType
                        putExtra(Intent.EXTRA_STREAM, export.prepared.uri)
                        clipData = ClipData.newRawUri(
                            export.prepared.displayName,
                            export.prepared.uri
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "分享会议音频"))
                    viewModel.consumeAudioExport()
                }
            }
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            kotlinx.coroutines.delay(5000)
            viewModel.clearError()
        }
    }

    LaunchedEffect(meetingId, launchAction, uiState.meetingTitle, launchActionConsumed) {
        if (launchActionConsumed || uiState.meetingTitle.isBlank()) return@LaunchedEffect
        when (launchAction) {
            RecordingLaunchAction.STANDARD -> Unit
            RecordingLaunchAction.START_RECORDING -> viewModel.startRecording()
            RecordingLaunchAction.OPEN_IMPORT -> viewModel.switchToImportMode()
        }
        launchActionConsumed = true
    }

    val displayedUiState = if (
        launchAction == RecordingLaunchAction.OPEN_IMPORT && !launchActionConsumed
    ) {
        uiState.copy(inputMode = InputMode.IMPORT)
    } else {
        uiState
    }

    if (showBackgroundRecordingNotice && (uiState.isRecording || uiState.isRecordingActionPending || uiState.isFinalizingRecording)) {
        AlertDialog(
            onDismissRequest = { showBackgroundRecordingNotice = false },
            title = { Text("录音已在后台继续") },
            text = { Text("离开应用期间，录音与实时转写由后台服务保持。返回后可以继续暂停、结束或查看已保存内容。") },
            confirmButton = {
                TextButton(onClick = { showBackgroundRecordingNotice = false }) { Text("知道了") }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }

    RecordingReferenceScaffold(
        uiState = displayedUiState,
        productPolicy = productPolicy,
        onNavigateBack = ::navigateBackToWorkspace,
        onNavigateToReport = { onNavigateToReport(meetingId) },
        onTitleChange = viewModel::onMeetingTitleChange,
        onSaveTitle = viewModel::saveMeetingTitle,
        onSelectTemplate = viewModel::selectReportTemplate,
        templateWorkflowReducedMotion = displayedUiState.templateWorkflowReducedMotion,
        templateWorkflowSeen = displayedUiState.templateWorkflowSeen,
        onTemplateWorkflowSeen = viewModel::markTemplateWorkflowSeen,
        onCustomTemplateLayoutChange = viewModel::updateCustomTemplateLayout,
        onSttEngineSelected = viewModel::switchSttEngine,
        onSttLanguageSelected = viewModel::switchSttLanguage,
        onStartRecording = viewModel::startRecording,
        onTogglePause = viewModel::togglePauseRecording,
        onAddMarker = viewModel::addRecordingMarker,
        onGenerateStageDraft = viewModel::generateLatestStageDraft,
        onOpenStageDraft = viewModel::openLatestStageDraft,
        onSaveStageDraftContent = viewModel::saveStageDraftContent,
        onConfirmStageDraft = viewModel::confirmStageDraft,
        onDismissStageDraftEditor = viewModel::dismissStageDraftEditor,
        onGenerateJourneyEdition = viewModel::generateJourneyEdition,
        onOpenJourneyEdition = viewModel::openLatestJourneyEdition,
        onSaveJourneyEditionContent = viewModel::saveJourneyEditionContent,
        onConfirmJourneyEdition = viewModel::confirmJourneyEdition,
        onDismissJourneyEditionEditor = viewModel::dismissJourneyEditionEditor,
        onCreatePublishedPost = viewModel::createPublishedPostSnapshot,
        onOpenPublishedPost = viewModel::openPublishedPostReview,
        onSavePublishedPostReview = viewModel::savePublishedPostReview,
        onUpdatePublishedPostMetadata = viewModel::updatePublishedPostMetadata,
        onSetPublishedPostMediaIncluded = viewModel::setPublishedPostMediaIncluded,
        onMarkPublishedPostReady = viewModel::markPublishedPostReady,
        onPublishPublishedPost = viewModel::publishPublishedPost,
        onWithdrawPublishedPost = viewModel::withdrawPublishedPost,
        onDismissPublishedPostReview = viewModel::dismissPublishedPostReview,
        onGenerateReport = viewModel::generateReport,
        onCancelTranscription = viewModel::cancelTranscription,
        onCancelReport = viewModel::cancelReportGeneration,
        onTextChange = viewModel::updateManualText,
        onPickExternalFile = { externalDocumentPickerLauncher.launch(arrayOf("*/*")) },
        onGenerateFromImport = viewModel::generateFromImport,
        onTakePhoto = viewModel::requestPhotoCapture,
        onPickImages = viewModel::requestPhotoLibrary,
        onDeleteAttachment = viewModel::deleteAttachment,
        onShareAudio = viewModel::shareArchivedAudio,
        onDismissError = viewModel::clearError,
        onSelectStreamingTranscript = viewModel::selectStreamingTranscript,
        onSelectBackendTranscript = viewModel::selectBackendTranscript,
        onDismissTranscriptPicker = viewModel::dismissTranscriptPicker
    )
}

@Composable
private fun MarkerMediaSourceDialog(
    transcriptAnchor: String,
    markerTimestampMs: Long?,
    markerAttachments: List<MeetingAttachment>,
    onTakePhoto: () -> Unit,
    onPickImages: () -> Unit,
    onKeepTextMarker: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加插图") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MarkerInsertionPreview(
                    markerTimestampMs = markerTimestampMs,
                    transcriptAnchor = transcriptAnchor,
                    attachments = markerAttachments
                )
                Text(
                    text = "红色「」是插图的左右边界。拍照或上传图片后，图片会关联到这段文字对应的位置。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (transcriptAnchor.isBlank()) {
                    Text(
                        text = "当前暂无可标红的转录文字，将按录音时间定位插图。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                FilledTonalButton(
                    onClick = onTakePhoto,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("立即拍照")
                }
                OutlinedButton(
                    onClick = onPickImages,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Collections, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("上传图片")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onKeepTextMarker) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
private fun MarkerInsertionPreview(
    markerTimestampMs: Long?,
    transcriptAnchor: String,
    attachments: List<MeetingAttachment>
) {
    val timestampLabel = markerTimestampMs?.let(::formatMarkerTimestampLabel)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.42f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "插图预览",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                if (timestampLabel != null) {
                    Text(
                        text = "插入位置 $timestampLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (transcriptAnchor.isNotBlank()) {
                Text(
                    text = "「$transcriptAnchor」",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            if (attachments.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.32f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Collections,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "图片确认后会插入到上方红色区域",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(attachments, key = { it.id }) { attachment ->
                        val bitmap = remember(attachment.localPath) {
                            OrientedImageDecoder.decode(
                                File(attachment.localPath),
                                maximumDimension = 320
                            )?.asImageBitmap()
                        }
                        Box(
                            modifier = Modifier
                                .size(width = 112.dp, height = 78.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = attachment.displayName,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.BrokenImage,
                                    contentDescription = "图片无法预览",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(4.dp),
                                shape = RoundedCornerShape(5.dp),
                                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.68f)
                            ) {
                                Text(
                                    text = "插入区域",
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMarkerTimestampLabel(timestampMs: Long): String {
    val totalSeconds = (timestampMs / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(Locale.ROOT, totalSeconds / 60L, totalSeconds % 60L)
}

@Composable
internal fun MeetingAudioExportCard(
    items: List<ArchivedMeetingAudio>,
    isLoading: Boolean,
    busyAudioId: String?,
    statusMessage: String,
    isTranscribing: Boolean,
    onShare: (ArchivedMeetingAudio) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.AudioFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "会议音频",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            if (items.isEmpty()) {
                Text(
                    text = when {
                        isLoading -> "正在查找服务器归档音频"
                        isTranscribing -> "正在准备会议音频"
                        else -> "暂无可导出的会议音频"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                items.forEach { audio ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = formatRecordingAudioTime(audio.createdAt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = listOfNotNull(
                                        audio.durationSec?.let(::formatRecordingAudioDuration),
                                        formatRecordingAudioSize(audio.bytes)
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (busyAudioId == audio.id) {
                                Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            } else {
                                IconButton(
                                    onClick = { onShare(audio) },
                                    enabled = busyAudioId == null,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "分享会议音频")
                                }
                            }
                        }
                    }
                }
            }

            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (statusMessage.contains("失败")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}

@Composable
private fun CompactInputModeToggle(
    inputMode: InputMode,
    onSwitchToVoice: () -> Unit,
    onSwitchToImport: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModeButton(
                text = "语音",
                icon = Icons.Default.Mic,
                selected = inputMode == InputMode.VOICE,
                onClick = onSwitchToVoice,
                modifier = Modifier.weight(1f)
            )
            ModeButton(
                text = "导入",
                icon = Icons.Default.FolderOpen,
                selected = inputMode == InputMode.IMPORT,
                onClick = onSwitchToImport,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModeButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TemplateSelectorCard(
    templates: List<PresetReportTemplate>,
    selectedTemplateName: String,
    onSelectTemplate: (PresetReportTemplate) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var previewTemplate by remember { mutableStateOf<PresetReportTemplate?>(null) }

    // 模板预览弹窗
    if (previewTemplate != null) {
        AlertDialog(
            onDismissRequest = { previewTemplate = null },
            title = { Text(previewTemplate!!.name, fontWeight = FontWeight.SemiBold) },
            text = {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Text(
                        text = previewTemplate!!.content.ifBlank { "模板内容为空" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { previewTemplate = null }) {
                    Text("关闭")
                }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            // 标题栏 - 显示当前选中的模板
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "纪要模板",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = selectedTemplateName.ifBlank { "选择模板" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 展开的模板列表
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    templates.forEach { template ->
                        val isSelected = template.name == selectedTemplateName
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        onSelectTemplate(template)
                                    },
                                    onLongClick = { previewTemplate = template }
                                ),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = template.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = templateSelectionHint(template),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
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

private fun templateSelectionHint(template: PresetReportTemplate): String {
    return template.subtitle
        .takeIf { it.isNotBlank() }
        ?.let { "$it · 长按预览" }
        ?: "长按预览内容"
}

@Composable
private fun TencentQuotaWarningBanner(
    warning: String,
    warningLevel: TencentAsrQuotaWarningLevel
) {
    val critical = warningLevel == TencentAsrQuotaWarningLevel.CRITICAL ||
        warningLevel == TencentAsrQuotaWarningLevel.EXHAUSTED
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = if (critical) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (critical) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun CompactErrorBanner(
    error: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
internal fun RuntimeServiceSwitcher(
    sttEngineType: STTEngineType,
    sttLanguage: STTLanguage,
    isSwitchingStt: Boolean,
    isSwitchingLanguage: Boolean,
    onSttEngineSelected: (STTEngineType) -> Unit,
    onSttLanguageSelected: (STTLanguage) -> Unit
) {
    var sttMenuExpanded by remember { mutableStateOf(false) }
    val sttLabel = sttEngineType.displayName

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Hub,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "实时识别",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (isSwitchingStt || isSwitchingLanguage) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                STTLanguage.entries.forEach { language ->
                    val selected = language == sttLanguage
                    OutlinedButton(
                        onClick = { onSttLanguageSelected(language) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        enabled = !isSwitchingLanguage,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            }
                        )
                    ) {
                        Text(language.displayName, maxLines = 1)
                        if (selected) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { sttMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = !isSwitchingStt,
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(
                        if (sttEngineType == STTEngineType.TENCENT_HYBRID) {
                            Icons.Default.Cloud
                        } else {
                            Icons.Default.Speed
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(sttLabel, maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = sttMenuExpanded,
                    onDismissRequest = { sttMenuExpanded = false }
                ) {
                    listOf(
                        STTEngineType.FASTER_WHISPER to STTEngineType.FASTER_WHISPER.displayName,
                        STTEngineType.TENCENT_HYBRID to STTEngineType.TENCENT_HYBRID.displayName
                    ).forEach { (engine, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = {
                                Icon(
                                    if (engine == STTEngineType.TENCENT_HYBRID) {
                                        Icons.Default.Cloud
                                    } else {
                                        Icons.Default.Speed
                                    },
                                    contentDescription = null
                                )
                            },
                            trailingIcon = {
                                if (engine == sttEngineType) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                sttMenuExpanded = false
                                onSttEngineSelected(engine)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DualActionCards(
    isRecording: Boolean,
    isTranscribing: Boolean,
    isGeneratingReport: Boolean,
    hasRecording: Boolean,
    onRecordClick: () -> Unit,
    onGenerateReport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 录音卡片
        ActionCard(
            icon = if (isRecording) Icons.Default.MicOff else Icons.Default.Mic,
            title = when {
                isTranscribing -> "整理中"
                isRecording -> "录音中"
                else -> "录音"
            },
            subtitle = when {
                isTranscribing -> "请稍候..."
                isRecording -> "点击停止"
                else -> "点击开始"
            },
            isActive = isRecording || isTranscribing,
            isActiveColor = isRecording,
            enabled = !isTranscribing,
            onClick = onRecordClick,
            modifier = Modifier.weight(1f),
            statusChips = null
        )

        // 生成纪要卡片
        ActionCard(
            icon = Icons.Default.Summarize,
            title = "生成纪要",
            subtitle = if (isGeneratingReport) "后台处理生成中"
                      else if (hasRecording && !isRecording) "点击生成"
                      else if (isRecording) "录音中..."
                      else "需先录音",
            isActive = isGeneratingReport || (hasRecording && !isRecording),
            isActiveColor = false,
            enabled = hasRecording && !isRecording && !isTranscribing && !isGeneratingReport,
            onClick = onGenerateReport,
            modifier = Modifier.weight(1f),
            statusChips = null
        )
    }
}

@Composable
private fun TextModeActionCard(
    hasContent: Boolean,
    isGeneratingReport: Boolean,
    onGenerateReport: () -> Unit
) {
    ActionCard(
        icon = Icons.Default.Summarize,
        title = "生成纪要",
        subtitle = if (isGeneratingReport) "后台处理生成中" else if (hasContent) "点击生成" else "需输入内容",
        isActive = isGeneratingReport || hasContent,
        isActiveColor = false,
        enabled = hasContent && !isGeneratingReport,
        onClick = onGenerateReport,
        modifier = Modifier.fillMaxWidth(),
        statusChips = null
    )
}

@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isActive: Boolean,
    isActiveColor: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusChips: (@Composable () -> Unit)? = null
) {
    val statusColor by animateColorAsState(
        targetValue = when {
            isActiveColor -> MaterialTheme.colorScheme.error
            isActive -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        },
        animationSpec = tween(300),
        label = "statusColor"
    )

    val iconScale by animateFloatAsState(
        targetValue = if (isActive) 1.05f else 1f,
        animationSpec = tween(600),
        label = "iconScale"
    )

    Card(
        modifier = modifier.border(
            width = 1.dp,
            color = statusColor.copy(alpha = if (enabled) 0.55f else 0.2f),
            shape = MaterialTheme.shapes.medium
        ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = if (enabled) onClick else {{}}
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .scale(iconScale)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (enabled) statusColor else statusColor.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(21.dp)
                )
            }

            // 标题
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 副标题
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 状态标签
            statusChips?.invoke()
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    isActive: Boolean
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isActive) MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
               else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CollapsibleTranscriptCard(
    transcript: String,
    previewMode: String,
    isRecording: Boolean
) {
    var expanded by remember { mutableStateOf(true) }
    var showFullDialog by remember { mutableStateOf(false) }
    val transcriptScrollState = rememberScrollState()

    LaunchedEffect(isRecording, transcript.isNotBlank()) {
        if (isRecording || transcript.isNotBlank()) {
            expanded = true
        }
    }

    LaunchedEffect(transcript) {
        if (transcript.isNotBlank()) {
            kotlinx.coroutines.delay(16)
            transcriptScrollState.scrollTo(transcriptScrollState.maxValue)
        }
    }

    if (showFullDialog) {
        AlertDialog(
            onDismissRequest = { showFullDialog = false },
            title = { Text("转写内容", fontWeight = FontWeight.SemiBold) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                ) {
                    if (transcript.isBlank()) {
                        Text(
                            text = "暂无内容",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = transcript,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFullDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "转写内容",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (transcript.isNotBlank()) {
                        Text(
                            text = "$previewMode · ${transcript.length}字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 展开内容
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    if (transcript.isNotBlank()) {
                        FilledTonalButton(
                            onClick = { showFullDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.OpenInFull, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("查看完整内容", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        if (transcript.isBlank()) {
                            Text(
                                text =
                                if (isRecording) previewMode.ifBlank { "实时预览处理中" }
                                else "开始录音后显示转写内容",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            SelectionContainer {
                                Text(
                                    text = transcript,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(transcriptScrollState)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MeetingImagesSection(
    attachments: List<MeetingAttachment>,
    isImporting: Boolean,
    importCompleted: Int,
    importTotal: Int,
    onTakePhoto: () -> Unit,
    onPickImages: () -> Unit,
    onDelete: (MeetingAttachment) -> Unit
) {
    var clearAllConfirmationVisible by remember { mutableStateOf(false) }

    if (clearAllConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { clearAllConfirmationVisible = false },
            title = { Text("清空插图") },
            text = { Text("将删除本次记录中的 ${attachments.size} 张图片，录音标记和文字不会受到影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearAllConfirmationVisible = false
                        attachments.forEach(onDelete)
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearAllConfirmationVisible = false }) { Text("取消") }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("插图管理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        isImporting -> "正在导入 ${importCompleted.coerceAtMost(importTotal)}/$importTotal"
                        attachments.isEmpty() -> "可随时记录白板、投影和现场资料"
                        else -> "已添加 ${attachments.size} 张"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(onClick = onTakePhoto, enabled = !isImporting) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "拍照")
                }
                IconButton(onClick = onPickImages, enabled = !isImporting) {
                    Icon(Icons.Default.Collections, contentDescription = "从相册添加")
                }
                if (attachments.isNotEmpty()) {
                    IconButton(
                        onClick = { clearAllConfirmationVisible = true },
                        enabled = !isImporting
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "清空插图",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        if (attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(attachments, key = { it.id }) { attachment ->
                    val isIllustration = !attachment.recordingMarkerId.isNullOrBlank()
                    val bitmap = remember(attachment.localPath) {
                        OrientedImageDecoder.decode(File(attachment.localPath), maximumDimension = 512)
                            ?.asImageBitmap()
                    }
                    val tileWidth = if (isIllustration) 132.dp else 96.dp
                    Column(
                        modifier = Modifier.width(tileWidth),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = tileWidth, height = 96.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .then(
                                    if (isIllustration) {
                                        Modifier.border(
                                            1.dp,
                                            MaterialTheme.colorScheme.error.copy(alpha = 0.72f),
                                            RoundedCornerShape(8.dp)
                                        )
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = attachment.displayName,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Default.BrokenImage, contentDescription = "图片无法预览")
                            }
                            if (isIllustration) {
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(4.dp),
                                    shape = RoundedCornerShape(5.dp),
                                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f)
                                ) {
                                    Text(
                                        text = "插图",
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDelete(attachment) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(30.dp)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "删除图片",
                                    modifier = Modifier.size(17.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (isIllustration) {
                            Text(
                                text = attachment.markerTimestampMs?.let {
                                    "插图位置 ${formatMarkerTimestampLabel(it)}"
                                } ?: "插图位置已标记",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = attachment.markerTranscriptAnchor
                                    ?.trim()
                                    ?.takeIf(String::isNotBlank)
                                    ?: "已关联标红区域",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}


private fun formatRecordingAudioTime(value: String): String = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .format(Date.from(Instant.parse(value)))
}.getOrDefault(value)

private fun formatRecordingAudioDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val remainingSeconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, remainingSeconds)
    } else {
        "%02d:%02d".format(Locale.US, minutes, remainingSeconds)
    }
}

private fun formatRecordingAudioSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun TranscriptPickerDialog(
    streamingText: String,
    backendText: String,
    onSelectStreaming: () -> Unit,
    onSelectBackend: () -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf<TranscriptSource?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择转写版本", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "请选择作为最终稿的转写文本：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TranscriptOptionItem(
                    label = "流式预览",
                    description = "实时转写",
                    isSelected = selected == TranscriptSource.STREAMING,
                    onClick = { selected = TranscriptSource.STREAMING }
                )

                TranscriptOptionItem(
                    label = "后台转写（推荐）",
                    description = "精度更高",
                    isSelected = selected == TranscriptSource.BACKEND,
                    onClick = { selected = TranscriptSource.BACKEND }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (selected) {
                        TranscriptSource.STREAMING -> onSelectStreaming()
                        TranscriptSource.BACKEND -> onSelectBackend()
                        null -> {}
                    }
                },
                enabled = selected != null
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun TranscriptOptionItem(
    label: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

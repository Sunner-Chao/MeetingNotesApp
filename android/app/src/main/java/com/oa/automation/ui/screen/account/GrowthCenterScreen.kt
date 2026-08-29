package com.oa.automation.ui.screen.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocalActivity
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.GrowthCampaign
import com.oa.automation.domain.model.GrowthCampaignDetail
import com.oa.automation.domain.model.GrowthPrivateChannel
import com.oa.automation.infrastructure.account.GrowthQrGalleryStore
import com.oa.automation.ui.location.MeetingGalleryPermission
import com.oa.automation.ui.theme.BrandBlue
import com.oa.automation.ui.theme.BrandDeepGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private val WelfareGreen = Color(0xFF087A55)
private val WelfareGold = Color(0xFFE9B949)

enum class GrowthCenterSection {
    ALL,
    ACTIVITIES,
    BENEFITS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrowthCenterScreen(
    onNavigateBack: () -> Unit = {},
    section: GrowthCenterSection = GrowthCenterSection.ALL,
    embedded: Boolean = false,
    viewModel: GrowthCenterViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val qrGalleryStore = remember(context.applicationContext) {
        GrowthQrGalleryStore(context.applicationContext)
    }
    var showAssetPreview by remember { mutableStateOf(false) }
    var previewManagerCard by remember { mutableStateOf(false) }
    var showApplicationSheet by remember { mutableStateOf(false) }
    var pendingManagerCardSave by remember { mutableStateOf(false) }

    val performAssetSave: () -> Unit = {
        val bytes = if (pendingManagerCardSave) uiState.managerCardImageBytes else uiState.qrImageBytes
        if (bytes == null) {
            coroutineScope.launch { snackbarHostState.showSnackbar("图片尚未加载完成") }
        } else {
            coroutineScope.launch {
                qrGalleryStore.save(
                    bytes,
                    displayNamePrefix = if (pendingManagerCardSave) "智悟本群主名片" else "智悟本福利7群二维码"
                ).fold(
                    onSuccess = { snackbarHostState.showSnackbar("图片已保存到手机相册") },
                    onFailure = { error ->
                        snackbarHostState.showSnackbar(error.message ?: "二维码保存失败")
                    }
                )
            }
        }
    }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (MeetingGalleryPermission.requestedPermissions.all { grants[it] == true }) {
            performAssetSave()
        } else {
            coroutineScope.launch { snackbarHostState.showSnackbar("需要相册权限才能保存二维码") }
        }
    }
    val requestAssetSave: (Boolean) -> Unit = { managerCard ->
        pendingManagerCardSave = managerCard
        if (MeetingGalleryPermission.isGranted(context)) {
            performAssetSave()
        } else {
            galleryPermissionLauncher.launch(MeetingGalleryPermission.requestedPermissions)
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = if (embedded) Color.Transparent else MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!embedded) {
                TopAppBar(
                    title = {
                        Text(
                            if (section == GrowthCenterSection.BENEFITS) "邀请好友" else "活动与福利",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::refresh, enabled = !uiState.isLoading) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        // Read once into a local so the non-null branch smart-casts.
        val overviewState = uiState.overview
        when {
            uiState.isLoading && overviewState == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            overviewState == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(28.dp).padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (section == GrowthCenterSection.BENEFITS) {
                                "邀请信息暂不可用"
                            } else {
                                "活动与福利暂不可用"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = viewModel::refresh) { Text("重新加载") }
                    }
                }
            }
            else -> {
                // Reading into a local keeps the smart cast; the branches above already
                // cover a null overview, so no early return is needed here.
                val overview = overviewState
                val canUseAccountFeatures = uiState.isAuthenticated
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = if (embedded) 8.dp else 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val activeCampaigns = overview.campaigns.filter {
                        it.status == "active" || it.status == "running"
                    }
                    if (section == GrowthCenterSection.ALL && !embedded) {
                        item {
                            GrowthFocusBanner(
                                campaignCount = activeCampaigns.size,
                                onOpen = activeCampaigns.firstOrNull()?.let { campaign ->
                                    { viewModel.openCampaign(campaign.id) }
                                }
                            )
                        }
                    }

                    if (section != GrowthCenterSection.ACTIVITIES) {
                        // 福利群置顶：入群入口排在积分/邀请面板之前。
                        overview.privateChannel?.let { channel ->
                            item {
                                PrivateChannelPanel(
                                    channel = channel,
                                    qrImageBytes = uiState.qrImageBytes,
                                    managerCardImageBytes = uiState.managerCardImageBytes,
                                    canApply = canUseAccountFeatures,
                                    onQrShown = { viewModel.recordChannelEvent(channel.id, "open_qr") },
                                    onPreviewQr = {
                                        previewManagerCard = false
                                        showAssetPreview = true
                                    },
                                    onSaveQr = { requestAssetSave(false) },
                                    onPreviewManagerCard = {
                                        previewManagerCard = true
                                        showAssetPreview = true
                                    },
                                    onSaveManagerCard = { requestAssetSave(true) },
                                    onApply = { showApplicationSheet = true },
                                    onOpenLink = {
                                        openUrl(context, channel.joinUrl.ifBlank { channel.shortUrl })
                                        viewModel.recordChannelEvent(channel.id, "click")
                                    },
                                    onCopyLink = {
                                        copyText(
                                            context,
                                            "福利群链接",
                                            channel.joinUrl.ifBlank { channel.shortUrl }
                                        )
                                        viewModel.recordChannelEvent(channel.id, "copy_link")
                                    }
                                )
                            }
                        }
                        if (canUseAccountFeatures) {
                            item {
                                GrowthSummaryBand(
                                    points = uiState.pointsRemaining,
                                    rewardPoints = overview.rewards["points"] ?: 0,
                                    campaignCount = overview.campaigns.size
                                )
                            }
                            item {
                                RedeemPanel(
                                    value = uiState.redeemCode,
                                    isLoading = uiState.isRedeeming,
                                    onValueChange = viewModel::updateRedeemCode,
                                    onRedeem = viewModel::redeem
                                )
                            }
                            item {
                                InvitationPanel(
                                    code = overview.referral.code,
                                    inviteUrl = uiState.inviteUrl,
                                    successfulInvites = overview.referral.successfulInvites,
                                    rewardPoints = overview.referral.rewardPoints,
                                    onCopyCode = {
                                        copyText(context, "邀请码", overview.referral.code)
                                    },
                                    onShare = {
                                        shareText(
                                            context,
                                            "我正在使用智悟本记录成长，注册时填写邀请码 ${overview.referral.code}，双方各得 ${overview.referral.rewardPoints} 积分。${uiState.inviteUrl}"
                                        )
                                    }
                                )
                            }
                        }
                    }

                    if (section != GrowthCenterSection.BENEFITS) {
                        item {
                            Text(
                                text = if (embedded) "近期活动" else "限时活动",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        items(activeCampaigns, key = { it.id }) { campaign ->
                            GrowthCampaignRow(campaign = campaign) {
                                viewModel.openCampaign(campaign.id)
                            }
                        }
                        if (activeCampaigns.isEmpty()) {
                            item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        "暂无进行中的活动",
                                        modifier = Modifier.padding(20.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    uiState.activeCampaignId?.let {
        GrowthCampaignSheet(
            detail = uiState.campaignDetail,
            isLoading = uiState.isCampaignLoading,
            busyAction = uiState.busyAction,
            onDismiss = viewModel::closeCampaign,
            onJoin = viewModel::joinCampaign,
            onCheckin = viewModel::checkinCampaign,
            onDraw = viewModel::drawCampaign,
            onAnswer = viewModel::answerCampaign
        )
    }

    if (showAssetPreview) {
        QrPreviewDialog(
            qrImageBytes = if (previewManagerCard) uiState.managerCardImageBytes else uiState.qrImageBytes,
            title = if (previewManagerCard) "群主名片" else "智悟本福利7群",
            subtitle = if (previewManagerCard) "添加群主后提交入群申请" else "审核通过后可扫码入群",
            onDismiss = { showAssetPreview = false },
            onSave = { requestAssetSave(previewManagerCard) }
        )
    }

    if (showApplicationSheet) {
        ChannelApplicationSheet(
            isSubmitting = uiState.isSubmittingApplication,
            onDismiss = { if (!uiState.isSubmittingApplication) showApplicationSheet = false },
            onSubmit = { answers ->
                viewModel.submitChannelApplication(answers)
                showApplicationSheet = false
            }
        )
    }
}

@Composable
private fun GrowthFocusBanner(campaignCount: Int, onOpen: (() -> Unit)?) {
    val transition = rememberInfiniteTransition(label = "growth-focus")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "growth-focus-pulse"
    )
    Surface(
        onClick = { onOpen?.invoke() },
        enabled = onOpen != null,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFEAF8F5),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF9AD8C4))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                color = WelfareGreen.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.LocalActivity,
                        contentDescription = null,
                        tint = WelfareGreen,
                        modifier = Modifier.graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                        }
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("福利正在进行", color = WelfareGreen, fontWeight = FontWeight.Bold)
                Text(
                    if (campaignCount > 0) "$campaignCount 项活动可参与，签到答题即可得积分"
                    else "关注活动中心，下一场福利上线会第一时间提醒你",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF416257),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onOpen != null) {
                Surface(shape = RoundedCornerShape(50), color = WelfareGreen) {
                    Text(
                        "去看看",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun GrowthSummaryBand(points: Int, rewardPoints: Int, campaignCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(156.dp),
        shape = RoundedCornerShape(12.dp),
        color = BrandDeepGreen
    ) {
        Box(
            modifier = Modifier.background(
                Brush.horizontalGradient(listOf(BrandDeepGreen, Color(0xFF075B68)))
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CardGiftcard, contentDescription = null, tint = WelfareGold)
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text("我的福利账户", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("参与活动，积分实时到账", color = Color.White.copy(alpha = 0.66f), fontSize = 11.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SummaryMetric(points.toString(), "可用积分")
                    SummaryMetric(rewardPoints.toString(), "活动所得")
                    SummaryMetric(campaignCount.toString(), "进行中")
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric(value: String, label: String) {
    Column {
        Text(value, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.68f), fontSize = 12.sp)
    }
}

@Composable
private fun RedeemPanel(
    value: String,
    isLoading: Boolean,
    onValueChange: (String) -> Unit,
    onRedeem: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Redeem, contentDescription = null, tint = BrandBlue)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("兑换中心", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("礼品码 · 兑换码", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            Text(
                "输入礼品码或兑换码，即时领取积分与虚拟权益。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入兑换码") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onRedeem,
                    enabled = !isLoading && value.length >= 4,
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("兑换")
                }
            }
        }
    }
}

@Composable
private fun InvitationPanel(
    code: String,
    inviteUrl: String,
    successfulInvites: Int,
    rewardPoints: Int,
    onCopyCode: () -> Unit,
    onShare: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = WelfareGreen)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("邀请好友", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("双方奖励，邀请进度实时更新", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            Text(
                "好友注册时填写邀请码，双方各得 $rewardPoints 积分。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("我的邀请码", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(code.ifBlank { "正在生成" }, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onCopyCode, enabled = code.isNotBlank()) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制邀请码")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已成功邀请 $successfulInvites 人", modifier = Modifier.weight(1f), fontSize = 12.sp)
                OutlinedButton(onClick = onShare, enabled = inviteUrl.isNotBlank()) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("分享")
                }
            }
        }
    }
}

@Composable
private fun PrivateChannelPanel(
    channel: GrowthPrivateChannel,
    qrImageBytes: ByteArray?,
    managerCardImageBytes: ByteArray?,
    canApply: Boolean,
    onQrShown: () -> Unit,
    onPreviewQr: () -> Unit,
    onSaveQr: () -> Unit,
    onPreviewManagerCard: () -> Unit,
    onSaveManagerCard: () -> Unit,
    onApply: () -> Unit,
    onOpenLink: () -> Unit,
    onCopyLink: () -> Unit
) {
    val qrBitmap = remember(qrImageBytes) {
        qrImageBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    val managerCardBitmap = remember(managerCardImageBytes) {
        managerCardImageBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    LaunchedEffect(qrBitmap) {
        if (qrBitmap != null) onQrShown()
    }
    val hasLink = channel.joinUrl.isNotBlank() || channel.shortUrl.isNotBlank()
    val application = channel.application
    val applicationStatus = application?.status.orEmpty()
    val approved = applicationStatus == "approved" && qrBitmap != null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF1FAF5),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Surface(shape = RoundedCornerShape(50), color = WelfareGreen.copy(alpha = 0.12f)) {
                    Text(
                        if (approved) "已通过审核 · 福利群" else "先联系群主 · 审核入群",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = WelfareGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    channel.slogan.ifBlank { "添加群主，交流后填写申请；审核通过即可查看入群二维码" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF53665D)
                )
            }
            Surface(
                onClick = onPreviewManagerCard,
                enabled = managerCardBitmap != null,
                modifier = Modifier.fillMaxWidth().height(220.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color.White
            ) {
                if (managerCardBitmap != null) {
                    Image(
                        bitmap = managerCardBitmap,
                        contentDescription = "群主企业微信名片，点击放大",
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (when (applicationStatus) {
                        "pending" -> "申请审核中，审核通过后会通知你"
                        "approved" -> "审核已通过，可查看下方入群二维码"
                        "rejected" -> "申请需要补充信息，可重新提交"
                        else -> "请先添加群主，充分交流后填写入群申请"
                    } + if (applicationStatus == "rejected" && !application?.reviewNote.isNullOrBlank()) {
                        "\n审核备注：${application?.reviewNote}"
                    } else ""),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (applicationStatus == "approved") WelfareGreen else Color(0xFF53665D)
                )
                IconButton(onClick = onPreviewManagerCard, enabled = managerCardBitmap != null) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "放大查看群主名片")
                }
                IconButton(onClick = onSaveManagerCard, enabled = managerCardBitmap != null) {
                    Icon(Icons.Default.Download, contentDescription = "保存群主名片到相册")
                }
            }
            if (applicationStatus != "approved") {
                Button(
                    onClick = onApply,
                    enabled = canApply && applicationStatus != "pending",
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        when {
                            !canApply -> "登录后申请入群"
                            applicationStatus == "rejected" -> "补充信息并重新申请"
                            else -> "填写申请，联系群主"
                        }
                    )
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            onClick = onPreviewQr,
                            enabled = qrBitmap != null,
                            modifier = Modifier.size(86.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = Color.White
                        ) {
                            if (qrBitmap != null) {
                                Image(
                                    bitmap = qrBitmap,
                                    contentDescription = "智悟本福利7群二维码",
                                    modifier = Modifier.fillMaxSize().padding(4.dp)
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("入群二维码", fontWeight = FontWeight.SemiBold)
                            Text("入群即送 ${channel.reward.quantity} 积分", style = MaterialTheme.typography.bodySmall, color = WelfareGreen)
                        }
                        IconButton(onClick = onSaveQr, enabled = qrBitmap != null) {
                            Icon(Icons.Default.Download, contentDescription = "保存群二维码到相册")
                        }
                    }
                }
                if (hasLink) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Button(onClick = onOpenLink, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Text("打开入群链接")
                        }
                        IconButton(onClick = onCopyLink) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制入群链接")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrPreviewDialog(
    qrImageBytes: ByteArray?,
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val qrBitmap = remember(qrImageBytes) {
        qrImageBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    val transformableState = rememberTransformableState { zoomChange, _, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Bold)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onSave, enabled = qrBitmap != null) {
                        Icon(Icons.Default.Download, contentDescription = "保存二维码到相册")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭预览")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White
                ) {
                    if (qrBitmap != null) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "智悟本福利7群二维码大图",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .transformable(transformableState)
                            )
                        }
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelApplicationSheet(
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (Map<String, String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("申请加入智悟本福利7群", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "请先添加群主并充分交流，提交信息后由管理员审核。审核通过后，福利页会显示群二维码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("怎么称呼你") }, singleLine = true)
            OutlinedTextField(city, { city = it }, modifier = Modifier.fillMaxWidth(), label = { Text("所在地区") }, placeholder = { Text("例如：杭州") }, singleLine = true)
            OutlinedTextField(purpose, { purpose = it }, modifier = Modifier.fillMaxWidth(), label = { Text("想加入福利群做什么") }, minLines = 2, maxLines = 4)
            OutlinedTextField(contact, { contact = it }, modifier = Modifier.fillMaxWidth(), label = { Text("方便群主联系你的方式（选填）") }, singleLine = true)
            Button(
                onClick = {
                    onSubmit(
                        mapOf(
                            "name" to name.trim(),
                            "city" to city.trim(),
                            "purpose" to purpose.trim(),
                            "contact" to contact.trim()
                        )
                    )
                },
                enabled = !isSubmitting && name.isNotBlank() && city.isNotBlank() && purpose.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isSubmitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("提交申请")
            }
        }
    }
}

@Composable
private fun GrowthCampaignRow(campaign: GrowthCampaign, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = when (campaign.campaignType) {
                    "quiz" -> Color(0xFFFFF2CC)
                    "ranking" -> Color(0xFFE8F3FF)
                    else -> Color(0xFFE7F6EF)
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (campaign.campaignType == "ranking") Icons.Default.EmojiEvents else Icons.Default.CardGiftcard,
                        contentDescription = null,
                        tint = if (campaign.campaignType == "quiz") Color(0xFF9A6700) else BrandBlue
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        campaign.title,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(shape = RoundedCornerShape(50), color = Color(0xFFE5F5EC)) {
                        Text(
                            "进行中",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = WelfareGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    campaign.summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatBeijingDate(campaign.startsAt)} - ${formatBeijingDate(campaign.endsAt)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                val instantReward = when {
                    campaign.rules.checkinReward > 0 -> "今日签到 +${campaign.rules.checkinReward} 积分"
                    campaign.rules.answerReward > 0 -> "答题最高 +${campaign.rules.answerReward} 积分"
                    campaign.rules.drawReward > 0 -> "抽奖最高 +${campaign.rules.drawReward} 积分"
                    else -> "查看活动奖励"
                }
                Text(instantReward, color = WelfareGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GrowthCampaignSheet(
    detail: GrowthCampaignDetail?,
    isLoading: Boolean,
    busyAction: String?,
    onDismiss: () -> Unit,
    onJoin: () -> Unit,
    onCheckin: () -> Unit,
    onDraw: () -> Unit,
    onAnswer: (String, String) -> Unit
) {
    val answers = remember(detail?.id) { mutableStateMapOf<String, String>() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (isLoading || detail == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val hasCheckedIn = detail.actions.any { it.actionType == "checkin" }
            val hasDrawn = detail.actions.any { it.actionType == "draw" }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("近期活动", color = WelfareGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(detail.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Surface(shape = RoundedCornerShape(50), color = Color(0xFFE5F5EC)) {
                        Text("进行中", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = WelfareGreen, fontSize = 12.sp)
                    }
                }
                Text(detail.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CampaignMetric(detail.myScore.toString(), "活动得分", Modifier.weight(1f))
                    CampaignMetric(detail.myRank?.toString() ?: "未上榜", "当前排名", Modifier.weight(1f))
                    CampaignMetric("${detail.rules.checkinReward} 分", "签到奖励", Modifier.weight(1f))
                }
                if (detail.rules.questions.isNotEmpty()) {
                    Text("每日答题", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    detail.rules.questions.forEachIndexed { index, question ->
                        val alreadyAnswered = detail.actions.any {
                            it.actionType == "answer" && it.actionKey == question.key
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("${index + 1}. ${question.question}", fontWeight = FontWeight.SemiBold)
                                question.options.forEach { option ->
                                    Surface(
                                        onClick = { answers[question.key] = option },
                                        enabled = !alreadyAnswered && busyAction == null,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(7.dp),
                                        color = if (answers[question.key] == option) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (answers[question.key] == option) {
                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                                                Spacer(Modifier.width(7.dp))
                                            }
                                            Text(option)
                                        }
                                    }
                                }
                                Button(
                                    onClick = { onAnswer(question.key, answers[question.key].orEmpty()) },
                                    enabled = !alreadyAnswered && answers[question.key].orEmpty().isNotBlank() && busyAction == null,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(if (alreadyAnswered) "已提交" else "提交答案")
                                }
                            }
                        }
                    }
                }
                Text("当前排行", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (detail.leaderboard.isEmpty()) {
                    Text("还没有参与记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    detail.leaderboard.take(5).forEachIndexed { index, entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(28.dp),
                                shape = CircleShape,
                                color = if (index < 3) WelfareGold.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(contentAlignment = Alignment.Center) { Text("${index + 1}", fontSize = 12.sp) }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(entry.displayName, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("${entry.score} 分", fontWeight = FontWeight.SemiBold)
                        }
                        if (index < detail.leaderboard.take(5).lastIndex) HorizontalDivider()
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!detail.joined) {
                        OutlinedButton(
                            onClick = onJoin,
                            enabled = busyAction == null,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("加入活动") }
                    }
                    OutlinedButton(
                        onClick = onCheckin,
                        enabled = detail.joined && !hasCheckedIn && busyAction == null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (hasCheckedIn) "已签到" else "签到")
                    }
                    Button(
                        onClick = onDraw,
                        enabled = detail.joined && !hasDrawn && busyAction == null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                    ) {
                        Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (hasDrawn) "已抽奖" else "抽一次")
                    }
                }
            }
        }
    }
}

@Composable
private fun CampaignMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(11.dp)) {
            Text(value, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatBeijingDate(timestamp: Long): String = SimpleDateFormat("MM月dd日", Locale.CHINA).apply {
    timeZone = TimeZone.getTimeZone("Asia/Shanghai")
}.format(Date(timestamp * 1000))

private fun copyText(context: Context, label: String, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareText(context: Context, text: String) {
    if (text.isBlank()) return
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            },
            "分享邀请"
        )
    )
}

private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

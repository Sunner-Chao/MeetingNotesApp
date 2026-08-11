package com.oa.automation.ui.screen.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.AccountProfile
import com.oa.automation.infrastructure.llm.AgentQuota
import com.oa.automation.ui.theme.BrandDeepGreen
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import org.koin.androidx.compose.koinViewModel

private val DetailGreen = Color(0xFF0078D4)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountProfileScreen(
    onNavigateBack: () -> Unit,
    onNavigateToVip: () -> Unit,
    viewModel: AccountViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(viewModel::selectProfileAvatar) }

    LaunchedEffect(uiState.profileMessage) {
        uiState.profileMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearProfileMessage()
        }
    }

    if (uiState.isProfileEditorVisible) {
        ProfileEditorDialog(
            username = uiState.username,
            displayName = uiState.profileDraftDisplayName,
            avatarDataUrl = uiState.profileDraftAvatarDataUrl,
            isImageProcessing = uiState.isProfileImageProcessing,
            isSaving = uiState.isProfileSaving,
            error = uiState.profileError,
            onDisplayNameChange = viewModel::updateProfileDisplayName,
            onChooseAvatar = { avatarPicker.launch("image/*") },
            onRemoveAvatar = viewModel::clearProfileAvatar,
            onDismiss = viewModel::dismissProfileEdit,
            onSave = viewModel::saveProfile
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { AccountDetailTopBar("个人资料", onNavigateBack) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfileSummaryCard(
                username = uiState.username,
                profile = uiState.profile,
                onEdit = viewModel::startProfileEdit
            )
            MembershipDetailCard(
                profile = uiState.profile,
                quota = uiState.quota,
                onClick = onNavigateToVip
            )
            ProfileInformationCard(uiState.profile, uiState.username)
        }
    }
}

@Composable
private fun ProfileSummaryCard(
    username: String,
    profile: AccountProfile?,
    onEdit: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProfileAvatar(
                avatarDataUrl = profile?.avatarDataUrl,
                fallbackText = profile?.displayName.orEmpty().ifBlank { username },
                size = 78.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile?.displayName.orEmpty().ifBlank { username.ifBlank { "未命名用户" } },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "@$username",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (profile?.isAdmin == true) "管理员账户" else "个人账户",
                    style = MaterialTheme.typography.labelMedium,
                    color = DetailGreen
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑个人资料", tint = DetailGreen)
            }
        }
    }
}

@Composable
private fun MembershipDetailCard(
    profile: AccountProfile?,
    quota: AgentQuota?,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = BrandDeepGreen
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.10f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.WorkspacePremium,
                        contentDescription = null,
                        tint = Color(0xFFF2B84B),
                        modifier = Modifier.size(25.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (profile?.vipEnabled == true || profile?.isAdmin == true) {
                        "VIP 会员与专业能力"
                    } else {
                        "开通 VIP 会员"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = buildString {
                        append(profile?.planName ?: "Free")
                        quota?.expiresAt?.let { append(" · 有效期至 ${formatDetailDate(it)}") }
                    },
                    color = Color.White.copy(alpha = 0.64f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f)
            )
        }
    }
}

@Composable
private fun ProfileInformationCard(profile: AccountProfile?, username: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = "账户信息",
                    modifier = Modifier.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                DetailRow("登录账号", username.ifBlank { "-" })
                DetailRow("用户 ID", profile?.id ?: "-")
                DetailRow("账户角色", if (profile?.isAdmin == true) "管理员" else "普通用户")
                DetailRow("账户状态", if (profile?.enabled != false) "正常" else "已停用")
                DetailRow("当前套餐", profile?.planName ?: "Free")
                DetailRow("剩余次数", "${profile?.quota?.requestsRemaining ?: 0} 次")
                DetailRow("注册时间", profile?.createdAt?.let(::formatDetailDateTime) ?: "-")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountQuotaDetailsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: AccountViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AccountDetailTopBar(
                title = "额度明细",
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(onClick = viewModel::refreshAccount, enabled = !uiState.isQuotaLoading) {
                        if (uiState.isQuotaLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新额度")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuotaDetailsHero(uiState.quota, uiState.profile)
            uiState.quotaError?.let {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            QuotaInformationCard(uiState.quota, uiState.profile)
            if (!uiState.tokenConfigured) {
                Button(onClick = onNavigateToSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("前往服务设置")
                }
            }
        }
    }
}

@Composable
private fun QuotaDetailsHero(quota: AgentQuota?, profile: AccountProfile?) {
    val limit = quota?.requestLimit ?: profile?.quota?.requestLimit ?: 0
    val remaining = quota?.requestsRemaining ?: profile?.quota?.requestsRemaining ?: 0
    val fraction = if (limit > 0) (remaining.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val percent = floor(fraction * 10_000f) / 100f
    val formatter = remember { NumberFormat.getIntegerInstance(Locale.US) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        shape = RoundedCornerShape(18.dp),
        color = BrandDeepGreen
    ) {
        Box {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(DetailGreen.copy(alpha = 0.24f), Color.Transparent),
                        center = Offset(size.width * 0.74f, size.height * 0.54f),
                        radius = size.width * 0.5f
                    ),
                    radius = size.width * 0.5f,
                    center = Offset(size.width * 0.74f, size.height * 0.54f)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AI 处理额度", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        formatter.format(remaining),
                        color = Color(0xFF60CDFF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        lineHeight = 38.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("剩余请求", color = Color.White.copy(alpha = 0.84f))
                    Spacer(Modifier.height(15.dp))
                    Text(
                        "总额度 ${formatter.format(limit)} 请求",
                        color = Color.White.copy(alpha = 0.58f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Box(modifier = Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFF8CC8FF),
                        trackColor = Color.White.copy(alpha = 0.14f),
                        strokeWidth = 11.dp,
                        strokeCap = StrokeCap.Round
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            String.format(Locale.US, "%.2f%%", percent),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text("剩余额度", color = Color.White.copy(alpha = 0.58f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotaInformationCard(quota: AgentQuota?, profile: AccountProfile?) {
    val formatter = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val limit = quota?.requestLimit ?: profile?.quota?.requestLimit ?: 0
    val used = quota?.requestsUsed ?: profile?.quota?.requestsUsed ?: 0
    val remaining = quota?.requestsRemaining ?: profile?.quota?.requestsRemaining ?: 0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "套餐详情",
                modifier = Modifier.padding(vertical = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            DetailRow("套餐名称", quota?.label ?: profile?.planName ?: "Free")
            DetailRow("总额度", "${formatter.format(limit)} 次")
            DetailRow("已使用", "${formatter.format(used)} 次")
            DetailRow("剩余额度", "${formatter.format(remaining)} 次")
            DetailRow("有效期", quota?.expiresAt?.let(::formatDetailDate) ?: "长期有效")
            DetailRow(
                "可用智能体",
                quota?.allowedProviders
                    ?.joinToString(" · ") { provider ->
                        when (provider) {
                            "claude-cli" -> "智能体"
                            "codex-cli" -> "小Woo"
                            else -> provider
                        }
                    }
                    .orEmpty()
                    .ifBlank { "暂无" }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountUserManagementScreen(
    onNavigateBack: () -> Unit,
    viewModel: AccountViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var userPendingDeletion by remember { mutableStateOf<AccountProfile?>(null) }

    userPendingDeletion?.let { user ->
        AlertDialog(
            onDismissRequest = { userPendingDeletion = null },
            title = { Text("删除用户") },
            text = { Text("将永久删除“${user.username}”及其会话、会员权益、充值记录和 AI 任务。此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        userPendingDeletion = null
                        viewModel.deleteUser(user.id)
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { userPendingDeletion = null }) { Text("取消") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AccountDetailTopBar("用户管理", onNavigateBack) }
    ) { paddingValues ->
        when {
            uiState.profile != null && uiState.profile?.isAdmin != true -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text("仅管理员可访问用户管理", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            uiState.isManagingUsers && uiState.managedUsers.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    uiState.managementError?.let { error ->
                        item {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    items(uiState.managedUsers, key = { it.id }) { user ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            ManagedUserRow(
                                user = user,
                                processing = uiState.processingUserId == user.id,
                                onEnabledChange = { enabled -> viewModel.setUserEnabled(user.id, enabled) },
                                onDelete = { userPendingDeletion = user }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.width(82.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDetailTopBar(
    title: String,
    onNavigateBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = DetailGreen
        )
    )
}

private fun formatDetailDate(epochSeconds: Long): String = SimpleDateFormat(
    "yyyy-MM-dd",
    Locale.SIMPLIFIED_CHINESE
).format(Date(epochSeconds * 1000))

private fun formatDetailDateTime(epochSeconds: Long): String = SimpleDateFormat(
    "yyyy-MM-dd HH:mm",
    Locale.SIMPLIFIED_CHINESE
).format(Date(epochSeconds * 1000))

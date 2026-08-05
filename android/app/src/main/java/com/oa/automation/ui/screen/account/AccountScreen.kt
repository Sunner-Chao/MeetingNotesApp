package com.oa.automation.ui.screen.account

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oa.automation.domain.model.AccountProfile
import com.oa.automation.infrastructure.llm.AgentQuota
import com.oa.automation.ui.component.AppLauncherIcon
import com.oa.automation.ui.theme.BrandCyan
import com.oa.automation.ui.theme.BrandDeepGreen
import com.oa.automation.ui.theme.BrandDeepGreenAlt
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import org.koin.androidx.compose.koinViewModel

private val AccountGreen = Color(0xFF12B981)
private val AccountGold = Color(0xFFF2B84B)

private data class AccountLayoutSpec(
    val compact: Boolean,
    val pagePadding: Dp,
    val headerHeight: Dp,
    val profileTop: Dp,
    val profileHeight: Dp,
    val heroHeight: Dp,
    val profileAvatarSize: Dp,
    val quotaHeight: Dp,
    val progressSize: Dp,
    val actionRowHeight: Dp,
    val sectionSpacing: Dp
)

private fun accountLayoutSpec(maxWidth: Dp, maxHeight: Dp): AccountLayoutSpec {
    val compact = maxWidth < 400.dp || maxHeight < 760.dp
    return if (compact) {
        AccountLayoutSpec(
            compact = true,
            pagePadding = 16.dp,
            headerHeight = 150.dp,
            profileTop = 82.dp,
            profileHeight = 130.dp,
            heroHeight = 224.dp,
            profileAvatarSize = 68.dp,
            quotaHeight = 200.dp,
            progressSize = 112.dp,
            actionRowHeight = 58.dp,
            sectionSpacing = 10.dp
        )
    } else {
        AccountLayoutSpec(
            compact = false,
            pagePadding = 20.dp,
            headerHeight = 170.dp,
            profileTop = 96.dp,
            profileHeight = 142.dp,
            heroHeight = 252.dp,
            profileAvatarSize = 76.dp,
            quotaHeight = 216.dp,
            progressSize = 124.dp,
            actionRowHeight = 64.dp,
            sectionSpacing = 14.dp
        )
    }
}

@Composable
fun AccountScreen(
    onNavigateToProfile: () -> Unit,
    onNavigateToQuotaDetails: () -> Unit,
    onNavigateToUserManagement: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    viewModel: AccountViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showLogoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isLoggedOut) {
        if (uiState.isLoggedOut) onLogout()
    }
    LaunchedEffect(uiState.profileMessage) {
        uiState.profileMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearProfileMessage()
        }
    }
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("退出后需要重新输入用户名才能进入应用。") },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("确认退出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val layout = accountLayoutSpec(maxWidth, maxHeight)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                AccountHero(
                    username = uiState.username,
                    profile = uiState.profile,
                    quota = uiState.quota,
                    layout = layout,
                    onOpenProfile = onNavigateToProfile,
                    onLogout = { showLogoutDialog = true }
                )

                Column(
                    modifier = Modifier.padding(horizontal = layout.pagePadding),
                    verticalArrangement = Arrangement.spacedBy(layout.sectionSpacing)
                ) {
                    AccountQuotaPanel(
                        quota = uiState.quota,
                        profile = uiState.profile,
                        isLoading = uiState.isQuotaLoading,
                        tokenConfigured = uiState.tokenConfigured,
                        errorMessage = uiState.quotaError,
                        layout = layout,
                        onRefresh = viewModel::refreshAccount,
                        onConfigure = onNavigateToSettings,
                        onOpenDetails = onNavigateToQuotaDetails
                    )
                    AccountActionGroup(
                        isAdmin = uiState.profile?.isAdmin == true,
                        layout = layout,
                        onOpenProfile = onNavigateToProfile,
                        onOpenSettings = onNavigateToSettings,
                        onManageUsers = onNavigateToUserManagement
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun AccountHero(
    username: String,
    profile: AccountProfile?,
    quota: AgentQuota?,
    layout: AccountLayoutSpec,
    onOpenProfile: () -> Unit,
    onLogout: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(layout.heroHeight)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.headerHeight)
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF021B18), BrandDeepGreen, BrandDeepGreenAlt),
                        start = Offset.Zero,
                        end = Offset(1400f, 700f)
                    )
                )
        ) {
            AccountHeaderArtwork(Modifier.fillMaxSize())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = layout.pagePadding, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppLauncherIcon(
                        modifier = Modifier.size(if (layout.compact) 38.dp else 42.dp),
                        contentDescription = "智悟本"
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "智悟本",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (layout.compact) 23.sp else 25.sp,
                            lineHeight = 29.sp
                        )
                        Text(
                            text = "智能体 · 小Woo",
                            color = BrandCyan,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Surface(
                    onClick = onLogout,
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "退出登录",
                            tint = Color.White,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }

        IdentityCard(
            username = username,
            profile = profile,
            quota = quota,
            avatarSize = layout.profileAvatarSize,
            onClick = onOpenProfile,
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.profileHeight)
                .padding(horizontal = layout.pagePadding)
                .offset(y = layout.profileTop)
        )
    }
}

@Composable
private fun AccountHeaderArtwork(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val lineColor = AccountGreen.copy(alpha = 0.20f)
        repeat(5) { index ->
            val inset = index * 12.dp.toPx()
            drawArc(
                color = lineColor,
                startAngle = 202f,
                sweepAngle = 118f,
                useCenter = false,
                topLeft = Offset(size.width * 0.32f + inset, size.height * 0.20f + inset * 0.25f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.82f, size.height * 1.15f),
                style = Stroke(width = 0.8.dp.toPx())
            )
        }
        val path = Path().apply {
            moveTo(size.width * 0.60f, size.height * 0.74f)
            lineTo(size.width * 0.70f, size.height * 0.60f)
            lineTo(size.width * 0.79f, size.height * 0.68f)
            lineTo(size.width, size.height * 0.44f)
        }
        drawPath(path, color = AccountGreen.copy(alpha = 0.18f), style = Stroke(1.dp.toPx()))
        drawCircle(
            color = BrandCyan.copy(alpha = 0.45f),
            radius = 2.dp.toPx(),
            center = Offset(size.width * 0.79f, size.height * 0.68f)
        )
    }
}

@Composable
private fun IdentityCard(
    username: String,
    profile: AccountProfile?,
    quota: AgentQuota?,
    avatarSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProfileAvatar(
                avatarDataUrl = profile?.avatarDataUrl,
                fallbackText = profile?.displayName.orEmpty().ifBlank { username },
                size = avatarSize
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = profile?.displayName.orEmpty().ifBlank {
                        username.ifBlank { "未命名用户" }
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 23.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (profile?.vipEnabled == true || profile?.isAdmin == true) {
                        AccountGold
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (profile?.vipEnabled == true || profile?.isAdmin == true) {
                            Icon(
                                imageVector = Icons.Default.WorkspacePremium,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Text(
                            text = if (profile?.vipEnabled == true || profile?.isAdmin == true) {
                                "VIP"
                            } else {
                                "FREE"
                            },
                            color = if (profile?.vipEnabled == true || profile?.isAdmin == true) {
                                Color.White
                            } else {
                                AccountGreen
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = AccountGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = membershipSummary(profile, quota),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "查看 VIP 会员与专业能力",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun AccountQuotaPanel(
    quota: AgentQuota?,
    profile: AccountProfile?,
    isLoading: Boolean,
    tokenConfigured: Boolean,
    errorMessage: String?,
    layout: AccountLayoutSpec,
    onRefresh: () -> Unit,
    onConfigure: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val limit = quota?.requestLimit ?: profile?.quota?.requestLimit ?: 0
    val remaining = quota?.requestsRemaining ?: profile?.quota?.requestsRemaining ?: 0
    val remainingFraction = when {
        limit > 0 -> (remaining.toFloat() / limit).coerceIn(0f, 1f)
        remaining > 0 -> 1f
        else -> 0f
    }
    val formatter = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val percentage = remember(remainingFraction) {
        val percentValue = floor(remainingFraction * 10_000f) / 100f
        String.format(Locale.US, "%.2f%%", percentValue)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(layout.quotaHeight),
        shape = RoundedCornerShape(18.dp),
        color = BrandDeepGreen,
        shadowElevation = 2.dp
    ) {
        Box {
            QuotaCircuitArtwork(Modifier.fillMaxSize())
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "AI 处理额度",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = if (tokenConfigured) onRefresh else onConfigure,
                        enabled = !isLoading,
                        modifier = Modifier.size(34.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(17.dp),
                                color = BrandCyan,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = if (tokenConfigured) "刷新额度" else "配置服务",
                                tint = Color.White.copy(alpha = 0.82f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(3.dp))
                    Surface(
                        onClick = onOpenDetails,
                        shape = RoundedCornerShape(50),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.32f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "额度明细",
                                color = Color.White.copy(alpha = 0.90f),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.72f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = formatter.format(remaining),
                            color = Color(0xFF2FE08F),
                            fontWeight = FontWeight.Bold,
                            fontSize = if (layout.compact) 31.sp else 35.sp,
                            lineHeight = 39.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "剩余请求",
                            color = Color.White,
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        )
                    }
                    Box(
                        modifier = Modifier.size(layout.progressSize),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = BrandCyan.copy(alpha = 0.14f),
                                radius = size.minDimension / 2f,
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                        CircularProgressIndicator(
                            progress = { remainingFraction },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(7.dp),
                            color = Color(0xFF2FE6B1),
                            trackColor = Color.White.copy(alpha = 0.14f),
                            strokeWidth = 10.dp,
                            strokeCap = StrokeCap.Round
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = percentage,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (layout.compact) 17.sp else 19.sp,
                                lineHeight = 22.sp
                            )
                            Text(
                                text = "剩余额度",
                                color = Color.White.copy(alpha = 0.58f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                Text(
                    text = when {
                        errorMessage != null && quota == null -> errorMessage
                        !tokenConfigured -> "点击刷新图标配置服务"
                        else -> "总额度 ${formatter.format(limit)} 请求"
                    },
                    color = if (errorMessage != null && quota == null) {
                        Color(0xFFFFC4BC)
                    } else {
                        Color.White.copy(alpha = 0.58f)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun QuotaCircuitArtwork(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(AccountGreen.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width * 0.72f, size.height * 0.62f),
                radius = size.width * 0.48f
            ),
            radius = size.width * 0.48f,
            center = Offset(size.width * 0.72f, size.height * 0.62f)
        )
        val circuitColor = AccountGreen.copy(alpha = 0.22f)
        repeat(4) { index ->
            val y = size.height * (0.70f + index * 0.055f)
            val path = Path().apply {
                moveTo(size.width * (0.42f + index * 0.025f), y)
                lineTo(size.width * 0.55f, y)
                lineTo(size.width * 0.61f, y - 13.dp.toPx())
                lineTo(size.width * 0.82f, y - 13.dp.toPx())
            }
            drawPath(path, circuitColor, style = Stroke(width = 0.8.dp.toPx()))
            drawCircle(
                color = BrandCyan.copy(alpha = 0.45f),
                radius = 1.8.dp.toPx(),
                center = Offset(size.width * (0.42f + index * 0.025f), y)
            )
        }
    }
}

@Composable
private fun AccountActionGroup(
    isAdmin: Boolean,
    layout: AccountLayoutSpec,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onManageUsers: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            AccountActionRow(
                icon = Icons.Default.AccountBox,
                title = "账户管理",
                rowHeight = layout.actionRowHeight,
                onClick = onOpenProfile
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 18.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            AccountActionRow(
                icon = Icons.Default.Settings,
                title = "服务设置",
                rowHeight = layout.actionRowHeight,
                onClick = onOpenSettings
            )
            if (isAdmin) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                AccountActionRow(
                    icon = Icons.Default.ManageAccounts,
                    title = "用户管理",
                    rowHeight = layout.actionRowHeight,
                    onClick = onManageUsers
                )
            }
        }
    }
}

@Composable
private fun AccountActionRow(
    icon: ImageVector,
    title: String,
    rowHeight: Dp,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = AccountGreen,
                        modifier = Modifier.size(23.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                lineHeight = 23.sp
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(25.dp)
            )
        }
    }
}

@Composable
private fun AdminManagementPanel(
    users: List<AccountProfile>,
    isLoading: Boolean,
    processingUserId: String?,
    error: String?,
    onEnabledChange: (String, Boolean) -> Unit,
    onDelete: (AccountProfile) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "用户管理",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            error?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            users.forEachIndexed { index, user ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                ManagedUserRow(
                    user = user,
                    processing = processingUserId == user.id,
                    onEnabledChange = { enabled -> onEnabledChange(user.id, enabled) },
                    onDelete = { onDelete(user) }
                )
            }
        }
    }
}

@Composable
internal fun ManagedUserRow(
    user: AccountProfile,
    processing: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.displayName.ifBlank { user.username },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    user.isAdmin -> "@${user.username} · 管理员 · 默认 VIP"
                    user.vipEnabled -> "@${user.username} · ${user.planName} · 剩余 ${user.quota.requestsRemaining}"
                    else -> "@${user.username} · ${user.planName} · 剩余 ${user.quota.requestsRemaining} 次试用"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (processing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Switch(
                checked = user.enabled,
                onCheckedChange = onEnabledChange,
                enabled = !user.isAdmin
            )
            if (!user.isAdmin) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除用户 ${user.username}",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProfileEditorDialog(
    username: String,
    displayName: String,
    avatarDataUrl: String?,
    isImageProcessing: Boolean,
    isSaving: Boolean,
    error: String?,
    onDisplayNameChange: (String) -> Unit,
    onChooseAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑个人资料") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ProfileAvatar(
                    avatarDataUrl = avatarDataUrl,
                    fallbackText = displayName.ifBlank { username },
                    size = 80.dp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onChooseAvatar,
                        enabled = !isImageProcessing && !isSaving
                    ) {
                        Text("更换头像")
                    }
                    if (avatarDataUrl != null) {
                        TextButton(
                            onClick = onRemoveAvatar,
                            enabled = !isImageProcessing && !isSaving
                        ) {
                            Text("移除")
                        }
                    }
                }
                if (isImageProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("昵称") },
                    singleLine = true,
                    enabled = !isSaving
                )
                Text(
                    text = "登录账号：$username",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Text(
                        text = it,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !isImageProcessing && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text("取消") }
        },
        shape = MaterialTheme.shapes.large
    )
}

@Composable
internal fun ProfileAvatar(
    avatarDataUrl: String?,
    fallbackText: String,
    size: Dp
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
        modifier = Modifier
            .size(size)
            .border(4.dp, MaterialTheme.colorScheme.primaryContainer, CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "用户头像",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = fallbackText.firstOrNull()?.uppercase() ?: "悟",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF075A3E),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun membershipSummary(profile: AccountProfile?, quota: AgentQuota?): String {
    val membership = when {
        profile?.isAdmin == true -> "尊享会员"
        profile?.vipEnabled == true -> "${profile.planName} · 尊享会员"
        profile != null -> "${profile.planName} · 剩余 ${profile.quota.requestsRemaining} 次体验"
        else -> "Free 套餐"
    }
    val expiry = quota?.expiresAt?.let(::formatExpiry)
    return if (expiry != null && (profile?.vipEnabled == true || profile?.isAdmin == true)) {
        "$membership  |  有效期至 $expiry"
    } else {
        membership
    }
}

private fun formatExpiry(epochSeconds: Long): String = SimpleDateFormat(
    "yyyy-MM-dd",
    Locale.SIMPLIFIED_CHINESE
).format(Date(epochSeconds * 1000))

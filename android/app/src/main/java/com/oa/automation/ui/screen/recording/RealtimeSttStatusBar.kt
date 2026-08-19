package com.oa.automation.ui.screen.recording

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oa.automation.infrastructure.service.RealtimeSttRouteState

internal enum class RealtimeSttTone {
    LOCAL,
    TRANSITION,
    CLOUD,
    ERROR
}

internal data class RealtimeSttStatusPresentation(
    val title: String,
    val detail: String,
    val tone: RealtimeSttTone,
    val inProgress: Boolean,
    val icon: ImageVector
)

internal fun realtimeSttStatusPresentation(
    route: RealtimeSttRouteState
): RealtimeSttStatusPresentation? = when (route) {
    RealtimeSttRouteState.IDLE -> null
    RealtimeSttRouteState.LOCAL_CONNECTING -> RealtimeSttStatusPresentation(
        title = "正在连接本地识别",
        detail = "本地优先 · 云端待命",
        tone = RealtimeSttTone.LOCAL,
        inProgress = true,
        icon = Icons.Default.Mic
    )
    RealtimeSttRouteState.LOCAL_ACTIVE -> RealtimeSttStatusPresentation(
        title = "本地识别",
        detail = "连接稳定 · 云端待命",
        tone = RealtimeSttTone.LOCAL,
        inProgress = false,
        icon = Icons.Default.Mic
    )
    RealtimeSttRouteState.LOCAL_RECOVERING -> RealtimeSttStatusPresentation(
        title = "本地连接波动",
        detail = "正在快速恢复 · 最多 3 秒",
        tone = RealtimeSttTone.TRANSITION,
        inProgress = true,
        icon = Icons.Default.Mic
    )
    RealtimeSttRouteState.SWITCHING_TO_CLOUD -> RealtimeSttStatusPresentation(
        title = "正在转接云端识别",
        detail = "本地连接中断 · 录音持续保存",
        tone = RealtimeSttTone.TRANSITION,
        inProgress = true,
        icon = Icons.Default.Cloud
    )
    RealtimeSttRouteState.CLOUD_CONNECTING -> RealtimeSttStatusPresentation(
        title = "正在连接云端识别",
        detail = "正在确认安全连接",
        tone = RealtimeSttTone.CLOUD,
        inProgress = true,
        icon = Icons.Default.Cloud
    )
    RealtimeSttRouteState.CLOUD_ACTIVE -> RealtimeSttStatusPresentation(
        title = "云端识别",
        detail = "实时连接已就绪",
        tone = RealtimeSttTone.CLOUD,
        inProgress = false,
        icon = Icons.Default.Cloud
    )
    RealtimeSttRouteState.CLOUD_FALLBACK_ACTIVE -> RealtimeSttStatusPresentation(
        title = "云端识别已接管",
        detail = "本地连接中断 · 录音未中断",
        tone = RealtimeSttTone.CLOUD,
        inProgress = false,
        icon = Icons.Default.Cloud
    )
    RealtimeSttRouteState.UNAVAILABLE -> RealtimeSttStatusPresentation(
        title = "实时识别暂不可用",
        detail = "录音仍在本机保存",
        tone = RealtimeSttTone.ERROR,
        inProgress = false,
        icon = Icons.Default.Close
    )
}

@Composable
internal fun RealtimeSttStatusBar(
    route: RealtimeSttRouteState,
    modifier: Modifier = Modifier
) {
    Crossfade(
        targetState = route,
        animationSpec = tween(durationMillis = 220),
        label = "realtime-stt-route",
        modifier = modifier
    ) { currentRoute ->
        val presentation = realtimeSttStatusPresentation(currentRoute)
        if (presentation == null) {
            Spacer(Modifier)
            return@Crossfade
        }
        val isDark = com.oa.automation.ui.theme.LocalAppIsDarkTheme.current
        val accent = when (presentation.tone) {
            RealtimeSttTone.LOCAL -> if (isDark) Color(0xFF6CCB5F) else Color(0xFF107C10)
            RealtimeSttTone.TRANSITION -> if (isDark) Color(0xFFFFCA5C) else Color(0xFF9D5D00)
            RealtimeSttTone.CLOUD -> if (isDark) Color(0xFF60CDFF) else Color(0xFF0067C0)
            RealtimeSttTone.ERROR -> if (isDark) Color(0xFFFF8A8A) else Color(0xFFC42B1C)
        }
        val contentColor = MaterialTheme.colorScheme.onSurface
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = accent.copy(alpha = if (isDark) 0.12f else 0.08f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(accent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (presentation.inProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = accent,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = presentation.icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Spacer(Modifier.width(9.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = presentation.title,
                        color = contentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = presentation.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

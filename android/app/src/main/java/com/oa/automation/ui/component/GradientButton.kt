package com.oa.automation.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ════════════════════════════════════════════════════════════════════════════
// GradientButton - 渐变按钮组件
// 提供视觉冲击力强的CTA按钮
// ════════════════════════════════════════════════════════════════════════════

/**
 * 渐变按钮
 *
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param enabled 是否启用
 * @param gradient 渐变色列表
 * @param contentPadding 内边距
 * @param elevation 阴影高度
 * @param content 按钮内容
 */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gradient: List<Color> = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary
    ),
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    elevation: Dp = 4.dp,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "buttonScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (enabled) elevation else 0.dp,
                shape = RoundedCornerShape(8.dp),
                ambientColor = gradient.first().copy(alpha = 0.3f)
            )
            .clip(RoundedCornerShape(8.dp))
            .background(
                brush = if (enabled) {
                    Brush.horizontalGradient(gradient)
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            )
            .clickable(
                onClick = onClick,
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null
            )
            .defaultMinSize(minHeight = 48.dp)
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            ProvideTextStyle(value = MaterialTheme.typography.labelLarge) {
                Row(
                    modifier = Modifier
                        .padding(contentPadding)
                        .align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }
    }
}

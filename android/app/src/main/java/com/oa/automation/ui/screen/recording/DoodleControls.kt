package com.oa.automation.ui.screen.recording

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-drawn circular button with icon.
 */
@Composable
fun DoodleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    skin: DoodleSkin,
    size: Dp = 48.dp,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.4f,
        animationSpec = tween(150),
        label = "doodleIconButtonAlpha"
    )

    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = false, radius = size / 2),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val centerPx = this.size.width / 2f
            doodleCircle(
                center = Offset(centerPx, centerPx),
                radius = centerPx - skin.strokeWidth.toPx(),
                color = skin.ink.copy(alpha = alpha),
                strokeWidth = skin.strokeWidth.toPx(),
                wobbleAmplitude = skin.wobbleAmplitude
            )
        }
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = skin.ink.copy(alpha = alpha),
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

/**
 * Hand-drawn round action button (like "插图").
 */
@Composable
fun DoodleRoundAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    skin: DoodleSkin,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.4f,
        animationSpec = tween(150),
        label = "doodleRoundActionAlpha"
    )

    Column(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = rememberRipple(bounded = false),
            enabled = enabled,
            onClick = onClick
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(54.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val centerPx = this.size.width / 2f
                doodleCircle(
                    center = Offset(centerPx, centerPx),
                    radius = centerPx - skin.strokeWidth.toPx(),
                    color = skin.ink.copy(alpha = alpha),
                    strokeWidth = skin.strokeWidth.toPx(),
                    wobbleAmplitude = skin.wobbleAmplitude
                )
            }
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = skin.ink.copy(alpha = alpha),
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = label,
            color = skin.inkMuted.copy(alpha = alpha),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            fontWeight = FontWeight.Normal
        )
    }
}

/**
 * Hand-drawn mic orb with pulsing ring when recording.
 */
@Composable
fun DoodleMicOrb(
    isRecording: Boolean,
    isPaused: Boolean,
    audioLevel: Float,
    onClick: () -> Unit,
    skin: DoodleSkin,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.4f,
        animationSpec = tween(150),
        label = "doodleMicOrbAlpha"
    )
    val pulseRadius by animateFloatAsState(
        targetValue = if (isRecording && !isPaused) 1f + audioLevel * 0.15f else 1f,
        animationSpec = tween(100),
        label = "doodleMicOrbPulse"
    )

    Box(
        modifier = modifier
            .size(84.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = false, radius = 42.dp),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val centerPx = this.size.width / 2f
            val baseRadius = centerPx - skin.strokeWidth.toPx() * 2f
            val actualRadius = baseRadius * pulseRadius

            // Outer ring
            doodleCircle(
                center = Offset(centerPx, centerPx),
                radius = actualRadius,
                color = if (isRecording) skin.accentRed.copy(alpha = alpha) else skin.ink.copy(alpha = alpha),
                strokeWidth = skin.strokeWidth.toPx() * 1.5f,
                wobbleAmplitude = skin.wobbleAmplitude
            )

            // Inner fill
            doodleCircle(
                center = Offset(centerPx, centerPx),
                radius = actualRadius * 0.7f,
                color = if (isRecording) skin.accentRed.copy(alpha = alpha * 0.2f) else skin.ink.copy(alpha = alpha * 0.1f),
                strokeWidth = 0f,
                filled = true
            )
        }
        androidx.compose.material3.Icon(
            imageVector = androidx.compose.material.icons.Icons.Default.Mic,
            contentDescription = null,
            tint = if (isRecording) skin.accentRed.copy(alpha = alpha) else skin.ink.copy(alpha = alpha),
            modifier = Modifier.size(36.dp)
        )
    }
}

/**
 * Hand-drawn pill-shaped button (like "生成纪要").
 */
@Composable
fun DoodlePillButton(
    text: String,
    onClick: () -> Unit,
    skin: DoodleSkin,
    enabled: Boolean = true,
    accent: Boolean = false,
    modifier: Modifier = Modifier
) {
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.4f,
        animationSpec = tween(150),
        label = "doodlePillAlpha"
    )
    val color = if (accent) skin.accentCyan else skin.ink

    Box(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = true),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = this.size.width
            val h = this.size.height
            val radius = h / 2f
            val path = Path().apply {
                // Left semicircle
                arcTo(
                    rect = androidx.compose.ui.geometry.Rect(
                        left = 0f,
                        top = 0f,
                        right = h,
                        bottom = h
                    ),
                    startAngleDegrees = 90f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false
                )
                // Top edge
                lineTo(w - radius, 0f)
                // Right semicircle
                arcTo(
                    rect = androidx.compose.ui.geometry.Rect(
                        left = w - h,
                        top = 0f,
                        right = w,
                        bottom = h
                    ),
                    startAngleDegrees = 270f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false
                )
                // Bottom edge
                lineTo(radius, h)
                close()
            }

            drawPath(
                path = path,
                color = color.copy(alpha = alpha),
                style = Stroke(
                    width = skin.strokeWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
        Text(
            text = text,
            color = color.copy(alpha = alpha),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            fontWeight = FontWeight.Medium
        )
    }
}

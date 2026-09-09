package com.oa.automation.ui.screen.recording

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Doodle skin: unified palette + stroke constants for hand-drawn components.
 */
data class DoodleSkin(
    val paper: Color,
    val ink: Color,
    val inkMuted: Color,
    val accentRed: Color,
    val accentCyan: Color,
    val accentViolet: Color,
    val strokeWidth: Dp = 2.2.dp,
    val wobbleAmplitude: Float = 1.8f
)

@Composable
fun rememberDoodleSkin(isDark: Boolean): DoodleSkin = remember(isDark) {
    if (isDark) {
        DoodleSkin(
            paper = Color(0xFF1B1A19),
            ink = Color(0xFFF3F2F1),
            inkMuted = Color(0xFFC8C6C4),
            accentRed = Color(0xFFFF5A5F),
            accentCyan = Color(0xFF60CDFF),
            accentViolet = Color(0xFF8E44AD)
        )
    } else {
        DoodleSkin(
            paper = Color(0xFFF5F5F5),
            ink = Color(0xFF242424),
            inkMuted = Color(0xFF605E5C),
            accentRed = Color(0xFFE54850),
            accentCyan = Color(0xFF0078D4),
            accentViolet = Color(0xFF6A3E98)
        )
    }
}

/**
 * Hand-drawn rounded rectangle with subtle wobble.
 */
fun DrawScope.doodleRoundRect(
    topLeft: Offset,
    size: Size,
    cornerRadius: CornerRadius,
    color: Color,
    strokeWidth: Float,
    wobbleAmplitude: Float = 1.8f,
    filled: Boolean = false
) {
    val path = Path().apply {
        val w = size.width
        val h = size.height
        val rx = cornerRadius.x.coerceAtMost(w / 2f)
        val ry = cornerRadius.y.coerceAtMost(h / 2f)
        val segments = 48
        val thetaStep = (2.0 * PI / segments).toFloat()
        var firstPoint: Offset? = null

        fun wobble(t: Float): Float = (sin(t * 7.3) * wobbleAmplitude).toFloat()

        // Top edge
        for (i in 0 until (segments / 4)) {
            val t = i.toFloat() / (segments / 4f)
            val x = topLeft.x + rx + t * (w - 2f * rx) + wobble(t)
            val y = topLeft.y + wobble(t)
            if (firstPoint == null) {
                moveTo(x, y)
                firstPoint = Offset(x, y)
            } else {
                lineTo(x, y)
            }
        }

        // Top-right arc
        for (i in 0 until (segments / 4)) {
            val angle = -PI / 2.0 + i * thetaStep
            val x = topLeft.x + w - rx + (rx * cos(angle)).toFloat() + wobble(i.toFloat())
            val y = topLeft.y + ry + (ry * sin(angle)).toFloat() + wobble(i.toFloat())
            lineTo(x, y)
        }

        // Right edge
        for (i in 0 until (segments / 4)) {
            val t = i.toFloat() / (segments / 4f)
            val x = topLeft.x + w + wobble(t)
            val y = topLeft.y + ry + t * (h - 2f * ry) + wobble(t)
            lineTo(x, y)
        }

        // Bottom-right arc
        for (i in 0 until (segments / 4)) {
            val angle = 0.0 + i * thetaStep
            val x = topLeft.x + w - rx + (rx * cos(angle)).toFloat() + wobble(i.toFloat())
            val y = topLeft.y + h - ry + (ry * sin(angle)).toFloat() + wobble(i.toFloat())
            lineTo(x, y)
        }

        // Bottom edge
        for (i in 0 until (segments / 4)) {
            val t = i.toFloat() / (segments / 4f)
            val x = topLeft.x + w - rx - t * (w - 2f * rx) + wobble(t)
            val y = topLeft.y + h + wobble(t)
            lineTo(x, y)
        }

        // Bottom-left arc
        for (i in 0 until (segments / 4)) {
            val angle = PI / 2.0 + i * thetaStep
            val x = topLeft.x + rx + (rx * cos(angle)).toFloat() + wobble(i.toFloat())
            val y = topLeft.y + h - ry + (ry * sin(angle)).toFloat() + wobble(i.toFloat())
            lineTo(x, y)
        }

        // Left edge
        for (i in 0 until (segments / 4)) {
            val t = i.toFloat() / (segments / 4f)
            val x = topLeft.x + wobble(t)
            val y = topLeft.y + h - ry - t * (h - 2f * ry) + wobble(t)
            lineTo(x, y)
        }

        // Top-left arc back to start
        for (i in 0 until (segments / 4)) {
            val angle = PI + i * thetaStep
            val x = topLeft.x + rx + (rx * cos(angle)).toFloat() + wobble(i.toFloat())
            val y = topLeft.y + ry + (ry * sin(angle)).toFloat() + wobble(i.toFloat())
            lineTo(x, y)
        }

        close()
    }

    if (filled) {
        drawPath(path, color = color)
    } else {
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

/**
 * Hand-drawn circle.
 */
fun DrawScope.doodleCircle(
    center: Offset,
    radius: Float,
    color: Color,
    strokeWidth: Float,
    wobbleAmplitude: Float = 1.2f,
    filled: Boolean = false
) {
    val segments = 36
    val path = Path()
    var first = true
    for (i in 0..segments) {
        val angle = (i.toFloat() / segments) * 2.0 * PI
        val wobble = sin(angle * 5.0) * wobbleAmplitude
        val r = radius + wobble.toFloat()
        val x = center.x + (r * cos(angle)).toFloat()
        val y = center.y + (r * sin(angle)).toFloat()
        if (first) {
            path.moveTo(x, y)
            first = false
        } else {
            path.lineTo(x, y)
        }
    }
    path.close()

    if (filled) {
        drawPath(path, color = color)
    } else {
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

/**
 * Hand-drawn line.
 */
fun DrawScope.doodleLine(
    start: Offset,
    end: Offset,
    color: Color,
    strokeWidth: Float,
    wobbleAmplitude: Float = 1.0f
) {
    val segments = 16
    val path = Path()
    path.moveTo(start.x, start.y)
    for (i in 1..segments) {
        val t = i.toFloat() / segments
        val wobble = sin(t * PI * 3.0) * wobbleAmplitude
        val x = start.x + t * (end.x - start.x) + wobble.toFloat()
        val y = start.y + t * (end.y - start.y) + wobble.toFloat()
        path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

/**
 * Wrapper composable for a doodle-bordered content box.
 */
@Composable
fun DoodleCard(
    skin: DoodleSkin,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            doodleRoundRect(
                topLeft = Offset.Zero,
                size = this.size,
                cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
                color = if (filled) skin.paper else skin.ink,
                strokeWidth = skin.strokeWidth.toPx(),
                wobbleAmplitude = skin.wobbleAmplitude,
                filled = filled
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

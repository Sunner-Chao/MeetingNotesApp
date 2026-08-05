package com.oa.automation.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A counter-clockwise flowing progress trail around a card or full screen. */
@Composable
fun FlowingProgressBorder(
    active: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    inset: Dp = 3.dp,
    strokeWidth: Dp = 2.2.dp,
    colors: List<Color> = listOf(
        Color(0xFF35E7FF),
        Color(0xFFB88BFF),
        Color(0xFFFF78C7),
        Color(0xFF35E7FF)
    ),
    content: @Composable BoxScope.() -> Unit
) {
    val transition = rememberInfiniteTransition(label = "flowingProgressBorder")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flowingProgressPhase"
    )

    Box(modifier = modifier) {
        content()
        if (active) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val borderInset = inset.toPx()
                val left = borderInset
                val top = borderInset
                val right = size.width - borderInset
                val bottom = size.height - borderInset
                val radius = cornerRadius.toPx().coerceAtMost((right - left) / 2f)
                val path = roundedRectPath(left, top, right, bottom, radius)
                val measure = PathMeasure()
                measure.setPath(path, false)
                val length = measure.length
                if (length > 0f) {
                    // Decreasing distance makes the head travel counter-clockwise.
                    val head = (1f - phase) * length
                    val trailLength = length * 0.23f
                    val steps = 11
                    val width = strokeWidth.toPx()

                    fun drawWrappedSegment(
                        startDistance: Float,
                        segmentLength: Float,
                        color: Color,
                        lineWidth: Float
                    ) {
                        var start = ((startDistance % length) + length) % length
                        var remaining = segmentLength
                        while (remaining > 0.01f) {
                            val stop = (start + remaining).coerceAtMost(length)
                            val segment = Path()
                            if (measure.getSegment(start, stop, segment, true)) {
                                drawPath(
                                    path = segment,
                                    color = color,
                                    style = Stroke(width = lineWidth, cap = StrokeCap.Round)
                                )
                            }
                            remaining -= stop - start
                            start = 0f
                        }
                    }

                    for (index in steps downTo 1) {
                        val tail = head - trailLength * index / steps
                        val fraction = 1f - index / (steps + 1f)
                        val color = colors[index % colors.size].copy(alpha = 0.12f + fraction * 0.18f)
                        drawWrappedSegment(tail, trailLength / steps * 1.25f, color, width * (2.8f - fraction))
                    }
                    drawWrappedSegment(
                        head - trailLength / steps,
                        trailLength / steps * 1.55f,
                        Color.White.copy(alpha = 0.45f),
                        width * 4.4f
                    )
                    drawWrappedSegment(
                        head - trailLength / steps,
                        trailLength / steps * 1.35f,
                        colors[0].copy(alpha = 0.98f),
                        width * 1.15f
                    )
                }
            }
        }
    }
}

private fun roundedRectPath(left: Float, top: Float, right: Float, bottom: Float, radius: Float): Path =
    Path().apply {
        moveTo(left + radius, top)
        lineTo(right - radius, top)
        quadraticTo(right, top, right, top + radius)
        lineTo(right, bottom - radius)
        quadraticTo(right, bottom, right - radius, bottom)
        lineTo(left + radius, bottom)
        quadraticTo(left, bottom, left, bottom - radius)
        lineTo(left, top + radius)
        quadraticTo(left, top, left + radius, top)
        close()
    }

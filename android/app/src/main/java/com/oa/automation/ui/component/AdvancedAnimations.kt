package com.oa.automation.ui.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// ════════════════════════════════════════════════════════════════════════════
// 高级动画组件库
// 用于提升产品的视觉吸引力和用户体验
// ════════════════════════════════════════════════════════════════════════════

/**
 * 波浪进度条
 *
 * 用于显示录音进度、报告生成进度等
 */
@Composable
fun WaveProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    waveColor: Color = MaterialTheme.colorScheme.primary,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    waveHeight: Dp = 8.dp,
    animationDuration: Int = 2000
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveOffset"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(8.dp),
            color = backgroundColor
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val waveHeightPx = waveHeight.toPx()
                val progressWidth = width * progress

                // 绘制波浪路径
                val path = androidx.compose.ui.graphics.Path()
                path.moveTo(0f, height / 2)

                var x = 0f
                while (x < progressWidth) {
                    val y = height / 2 + waveHeightPx * sin(Math.toRadians((x / width * 360 + waveOffset).toDouble())).toFloat()
                    path.lineTo(x, y)
                    x += 1f
                }

                path.lineTo(progressWidth, height)
                path.lineTo(0f, height)
                path.close()

                drawPath(
                    path = path,
                    color = waveColor
                )
            }
        }

        // 进度文字
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (progress > 0.5f) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/**
 * 圆形渐变进度条
 *
 * 用于显示报告生成进度、上传进度等
 */
@Composable
fun CircularGradientProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    strokeWidth: Dp = 12.dp,
    gradientColors: List<Color> = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary
    ),
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = this.size.minDimension
            val radius = (canvasSize - strokeWidth.toPx()) / 2
            val center = Offset(canvasSize / 2, canvasSize / 2)

            // 背景圆环
            drawCircle(
                color = backgroundColor,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            )

            // 进度圆弧
            val sweepAngle = 360f * progress
            drawArc(
                brush = Brush.sweepGradient(
                    colors = gradientColors,
                    center = center
                ),
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
                topLeft = Offset(strokeWidth.toPx() / 2, strokeWidth.toPx() / 2),
                size = androidx.compose.ui.geometry.Size(canvasSize - strokeWidth.toPx(), canvasSize - strokeWidth.toPx())
            )
        }

        content()
    }
}

/**
 * 脉冲动画容器
 *
 * 用于强调重要元素（录音按钮、通知徽章等）
 */
@Composable
fun PulsingContainer(
    modifier: Modifier = Modifier,
    pulseColor: Color = MaterialTheme.colorScheme.primary,
    pulseCount: Int = 3,
    animationDuration: Int = 1500,
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 绘制脉冲波纹
        repeat(pulseCount) { index ->
            val delay = (animationDuration / pulseCount) * index
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.8f,
                targetValue = 2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = animationDuration,
                        delayMillis = delay,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulseScale$index"
            )

            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = animationDuration,
                        delayMillis = delay,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulseAlpha$index"
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2
                val centerY = size.height / 2
                val radius = size.minDimension / 2 * scale

                drawCircle(
                    color = pulseColor.copy(alpha = alpha),
                    radius = radius,
                    center = Offset(centerX, centerY)
                )
            }
        }

        content()
    }
}

/**
 * 粒子爆炸效果
 *
 * 用于庆祝动画（完成会议、生成报告成功等）
 */
@Composable
fun ParticleExplosion(
    trigger: Boolean,
    modifier: Modifier = Modifier,
    particleCount: Int = 20,
    particleColor: Color = MaterialTheme.colorScheme.primary,
    onAnimationComplete: () -> Unit = {}
) {
    if (!trigger) return

    val particles = remember {
        List(particleCount) {
            Particle(
                angle = (360f / particleCount) * it,
                speed = (50..150).random().toFloat()
            )
        }
    }

    LaunchedEffect(trigger) {
        kotlinx.coroutines.delay(1000)
        onAnimationComplete()
    }

    particles.forEach { particle ->
        val offsetX by animateFloatAsState(
            targetValue = if (trigger) particle.speed * cos(Math.toRadians(particle.angle.toDouble())).toFloat() else 0f,
            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            label = "particleX"
        )

        val offsetY by animateFloatAsState(
            targetValue = if (trigger) particle.speed * sin(Math.toRadians(particle.angle.toDouble())).toFloat() else 0f,
            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            label = "particleY"
        )

        val alpha by animateFloatAsState(
            targetValue = if (trigger) 0f else 1f,
            animationSpec = tween(durationMillis = 800),
            label = "particleAlpha"
        )

        Canvas(modifier = modifier) {
            drawCircle(
                color = particleColor.copy(alpha = alpha),
                radius = 4.dp.toPx(),
                center = Offset(
                    x = size.width / 2 + offsetX,
                    y = size.height / 2 + offsetY
                )
            )
        }
    }
}

private data class Particle(
    val angle: Float,
    val speed: Float
)

/**
 * 呼吸灯效果
 *
 * 用于录音状态指示、通知提醒等
 */
@Composable
fun BreathingLight(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.error,
    minAlpha: Float = 0.3f,
    maxAlpha: Float = 1f,
    animationDuration: Int = 1000
) {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = minAlpha,
        targetValue = maxAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDuration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingAlpha"
    )

    Canvas(modifier = modifier.size(16.dp)) {
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = size.minDimension / 2
        )
    }
}

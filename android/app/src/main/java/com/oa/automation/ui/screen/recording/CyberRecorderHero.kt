package com.oa.automation.ui.screen.recording

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oa.automation.R
import com.oa.automation.ui.theme.LocalAppIsDarkTheme
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun CyberRecorderHero(
    isRecording: Boolean,
    isTranscribing: Boolean,
    isGeneratingReport: Boolean,
    actionEnabled: Boolean,
    durationSeconds: Long,
    status: String,
    progressPercent: Int?,
    processingStage: String,
    height: Dp,
    microphoneSize: Dp,
    onMainAction: () -> Unit,
    onCancelProcessing: () -> Unit
) {
    if (!LocalAppIsDarkTheme.current) {
        DaylightRecorderHero(
            isRecording = isRecording,
            isTranscribing = isTranscribing,
            isGeneratingReport = isGeneratingReport,
            actionEnabled = actionEnabled,
            durationSeconds = durationSeconds,
            status = status,
            progressPercent = progressPercent,
            processingStage = processingStage,
            height = height,
            microphoneSize = microphoneSize,
            onMainAction = onMainAction,
            onCancelProcessing = onCancelProcessing
        )
        return
    }

    val isBusy = isTranscribing || isGeneratingReport
    val green = Color(0xFF42FF78)
    val darkGreen = Color(0xFF0DBA4E)
    val red = Color(0xFFFF3D38)
    val backgroundRes = when {
        isBusy -> R.drawable.recorder_hud_processing
        isRecording -> R.drawable.recorder_hud_recording
        else -> R.drawable.recorder_hud_idle
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val impact = remember { Animatable(1f) }
    val infinite = rememberInfiniteTransition(label = "cyberHud")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "press"
    )
    val bottomScrim = remember {
        Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.16f), Color.Black.copy(alpha = 0.96f))
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(5.dp),
        color = Color(0xFF010704),
        border = BorderStroke(1.dp, darkGreen.copy(alpha = 0.78f)),
        shadowElevation = 3.dp
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxHeight < 205.dp
            val actionSize = minOf(
                microphoneSize * if (compact) 0.88f else 1.02f,
                maxHeight * 0.47f
            )
            val centerFraction = if (isBusy) 0.29f else 0.36f
            val actionTop = (maxHeight * centerFraction - actionSize / 2f).coerceAtLeast(3.dp)

            Image(
                painter = painterResource(backgroundRes),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = if (isPressed && !isBusy) 0.992f else 1f
                        scaleY = if (isPressed && !isBusy) 0.992f else 1f
                    },
                contentScale = ContentScale.Crop
            )
            Box(modifier = Modifier.fillMaxSize().background(bottomScrim))

            Canvas(modifier = Modifier.fillMaxSize()) {
                var lineY = 0f
                val spacing = 7.dp.toPx()
                while (lineY < size.height) {
                    drawLine(
                        color = green.copy(alpha = 0.055f),
                        start = Offset(0f, lineY),
                        end = Offset(size.width, lineY),
                        strokeWidth = 0.55.dp.toPx()
                    )
                    lineY += spacing
                }
                val scanY = phase * size.height
                drawLine(
                    color = green.copy(alpha = 0.24f),
                    start = Offset(0f, scanY),
                    end = Offset(size.width, scanY),
                    strokeWidth = 1.dp.toPx()
                )

                val center = Offset(size.width / 2f, size.height * centerFraction)
                val radius = actionSize.toPx() / 2f
                if (impact.value < 1f) {
                    val completion = impact.value
                    val fade = (1f - completion) * (1f - completion)
                    drawCircle(
                        color = green.copy(alpha = fade * 0.92f),
                        radius = radius * (1f + completion * 1.25f),
                        center = center,
                        style = Stroke(width = (0.8.dp + 3.dp * fade).toPx())
                    )
                    drawCircle(
                        color = green.copy(alpha = fade * 0.34f),
                        radius = radius * (1.18f + completion * 1.72f),
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
                if (isRecording) {
                    val pulse = 0.5f + 0.5f * sin(phase * (PI * 2f).toFloat())
                    drawCircle(
                        color = green.copy(alpha = 0.16f + pulse * 0.20f),
                        radius = radius * (1.08f + pulse * 0.12f),
                        center = center,
                        style = Stroke(width = 1.4.dp.toPx())
                    )
                    drawRecorderSideWaveforms(
                        center = center,
                        coreRadius = radius,
                        phase = phase,
                        color = green,
                        active = true
                    )
                }
            }

            if (!isBusy) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = actionTop)
                        .size(actionSize)
                        .graphicsLayer {
                            scaleX = pressScale
                            scaleY = pressScale
                        }
                        .clip(CircleShape)
                        .then(
                            if (actionEnabled) {
                                Modifier.clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    role = Role.Button,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        scope.launch {
                                            impact.snapTo(0f)
                                            impact.animateTo(
                                                targetValue = 1f,
                                                animationSpec = tween(560, easing = FastOutSlowInEasing)
                                            )
                                        }
                                        onMainAction()
                                    }
                                )
                            } else {
                                Modifier
                            }
                        )
                )
            }

            if (isRecording) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp),
                    shape = RoundedCornerShape(2.dp),
                    color = Color.Black.copy(alpha = 0.82f),
                    border = BorderStroke(1.dp, green.copy(alpha = 0.75f))
                ) {
                    Text(
                        text = "● REC",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = cyberTextStyle(green, if (compact) 9.sp else 10.sp, shadow = true)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 7.dp else 12.dp,
                        end = if (compact) 7.dp else 12.dp,
                        bottom = if (compact) 4.dp else 7.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)
            ) {
                CyberDigitalTimer(
                    durationSeconds = durationSeconds,
                    compact = compact,
                    modifier = Modifier.fillMaxWidth(if (isBusy) 0.62f else 0.70f)
                )
                CyberHudStatusLine(
                    status = processingStage.ifBlank { status },
                    isRecording = isRecording,
                    isBusy = isBusy,
                    progressPercent = progressPercent,
                    phase = phase,
                    compact = compact
                )
                if (isBusy) {
                    Surface(
                        onClick = onCancelProcessing,
                        modifier = Modifier
                            .fillMaxWidth(if (compact) 0.72f else 0.68f)
                            .height(if (compact) 29.dp else 36.dp),
                        shape = RoundedCornerShape(3.dp),
                        color = Color(0xFF250303).copy(alpha = 0.96f),
                        border = BorderStroke(1.5.dp, red),
                        shadowElevation = 3.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(if (compact) 14.dp else 17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isTranscribing) "终止最终转录" else "终止纪要生成",
                                style = cyberTextStyle(Color.White, if (compact) 11.sp else 14.sp, shadow = true)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DaylightRecorderHero(
    isRecording: Boolean,
    isTranscribing: Boolean,
    isGeneratingReport: Boolean,
    actionEnabled: Boolean,
    durationSeconds: Long,
    status: String,
    progressPercent: Int?,
    processingStage: String,
    height: Dp,
    microphoneSize: Dp,
    onMainAction: () -> Unit,
    onCancelProcessing: () -> Unit
) {
    val isBusy = isTranscribing || isGeneratingReport
    val emerald = Color(0xFF08795D)
    val mint = Color(0xFF2DBE8C)
    val muted = Color(0xFF5B746A)
    val red = Color(0xFFC53936)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val impact = remember { Animatable(1f) }
    val infinite = rememberInfiniteTransition(label = "daylightRecorder")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "daylightPress"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF8FCFA),
        border = BorderStroke(1.dp, Color(0xFFB9E6D4)),
        shadowElevation = 2.dp
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxHeight < 205.dp
            val actionSize = minOf(
                microphoneSize * if (compact) 0.88f else 1.02f,
                maxHeight * 0.47f
            )
            val centerFraction = if (isBusy) 0.36f else 0.38f
            val actionTop = (maxHeight * centerFraction - actionSize / 2f).coerceAtLeast(8.dp)

            Canvas(modifier = Modifier.fillMaxSize()) {
                val grid = 20.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = emerald.copy(alpha = 0.055f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                    x += grid
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(
                        color = emerald.copy(alpha = 0.05f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                    y += grid
                }

                val center = Offset(size.width / 2f, size.height * centerFraction)
                val radius = actionSize.toPx() / 2f
                drawCircle(
                    color = mint.copy(alpha = if (isRecording) 0.13f else 0.07f),
                    radius = radius * 1.34f,
                    center = center
                )
                drawCircle(
                    color = emerald.copy(alpha = 0.24f),
                    radius = radius * 1.17f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
                if (impact.value < 1f) {
                    val completion = impact.value
                    val fade = (1f - completion) * (1f - completion)
                    drawCircle(
                        color = emerald.copy(alpha = fade * 0.65f),
                        radius = radius * (1f + completion * 1.35f),
                        center = center,
                        style = Stroke(width = (1.dp + 3.dp * fade).toPx())
                    )
                }
                if (isRecording) {
                    val pulse = 0.5f + 0.5f * sin(phase * (PI * 2f).toFloat())
                    drawCircle(
                        color = mint.copy(alpha = 0.20f + pulse * 0.18f),
                        radius = radius * (1.16f + pulse * 0.13f),
                        center = center,
                        style = Stroke(width = 1.8.dp.toPx())
                    )
                    drawRecorderSideWaveforms(
                        center = center,
                        coreRadius = radius,
                        phase = phase,
                        color = emerald,
                        active = true
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        top = when {
                            isBusy -> 0.dp
                            compact -> 4.dp
                            else -> 7.dp
                        }
                    ),
                shape = RoundedCornerShape(16.dp),
                color = if (isRecording) Color(0xFFE2F8ED) else Color(0xFFF0F7F4),
                border = BorderStroke(1.dp, emerald.copy(alpha = 0.24f))
            ) {
                Text(
                    text = when {
                        isBusy -> "正在整理录音"
                        isRecording -> "正在录音"
                        else -> "准备开始录音"
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    style = TextStyle(
                        color = if (isBusy) muted else emerald,
                        fontSize = if (compact) 10.sp else 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }

            if (!isBusy) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = actionTop)
                        .size(actionSize)
                        .graphicsLayer {
                            scaleX = pressScale
                            scaleY = pressScale
                        }
                        .clip(CircleShape)
                        .then(
                            if (actionEnabled) {
                                Modifier.clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    role = Role.Button,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        scope.launch {
                                            impact.snapTo(0f)
                                            impact.animateTo(
                                                targetValue = 1f,
                                                animationSpec = tween(560, easing = FastOutSlowInEasing)
                                            )
                                        }
                                        onMainAction()
                                    }
                                )
                            } else {
                                Modifier
                            }
                        ),
                    shape = CircleShape,
                    color = if (isRecording) Color(0xFFC33A38) else emerald,
                    border = BorderStroke(5.dp, Color.White.copy(alpha = 0.92f)),
                    shadowElevation = 8.dp
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecording) "结束录音" else "开始录音",
                        tint = Color.White,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(actionSize * 0.28f)
                    )
                }
            } else {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = actionTop)
                        .size(actionSize),
                    shape = CircleShape,
                    color = Color(0xFFE5F6EE),
                    border = BorderStroke(4.dp, Color.White),
                    shadowElevation = 5.dp
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = emerald,
                        modifier = Modifier.padding(actionSize * 0.27f)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 7.dp else 12.dp,
                        end = if (compact) 7.dp else 12.dp,
                        bottom = if (compact) 5.dp else 8.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp)
            ) {
                CyberDigitalTimer(
                    durationSeconds = durationSeconds,
                    compact = compact,
                    light = true,
                    modifier = Modifier.fillMaxWidth(if (isBusy) 0.62f else 0.70f)
                )
                CyberHudStatusLine(
                    status = processingStage.ifBlank { status },
                    isRecording = isRecording,
                    isBusy = isBusy,
                    progressPercent = progressPercent,
                    phase = phase,
                    compact = compact,
                    light = true
                )
                if (isBusy) {
                    Surface(
                        onClick = onCancelProcessing,
                        modifier = Modifier
                            .fillMaxWidth(if (compact) 0.72f else 0.68f)
                            .height(if (compact) 29.dp else 36.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFFFFEFEE),
                        border = BorderStroke(1.dp, red),
                        shadowElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                tint = red,
                                modifier = Modifier.size(if (compact) 14.dp else 17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isTranscribing) "终止最终转录" else "终止纪要生成",
                                style = TextStyle(
                                    color = red,
                                    fontSize = if (compact) 11.sp else 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawRecorderSideWaveforms(
    center: Offset,
    coreRadius: Float,
    phase: Float,
    color: Color,
    active: Boolean
) {
    if (!active) return
    val outerGap = 11.dp.toPx()
    val lineCount = 4
    val samples = 42
    val baseAmplitude = size.height.coerceAtMost(220.dp.toPx()) * 0.105f
    val leftStart = 8.dp.toPx()
    val leftEnd = (center.x - coreRadius - outerGap).coerceAtLeast(leftStart)
    val rightStart = (center.x + coreRadius + outerGap).coerceAtMost(size.width - 8.dp.toPx())
    val rightEnd = size.width - 8.dp.toPx()

    fun drawSide(startX: Float, endX: Float, direction: Float) {
        if (endX - startX < 18.dp.toPx()) return
        repeat(lineCount) { lane ->
            val path = Path()
            val lanePhase = phase * (PI * 2f).toFloat() + lane * 0.82f
            for (sample in 0..samples) {
                val t = sample / samples.toFloat()
                val envelope = sin(t * PI).toFloat().coerceAtLeast(0f)
                val amplitude = baseAmplitude * envelope * (0.42f + lane * 0.17f)
                val y = center.y + sin(t * 4.8f * PI.toFloat() + lanePhase * direction) * amplitude
                val x = startX + (endX - startX) * t
                if (sample == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color.copy(alpha = 0.22f + lane * 0.10f),
                style = Stroke(width = (0.8f + lane * 0.28f).dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }

    drawSide(leftStart, leftEnd, -1f)
    drawSide(rightStart, rightEnd, 1f)
}

@Composable
private fun CyberDigitalTimer(
    durationSeconds: Long,
    compact: Boolean,
    light: Boolean = false,
    modifier: Modifier = Modifier
) {
    val active = if (light) Color(0xFF08795D) else Color(0xFF67FF8D)
    val inactive = if (light) Color(0xFFB7D9CA) else Color(0xFF123D21)
    val value = cyberFormatDuration(durationSeconds)
    Surface(
        modifier = modifier.height(if (compact) 29.dp else 39.dp),
        shape = RoundedCornerShape(2.dp),
        color = if (light) Color(0xFFFFFFFF).copy(alpha = 0.97f) else Color(0xFF010805).copy(alpha = 0.96f),
        border = BorderStroke(
            1.dp,
            if (light) Color(0xFFA8DFC9) else Color(0xFF20D85C).copy(alpha = 0.72f)
        )
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 3.dp)) {
            val digitHeight = size.height * 0.88f
            val digitWidth = digitHeight * 0.48f
            val colonWidth = digitHeight * 0.18f
            val gap = digitHeight * 0.115f
            val digitCount = value.count { it.isDigit() }
            val totalWidth = digitCount * digitWidth +
                (value.length - digitCount) * colonWidth +
                (value.length - 1) * gap
            var cursor = (size.width - totalWidth).coerceAtLeast(0f) / 2f
            val top = (size.height - digitHeight) / 2f
            val thickness = (digitHeight * 0.085f).coerceAtLeast(1.2.dp.toPx())

            value.forEach { char ->
                if (char == ':') {
                    val x = cursor + colonWidth / 2f
                    fun colonDot(fraction: Float) {
                        drawCircle(
                            color = active.copy(alpha = 0.35f),
                            radius = thickness * 1.2f,
                            center = Offset(x, top + digitHeight * fraction)
                        )
                        drawCircle(
                            color = active,
                            radius = thickness * 0.58f,
                            center = Offset(x, top + digitHeight * fraction)
                        )
                    }
                    colonDot(0.33f)
                    colonDot(0.70f)
                    cursor += colonWidth + gap
                } else {
                    val mask = cyberDigitMask(char)
                    val left = cursor
                    val right = cursor + digitWidth
                    val middle = top + digitHeight / 2f
                    val bottom = top + digitHeight
                    val inset = thickness * 0.72f
                    fun segment(bit: Int, start: Offset, end: Offset) {
                        val enabled = mask and bit != 0
                        drawLine(
                            color = if (enabled) active.copy(alpha = 0.28f) else inactive.copy(alpha = 0.55f),
                            start = start,
                            end = end,
                            strokeWidth = if (enabled) thickness * 2f else thickness,
                            cap = StrokeCap.Square
                        )
                        if (enabled) {
                            drawLine(
                                color = active,
                                start = start,
                                end = end,
                                strokeWidth = thickness,
                                cap = StrokeCap.Square
                            )
                        }
                    }
                    segment(0b0000001, Offset(left + inset, top), Offset(right - inset, top))
                    segment(0b0000010, Offset(right, top + inset), Offset(right, middle - inset))
                    segment(0b0000100, Offset(right, middle + inset), Offset(right, bottom - inset))
                    segment(0b0001000, Offset(left + inset, bottom), Offset(right - inset, bottom))
                    segment(0b0010000, Offset(left, middle + inset), Offset(left, bottom - inset))
                    segment(0b0100000, Offset(left, top + inset), Offset(left, middle - inset))
                    segment(0b1000000, Offset(left + inset, middle), Offset(right - inset, middle))
                    cursor += digitWidth + gap
                }
            }
        }
    }
}

@Composable
private fun CyberHudStatusLine(
    status: String,
    isRecording: Boolean,
    isBusy: Boolean,
    progressPercent: Int?,
    phase: Float,
    compact: Boolean,
    light: Boolean = false
) {
    val green = if (light) Color(0xFF08795D) else Color(0xFF48FF7B)
    val progress = progressPercent?.coerceIn(0, 100)
    Surface(
        modifier = Modifier
            .fillMaxWidth(if (isBusy) 0.92f else 0.84f)
            .height(if (compact) 27.dp else 34.dp),
        shape = RoundedCornerShape(2.dp),
        color = if (light) Color.White.copy(alpha = 0.96f) else Color(0xFF010805).copy(alpha = 0.95f),
        border = BorderStroke(1.dp, green.copy(alpha = if (light) 0.28f else 0.55f))
    ) {
        if (isBusy) {
            Column(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CyberSignalGlyph(
                        phase = phase,
                        color = green,
                        modifier = Modifier.width(if (compact) 25.dp else 31.dp).fillMaxHeight()
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = status,
                        modifier = Modifier.weight(1f),
                        style = cyberTextStyle(green, if (compact) 9.sp else 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = progress?.let { it.toString() + "%" } ?: "•••",
                        style = cyberTextStyle(green, if (compact) 9.sp else 11.sp)
                    )
                }
                CyberSegmentProgress(
                    progressPercent = progress,
                    phase = phase,
                    activeColor = green,
                    inactiveColor = if (light) Color(0xFFD8EEE4) else Color(0xFF123D21),
                    modifier = Modifier.fillMaxWidth().height(if (compact) 3.dp else 5.dp)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CyberSignalGlyph(
                    phase = phase,
                    color = green,
                    modifier = Modifier.width(if (compact) 28.dp else 34.dp).fillMaxHeight(0.72f)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = if (isRecording) "正在录音..." else status,
                    style = cyberTextStyle(green, if (compact) 11.sp else 14.sp, shadow = true),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CyberSignalGlyph(
    phase: Float,
    color: Color = Color(0xFF48FF7B),
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val count = 5
        val gap = 2.dp.toPx()
        val width = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        repeat(count) { index ->
            val pulse = 0.5f + 0.5f * sin(phase * (PI * 2f).toFloat() + index * 0.9f)
            val barHeight = size.height * (0.30f + (index + 1) / count.toFloat() * 0.40f + pulse * 0.18f)
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (width + gap), size.height - barHeight),
                size = Size(width, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
            )
        }
    }
}

@Composable
private fun CyberSegmentProgress(
    progressPercent: Int?,
    phase: Float,
    activeColor: Color = Color(0xFF58FF85),
    inactiveColor: Color = Color(0xFF123D21),
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val count = 18
        val gap = 2.dp.toPx()
        val width = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        val activeCount = progressPercent?.let { (it * count / 100f).toInt() }
        val pulseIndex = (phase * count).toInt().coerceIn(0, count - 1)
        repeat(count) { index ->
            val active = activeCount?.let { index < it }
                ?: ((index - pulseIndex + count) % count < 4)
            drawRect(
                color = if (active) activeColor else inactiveColor,
                topLeft = Offset(index * (width + gap), 0f),
                size = Size(width, size.height)
            )
        }
    }
}

private fun cyberTextStyle(color: Color, size: androidx.compose.ui.unit.TextUnit, shadow: Boolean = false) =
    TextStyle(
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = size,
        fontWeight = FontWeight.Bold,
        shadow = if (shadow) Shadow(color.copy(alpha = 0.72f), blurRadius = 8f) else null
    )

private fun cyberDigitMask(char: Char): Int = when (char) {
    '0' -> 0b0111111
    '1' -> 0b0000110
    '2' -> 0b1011011
    '3' -> 0b1001111
    '4' -> 0b1100110
    '5' -> 0b1101101
    '6' -> 0b1111101
    '7' -> 0b0000111
    '8' -> 0b1111111
    '9' -> 0b1101111
    else -> 0
}

private fun cyberFormatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(Locale.US, safe / 3600, safe % 3600 / 60, safe % 60)
}

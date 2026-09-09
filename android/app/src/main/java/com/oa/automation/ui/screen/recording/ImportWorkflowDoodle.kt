package com.oa.automation.ui.screen.recording

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

// Board palette. Dark mode keeps the chalk-on-slate look from the mock-up;
// light mode becomes ink-on-paper so the board matches the recording page's
// doodle sketches instead of dropping a black slab onto a light screen.
internal data class BoardSkin(
    val board: Color,
    val border: Color,
    val chalk: Color,
    val chalkMuted: Color,
    val chalkFaint: Color,
    val orange: Color,
    val green: Color,
    val yellow: Color,
    val violet: Color,
    val blue: Color,
    val pink: Color,
    val cyan: Color
)

private val DarkBoardSkin = BoardSkin(
    board = Color(0xFF14161A),
    border = Color(0x24FFFFFF),
    chalk = Color(0xFFF2F4F7),
    chalkMuted = Color(0xFFA8AFBA),
    chalkFaint = Color(0xFF3A414C),
    orange = Color(0xFFFF9A3C),
    green = Color(0xFF6FD08C),
    yellow = Color(0xFFFFD24A),
    violet = Color(0xFFB98CFF),
    blue = Color(0xFF5AB8FF),
    pink = Color(0xFFFF7EA8),
    cyan = Color(0xFF4FD8D0)
)

// Ink and paper reuse the recording page's doodle values so a template sketch
// and this board read as one family; the accents are darkened to hold contrast
// against white.
private val LightBoardSkin = BoardSkin(
    board = Color(0xFFFFFFFF),
    border = Color(0xFFE1E7EE),
    chalk = DoodleInk,
    chalkMuted = DoodleGray,
    chalkFaint = Color(0xFFD5DAE1),
    orange = Color(0xFFC96A11),
    green = Color(0xFF2E8B57),
    yellow = Color(0xFFA97A06),
    violet = Color(0xFF6F45C0),
    blue = Color(0xFF1C6FC4),
    pink = Color(0xFFC44C76),
    cyan = Color(0xFF0F8279)
)

private val LocalBoardSkin = staticCompositionLocalOf { DarkBoardSkin }

private data class ImportStep(
    val index: Int,
    val title: String,
    val accent: Color
)

private fun importSteps(s: BoardSkin) = listOf(
    ImportStep(1, "录入音频", s.orange),
    ImportStep(2, "AI 识别声音", s.green),
    ImportStep(3, "智能分段", s.yellow),
    ImportStep(4, "关键词提取", s.violet),
    ImportStep(5, "生成文字稿", s.blue),
    ImportStep(6, "输出会议纪要", s.orange)
)

/**
 * The 顷刻成稿 explainer: a chalkboard poster of the whole
 * audio → transcript → minutes pipeline, drawn in the same hand-sketched
 * grammar as the recording page's template workflows.
 *
 * The status pill lives on the page rather than here, so the same indicator
 * serves this board and the per-template sketches.
 */
@Composable
internal fun ImportWorkflowDoodle(
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val s = if (isDark) DarkBoardSkin else LightBoardSkin
    val steps = remember(s) { importSteps(s) }
    CompositionLocalProvider(LocalBoardSkin provides s) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = s.board,
            border = BorderStroke(1.dp, s.border)
        ) {
            // Fill whatever height the page gives us so the rows breathe on tall
            // screens, while staying scrollable when the board is taller than the
            // viewport (compact phones, split screen).
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val fillViewport = if (maxHeight != Dp.Infinity) Modifier.heightIn(min = maxHeight) else Modifier
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .then(fillViewport)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    BoardTitle()
                    StepRow(steps[0], steps[1]) {
                        RecordAudioPanel(modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        RecognisePanel(modifier = Modifier.weight(1f))
                    }
                    StepRow(steps[2], steps[3]) {
                        SegmentPanel(modifier = Modifier.weight(1.15f))
                        Spacer(Modifier.width(8.dp))
                        KeywordPanel(modifier = Modifier.weight(0.85f))
                    }
                    StepRow(steps[4], steps[5]) {
                        TranscriptPanel(modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        MinutesPanel(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}


@Composable
private fun BoardTitle() {
    val s = LocalBoardSkin.current
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
            chalkRoundRect(
                topLeft = Offset(size.width * 0.06f, 4f),
                width = size.width * 0.88f,
                height = size.height - 8f,
                radius = 14.dp.toPx(),
                color = s.chalk,
                strokeWidth = 1.8.dp.toPx()
            )
            // Sparkles either side of the banner, as in the mock.
            sparkle(Offset(size.width * 0.045f, size.height * 0.28f), 5.dp.toPx(), s.yellow)
            sparkle(Offset(size.width * 0.965f, size.height * 0.22f), 6.dp.toPx(), s.yellow)
        }
        Text(
            text = "音频转文字 · 生成会议纪要全流程",
            color = s.chalk,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StepRow(
    left: ImportStep,
    right: ImportStep,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            StepHeading(step = left, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StepHeading(step = right, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(7.dp))
        Row(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun StepHeading(step: ImportStep, modifier: Modifier = Modifier) {
    val s = LocalBoardSkin.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxWidth().height(22.dp)) {
                chalkCircle(
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.minDimension / 2f - 1.5.dp.toPx(),
                    color = step.accent,
                    strokeWidth = 1.6.dp.toPx()
                )
            }
            Text(
                text = step.index.toString(),
                color = step.accent,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(5.dp))
        Column {
            Text(
                text = step.title,
                color = s.chalk,
                fontSize = 12.5.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Hand-drawn underline in the step's own colour.
            Canvas(modifier = Modifier.width(58.dp).height(4.dp)) {
                wobblyUnderline(step.accent, 1.6.dp.toPx())
            }
        }
    }
}

/** Step 1 — microphone and waveform. */
@Composable
private fun RecordAudioPanel(modifier: Modifier = Modifier) {
    val s = LocalBoardSkin.current
    BoardPanel(modifier = modifier, caption = "上传音频文件\n开始转写") {
        Canvas(modifier = Modifier.fillMaxWidth().height(56.dp)) {
            val cx = size.width * 0.26f
            val cy = size.height * 0.48f
            // Mic capsule + stand.
            chalkRoundRect(
                topLeft = Offset(cx - 9.dp.toPx(), cy - 20.dp.toPx()),
                width = 18.dp.toPx(),
                height = 26.dp.toPx(),
                radius = 9.dp.toPx(),
                color = s.chalk,
                strokeWidth = 1.7.dp.toPx()
            )
            chalkLine(Offset(cx, cy + 6.dp.toPx()), Offset(cx, cy + 14.dp.toPx()), s.chalk, 1.7.dp.toPx())
            chalkLine(
                Offset(cx - 9.dp.toPx(), cy + 14.dp.toPx()),
                Offset(cx + 9.dp.toPx(), cy + 14.dp.toPx()),
                s.chalk,
                1.7.dp.toPx()
            )
            // Waveform marching to the right.
            val startX = size.width * 0.44f
            val bars = 11
            val gap = (size.width * 0.5f) / bars
            repeat(bars) { i ->
                val t = i / (bars - 1f)
                val amp = (0.22f + 0.78f * kotlin.math.abs(sin(t * 6.2f))) * 18.dp.toPx()
                val x = startX + i * gap
                chalkLine(
                    Offset(x, cy - amp / 2f),
                    Offset(x, cy + amp / 2f),
                    s.blue,
                    2.dp.toPx()
                )
            }
        }
    }
}

/** Step 2 — the AI robot with a speaker-attribution bubble. */
@Composable
private fun RecognisePanel(modifier: Modifier = Modifier) {
    val s = LocalBoardSkin.current
    BoardPanel(modifier = modifier, caption = "AI 智能识别语音内容\n区分说话人") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(52.dp)) { chalkRobot(s) }
            Spacer(Modifier.width(6.dp))
            Column {
                ChalkTag(text = "自动识别", accent = s.green)
                Spacer(Modifier.height(3.dp))
                ChalkTag(text = "说话人", accent = s.green)
            }
        }
    }
}

/** Step 3 — utterances resolving into timestamped topics. */
@Composable
private fun SegmentPanel(modifier: Modifier = Modifier) {
    val s = LocalBoardSkin.current
    BoardPanel(modifier = modifier, caption = null) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(
                Triple("关于本次复盘…", "主题 1 问题回顾", "00:00 – 08:15"),
                Triple("接下来聊方案…", "主题 2 解决方案", "08:16 – 16:40"),
                Triple("确定后续任务…", "主题 3 任务分配", "16:41 – 24:30")
            ).forEachIndexed { index, (utterance, topic, span) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeechBubble(text = utterance, modifier = Modifier.weight(1f))
                    Canvas(modifier = Modifier.width(14.dp).height(10.dp)) {
                        chalkArrow(s.chalkMuted, 1.4.dp.toPx())
                    }
                    TopicCard(
                        topic = topic,
                        span = span,
                        accent = s.yellow,
                        modifier = Modifier.weight(1.15f)
                    )
                }
                if (index == 2) {
                    Text(
                        text = "…",
                        color = s.chalkMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

/** Step 4 — the keyword cloud. */
@Composable
private fun KeywordPanel(modifier: Modifier = Modifier) {
    val s = LocalBoardSkin.current
    BoardPanel(modifier = modifier, caption = null) {
        Box(modifier = Modifier.fillMaxWidth().height(112.dp)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(112.dp)) {
                chalkCloud(s.chalkMuted, 1.5.dp.toPx())
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ChalkTag(text = "问题", accent = s.orange)
                ChalkTag(text = "方案", accent = s.green)
                ChalkTag(text = "任务", accent = s.cyan)
            }
        }
    }
}

/** Step 5 — the speaker-attributed transcript page. */
@Composable
private fun TranscriptPanel(modifier: Modifier = Modifier) {
    val s = LocalBoardSkin.current
    BoardPanel(modifier = modifier, caption = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            listOf(
                Triple("主题1：问题回顾", "张三", "先看问题，主要有以下几点…"),
                Triple("主题2：解决方案", "李四", "针对这些问题，解决方案是…"),
                Triple("主题3：任务分配", "王五", "后续任务安排如下，请确认…")
            ).forEach { (heading, speaker, line) ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = heading,
                        color = s.yellow,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$speaker：$line",
                        color = s.chalk,
                        fontSize = 8.5.sp,
                        lineHeight = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Step 6 — the summary checklist beside owner-stamped action cards. */
@Composable
private fun MinutesPanel(modifier: Modifier = Modifier) {
    val s = LocalBoardSkin.current
    BoardPanel(modifier = modifier, caption = null) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "要点总结",
                    color = s.chalk,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                listOf("问题回顾", "解决方案", "任务分配", "风险与建议").forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(modifier = Modifier.size(9.dp)) {
                            chalkCheck(s.green, 1.5.dp.toPx())
                        }
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = item,
                            color = s.chalkMuted,
                            fontSize = 8.5.sp,
                            lineHeight = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(
                modifier = Modifier.weight(1.05f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "行动项",
                    color = s.chalk,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                listOf(
                    Triple("张三", "完善问题清单", "09-06"),
                    Triple("李四", "方案细化 PPT", "09-07"),
                    Triple("王五", "跟进进度与执行", "09-08")
                ).forEach { (owner, task, due) ->
                    ActionCard(owner = owner, task = task, due = due)
                }
            }
        }
    }
}

@Composable
private fun ActionCard(owner: String, task: String, due: String) {
    val s = LocalBoardSkin.current
    Box(modifier = Modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(30.dp)) {
            chalkRoundRect(
                topLeft = Offset.Zero,
                width = size.width,
                height = size.height,
                radius = 6.dp.toPx(),
                color = s.chalkFaint,
                strokeWidth = 1.2.dp.toPx()
            )
        }
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
            Text(
                text = "@$owner $task",
                color = s.chalk,
                fontSize = 8.sp,
                lineHeight = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "截止：$due",
                color = s.orange,
                fontSize = 7.5.sp,
                lineHeight = 9.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun BoardPanel(
    modifier: Modifier = Modifier,
    caption: String?,
    content: @Composable () -> Unit
) {
    val s = LocalBoardSkin.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
        if (caption != null) {
            Text(
                text = caption,
                color = s.chalkMuted,
                fontSize = 8.5.sp,
                lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SpeechBubble(text: String, modifier: Modifier = Modifier) {
    val s = LocalBoardSkin.current
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(26.dp)) {
            chalkRoundRect(
                topLeft = Offset.Zero,
                width = size.width,
                height = size.height,
                radius = 7.dp.toPx(),
                color = s.chalkFaint,
                strokeWidth = 1.2.dp.toPx()
            )
        }
        Text(
            text = text,
            color = s.chalkMuted,
            fontSize = 8.sp,
            lineHeight = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun TopicCard(topic: String, span: String, accent: Color, modifier: Modifier = Modifier) {
    val s = LocalBoardSkin.current
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(26.dp)) {
            chalkRoundRect(
                topLeft = Offset.Zero,
                width = size.width,
                height = size.height,
                radius = 5.dp.toPx(),
                color = accent,
                strokeWidth = 1.3.dp.toPx()
            )
        }
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(
                text = topic,
                color = accent,
                fontSize = 8.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = span,
                color = s.chalkMuted,
                fontSize = 7.sp,
                lineHeight = 9.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ChalkTag(text: String, accent: Color) {
    Box {
        Canvas(modifier = Modifier.width(52.dp).height(20.dp)) {
            chalkRoundRect(
                topLeft = Offset.Zero,
                width = size.width,
                height = size.height,
                radius = 6.dp.toPx(),
                color = accent,
                strokeWidth = 1.4.dp.toPx()
            )
        }
        Box(
            modifier = Modifier.width(52.dp).height(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = accent,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

// ---------------------------------------------------------------------------
// s.chalk primitives. Each adds a small sine wobble so strokes read as drawn by
// hand rather than generated, matching the recording page's doodle grammar.
// ---------------------------------------------------------------------------

private fun DrawScope.chalkStroke(width: Float) =
    Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.chalkRoundRect(
    topLeft: Offset,
    width: Float,
    height: Float,
    radius: Float,
    color: Color,
    strokeWidth: Float
) {
    val r = radius.coerceAtMost(minOf(width, height) / 2.2f)
    val wob = 0.7f
    val path = Path().apply {
        moveTo(topLeft.x + r, topLeft.y + wob)
        lineTo(topLeft.x + width - r, topLeft.y - wob)
        quadraticTo(topLeft.x + width, topLeft.y, topLeft.x + width + wob, topLeft.y + r)
        lineTo(topLeft.x + width - wob, topLeft.y + height - r)
        quadraticTo(
            topLeft.x + width,
            topLeft.y + height,
            topLeft.x + width - r,
            topLeft.y + height + wob
        )
        lineTo(topLeft.x + r, topLeft.y + height - wob)
        quadraticTo(topLeft.x, topLeft.y + height, topLeft.x - wob, topLeft.y + height - r)
        lineTo(topLeft.x + wob, topLeft.y + r)
        quadraticTo(topLeft.x, topLeft.y, topLeft.x + r, topLeft.y + wob)
        close()
    }
    drawPath(path, color = color, style = chalkStroke(strokeWidth))
}

private fun DrawScope.chalkCircle(
    center: Offset,
    radius: Float,
    color: Color,
    strokeWidth: Float
) {
    val path = Path()
    val segments = 28
    for (i in 0..segments) {
        val a = (i / segments.toFloat()) * 2f * Math.PI.toFloat()
        val r = radius + sin(a * 4f) * (radius * 0.035f)
        val x = center.x + r * kotlin.math.cos(a)
        val y = center.y + r * sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color = color, style = chalkStroke(strokeWidth))
}

private fun DrawScope.chalkLine(
    start: Offset,
    end: Offset,
    color: Color,
    strokeWidth: Float
) {
    val path = Path()
    path.moveTo(start.x, start.y)
    val steps = 8
    for (i in 1..steps) {
        val t = i / steps.toFloat()
        val wob = sin(t * Math.PI.toFloat() * 2f) * 0.6f
        path.lineTo(start.x + (end.x - start.x) * t + wob, start.y + (end.y - start.y) * t + wob)
    }
    drawPath(path, color = color, style = chalkStroke(strokeWidth))
}

private fun DrawScope.wobblyUnderline(color: Color, strokeWidth: Float) {
    val path = Path()
    path.moveTo(0f, size.height / 2f)
    val steps = 12
    for (i in 1..steps) {
        val t = i / steps.toFloat()
        path.lineTo(size.width * t, size.height / 2f + sin(t * 9f) * (size.height / 3f))
    }
    drawPath(path, color = color, style = chalkStroke(strokeWidth))
}

private fun DrawScope.chalkArrow(color: Color, strokeWidth: Float) {
    val midY = size.height / 2f
    chalkLine(Offset(0f, midY), Offset(size.width, midY), color, strokeWidth)
    val path = Path().apply {
        moveTo(size.width - 4f, midY - 3.5f)
        lineTo(size.width, midY)
        lineTo(size.width - 4f, midY + 3.5f)
    }
    drawPath(path, color = color, style = chalkStroke(strokeWidth))
}

private fun DrawScope.chalkCheck(color: Color, strokeWidth: Float) {
    val path = Path().apply {
        moveTo(size.width * 0.16f, size.height * 0.55f)
        lineTo(size.width * 0.42f, size.height * 0.80f)
        lineTo(size.width * 0.86f, size.height * 0.22f)
    }
    drawPath(path, color = color, style = chalkStroke(strokeWidth))
}

private fun DrawScope.sparkle(center: Offset, radius: Float, color: Color) {
    chalkLine(Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), color, 1.4f)
    chalkLine(Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), color, 1.4f)
}

private fun DrawScope.chalkCloud(color: Color, strokeWidth: Float) {
    // A dashed thought-cloud so the keyword bubble reads as inferred content.
    val w = size.width
    val h = size.height
    drawPath(
        path = Path().apply {
            moveTo(w * 0.20f, h * 0.72f)
            quadraticTo(w * 0.02f, h * 0.62f, w * 0.14f, h * 0.42f)
            quadraticTo(w * 0.14f, h * 0.14f, w * 0.42f, h * 0.16f)
            quadraticTo(w * 0.60f, h * 0.02f, w * 0.76f, h * 0.18f)
            quadraticTo(w * 0.99f, h * 0.24f, w * 0.88f, h * 0.50f)
            quadraticTo(w * 0.98f, h * 0.72f, w * 0.74f, h * 0.78f)
            quadraticTo(w * 0.46f, h * 0.94f, w * 0.20f, h * 0.72f)
            close()
        },
        color = color,
        style = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f))
        )
    )
}

private fun DrawScope.chalkRobot(s: BoardSkin) {
    val w = size.width
    val h = size.height
    // Antenna.
    chalkLine(Offset(w * 0.5f, h * 0.10f), Offset(w * 0.5f, h * 0.24f), s.chalk, 1.5f)
    chalkCircle(Offset(w * 0.5f, h * 0.08f), w * 0.05f, s.green, 1.5f)
    // Head.
    chalkRoundRect(
        topLeft = Offset(w * 0.18f, h * 0.24f),
        width = w * 0.64f,
        height = h * 0.46f,
        radius = w * 0.16f,
        color = s.chalk,
        strokeWidth = 1.7f
    )
    // Eyes.
    chalkCircle(Offset(w * 0.37f, h * 0.45f), w * 0.055f, s.cyan, 1.6f)
    chalkCircle(Offset(w * 0.63f, h * 0.45f), w * 0.055f, s.cyan, 1.6f)
    // Smile.
    drawPath(
        path = Path().apply {
            moveTo(w * 0.38f, h * 0.56f)
            quadraticTo(w * 0.5f, h * 0.64f, w * 0.62f, h * 0.56f)
        },
        color = s.chalk,
        style = chalkStroke(1.6f)
    )
    // Ears.
    chalkLine(Offset(w * 0.14f, h * 0.40f), Offset(w * 0.14f, h * 0.52f), s.chalk, 1.5f)
    chalkLine(Offset(w * 0.86f, h * 0.40f), Offset(w * 0.86f, h * 0.52f), s.chalk, 1.5f)
    // Body hint.
    chalkLine(Offset(w * 0.34f, h * 0.76f), Offset(w * 0.66f, h * 0.76f), s.chalkMuted, 1.5f)
}

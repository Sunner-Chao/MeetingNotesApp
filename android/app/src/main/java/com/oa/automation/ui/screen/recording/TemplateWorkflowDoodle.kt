package com.oa.automation.ui.screen.recording

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oa.automation.domain.model.CustomTemplateLayout
import kotlin.math.PI
import kotlin.math.sin

private val DoodlePaper = Color(0xFFFFFFFF)
internal val DoodleInk = Color(0xFF2B2A28)
internal val DoodleGray = Color(0xFF6E6C69)
private val StickyYellow = Color(0xFFFFF0A6)
private val StickyGreen = Color(0xFFD7F2CF)
private val StickyPink = Color(0xFFFFD9DD)

/**
 * Main-area panel shown before a recording starts. It explains, in a doodle
 * sketchbook style, how the selected template listens, analyses, summarises
 * and turns the meeting into actions. Templates with an approved raster
 * illustration render it directly; the others are drawn natively with the
 * same grammar so all eight moods read as one family.
 */
@Composable
internal fun TemplateWorkflowDoodlePanel(
    templateName: String,
    hasBeenSeen: Boolean,
    onViewed: (String) -> Unit,
    isDark: Boolean,
    customTemplateLayout: CustomTemplateLayout = CustomTemplateLayout.DEFAULT,
    onCustomTemplateLayoutChange: (CustomTemplateLayout) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val mood = remember(templateName) { templateMoodFor(templateName) }
    val workflow = remember(templateName) { templateWorkflowFor(templateName) }
    LaunchedEffect(workflow.templateName) {
        if (!hasBeenSeen) onViewed(workflow.templateName)
    }
    val scrollState = rememberScrollState()
    Column(modifier = modifier.verticalScroll(scrollState)) {
        Surface(
            // The sketches are white-paper artwork. Left at full strength they
            // glare in dark mode, so the whole sheet is dimmed towards the page
            // instead of tinting the drawings themselves.
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (isDark) 0.82f else 1f),
            shape = RoundedCornerShape(18.dp),
            color = DoodlePaper,
            border = BorderStroke(
                1.dp,
                if (isDark) Color.White.copy(alpha = 0.14f) else Color(0xFFE1E7EE)
            ),
            shadowElevation = if (isDark) 0.dp else 1.dp
        ) {
            val illustration = mood.illustrationRes
            if (illustration != null) {
                Image(
                    painter = painterResource(illustration),
                    contentDescription = "${mood.displayName} 工作流示意图",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.FillWidth
                )
            } else {
                NativeWorkflowDoodle(
                    mood = mood,
                    workflow = workflow,
                    customTemplateLayout = customTemplateLayout,
                    onCustomTemplateLayoutChange = onCustomTemplateLayoutChange
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

// ---------------------------------------------------------------------------
// Native doodle grammar
// ---------------------------------------------------------------------------

private data class DoodleCopy(
    val badge: String,
    val widgetTitle: String,
    val widgetRows: List<List<String>>,
    val notes: List<String>,
    val aiItems: List<String>,
    val summary: String
)

private fun doodleCopyFor(family: TemplateMoodFamily): DoodleCopy = when (family) {
    TemplateMoodFamily.NEGOTIATION -> DoodleCopy(
        badge = "立场差距：中",
        widgetTitle = "条款对照表",
        widgetRows = listOf(
            listOf("条款", "我方", "对方"),
            listOf("价格", "98 万", "92 万"),
            listOf("范围", "含培训", "仅交付"),
            listOf("周期", "6 周", "4 周")
        ),
        notes = listOf("价格\n可让 3%", "交付期\n待确认"),
        aiItems = listOf("让步空间", "风险表述", "对外口径"),
        summary = "已捕捉 5 项条款"
    )
    TemplateMoodFamily.RETROSPECTIVE -> DoodleCopy(
        badge = "根因 2 个",
        widgetTitle = "事件时间线",
        widgetRows = listOf(
            listOf("09:20", "告警触发"),
            listOf("09:35", "紧急回滚"),
            listOf("10:10", "服务恢复")
        ),
        notes = listOf("监控告警\n缺失", "发布窗口\n太晚"),
        aiItems = listOf("根因归纳", "经验提炼", "改进建议"),
        summary = "已生成 4 项改进"
    )
    TemplateMoodFamily.STANDUP -> DoodleCopy(
        badge = "阻塞 1 项",
        widgetTitle = "三问速记",
        widgetRows = listOf(
            listOf("昨天", "接口联调完成"),
            listOf("今天", "补齐单元测试"),
            listOf("阻塞", "设计稿待补")
        ),
        notes = listOf("接口联调\n今天完成", "设计稿\n缺人 需协助"),
        aiItems = listOf("阻塞提醒", "承诺追踪", "今日焦点"),
        summary = "已识别 3 项承诺"
    )
    TemplateMoodFamily.FORUM -> DoodleCopy(
        badge = "议程 3 段",
        widgetTitle = "发言人名录",
        widgetRows = listOf(
            listOf("主持", "开场串场"),
            listOf("主讲", "观点与案例"),
            listOf("嘉宾", "圆桌讨论"),
            listOf("提问", "现场问答")
        ),
        notes = listOf("观点 A\n达成共识", "议题 B\n待再议"),
        aiItems = listOf("议程切分", "发言归属", "共识提炼"),
        summary = "已归档 6 位发言"
    )
    TemplateMoodFamily.CUSTOM -> DoodleCopy(
        badge = "拖拽编排 已开放",
        widgetTitle = "模块库 · 可拖拽",
        widgetRows = emptyList(),
        notes = listOf("长按拖动\n调整顺序", "停用模块\n不再输出"),
        aiItems = listOf("按你的顺序", "不补造内容", "空模块省略"),
        summary = "编排即时保存"
    )
    TemplateMoodFamily.DIRECTIVE -> DoodleCopy(
        badge = "指令已识别",
        widgetTitle = "落实追踪表",
        widgetRows = listOf(listOf("事项", "负责人", "时间")),
        notes = listOf("周五前\n提交方案", "确保\n接口稳定"),
        aiItems = listOf("指令提取", "任务分发", "落实追踪"),
        summary = "落实清单已生成"
    )
    TemplateMoodFamily.PROGRESS -> DoodleCopy(
        badge = "项目健康度",
        widgetTitle = "风险看板",
        widgetRows = listOf(listOf("高", "中", "低")),
        notes = listOf("A 节点\n延期至周五", "缺口：\n设计资源"),
        aiItems = listOf("风险预警", "偏差分析", "建议行动"),
        summary = "任务已提取"
    )
    TemplateMoodFamily.CO_CREATE -> DoodleCopy(
        badge = "AI 正在聚类创意",
        widgetTitle = "创意评分墙",
        widgetRows = listOf(listOf("新颖度", "可行性")),
        notes = listOf("多人共创", "破冰小游戏"),
        aiItems = listOf("自动聚类", "共识识别", "验证建议"),
        summary = "创意池已形成"
    )
    TemplateMoodFamily.GENERAL -> DoodleCopy(
        badge = "结构自适应",
        widgetTitle = "章节草稿",
        widgetRows = listOf(
            listOf("议题", "自动归类"),
            listOf("结论", "已确认 / 待确认"),
            listOf("行动", "负责人与时间")
        ),
        notes = listOf("议题\n自动归类", "待确认\n高亮"),
        aiItems = listOf("议题识别", "结论提炼", "行动项"),
        summary = "结构化纪要"
    )
}

@Composable
private fun NativeWorkflowDoodle(
    mood: TemplateMood,
    workflow: TemplateWorkflow,
    customTemplateLayout: CustomTemplateLayout,
    onCustomTemplateLayoutChange: (CustomTemplateLayout) -> Unit
) {
    val copy = remember(mood.family) { doodleCopyFor(mood.family) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        DoodleTitleRow(mood = mood, badge = copy.badge)
        Spacer(Modifier.height(10.dp))
        DoodleStepFlow(steps = workflow.steps, mood = mood)
        Spacer(Modifier.height(10.dp))
        DoodleDashedDivider()
        Spacer(Modifier.height(9.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            DoodleFamilyWidget(
                mood = mood,
                copy = copy,
                customTemplateLayout = customTemplateLayout,
                onCustomTemplateLayoutChange = onCustomTemplateLayoutChange,
                modifier = Modifier.weight(1.2f)
            )
            Spacer(Modifier.width(10.dp))
            DoodleAiBubble(
                items = copy.aiItems,
                mood = mood,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DoodleStickyNote(
                text = copy.notes.getOrElse(0) { "" },
                color = StickyYellow,
                rotation = -2.5f,
                modifier = Modifier.weight(1f)
            )
            DoodleStickyNote(
                text = copy.notes.getOrElse(1) { "" },
                color = if (mood.family == TemplateMoodFamily.RETROSPECTIVE) StickyPink else StickyGreen,
                rotation = 2f,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DoodleSummaryPill(text = copy.summary, mood = mood)
        }
    }
}

@Composable
private fun DoodleTitleRow(mood: TemplateMood, badge: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(20.dp, 18.dp)) {
            val gap = 2.dp.toPx()
            val barWidth = ((size.width - gap * 4) / 5).coerceAtLeast(1f)
            val heights = listOf(0.45f, 0.85f, 0.6f, 1f, 0.5f)
            heights.forEachIndexed { index, ratio ->
                val h = size.height * ratio
                drawRoundRect(
                    color = if (index % 2 == 0) mood.accent else mood.ink,
                    topLeft = Offset(index * (barWidth + gap), (size.height - h) / 2f),
                    size = Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth, barWidth)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = mood.displayName,
            color = mood.ink,
            fontSize = 21.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.drawBehind {
                // Hand-drawn wavy underline beneath the title.
                val path = Path()
                val baseline = size.height - 1.dp.toPx()
                val amplitude = 1.6.dp.toPx()
                path.moveTo(0f, baseline)
                val segments = 18
                for (i in 1..segments) {
                    val x = size.width * i / segments
                    val y = baseline + sin(i * PI / 2.2).toFloat() * amplitude
                    path.lineTo(x, y)
                }
                drawPath(
                    path,
                    color = mood.accent,
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        )
        Spacer(Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(50),
            color = DoodlePaper,
            border = BorderStroke(1.2.dp, mood.accent.copy(alpha = 0.9f))
        ) {
            Text(
                text = badge,
                color = mood.ink,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun DoodleStepFlow(steps: List<TemplateWorkflowStep>, mood: TemplateMood) {
    val first = steps.getOrNull(0)
    val second = steps.getOrNull(1)
    val third = steps.getOrNull(2)
    val fourth = steps.getOrNull(3)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            first?.let { DoodleStepBox(index = 1, step = it, mood = mood, tilt = -1.2f, modifier = Modifier.weight(1f)) }
            DoodleArrow(direction = ArrowDirection.RIGHT, modifier = Modifier.size(30.dp, 26.dp))
            second?.let { DoodleStepBox(index = 2, step = it, mood = mood, tilt = 1f, modifier = Modifier.weight(1f)) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DoodleArrow(
                direction = ArrowDirection.DOWN,
                modifier = Modifier
                    .padding(end = 34.dp)
                    .size(24.dp, 22.dp)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            fourth?.let { DoodleStepBox(index = 4, step = it, mood = mood, tilt = 0.8f, modifier = Modifier.weight(1f)) }
            DoodleArrow(direction = ArrowDirection.LEFT, modifier = Modifier.size(30.dp, 26.dp))
            third?.let { DoodleStepBox(index = 3, step = it, mood = mood, tilt = -0.9f, modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DoodleStepBox(
    index: Int,
    step: TemplateWorkflowStep,
    mood: TemplateMood,
    tilt: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(90.dp)
            .rotate(tilt)
            .sketchyBorder(color = DoodleInk, strokeWidth = 1.8.dp, cornerRadius = 14.dp, seed = index * 17)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(18.dp)
                .clip(CircleShape)
                .background(mood.accent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index.toString(),
                color = Color.White,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = workflowIcon(step.iconKey),
                contentDescription = null,
                tint = DoodleInk,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = step.title,
                color = DoodleInk,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = step.detail,
                color = DoodleGray,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private enum class ArrowDirection { RIGHT, LEFT, DOWN }

@Composable
private fun DoodleArrow(direction: ArrowDirection, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val head = 5.dp.toPx()
        val path = Path()
        when (direction) {
            ArrowDirection.RIGHT -> {
                val y = size.height / 2f
                path.moveTo(2f, y + 1.5f)
                path.quadraticBezierTo(size.width / 2f, y - 3f, size.width - 3f, y)
                drawPath(path, DoodleInk, style = stroke)
                drawLine(DoodleInk, Offset(size.width - 3f, y), Offset(size.width - 3f - head, y - head), stroke.width, StrokeCap.Round)
                drawLine(DoodleInk, Offset(size.width - 3f, y), Offset(size.width - 3f - head, y + head), stroke.width, StrokeCap.Round)
            }
            ArrowDirection.LEFT -> {
                val y = size.height / 2f
                path.moveTo(size.width - 2f, y - 1.5f)
                path.quadraticBezierTo(size.width / 2f, y + 3f, 3f, y)
                drawPath(path, DoodleInk, style = stroke)
                drawLine(DoodleInk, Offset(3f, y), Offset(3f + head, y - head), stroke.width, StrokeCap.Round)
                drawLine(DoodleInk, Offset(3f, y), Offset(3f + head, y + head), stroke.width, StrokeCap.Round)
            }
            ArrowDirection.DOWN -> {
                val x = size.width / 2f
                path.moveTo(x - 1.5f, 2f)
                path.quadraticBezierTo(x + 3f, size.height / 2f, x, size.height - 3f)
                drawPath(path, DoodleInk, style = stroke)
                drawLine(DoodleInk, Offset(x, size.height - 3f), Offset(x - head, size.height - 3f - head), stroke.width, StrokeCap.Round)
                drawLine(DoodleInk, Offset(x, size.height - 3f), Offset(x + head, size.height - 3f - head), stroke.width, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun DoodleDashedDivider() {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
    ) {
        drawLine(
            color = DoodleInk.copy(alpha = 0.7f),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 6.dp.toPx()))
        )
    }
}

@Composable
private fun DoodleFamilyWidget(
    mood: TemplateMood,
    copy: DoodleCopy,
    customTemplateLayout: CustomTemplateLayout,
    onCustomTemplateLayoutChange: (CustomTemplateLayout) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .sketchyBorder(color = DoodleInk, strokeWidth = 1.5.dp, cornerRadius = 12.dp, seed = 91)
            .padding(horizontal = 9.dp, vertical = 8.dp)
    ) {
        DoodleSectionTitle(text = copy.widgetTitle, mood = mood)
        Spacer(Modifier.height(6.dp))
        when (mood.family) {
            TemplateMoodFamily.CUSTOM -> CustomModuleEditor(
                layout = customTemplateLayout,
                mood = mood,
                onLayoutChange = onCustomTemplateLayoutChange
            )
            TemplateMoodFamily.FORUM -> ForumRoster(rows = copy.widgetRows, mood = mood)
            TemplateMoodFamily.RETROSPECTIVE -> RetroTimeline(rows = copy.widgetRows, mood = mood)
            else -> MiniTable(rows = copy.widgetRows, mood = mood)
        }
    }
}

@Composable
private fun DoodleSectionTitle(text: String, mood: TemplateMood) {
    Text(
        text = text,
        color = DoodleInk,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.drawBehind {
            drawLine(
                color = mood.accent.copy(alpha = 0.85f),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height - 1f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    )
}

@Composable
private fun MiniTable(rows: List<List<String>>, mood: TemplateMood) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEachIndexed { rowIndex, cells ->
            Row(modifier = Modifier.fillMaxWidth()) {
                cells.forEachIndexed { cellIndex, cell ->
                    Text(
                        text = cell,
                        color = if (rowIndex == 0 && rows.size > 1) mood.ink else DoodleInk,
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (rowIndex == 0 || cellIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(if (cellIndex == 0) 0.8f else 1f)
                    )
                }
            }
            if (rowIndex < rows.size - 1) {
                Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
                    drawLine(
                        DoodleGray.copy(alpha = 0.45f),
                        Offset(0f, 0f),
                        Offset(size.width, 0f),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    )
                }
            }
        }
    }
}

@Composable
private fun RetroTimeline(rows: List<List<String>>, mood: TemplateMood) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        rows.forEachIndexed { index, cells ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(
                            when (index) {
                                0 -> Color(0xFFE05252)
                                1 -> Color(0xFFF0B429)
                                else -> mood.accent
                            }
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = cells.getOrElse(0) { "" },
                    color = mood.ink,
                    fontSize = 9.5.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = cells.getOrElse(1) { "" },
                    color = DoodleInk,
                    fontSize = 9.5.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "根因树：现象 → 原因 → 根因",
            color = DoodleGray,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ForumRoster(rows: List<List<String>>, mood: TemplateMood) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        rows.forEach { cells ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(mood.accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = cells.getOrElse(0) { "" }.take(1),
                        color = mood.ink,
                        fontSize = 8.sp,
                        lineHeight = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = cells.getOrElse(0) { "" },
                    color = mood.ink,
                    fontSize = 9.5.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = cells.getOrElse(1) { "" },
                    color = DoodleInk,
                    fontSize = 9.5.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = "问答脉络：问 → 答 → 追问",
            color = DoodleGray,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 1
        )
    }
}


@Composable
private fun DoodleAiBubble(items: List<String>, mood: TemplateMood, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .sketchyBorder(color = mood.accent, strokeWidth = 1.5.dp, cornerRadius = 12.dp, seed = 47)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(22.dp)) { drawRobot(mood) }
            Spacer(Modifier.width(5.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = mood.accent.copy(alpha = 0.14f)
            ) {
                Text(
                    text = "AI自动生成",
                    color = mood.ink,
                    fontSize = 8.5.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        items.forEach { item ->
            Text(
                text = "• $item",
                color = DoodleInk,
                fontSize = 9.5.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun DrawScope.drawRobot(mood: TemplateMood) {
    val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
    val headTop = size.height * 0.28f
    val headRect = androidx.compose.ui.geometry.Rect(
        left = size.width * 0.12f,
        top = headTop,
        right = size.width * 0.88f,
        bottom = size.height * 0.92f
    )
    drawRoundRect(
        color = DoodleInk,
        topLeft = Offset(headRect.left, headRect.top),
        size = Size(headRect.width, headRect.height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx(), 5.dp.toPx()),
        style = stroke
    )
    // Antenna
    drawLine(DoodleInk, Offset(size.width / 2f, headTop), Offset(size.width / 2f, size.height * 0.1f), stroke.width, StrokeCap.Round)
    drawCircle(mood.accent, radius = 1.8.dp.toPx(), center = Offset(size.width / 2f, size.height * 0.08f))
    // Eyes
    val eyeY = headTop + headRect.height * 0.42f
    drawCircle(mood.accent, radius = 1.9.dp.toPx(), center = Offset(size.width * 0.36f, eyeY))
    drawCircle(mood.accent, radius = 1.9.dp.toPx(), center = Offset(size.width * 0.64f, eyeY))
    // Smile
    val smile = Path().apply {
        moveTo(size.width * 0.36f, headTop + headRect.height * 0.7f)
        quadraticBezierTo(size.width / 2f, headTop + headRect.height * 0.86f, size.width * 0.64f, headTop + headRect.height * 0.7f)
    }
    drawPath(smile, DoodleInk, style = stroke)
}

@Composable
private fun DoodleStickyNote(text: String, color: Color, rotation: Float, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .height(48.dp)
            .rotate(rotation),
        shape = RoundedCornerShape(3.dp),
        color = color,
        shadowElevation = 2.dp
    ) {
        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = DoodleInk,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DoodleSummaryPill(text: String, mood: TemplateMood) {
    Surface(
        shape = RoundedCornerShape(50),
        color = mood.accent.copy(alpha = 0.10f),
        border = BorderStroke(1.4.dp, mood.accent)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(mood.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(9.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                color = mood.ink,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/**
 * Draws a rounded rectangle whose edges wobble slightly, giving the box a
 * marker-on-paper feel. The wobble is deterministic per [seed] so it does not
 * jitter across recompositions.
 */
internal fun Modifier.sketchyBorder(color: Color, strokeWidth: Dp, cornerRadius: Dp, seed: Int): Modifier =
    drawBehind {
        val jitter = 1.1.dp.toPx()
        var state = (seed * 1103515245L + 12345L) and 0x7fffffffL
        fun next(): Float {
            state = (state * 1103515245L + 12345L) and 0x7fffffffL
            return ((state % 1000L) / 1000f - 0.5f) * 2f * jitter
        }
        val r = cornerRadius.toPx()
        val w = size.width
        val h = size.height
        val path = Path()
        path.moveTo(r, next())
        // Top edge in two sketched segments
        path.lineTo(w / 2f, next())
        path.lineTo(w - r, next())
        path.quadraticBezierTo(w + next() * 0.4f, next() * 0.4f, w + next() * 0.4f, r)
        path.lineTo(w + next() * 0.6f, h / 2f)
        path.lineTo(w + next() * 0.6f, h - r)
        path.quadraticBezierTo(w + next() * 0.4f, h + next() * 0.4f, w - r, h + next() * 0.5f)
        path.lineTo(w / 2f, h + next() * 0.6f)
        path.lineTo(r, h + next() * 0.5f)
        path.quadraticBezierTo(next() * 0.4f, h + next() * 0.4f, next() * 0.4f, h - r)
        path.lineTo(next() * 0.6f, h / 2f)
        path.lineTo(next() * 0.6f, r)
        path.quadraticBezierTo(next() * 0.4f, next() * 0.4f, r, next() * 0.5f)
        path.close()
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        )
    }

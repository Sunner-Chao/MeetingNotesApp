package com.oa.automation.ui.screen.recording

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.oa.automation.R
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.CustomTemplateLayout
import kotlinx.coroutines.delay
import kotlin.math.sin

internal data class LiteRecordingTab(val templateName: String, val label: String, val expandedLabel: String, val color: Color)

internal val LiteRecordingTabs = listOf(
    LiteRecordingTab("宣贯·落实会", "宣贯", "落实会", Color(0xFF9D2429)),
    LiteRecordingTab("推演·进度会", "推演", "进度会", Color(0xFF45694A)),
    LiteRecordingTab("启迪·共创会", "共创", "头脑风暴", Color(0xFF9D7C29)),
    LiteRecordingTab("博弈·洽谈会", "洽谈", "博弈会", Color(0xFF78618E)),
    LiteRecordingTab("复盘·分析会", "复盘", "分析会", Color(0xFF9B663D)),
    LiteRecordingTab("自定义会议", "自定义", "自定义", Color(0xFF76746C)),
    LiteRecordingTab("聆听·策划会", "聆听", "策划会", Color(0xFF3B7890))
)

internal val LiteRecorderPalette = siriDarkPalette().copy(
    background = listOf(Color(0xFF303943), Color(0xFF252F39)),
    text = Color(0xFF28251F), muted = Color(0xFF685B48),
    cyan = Color(0xFF66513C), border = Color.Transparent,
    control = Color.Transparent, controlBorder = Color.White
)

/** Visible paper starts/ends inside the layout bounds because of the torn edge. */
internal val LitePaperTornInset = 5.dp

@Composable
internal fun LiteRecorderPaper(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .shadow(10.dp, TornPaperShape, ambientColor = Color(0xFF17130E).copy(alpha = 0.42f), spotColor = Color(0xFF17130E).copy(alpha = 0.52f))
            .clip(TornPaperShape)
            .background(Color(0xFFF2E5C9))
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.linearGradient(listOf(Color(0xFFF5EAD5), Color(0xFFEDE0C4), Color(0xFFF6EAD2))))
            // Stable fine flecks keep the paper warm without a large bitmap or animation.
            repeat(2600) { index ->
                val x = ((index * 79L % 997) / 997f) * size.width
                val y = ((index * 137L % 991) / 991f) * size.height
                drawCircle(Color(0xFF795D30).copy(alpha = 0.035f), radius = 0.55.dp.toPx(), center = Offset(x, y))
            }
            // The reference uses a tactile kraft sheet. Keep the grain subtle in
            // the centre and make the torn perimeter visibly layered instead of
            // relying on a single flat outline.
            val creaseColor = Color(0xFF8B6A3D).copy(alpha = 0.15f)
            repeat(11) { index ->
                val y = size.height * (0.08f + index * 0.083f)
                val crease = Path().apply {
                    moveTo(-8.dp.toPx(), y)
                    cubicTo(
                        size.width * 0.27f, y - 2.dp.toPx(),
                        size.width * 0.63f, y + if (index % 2 == 0) 2.dp.toPx() else -2.dp.toPx(),
                        size.width + 8.dp.toPx(), y + 1.5.dp.toPx()
                    )
                }
                drawPath(crease, creaseColor, style = androidx.compose.ui.graphics.drawscope.Stroke(0.8.dp.toPx()))
            }
            val edgeDark = Color(0xFF76542D).copy(alpha = 0.30f)
            val edgeLight = Color(0xFFFFF8E8).copy(alpha = 0.60f)
            val topEdge = Path().apply {
                moveTo(0f, 5.dp.toPx())
                for (i in 0..72) {
                    val x = size.width * i / 72f
                    val y = 4.dp.toPx() + (sin(i * 1.71f) * 1.9f + sin(i * 0.43f) * 1.2f).dp.toPx()
                    lineTo(x, y)
                }
            }
            val bottomEdge = Path().apply {
                moveTo(0f, size.height - 5.dp.toPx())
                for (i in 0..72) {
                    val x = size.width * i / 72f
                    val y = size.height - 4.dp.toPx() + (sin(i * 1.23f) * 2.1f + sin(i * 0.37f) * 1.1f).dp.toPx()
                    lineTo(x, y)
                }
            }
            drawPath(topEdge, edgeDark, style = androidx.compose.ui.graphics.drawscope.Stroke(2.1.dp.toPx()))
            drawPath(bottomEdge, edgeDark, style = androidx.compose.ui.graphics.drawscope.Stroke(2.1.dp.toPx()))
            drawPath(topEdge, edgeLight, style = androidx.compose.ui.graphics.drawscope.Stroke(0.9.dp.toPx()))
            drawPath(bottomEdge, edgeLight, style = androidx.compose.ui.graphics.drawscope.Stroke(0.9.dp.toPx()))
            // Short edge creases add the folded, fibrous look without making the
            // content area noisy or affecting text contrast.
            repeat(18) { index ->
                val x = size.width * ((index * 47L % 101) / 101f)
                val length = (5f + (index % 4) * 2f).dp.toPx()
                val tilt = (sin(index * 2.2f) * 1.6f).dp.toPx()
                drawLine(edgeDark.copy(alpha = 0.18f), Offset(x, 1.dp.toPx()), Offset(x + tilt, length), 1.dp.toPx())
                drawLine(edgeDark.copy(alpha = 0.18f), Offset(x, size.height - 1.dp.toPx()), Offset(x - tilt, size.height - length), 1.dp.toPx())
            }
            repeat(9) { index ->
                val y = size.height * (0.08f + index * 0.105f)
                drawLine(edgeDark.copy(alpha = 0.17f), Offset(1.dp.toPx(), y), Offset(6.dp.toPx(), y + sin(index * 1.8f) * 4.dp.toPx()), 1.dp.toPx())
                drawLine(edgeDark.copy(alpha = 0.17f), Offset(size.width - 1.dp.toPx(), y), Offset(size.width - 6.dp.toPx(), y + sin(index * 1.4f) * 4.dp.toPx()), 1.dp.toPx())
            }
            repeat(5) { index ->
                val x = size.width * (0.14f + index * 0.19f)
                drawLine(
                    color = Color.White.copy(alpha = 0.16f),
                    start = Offset(x, 0f),
                    end = Offset(x + 10.dp.toPx(), size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
        content()
    }
}

@Composable
internal fun RecorderNotebookSurface(
    skin: DoodleSkin,
    templates: List<PresetReportTemplate>,
    selectedName: String,
    onSelect: (PresetReportTemplate) -> Unit,
    reducedMotion: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    if (com.oa.automation.domain.model.ProductEdition.current == com.oa.automation.domain.model.ProductEdition.LIGHT_ENJOY) {
        // The reference paper stays light in either app theme, including live STT status and dialogs.
        CompositionLocalProvider(com.oa.automation.ui.theme.LocalAppIsDarkTheme provides false) {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF7D252A), onPrimary = Color.White,
                surface = Color(0xFFF2E5C9), onSurface = LiteRecorderPalette.text,
                onSurfaceVariant = LiteRecorderPalette.muted
            )) {
                LiteNotebookSurface(templates, selectedName, onSelect, reducedMotion, modifier, content)
            }
        }
    } else {
        DoodleCard(skin = skin, modifier = modifier, filled = false, content = content)
    }
}

private object TornPaperShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val inset = with(density) { LitePaperTornInset.toPx() }
        fun ripple(i: Int) = inset * (0.72f + 0.52f * sin(i * 1.7f) + 0.30f * sin(i * 3.1f))
        val path = Path().apply {
            moveTo(inset, inset)
            for (i in 1..60) lineTo(size.width * i / 60, ripple(i))
            for (i in 1..100) lineTo(size.width - ripple(i + 63), size.height * i / 100)
            for (i in 60 downTo 0) lineTo(size.width * i / 60, size.height - ripple(i + 23))
            for (i in 100 downTo 0) lineTo(ripple(i + 7), size.height * i / 100)
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
internal fun LiteWorkflowIllustration(templateName: String?, modifier: Modifier = Modifier) {
    val illustration = templateMoodFor(templateName.orEmpty()).illustrationRes ?: R.drawable.template_workflow_custom_flow
    Image(painterResource(illustration), contentDescription = "${templateName.orEmpty()}工作流示意，非实际记录统计",
        modifier = modifier, contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(Color(0xFFF2E5C9), BlendMode.Multiply))
}

@Composable
internal fun LiteRecorderSystemBars() {
    val view = LocalView.current
    val darkTheme = com.oa.automation.ui.theme.LocalAppIsDarkTheme.current
    DisposableEffect(view, darkTheme) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = window?.statusBarColor
        val nav = window?.navigationBarColor
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNav = controller?.isAppearanceLightNavigationBars
        window?.statusBarColor = Color(0xFF181917).toArgb()
        window?.navigationBarColor = Color(0xFF252F39).toArgb()
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (window != null && status != null && nav != null) {
                window.statusBarColor = status
                window.navigationBarColor = nav
                controller?.isAppearanceLightStatusBars = lightStatus ?: false
                controller?.isAppearanceLightNavigationBars = lightNav ?: false
            }
        }
    }
}

@Composable
private fun LiteNotebookSurface(
    templates: List<PresetReportTemplate>, selectedName: String,
    onSelect: (PresetReportTemplate) -> Unit, reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var expandedName by rememberSaveable { mutableStateOf<String?>(null) }
    val fontScale = LocalDensity.current.fontScale
    val tabs = LiteRecordingTabs.mapNotNull { tab ->
        templates.firstOrNull { it.name == tab.templateName }?.let { tab to it }
    }
    LaunchedEffect(expandedName) {
        if (expandedName != null) {
            delay(1_050L)
            expandedName = null
        }
    }
    BoxWithConstraints(modifier) {
        // Only the exposed label needs space. The rest of each bookmark lies
        // behind the sheet; there is no full-height rail shadow or touch layer.
        val paperInset = if (tabs.isEmpty()) 0.dp else 36.dp
        LiteRecorderPaper(Modifier.fillMaxSize().padding(start = paperInset).zIndex(1f), content)
        val tabHeight = ((maxHeight - LitePaperTornInset * 2) / tabs.size.coerceAtLeast(1)).coerceAtLeast(0.dp)
        tabs.forEachIndexed { index, (tab, template) ->
            val isExpanded = expandedName == tab.templateName
            val isSelected = selectedName == tab.templateName
            val collapsedName = templateMoodFor(tab.templateName).collapsedName
            val labelLineHeight = minOf(20f, (tabHeight.value - 8f) / collapsedName.length / fontScale).coerceAtLeast(1f)
            val expandedWidth = maxOf(116f, tab.expandedLabel.length * 14f * fontScale + 20f).dp.coerceAtMost(maxWidth)
            val width by animateDpAsState(
                if (isExpanded) expandedWidth else 48.dp,
                if (reducedMotion) tween(0) else spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "bookmarkWidth"
            )
            val shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 3.dp, bottomEnd = 3.dp)
            Box(
                modifier = Modifier
                    .offset(y = LitePaperTornInset + tabHeight * index)
                    .height(tabHeight)
                    .width(width)
                    .zIndex(if (isExpanded) 2f else 0f)
                    .shadow(if (isExpanded) 4.dp else 1.dp, shape)
                    .clip(shape)
                    .background(Brush.horizontalGradient(listOf(tab.color, tab.color.copy(alpha = 0.94f))))
                    .semantics(mergeDescendants = true) { contentDescription = tab.templateName; selected = isSelected }
                    .clickable(role = Role.Button) {
                        expandedName = if (isExpanded) null else tab.templateName
                        onSelect(template)
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                if (isSelected) {
                    Box(Modifier.align(Alignment.CenterStart).width(2.dp).fillMaxHeight(0.65f).background(Color.White.copy(alpha = 0.85f)))
                }
                AnimatedContent(
                    targetState = isExpanded,
                    transitionSpec = {
                        fadeIn(tween(if (reducedMotion) 0 else 170)) togetherWith
                            fadeOut(tween(if (reducedMotion) 0 else 120))
                    },
                    label = "bookmarkLabelTransition"
                ) { expanded ->
                    if (expanded) {
                        Text(tab.expandedLabel, modifier = Modifier.width(expandedWidth).padding(horizontal = 7.dp),
                            color = Color.White, textAlign = TextAlign.Center, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, softWrap = false)
                    } else {
                        Text(collapsedName.toList().joinToString("\n"), modifier = Modifier.width(36.dp), color = Color.White,
                            textAlign = TextAlign.Center, fontSize = minOf(13f, labelLineHeight * 0.72f).sp,
                            lineHeight = labelLineHeight.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiteTemplateWorkflow(
    templateName: String,
    workflowSeen: Set<String>,
    onViewed: (String) -> Unit,
    customTemplateLayout: CustomTemplateLayout,
    onCustomTemplateLayoutChange: (CustomTemplateLayout) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(templateName) {
        if (templateName.isNotBlank() && templateName !in workflowSeen) onViewed(templateName)
    }
    if (templateMoodFor(templateName).family == TemplateMoodFamily.CUSTOM) {
        Column(modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text("自定义会议", color = LiteRecorderPalette.text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            CustomModuleEditor(customTemplateLayout, templateMoodFor("自定义会议"), onCustomTemplateLayoutChange)
        }
    } else {
        LiteWorkflowIllustration(templateName, modifier)
    }
}

@Composable
internal fun LiteRecorderCircleButton(
    icon: ImageVector, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, size: Dp = 44.dp, enabled: Boolean = true,
    busy: Boolean = false, fill: Color = Color(0xFFE8DECA)
) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.size(size), shape = CircleShape,
        color = Color.Transparent, contentColor = Color(0xFF292D2D),
        border = BorderStroke(1.5.dp, fill.copy(alpha = if (enabled) 1f else 0.55f))) {
        Box(Modifier.padding(4.dp).background(fill.copy(alpha = if (enabled) 1f else 0.5f), CircleShape), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(size * 0.45f).semantics { contentDescription = description }, color = Color(0xFF292D2D), strokeWidth = 2.dp)
            else Icon(icon, description, modifier = Modifier.size(size * 0.46f), tint = Color(0xFF292D2D))
        }
    }
}

@Composable
internal fun LiteRecorderTimer(seconds: Long, recording: Boolean) {
    val duration = "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    Text(duration, color = Color(0xFFF0EEE8), fontSize = 29.sp,
        modifier = Modifier.semantics { contentDescription = "${if (recording) "录音时长" else "会议时长"} $duration" })
}

@Composable
internal fun LiteRecorderActions(
    isRecording: Boolean, isPaused: Boolean, actionEnabled: Boolean,
    hasSelectedTemplate: Boolean, hasActivePhotoMarker: Boolean,
    canGenerate: Boolean, isTranscribing: Boolean, isGeneratingReport: Boolean,
    onPhoto: () -> Unit, onMainAction: () -> Unit, onGenerate: () -> Unit, onCancel: () -> Unit
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LiteRecorderCircleButton(Icons.Default.PhotoCamera, "拍照或添加图片标记", onPhoto, size = 62.dp, fill = Color(0xFFD2D0DD),
                enabled = isRecording && !isPaused && actionEnabled)
            Text(if (hasActivePhotoMarker) "待插图" else "插图", color = Color(0xFFE0DCD4), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LiteRecorderCircleButton(
                if (isRecording && !isPaused) Icons.Default.Pause else if (isPaused) Icons.Default.PlayArrow else Icons.Default.Mic,
                if (isRecording && !isPaused) "暂停录音" else if (isPaused) "继续录音" else "开始录音",
                onMainAction, size = 76.dp, enabled = actionEnabled, fill = Color(0xFFF4ECD6))
            Text(if (isRecording && !isPaused) "暂停" else if (isPaused) "继续" else "开始", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            if (!hasSelectedTemplate) Text("请先选择左侧会议类型", color = Color(0xFFD0CCC4), fontSize = 10.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LiteRecorderCircleButton(if (isGeneratingReport) Icons.Default.Stop else Icons.Default.Summarize,
                if (isGeneratingReport) "停止生成" else "生成纪要", if (isGeneratingReport) onCancel else onGenerate,
                size = 62.dp, enabled = isGeneratingReport || (canGenerate && !isTranscribing), busy = isTranscribing)
            Text(if (isGeneratingReport) "停止生成" else if (isTranscribing) "整理中" else "生成纪要", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

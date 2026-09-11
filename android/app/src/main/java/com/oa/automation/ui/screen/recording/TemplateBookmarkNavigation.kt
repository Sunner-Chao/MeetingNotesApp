package com.oa.automation.ui.screen.recording

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import com.oa.automation.domain.model.PresetReportTemplate
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Horizontal footprint the rail overlays on top of the canvas. The rail is
 * drawn as an overlay rather than a layout column, so the workflow canvas, the
 * top bar and the three bottom actions always use the full screen width.
 */
internal val TemplateRailOverlayWidth = 96.dp

/**
 * Width of the colour sliver left visible while a bookmark is hidden.
 *
 * A bookmark has exactly two states: this hidden sliver, and [ExpandedWidth].
 * There is deliberately no intermediate "slightly out" width — sticking out
 * *is* the expanded state.
 */
private val SliverWidth = 24.dp
private val ExpandedWidth = 86.dp

/** Minimum touchable width so a hidden sliver is still comfortable to hit. */
private val TouchTargetWidth = 34.dp
private val TabSpacing = 8.dp
private val MaxTabHeight = 58.dp

/** Holding a bookmark for this long pins it open instead of peeking. */
private const val LongPressMillis = 380L

/** How long a peeked bookmark stays out before sliding back on its own. */
private const val PeekHoldMillis = 1_150L

/**
 * Bookmark navigation on the left edge of the recording page.
 *
 * Bookmarks are hidden by default and only show a sliver of their own mood
 * colour. Touching or short pressing one slides it out with a spring and it
 * retracts on its own; long pressing pins it open; tapping a pinned bookmark
 * or guiding it back in with a finger hides it again.
 */
@Composable
internal fun TemplateBookmarkRail(
    templates: List<PresetReportTemplate>,
    selectedTemplateName: String,
    palette: SiriRecorderPalette,
    skin: DoodleSkin,
    isDark: Boolean,
    onSelectTemplate: (PresetReportTemplate) -> Unit,
    modifier: Modifier = Modifier
) {
    if (templates.isEmpty()) return
    var revealedIndex by remember { mutableIntStateOf(-1) }
    var pinned by remember { mutableStateOf(false) }

    // Keep the reveal inside bounds when the visible catalog changes.
    LaunchedEffect(templates.size) {
        if (revealedIndex >= templates.size) {
            revealedIndex = -1
            pinned = false
        }
    }
    val expanded = revealedIndex in templates.indices
    LaunchedEffect(revealedIndex, pinned) {
        if (expanded && !pinned) {
            delay(PeekHoldMillis)
            revealedIndex = -1
        }
    }

    BoxWithConstraints(modifier = modifier.width(TemplateRailOverlayWidth)) {
        val available = maxHeight - TabSpacing * (templates.size - 1)
        val tabHeight = minOf(MaxTabHeight, (available / templates.size).coerceAtLeast(28.dp))
        Column(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalArrangement = Arrangement.spacedBy(TabSpacing)
        ) {
            templates.forEachIndexed { index, template ->
                BookmarkTab(
                    mood = templateMoodFor(template.name),
                    selected = template.name == selectedTemplateName,
                    revealed = revealedIndex == index,
                    pinned = pinned && revealedIndex == index,
                    index = index,
                    tabCount = templates.size,
                    tabHeight = tabHeight,
                    tabSpacing = TabSpacing,
                    palette = palette,
                    skin = skin,
                    isDark = isDark,
                    // A finger sliding down the rail hands the reveal to
                    // whichever bookmark it passes over, so the whole strip
                    // can be browsed in one stroke without lifting.
                    onRevealIndex = { target ->
                        revealedIndex = target.coerceIn(0, templates.lastIndex)
                        pinned = false
                    },
                    onPinIndex = { target ->
                        revealedIndex = target.coerceIn(0, templates.lastIndex)
                        pinned = true
                    },
                    onHide = {
                        revealedIndex = -1
                        pinned = false
                    },
                    onSelectIndex = { target ->
                        templates.getOrNull(target)?.let(onSelectTemplate)
                    }
                )
            }
        }
    }
}

@Composable
private fun BookmarkTab(
    mood: TemplateMood,
    selected: Boolean,
    revealed: Boolean,
    pinned: Boolean,
    index: Int,
    tabCount: Int,
    tabHeight: Dp,
    tabSpacing: Dp,
    palette: SiriRecorderPalette,
    skin: DoodleSkin,
    isDark: Boolean,
    onRevealIndex: (Int) -> Unit,
    onPinIndex: (Int) -> Unit,
    onHide: () -> Unit,
    onSelectIndex: (Int) -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bookmarkReveal"
    )
    val tabWidth = lerp(SliverWidth, ExpandedWidth, progress)
    val hitWidth = maxOf(tabWidth, TouchTargetWidth)
    val labelAlpha = ((progress - 0.42f) / 0.58f).coerceIn(0f, 1f)
    val openStroke = if (selected) mood.accent else skin.ink.copy(alpha = 0.32f)
    val stroke = lerp(mood.ink, openStroke, progress)

    // Read the latest interaction state from inside the long lived gesture loop.
    val currentPinned by rememberUpdatedState(pinned)
    val revealIndex by rememberUpdatedState(onRevealIndex)
    val pinIndex by rememberUpdatedState(onPinIndex)
    val hide by rememberUpdatedState(onHide)
    val selectIndex by rememberUpdatedState(onSelectIndex)

    Box(
        modifier = Modifier
            .width(hitWidth)
            .height(tabHeight)
            .semantics(mergeDescendants = true) {
                contentDescription = if (selected) {
                    "${mood.displayName}，${mood.moodWord}，当前模板"
                } else {
                    "${mood.displayName}，${mood.moodWord}"
                }
                role = Role.Button
                onClick(label = "选择模板") {
                    selectIndex(index)
                    revealIndex(index)
                    true
                }
                onLongClick(label = "固定展开") {
                    pinIndex(index)
                    true
                }
            }
            .pointerInput(index, tabCount, tabHeight, tabSpacing) {
                val guideThreshold = 14.dp.toPx()
                // Tabs are uniform, so the bookmark under a sliding finger can
                // be derived from the vertical travel out of this tab's own box
                // without the sibling tabs ever receiving the pointer.
                val stepPx = (tabHeight + tabSpacing).toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startedPinned = currentPinned
                    var hovered = index
                    // Touching the rail already slides a bookmark fully out, so
                    // a finger resting on it previews without committing.
                    revealIndex(hovered)
                    var dragX = 0f
                    var slid = false

                    fun trackHover(localY: Float) {
                        if (stepPx <= 0f) return
                        val target = (index + floor(localY / stepPx).toInt())
                            .coerceIn(0, tabCount - 1)
                        if (target != hovered) {
                            hovered = target
                            slid = true
                            revealIndex(target)
                        }
                    }

                    val release: PointerInputChange? = withTimeoutOrNull(LongPressMillis) {
                        var lifted: PointerInputChange? = null
                        while (lifted == null) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null) {
                                dragX += change.positionChange().x
                                trackHover(change.position.y)
                                if (!change.pressed) lifted = change
                            }
                        }
                        lifted
                    }
                    if (release == null) {
                        // Held long enough: pin whatever is under the finger and
                        // keep following it until the lift.
                        pinIndex(hovered)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: continue
                            val before = hovered
                            trackHover(change.position.y)
                            if (hovered != before) pinIndex(hovered)
                            if (!change.pressed) break
                        }
                        return@awaitEachGesture
                    }
                    when {
                        // Guiding a bookmark back in with the finger hides it.
                        dragX <= -guideThreshold -> hide()
                        // Browsing the rail by sliding is preview only: it never
                        // commits a template, and the rail retracts on lift.
                        slid -> hide()
                        // A tap always commits. From a pinned bookmark it also
                        // retracts; otherwise the reveal slides back on its own.
                        startedPinned -> {
                            selectIndex(hovered)
                            hide()
                        }
                        else -> {
                            selectIndex(hovered)
                            revealIndex(hovered)
                        }
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .width(tabWidth)
                .fillMaxHeight()
        ) {
            // Hand-drawn frame background
            androidx.compose.foundation.Canvas(
                modifier = Modifier.matchParentSize()
            ) {
                val w = size.width
                val h = size.height
                // Clamp the corner so the six-sided bookmark stays legible at
                // both the collapsed and expanded widths.
                val corner = minOf(12.dp.toPx(), w / 2.5f, h / 2.5f)
                val sw = if (selected) 1.7.dp.toPx() else 1.3.dp.toPx()

                // Collapsed: a slim coloured label spine. Expanded: a single
                // mood-coloured hexagon, with no white paper rectangle.
                val path = androidx.compose.ui.graphics.Path()
                if (progress > 0.35f) {
                    val inset = minOf(corner, w / 3f)
                    path.moveTo(inset, 0f)
                    path.lineTo(w - inset, 0f)
                    path.lineTo(w, h / 2f)
                    path.lineTo(w - inset, h)
                    path.lineTo(inset, h)
                    path.lineTo(0f, h / 2f)
                } else {
                    path.moveTo(0f, 0f)
                    path.lineTo(w, 0f)
                    path.lineTo(w, h)
                    path.lineTo(0f, h)
                }
                path.close()

                drawPath(
                    path = path,
                    color = mood.accent.copy(alpha = if (progress > 0.35f) 0.96f else 0.88f)
                )

                drawPath(
                    path = path,
                    color = stroke,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = sw,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }

            if (progress > 0.04f) {
                // Mood spine, echoing the coloured edge of a paper bookmark.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        .width(5.dp)
                        .fillMaxHeight(0.66f)
                        .graphicsLayer { alpha = progress }
                        .background(mood.accent, RoundedCornerShape(3.dp))
                )
            }
            if (labelAlpha > 0.01f) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, end = 5.dp, top = 5.dp, bottom = 5.dp)
                        .graphicsLayer { alpha = labelAlpha },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = mood.icon,
                        contentDescription = null,
                        tint = if (progress > 0.35f) Color.White else mood.accent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = mood.displayName,
                        color = if (progress > 0.35f) Color.White else if (isDark && !selected) palette.text else mood.ink,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                )
                }
            }
            if (labelAlpha <= 0.01f) {
                Text(
                    text = mood.displayName,
                    color = if (isDark && !selected) palette.text else mood.ink,
                    fontSize = 8.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer { rotationZ = -90f }
                )
            }
        }
    }
}

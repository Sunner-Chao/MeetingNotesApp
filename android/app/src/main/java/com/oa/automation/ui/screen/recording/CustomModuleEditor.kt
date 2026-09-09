package com.oa.automation.ui.screen.recording

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oa.automation.domain.model.CustomTemplateLayout
import com.oa.automation.domain.model.CustomTemplateModule
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

private val RowHeight = 26.dp
private val RowGap = 4.dp

/** Holding a row this long picks it up for dragging. */
private const val PickUpMillis = 220L

/**
 * The real drag-and-drop editor for the 自定义会议 template.
 *
 * Long press a row to pick it up, drag vertically to reorder, and tap a row to
 * include or exclude that module. Every change is committed through
 * [onLayoutChange] so it persists and drives the generated report.
 */
@Composable
internal fun CustomModuleEditor(
    layout: CustomTemplateLayout,
    mood: TemplateMood,
    onLayoutChange: (CustomTemplateLayout) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val stepPx = with(density) { (RowHeight + RowGap).toPx() }

    // Index currently held by the finger, and how far it has travelled.
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx by remember { mutableStateOf(0f) }

    val currentLayout by rememberUpdatedState(layout)
    val commit by rememberUpdatedState(onLayoutChange)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RowGap)
    ) {
        layout.order.forEachIndexed { index, module ->
            // While a row is held, its neighbours slide to open a gap so the
            // drop position is visible before the finger lifts.
            val shift = when {
                draggingIndex < 0 -> 0f
                index == draggingIndex -> 0f
                else -> {
                    val target = draggingIndex + (dragOffsetPx / stepPx).roundToInt()
                    val landing = target.coerceIn(0, layout.order.lastIndex)
                    when {
                        draggingIndex < index && index <= landing -> -stepPx
                        draggingIndex > index && index >= landing -> stepPx
                        else -> 0f
                    }
                }
            }
            val animatedShift by animateFloatAsState(
                targetValue = shift,
                animationSpec = spring(stiffness = 900f),
                label = "moduleShift"
            )

            ModuleRow(
                module = module,
                enabled = layout.isEnabled(module),
                dragging = index == draggingIndex,
                mood = mood,
                translationY = if (index == draggingIndex) dragOffsetPx else animatedShift,
                onToggle = { commit(currentLayout.toggled(module)) },
                onDragStart = {
                    draggingIndex = index
                    dragOffsetPx = 0f
                },
                onDrag = { delta -> dragOffsetPx += delta },
                onDragEnd = {
                    val moved = (dragOffsetPx / stepPx).roundToInt()
                    if (moved != 0) {
                        commit(currentLayout.moved(index, index + moved))
                    }
                    draggingIndex = -1
                    dragOffsetPx = 0f
                },
                onDragCancel = {
                    draggingIndex = -1
                    dragOffsetPx = 0f
                },
                // Accessibility fallback for reordering without a drag gesture.
                onMoveUp = { commit(currentLayout.moved(index, index - 1)) },
                onMoveDown = { commit(currentLayout.moved(index, index + 1)) }
            )
        }

        Spacer(Modifier.height(1.dp))
        Text(
            text = "长按拖动排序 · 点按启用或停用 · 已启用 ${layout.activeModules.size}/${layout.order.size}",
            color = mood.ink,
            fontSize = 8.sp,
            lineHeight = 10.sp,
            maxLines = 2
        )
    }
}

@Composable
private fun ModuleRow(
    module: CustomTemplateModule,
    enabled: Boolean,
    dragging: Boolean,
    mood: TemplateMood,
    translationY: Float,
    onToggle: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val toggle by rememberUpdatedState(onToggle)
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val dragCancel by rememberUpdatedState(onDragCancel)

    val lift by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(120),
        label = "moduleLift"
    )
    val rowColor = if (enabled) mood.accent else DoodleGray
    val labelColor = if (enabled) DoodleInk else DoodleInk.copy(alpha = 0.42f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .graphicsLayer {
                this.translationY = translationY
                // A held row rides above the others.
                scaleX = 1f + 0.02f * lift
                scaleY = 1f + 0.02f * lift
                shadowElevation = 6f * lift
            }
            .semantics(mergeDescendants = true) {
                contentDescription = if (enabled) {
                    "${module.title}，已启用，${module.hint}"
                } else {
                    "${module.title}，已停用，${module.hint}"
                }
                role = Role.Switch
                onClick(label = if (enabled) "停用模块" else "启用模块") {
                    toggle()
                    true
                }
                onLongClick(label = "向上移动") {
                    onMoveUp()
                    true
                }
            }
            .pointerInput(module) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalDy = 0f
                    // A short press toggles; holding picks the row up instead.
                    val quickRelease: PointerInputChange? =
                        withTimeoutOrNull(PickUpMillis) {
                            var lifted: PointerInputChange? = null
                            while (lifted == null) {
                                val change = awaitPointerEvent().changes
                                    .firstOrNull { it.id == down.id } ?: continue
                                totalDy += change.positionChange().y
                                if (!change.pressed) lifted = change
                            }
                            lifted
                        }
                    if (quickRelease != null) {
                        if (abs(totalDy) < 12.dp.toPx()) toggle()
                        return@awaitEachGesture
                    }

                    dragStart()
                    var cancelled = false
                    while (true) {
                        val change = awaitPointerEvent().changes
                            .firstOrNull { it.id == down.id }
                        if (change == null) {
                            cancelled = true
                            break
                        }
                        if (!change.pressed) break
                        drag(change.positionChange().y)
                        change.consume()
                    }
                    if (cancelled) dragCancel() else dragEnd()
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(RowHeight)
                .sketchyBorder(
                    color = rowColor,
                    strokeWidth = if (dragging) 1.6.dp else 1.dp,
                    cornerRadius = 8.dp,
                    seed = module.ordinal * 7 + 3
                )
                .background(
                    color = if (dragging) rowColor.copy(alpha = 0.12f) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.DragIndicator,
                contentDescription = null,
                tint = rowColor,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = module.title,
                color = labelColor,
                fontSize = 9.5.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ModuleCheckBox(enabled = enabled, color = rowColor)
        }
    }
}

@Composable
private fun ModuleCheckBox(enabled: Boolean, color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .drawBehind {
                if (enabled) {
                    drawRoundRect(
                        color = color,
                        style = Stroke(width = 1.2.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            3.dp.toPx(),
                            3.dp.toPx()
                        )
                    )
                } else {
                    drawRoundRect(
                        color = color,
                        style = Stroke(
                            width = 1.2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(2.dp.toPx(), 2.dp.toPx())
                            )
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            3.dp.toPx(),
                            3.dp.toPx()
                        )
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (enabled) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

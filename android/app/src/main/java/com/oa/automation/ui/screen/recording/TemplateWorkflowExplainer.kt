package com.oa.automation.ui.screen.recording

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/**
 * Compact first-use explainer shown below the template selector. It keeps a
 * fixed height so its animation never moves the recorder or transcript card.
 */
@Composable
internal fun TemplateWorkflowExplainer(
    templateName: String,
    reducedMotion: Boolean = false,
    hasBeenSeen: Boolean = false,
    onViewed: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    surfaceColor: Color = MaterialTheme.colorScheme.surface,
    raisedColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    inkColor: Color = MaterialTheme.colorScheme.onSurface,
    mutedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant
) {
    val workflow = remember(templateName) { templateWorkflowFor(templateName) }
    var selectedStep by remember(templateName) { mutableIntStateOf(0) }
    val currentStep = workflow.steps.getOrNull(selectedStep) ?: workflow.steps.first()
    val selectedLabel = "查看第 ${selectedStep + 1} 步"

    LaunchedEffect(workflow.templateName) {
        if (!hasBeenSeen) onViewed(workflow.templateName)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(142.dp),
        shape = RoundedCornerShape(12.dp),
        color = surfaceColor.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${workflow.templateName} · 工作流",
                    color = inkColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (hasBeenSeen) "点击节点看细节" else "首次了解 · 点击节点",
                    color = mutedColor,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }
            Text(
                text = workflow.goal,
                color = mutedColor,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            WorkflowStepRail(
                steps = workflow.steps,
                selectedStep = selectedStep,
                reducedMotion = reducedMotion,
                raisedColor = raisedColor,
                inkColor = inkColor,
                mutedColor = mutedColor,
                accentColor = accentColor,
                borderColor = borderColor,
                onStepSelected = { selectedStep = it }
            )
            if (reducedMotion) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = accentColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = selectedLabel,
                            color = accentColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentStep.detail,
                            color = inkColor,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "AI关注：${workflow.aiFocus}",
                            color = mutedColor,
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else AnimatedContent(
                targetState = currentStep,
                label = "workflowStepDetail"
            ) { step ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = accentColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = selectedLabel,
                            color = accentColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(step.detail, color = inkColor, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("AI关注：${workflow.aiFocus}", color = mutedColor, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(
                text = "产出：${workflow.output}  ·  ${workflow.confirmation}",
                color = mutedColor,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WorkflowStepRail(
    steps: List<TemplateWorkflowStep>,
    selectedStep: Int,
    reducedMotion: Boolean,
    raisedColor: Color,
    inkColor: Color,
    mutedColor: Color,
    accentColor: Color,
    borderColor: Color,
    onStepSelected: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            if (steps.size > 1) {
                val segment = size.width / (steps.size - 1)
                val y = size.height / 2f
                val points = (0..24).map { index ->
                    val x = size.width * index / 24f
                    Offset(x, y + sin(index * 1.7f) * 1.5f)
                }
                points.zipWithNext().forEach { (start, end) ->
                    drawLine(
                        color = borderColor.copy(alpha = 0.72f),
                        start = start,
                        end = end,
                        strokeWidth = 2.dp.toPx()
                    )
                }
                // Small hand-drawn ticks keep the rail lively without using
                // platform screenshots or third-party artwork.
                steps.dropLast(1).forEachIndexed { index, _ ->
                    val x = segment * index + segment / 2f
                    drawLine(
                        color = accentColor.copy(alpha = 0.38f),
                        start = Offset(x, y - 4.dp.toPx()),
                        end = Offset(x + 2.dp.toPx(), y + 4.dp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            steps.forEachIndexed { index, step ->
                val selected = index == selectedStep
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1.06f else 1f,
                    animationSpec = if (reducedMotion) snap() else tween(180, easing = FastOutSlowInEasing),
                    label = "workflowNodeScale"
                )
                val nodeColor by animateColorAsState(
                    targetValue = if (selected) accentColor else raisedColor,
                    animationSpec = if (reducedMotion) snap() else tween(180, easing = FastOutSlowInEasing),
                    label = "workflowNodeColor"
                )
                Column(
                    modifier = Modifier
                        .width(66.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            role = Role.Button,
                            onClick = { onStepSelected(index) }
                        )
                        .padding(vertical = 1.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier.size(25.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = nodeColor,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) accentColor else borderColor
                        )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = workflowIcon(step.iconKey),
                                contentDescription = null,
                                tint = if (selected) Color.White else inkColor,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Text(
                        text = step.title,
                        color = if (selected) accentColor else mutedColor,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun workflowIcon(key: String): ImageVector = when (key) {
    "listen" -> Icons.Default.Mic
    "split" -> Icons.Default.Settings
    "check", "done" -> Icons.Default.Check
    "timeline" -> Icons.Default.Timeline
    "risk", "block" -> Icons.Default.WarningAmber
    "branch", "cluster", "filter" -> Icons.Default.Groups
    "spark", "insight" -> Icons.Default.Lightbulb
    "people", "terms" -> Icons.Default.Person
    "signal" -> Icons.Default.Search
    "search" -> Icons.Default.Search
    "today" -> Icons.Default.Speed
    "output" -> Icons.Default.Description
    else -> Icons.Default.AutoAwesome
}

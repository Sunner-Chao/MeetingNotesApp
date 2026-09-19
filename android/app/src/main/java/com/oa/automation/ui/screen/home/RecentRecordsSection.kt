package com.oa.automation.ui.screen.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oa.automation.domain.model.displayTitle
import com.oa.automation.ui.formatBeijingTime
import com.oa.automation.ui.theme.BrandBlue
import com.oa.automation.ui.theme.LocalAppIsDarkTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * Per-card colour set. The leading stripe encodes *what kind* of record this is
 * once it is finished (blue = 即刻倾听, purple = 顷刻成稿) and switches to amber
 * while the record still needs work, so a glance down the column reads as
 * "what still needs me" before it reads as "what is this".
 */
private data class RecordAccent(
    val stripe: Color,
    val badgeContainer: Color,
    val badgeContent: Color,
    val chipContainer: Color,
    val chipContent: Color,
    val artTint: Color,
    val artInk: Color
)

@Composable
private fun recordAccent(
    isActive: Boolean,
    hasReport: Boolean,
    type: RecentRecordType
): RecordAccent {
    val colors = homeColors()
    val dark = LocalAppIsDarkTheme.current
    val typeInk = if (type == RecentRecordType.IMPORT) {
        if (dark) Color(0xFFB3A4F5) else Color(0xFF6D5BD0)
    } else {
        if (dark) Color(0xFF7FC0F5) else BrandBlue
    }
    val artTint = if (type == RecentRecordType.IMPORT) {
        if (dark) Color(0xFF272641) else Color(0xFFF1EDFD)
    } else {
        if (dark) Color(0xFF16303F) else Color(0xFFEAF3FC)
    }
    return when {
        isActive -> RecordAccent(
            stripe = BrandBlue,
            badgeContainer = BrandBlue.copy(alpha = 0.14f),
            badgeContent = BrandBlue,
            chipContainer = typeInk.copy(alpha = 0.12f),
            chipContent = typeInk,
            artTint = artTint,
            artInk = typeInk
        )
        hasReport -> RecordAccent(
            stripe = typeInk,
            badgeContainer = colors.completedContainer,
            badgeContent = colors.completedContent,
            chipContainer = typeInk.copy(alpha = 0.12f),
            chipContent = typeInk,
            artTint = artTint,
            artInk = typeInk
        )
        else -> RecordAccent(
            // The badge text colour is tuned for contrast on its own container and
            // reads muddy as a solid stripe, so the stripe gets its own amber.
            stripe = if (dark) Color(0xFFF0C24B) else Color(0xFFF2A63B),
            badgeContainer = colors.pendingContainer,
            badgeContent = colors.pendingContent,
            chipContainer = typeInk.copy(alpha = 0.12f),
            chipContent = typeInk,
            artTint = artTint,
            artInk = typeInk
        )
    }
}

/**
 * Cards lift off the page in the light theme; on the dark theme a drop shadow is
 * invisible at best and reads as grime at worst, so it is dropped there.
 */
@Composable
internal fun surfaceLift(elevation: Dp): Dp =
    if (LocalAppIsDarkTheme.current) 0.dp else elevation

/** A quiet heading and one clear route into the complete record list. */
@Composable
internal fun RecentRecordsHeader(
    count: Int,
    onShowAll: () -> Unit,
    layout: HomeLayoutSpec
) {
    val colors = homeColors()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "最近记录",
            modifier = Modifier.weight(1f),
            fontSize = if (layout.compact) 19.sp else 21.sp,
            fontWeight = FontWeight.Bold,
            color = colors.ink
        )
        if (count > 0) {
            Text(count.toString(), fontSize = 12.sp, color = colors.mutedInk)
            TextButton(onClick = onShowAll) {
                Text("查看全部", fontSize = 13.sp, color = colors.mutedInk)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = colors.mutedInk, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** One compact menu keeps filters out of the home preview. */
@Composable
internal fun RecentRecordsFilterBar(
    filter: RecentRecordFilter,
    counts: RecentRecordCounts,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onFilterChange: (RecentRecordFilter) -> Unit
) {
    val colors = homeColors()
    val summary = if (filter.isDefault) {
        "共 ${counts.total} 条"
    } else {
        listOfNotNull(
            filter.type.takeIf { it != RecentRecordType.ALL }?.label,
            filter.status.takeIf { it != RecentRecordStatus.ALL }?.label,
            filter.sort.takeIf { it != RecentRecordSort.NEWEST }?.label
        ).joinToString(" · ")
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            summary, modifier = Modifier.weight(1f),
            color = if (filter.isDefault) colors.mutedInk else BrandBlue,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Box {
            TextButton(onClick = { onExpandedChange(!expanded) }) {
                Icon(Icons.Default.FilterList, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("筛选", fontSize = 12.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
                RecentRecordType.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(if (option == RecentRecordType.ALL) "全部类型" else option.label) },
                        trailingIcon = { if (filter.type == option) Icon(Icons.Default.Check, "已选择") },
                        onClick = { onFilterChange(filter.copy(type = option)); onExpandedChange(false) }
                    )
                }
                HorizontalDivider()
                RecentRecordStatus.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(if (option == RecentRecordStatus.ALL) "全部状态" else option.label) },
                        trailingIcon = { if (filter.status == option) Icon(Icons.Default.Check, "已选择") },
                        onClick = { onFilterChange(filter.copy(status = option)); onExpandedChange(false) }
                    )
                }
                HorizontalDivider()
                RecentRecordSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        trailingIcon = { if (filter.sort == option) Icon(Icons.Default.Check, "已选择") },
                        onClick = { onFilterChange(filter.copy(sort = option)); onExpandedChange(false) }
                    )
                }
                if (!filter.isDefault) {
                    DropdownMenuItem(
                        text = { Text("重置筛选") },
                        onClick = { onFilterChange(RecentRecordFilter()); onExpandedChange(false) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordTally(counts: RecentRecordCounts, colors: HomeColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${counts.total} 条记录",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = colors.mutedInk,
            maxLines = 1
        )
        if (counts.pending > 0) {
            Text(
                text = " · ",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = colors.mutedInk
            )
            Text(
                text = "${counts.pending} 条待完善",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = colors.pendingContent,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun FilterRow(label: String, content: @Composable () -> Unit) {
    val colors = homeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = colors.mutedInk,
            modifier = Modifier.width(34.dp)
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
private fun FilterChip(
    text: String,
    badge: Int?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = homeColors()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) BrandBlue else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) BrandBlue else colors.mutedInk.copy(alpha = 0.24f)
        ),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                color = if (selected) Color.White else colors.ink,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
            if (badge != null) {
                Text(
                    text = badge.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (selected) Color.White.copy(alpha = 0.82f) else colors.mutedInk,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SortToggle(sort: RecentRecordSort, onSortChange: (RecentRecordSort) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, BrandBlue.copy(alpha = 0.45f)),
            modifier = Modifier.clickable { menuOpen = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = null,
                    tint = BrandBlue,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = sort.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color = BrandBlue,
                    maxLines = 1
                )
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = BrandBlue,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            RecentRecordSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, fontSize = 13.sp) },
                    trailingIcon = {
                        if (option == sort) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = BrandBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onSortChange(option)
                    }
                )
            }
        }
    }
}

/**
 * A compact single-column row: title and time carry the record, while the
 * status/type remain available at the trailing edge.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun RecentRecordCard(
    item: MeetingWithReport,
    activeRecording: ActiveRecordingSummary?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = homeColors()
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val displayTitle = item.meeting.displayTitle()
    val isActive = activeRecording != null
    val type = item.meeting.origin.toRecentRecordType()
    val accent = recordAccent(isActive = isActive, hasReport = item.hasReport, type = type)
    val stripeWidth = 4.dp

    val pulse = rememberInfiniteTransition(label = "activeRecordCard")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1_100), RepeatMode.Reverse),
        label = "activeRecordCardAlpha"
    )
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除会议") },
            text = { Text("“$displayTitle”及其录音、转写和纪要将被永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "会议操作",
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(16.dp),
        color = colors.meetingSurface,
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) {
                BrandBlue.copy(alpha = pulseAlpha)
            } else {
                colors.mutedInk.copy(alpha = 0.14f)
            }
        ),
        shadowElevation = surfaceLift(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(color = accent.stripe, size = Size(stripeWidth.toPx(), size.height))
                }
                .padding(start = stripeWidth)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(36.dp).background(accent.artTint, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (type == RecentRecordType.IMPORT) Icons.Default.Description else Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = accent.artInk,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 14.sp,
                            lineHeight = 18.sp
                        ),
                        color = colors.ink,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = colors.mutedInk,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "${formatBeijingTime(item.meeting.createdAt, "MM-dd HH:mm")} · " +
                                meetingDurationLabel(item.meeting),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = colors.mutedInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusBadge(
                        text = when {
                            isActive && activeRecording?.isPaused == true -> "已暂停"
                            isActive -> "录音中"
                            item.hasReport -> "已完成"
                            else -> "待完善"
                        },
                        icon = when {
                            isActive -> Icons.Default.Mic
                            item.hasReport -> Icons.Default.CheckCircle
                            else -> Icons.Default.Edit
                        },
                        accent = accent
                    )
                    TypeChip(text = type.label, accent = accent)
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "打开会议",
                    tint = colors.mutedInk,
                    modifier = Modifier.size(16.dp)
                )
            }
            Box(modifier = Modifier.size(1.dp)) {
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("修改名称") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteOutline,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    icon: ImageVector,
    accent: RecordAccent,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = accent.badgeContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                color = accent.badgeContent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent.badgeContent,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

@Composable
private fun TypeChip(text: String, accent: RecordAccent, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = accent.chipContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = accent.chipContent,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

/**
 * Waveform band for spoken records. The bar heights come from the meeting id, so
 * every card looks individual but never changes between recompositions.
 */
@Composable
private fun WaveformBandArtwork(ink: Color, seed: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val bars = 30
        val slot = size.width / bars
        val barWidth = slot * 0.42f
        var state = seed or 1
        for (index in 0 until bars) {
            state = state * 1_103_515_245 + 12_345
            val noise = ((state ushr 16) and 0x7FFF) / 32_767f
            // A gentle envelope keeps the middle taller, like a real utterance.
            val envelope = sin(PI * (index + 0.5) / bars).toFloat()
            val barHeight = size.height * (0.14f + 0.80f * noise * envelope)
            drawRoundRect(
                color = ink.copy(alpha = 0.30f + 0.55f * envelope),
                topLeft = Offset(
                    x = index * slot + (slot - barWidth) / 2f,
                    y = (size.height - barHeight) / 2f
                ),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

/** Text-lines-plus-folded-page band for imported records. */
@Composable
private fun DocumentBandArtwork(ink: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val lineHeight = size.height * 0.11f
        val gap = size.height * 0.13f
        val lineWidths = listOf(0.62f, 0.48f, 0.56f, 0.36f)
        lineWidths.forEachIndexed { index, fraction ->
            drawRoundRect(
                color = ink.copy(alpha = if (index == 0) 0.55f else 0.30f),
                topLeft = Offset(0f, index * (lineHeight + gap)),
                size = Size(size.width * fraction, lineHeight),
                cornerRadius = CornerRadius(lineHeight / 2f)
            )
        }
        // Folded page on the trailing side.
        val pageWidth = size.width * 0.19f
        val pageHeight = size.height * 0.86f
        val pageLeft = size.width - pageWidth
        val pageTop = size.height * 0.07f
        val fold = pageWidth * 0.42f
        drawRoundRect(
            color = ink.copy(alpha = 0.22f),
            topLeft = Offset(pageLeft, pageTop),
            size = Size(pageWidth, pageHeight),
            cornerRadius = CornerRadius(pageWidth * 0.18f)
        )
        val foldPath = Path().apply {
            moveTo(pageLeft + pageWidth - fold, pageTop)
            lineTo(pageLeft + pageWidth, pageTop + fold)
            lineTo(pageLeft + pageWidth - fold, pageTop + fold)
            close()
        }
        drawPath(foldPath, color = ink.copy(alpha = 0.45f))
    }
}

/** Empty state shown when a filter matches nothing. */
@Composable
internal fun RecentRecordsFilteredEmpty(onReset: () -> Unit) {
    val colors = homeColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.meetingSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = null,
                tint = colors.mutedInk.copy(alpha = 0.6f),
                modifier = Modifier.size(26.dp)
            )
            Text(
                text = "没有符合筛选条件的记录",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = colors.ink
            )
            TextButton(onClick = onReset) {
                Text("重置筛选", color = BrandBlue, fontSize = 12.sp)
            }
        }
    }
}

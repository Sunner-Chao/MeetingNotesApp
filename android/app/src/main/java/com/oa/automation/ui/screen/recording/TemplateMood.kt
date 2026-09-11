package com.oa.automation.ui.screen.recording

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import com.oa.automation.R

/**
 * The eight meeting moods of the Light Enjoyment recording page. Each template
 * is expressed through one colour so the left bookmark rail, the drawer and the
 * doodle workflow panel stay visually consistent.
 */
internal enum class TemplateMoodFamily {
    DIRECTIVE,
    PROGRESS,
    CO_CREATE,
    NEGOTIATION,
    RETROSPECTIVE,
    STANDUP,
    FORUM,
    CUSTOM,
    GENERAL
}

internal data class TemplateMood(
    val family: TemplateMoodFamily,
    /** Short label shown on bookmarks; persisted template names may be longer. */
    val displayName: String,
    /** One-word mood shown under the name in the drawer. */
    val moodWord: String,
    /** Spine, border and icon colour. */
    val accent: Color,
    /** Title text colour; slightly deeper than the accent for legibility. */
    val ink: Color,
    val icon: ImageVector,
    /** Raster doodle cropped from the approved effect diagram, when available. */
    val illustrationRes: Int?
) {
    val soft: Color get() = accent.copy(alpha = 0.10f)
}

private val DirectiveMood = TemplateMood(
    family = TemplateMoodFamily.DIRECTIVE,
    displayName = "行政类",
    moodWord = "果断",
    accent = Color(0xFFCF2425),
    ink = Color(0xFFC72215),
    icon = Icons.Default.Flag,
    illustrationRes = R.drawable.template_workflow_directive
)

private val ProgressMood = TemplateMood(
    family = TemplateMoodFamily.PROGRESS,
    displayName = "项目管理类",
    moodWord = "稳定",
    accent = Color(0xFF61A553),
    ink = Color(0xFF4E9A45),
    icon = Icons.Default.TrendingUp,
    illustrationRes = R.drawable.template_workflow_progress
)

private val CoCreateMood = TemplateMood(
    family = TemplateMoodFamily.CO_CREATE,
    displayName = "共创类",
    moodWord = "发散",
    accent = Color(0xFFF3B61D),
    ink = Color(0xFFDD9F05),
    icon = Icons.Default.Lightbulb,
    illustrationRes = R.drawable.template_workflow_cocreate
)

private val NegotiationMood = TemplateMood(
    family = TemplateMoodFamily.NEGOTIATION,
    displayName = "洽谈类",
    moodWord = "博弈",
    accent = Color(0xFF7A6DDF),
    ink = Color(0xFF6958D3),
    icon = Icons.Default.Handshake,
    illustrationRes = R.drawable.template_workflow_negotiation
)

private val RetrospectiveMood = TemplateMood(
    family = TemplateMoodFamily.RETROSPECTIVE,
    displayName = "复盘类",
    moodWord = "聚焦",
    accent = Color(0xFFEB852F),
    ink = Color(0xFFE26B10),
    icon = Icons.Default.TrackChanges,
    illustrationRes = R.drawable.template_workflow_retrospective
)

private val StandupMood = TemplateMood(
    family = TemplateMoodFamily.STANDUP,
    displayName = "敏捷类",
    moodWord = "高效",
    accent = Color(0xFF27B0AD),
    ink = Color(0xFF0A9E90),
    icon = Icons.Default.Groups,
    illustrationRes = R.drawable.template_workflow_standup
)

private val ForumMood = TemplateMood(
    family = TemplateMoodFamily.FORUM,
    displayName = "论坛类",
    moodWord = "开放",
    accent = Color(0xFF3172D7),
    ink = Color(0xFF1B62D6),
    icon = Icons.Default.Forum,
    illustrationRes = R.drawable.template_workflow_forum
)

private val CustomMood = TemplateMood(
    family = TemplateMoodFamily.CUSTOM,
    displayName = "自定义",
    moodWord = "灵活",
    accent = Color(0xFF83878A),
    ink = Color(0xFF66696E),
    icon = Icons.Default.Settings,
    illustrationRes = null
)

private val GeneralMood = TemplateMood(
    family = TemplateMoodFamily.GENERAL,
    displayName = "通用类",
    moodWord = "均衡",
    accent = Color(0xFF5B7A99),
    ink = Color(0xFF47617D),
    icon = Icons.Default.Description,
    illustrationRes = null
)

/** Resolves the mood for a persisted or display template name, tolerating legacy aliases. */
internal fun templateMoodFor(templateName: String): TemplateMood {
    val normalized = templateName.trim()
    return when {
        normalized.contains("宣贯") || normalized.contains("行政") -> DirectiveMood
        normalized.contains("推演") || normalized.contains("进度") || normalized.contains("项目") -> ProgressMood
        normalized.contains("启迪") || normalized.contains("共创") || normalized.contains("头脑") -> CoCreateMood
        normalized.contains("博弈") || normalized.contains("洽谈") -> NegotiationMood
        normalized.contains("复盘") || normalized.contains("分析") -> RetrospectiveMood
        normalized.contains("敏捷") || normalized.contains("站会") -> StandupMood
        normalized.contains("论坛") || normalized.contains("共识") || normalized.contains("聚智") -> ForumMood
        normalized.contains("自定义") -> CustomMood
        normalized.contains("研学") || normalized.contains("考察") ->
            GeneralMood.copy(displayName = "研学考察类")
        normalized.contains("通用") -> GeneralMood
        else -> GeneralMood.copy(displayName = normalized.ifBlank { GeneralMood.displayName })
    }
}

/** User-facing category label; legacy template ids remain unchanged for storage. */
internal fun templateDisplayName(templateName: String): String =
    templateMoodFor(templateName).displayName

/** Keep category hues while guaranteeing at least 4.5:1 contrast with white. */
internal fun bookmarkSurfaceColor(accent: Color): Color {
    var surface = accent.copy(alpha = 1f)
    while ((1.05f / (surface.luminance() + 0.05f)) < 4.5f) {
        surface = lerp(surface, Color.Black, 0.04f)
    }
    return surface
}

package com.oa.automation.ui.screen.report

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual skin for the report reference layout. The Pro/Social edition keeps the
 * original deep-blue glassmorphism; Lite uses the slate-and-paper palette from
 * the realtime recording screen instead of the former blue-purple gradient.
 * Everything is driven off one palette so the many cards flip
 * cohesively through a single [LocalReportSkin] rather than per-widget branches.
 */
internal data class ReportSkin(
    val doodle: Boolean,
    /** Primary text/icon colour. */
    val ink: Color,
    /** Secondary / meta text colour. */
    val muted: Color,
    /** Hairline used for placeholders and thin separators. */
    val border: Color,
    /** Card gradient (glass); in doodle mode [cardFill] is used instead. */
    val glassTop: Color,
    val glassBottom: Color,
    val lavender: Color,
    val sky: Color,
    val pink: Color,
    val mint: Color,
    val statusBar: Color,
    val navBar: Color,
    /** true → dark icons on light system bars (paper); false → light icons. */
    val lightSystemIcons: Boolean,
    /** Doodle card paper fill. */
    val cardFill: Color,
    /** Doodle hand-drawn border ink. */
    val cardStroke: Color,
    /** Backdrop gradient stops. */
    val paperTop: Color,
    val paperBottom: Color,
    /** Sticky-note accents (yellow / pink / green). */
    val sticky1: Color,
    val sticky2: Color,
    val sticky3: Color,
    /** Warm accent for the "生成完成" badge / back button in doodle mode. */
    val accentWarm: Color
)

internal fun reportGlassSkin(): ReportSkin = ReportSkin(
    doodle = false,
    ink = Color(0xFFF7F8FF),
    muted = Color(0xFFD2E4F4),
    border = Color(0xFF8CC8FF).copy(alpha = 0.32f),
    glassTop = Color(0xFFB9DDF5).copy(alpha = 0.16f),
    glassBottom = Color(0xFF0F3554).copy(alpha = 0.20f),
    lavender = Color(0xFF8CC8FF),
    sky = Color(0xFF60CDFF),
    pink = Color(0xFF3A96DD),
    mint = Color(0xFF99D6FF),
    statusBar = Color(0xFF0F3554),
    navBar = Color(0xFF0A243A),
    lightSystemIcons = false,
    cardFill = Color(0xFF123B5D),
    cardStroke = Color(0xFF8CC8FF),
    paperTop = Color(0xFF0A243A),
    paperBottom = Color(0xFF0B1F33),
    sticky1 = Color(0xFFFFF0A6),
    sticky2 = Color(0xFFFFD9DD),
    sticky3 = Color(0xFFD7F2CF),
    accentWarm = Color(0xFF60CDFF)
)

internal fun reportLiteGlassSkin(): ReportSkin = reportGlassSkin().copy(
    border = Color.White.copy(alpha = 0.28f),
    glassTop = Color.White.copy(alpha = 0.16f),
    glassBottom = Color(0xFF252F39).copy(alpha = 0.42f),
    lavender = Color(0xFFB4C7D2),
    sky = Color(0xFFF2E5C9),
    pink = Color(0xFFD58B78),
    mint = Color(0xFFA6C6A8),
    statusBar = Color(0xFF181917),
    navBar = Color(0xFF252F39),
    paperTop = Color(0xFF303943),
    paperBottom = Color(0xFF252F39),
    accentWarm = Color(0xFFF2E5C9)
)

internal fun reportDoodleSkin(isDark: Boolean): ReportSkin = if (isDark) {
    ReportSkin(
        doodle = true,
        ink = Color(0xFFF3F2F1),
        muted = Color(0xFFB4B2AF),
        border = Color(0xFFD9D7D4).copy(alpha = 0.24f),
        glassTop = Color(0xFF201F1E),
        glassBottom = Color(0xFF201F1E),
        lavender = Color(0xFFB08BE0),
        sky = Color(0xFF60CDFF),
        pink = Color(0xFFFF7A82),
        mint = Color(0xFF8FD08A),
        statusBar = Color(0xFF1B1A19),
        navBar = Color(0xFF1B1A19),
        lightSystemIcons = false,
        cardFill = Color(0xFF262523),
        cardStroke = Color(0xFFD9D7D4),
        paperTop = Color(0xFF1B1A19),
        paperBottom = Color(0xFF201F1E),
        sticky1 = Color(0xFF5C5326),
        sticky2 = Color(0xFF5C3236),
        sticky3 = Color(0xFF31452C),
        accentWarm = Color(0xFFF3C64B)
    )
} else {
    ReportSkin(
        doodle = true,
        ink = Color(0xFF242424),
        muted = Color(0xFF6E6C69),
        border = Color(0xFF9AA0A6).copy(alpha = 0.55f),
        glassTop = Color(0xFFFFFFFF),
        glassBottom = Color(0xFFFFFFFF),
        lavender = Color(0xFF6A3E98),
        sky = Color(0xFF0078D4),
        pink = Color(0xFFE0525C),
        mint = Color(0xFF61A553),
        statusBar = Color(0xFFF4F6FA),
        navBar = Color(0xFFEFF2F7),
        lightSystemIcons = true,
        cardFill = Color(0xFFFFFFFF),
        cardStroke = Color(0xFF2B2A28),
        paperTop = Color(0xFFF6F7F5),
        paperBottom = Color(0xFFEFF2F7),
        sticky1 = Color(0xFFFFF0A6),
        sticky2 = Color(0xFFFFD9DD),
        sticky3 = Color(0xFFD7F2CF),
        accentWarm = Color(0xFFF3B61D)
    )
}

internal val LocalReportSkin = staticCompositionLocalOf { reportGlassSkin() }

// ---------------------------------------------------------------------------
// Doodle drawing helpers shared by the report cards
// ---------------------------------------------------------------------------

/**
 * Scatters a handful of tiny hand-drawn marks (stars, sparkles, rings, hearts)
 * across the paper backdrop so empty margins read like a sketchbook page. The
 * positions are fixed so nothing jitters between recompositions.
 */
internal fun DrawScope.drawPaperDoodads(skin: ReportSkin) {
    val w = size.width
    val h = size.height
    val star = skin.accentWarm.copy(alpha = if (skin.doodle) 0.55f else 0.0f)
    val ring = skin.sky.copy(alpha = 0.35f)
    val heart = skin.pink.copy(alpha = 0.40f)
    val spark = skin.mint.copy(alpha = 0.42f)
    if (!skin.doodle) return
    drawDoodleStar(Offset(w * 0.10f, h * 0.16f), 9.dp.toPx(), star)
    drawDoodleStar(Offset(w * 0.90f, h * 0.30f), 7.dp.toPx(), spark)
    drawDoodleStar(Offset(w * 0.14f, h * 0.62f), 6.dp.toPx(), ring)
    drawDoodleStar(Offset(w * 0.88f, h * 0.72f), 10.dp.toPx(), star)
    drawDoodleRing(Offset(w * 0.06f, h * 0.40f), 5.dp.toPx(), ring)
    drawDoodleRing(Offset(w * 0.94f, h * 0.52f), 4.dp.toPx(), heart)
    drawDoodleHeart(Offset(w * 0.92f, h * 0.90f), 8.dp.toPx(), heart)
    drawDoodleHeart(Offset(w * 0.08f, h * 0.86f), 6.dp.toPx(), spark)
}

/** A four-point sparkle star. */
internal fun DrawScope.drawDoodleStar(center: Offset, radius: Float, color: Color) {
    val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val path = Path()
    val inner = radius * 0.34f
    for (i in 0 until 8) {
        val angle = (i * PI / 4.0) - PI / 2.0
        val r = if (i % 2 == 0) radius else inner
        val x = center.x + (r * cos(angle)).toFloat()
        val y = center.y + (r * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color = color, style = stroke)
}

/** A small open ring. */
internal fun DrawScope.drawDoodleRing(center: Offset, radius: Float, color: Color) {
    drawCircle(
        color = color,
        radius = radius,
        center = center,
        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
    )
}

/** A tiny outlined heart. */
internal fun DrawScope.drawDoodleHeart(center: Offset, radius: Float, color: Color) {
    val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val r = radius
    val path = Path().apply {
        moveTo(center.x, center.y + r * 0.7f)
        cubicTo(
            center.x - r * 1.3f, center.y - r * 0.2f,
            center.x - r * 0.4f, center.y - r * 1.0f,
            center.x, center.y - r * 0.25f
        )
        cubicTo(
            center.x + r * 0.4f, center.y - r * 1.0f,
            center.x + r * 1.3f, center.y - r * 0.2f,
            center.x, center.y + r * 0.7f
        )
        close()
    }
    drawPath(path, color = color, style = stroke)
}

/** A hand-drawn wavy underline used to emphasise a line of text. */
internal fun DrawScope.drawWavyUnderline(width: Float, y: Float, color: Color, amplitude: Float) {
    val path = Path()
    path.moveTo(0f, y)
    val segments = 22
    for (i in 1..segments) {
        val x = width * i / segments
        val yy = y + sin(i * PI / 2.2).toFloat() * amplitude
        path.lineTo(x, yy)
    }
    drawPath(path, color = color, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
}

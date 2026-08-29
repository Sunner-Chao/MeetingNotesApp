package com.oa.automation.ui.screen.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal data class SettingsPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceStrong: Color,
    val text: Color,
    val mutedText: Color,
    val purple: Color,
    val blue: Color,
    val cyan: Color,
    val pink: Color,
    val error: Color,
    val outline: Color,
    /** Pale filled chip/pill background (value pills, hint bars, segment fills). */
    val pillFill: Color,
    /** Selected segment fill. */
    val selectedFill: Color,
    /** Success/ready value color. */
    val success: Color
)

internal val DarkSettingsPalette = SettingsPalette(
    isDark = true,
    background = Color(0xFF1B1A19),
    surface = Color(0xE8252423),
    surfaceStrong = Color(0xF2323130),
    text = Color(0xFFF3F2F1),
    mutedText = Color(0xFFC8C6C4),
    purple = Color(0xFF60CDFF),
    blue = Color(0xFF0078D4),
    cyan = Color(0xFF8CC8FF),
    pink = Color(0xFF3A96DD),
    error = Color(0xFFFF7B8A),
    outline = Color(0xFF605E5C),
    pillFill = Color(0xFF2E3A4A),
    selectedFill = Color(0xFF164566),
    success = Color(0xFF6CCB8F)
)

internal val LightSettingsPalette = SettingsPalette(
    isDark = false,
    background = Color(0xFFF5F8FC),
    surface = Color(0xFAFFFFFF),
    surfaceStrong = Color(0xFFFFFFFF),
    text = Color(0xFF1A1F26),
    mutedText = Color(0xFF6B7480),
    purple = Color(0xFF0078D4),
    blue = Color(0xFF0067B8),
    cyan = Color(0xFF2B88B9),
    pink = Color(0xFF4F8FB7),
    error = Color(0xFFC62828),
    outline = Color(0xFFDCE9F5),
    pillFill = Color(0xFFEFF5FB),
    selectedFill = Color(0xFFDCEBFA),
    success = Color(0xFF16794A)
)

internal val LocalSettingsPalette = staticCompositionLocalOf { DarkSettingsPalette }

internal val SettingsSurfaceStrong: Color @Composable get() = LocalSettingsPalette.current.surfaceStrong
internal val SettingsText: Color @Composable get() = LocalSettingsPalette.current.text
internal val SettingsMutedText: Color @Composable get() = LocalSettingsPalette.current.mutedText
internal val SettingsBlue: Color @Composable get() = LocalSettingsPalette.current.blue
internal val SettingsCyan: Color @Composable get() = LocalSettingsPalette.current.cyan
internal val SettingsError: Color @Composable get() = LocalSettingsPalette.current.error
internal val SettingsSuccess: Color @Composable get() = LocalSettingsPalette.current.success

internal val SettingsCardShape = RoundedCornerShape(26.dp)
internal val SettingsPillShape = RoundedCornerShape(14.dp)

internal fun settingsColorScheme(palette: SettingsPalette): ColorScheme = if (palette.isDark) {
    darkColorScheme(
        primary = palette.blue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF004578),
        onPrimaryContainer = Color(0xFFC7E9FF),
        secondary = palette.cyan,
        onSecondary = Color(0xFF001F23),
        secondaryContainer = Color(0xFF0F4160),
        onSecondaryContainer = Color(0xFFC7E9FF),
        tertiary = palette.pink,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFF334E68),
        onTertiaryContainer = Color(0xFFD7E9FA),
        error = palette.error,
        onError = Color(0xFF3B0710),
        errorContainer = Color(0xFF5A1C29),
        onErrorContainer = Color(0xFFFFD9DE),
        background = palette.background,
        onBackground = palette.text,
        surface = palette.surfaceStrong,
        onSurface = palette.text,
        surfaceVariant = Color(0xFF323130),
        onSurfaceVariant = palette.mutedText,
        outline = Color(0xFF8A8886),
        outlineVariant = palette.outline
    )
} else {
    lightColorScheme(
        primary = palette.blue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE5F1FB),
        onPrimaryContainer = Color(0xFF00395D),
        secondary = palette.cyan,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDDEBF7),
        onSecondaryContainer = Color(0xFF00395D),
        tertiary = palette.pink,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE1ECF5),
        onTertiaryContainer = Color(0xFF19344D),
        error = palette.error,
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = palette.background,
        onBackground = palette.text,
        surface = palette.surfaceStrong,
        onSurface = palette.text,
        surfaceVariant = Color(0xFFF0F0F0),
        onSurfaceVariant = palette.mutedText,
        outline = Color(0xFF8A8886),
        outlineVariant = palette.outline
    )
}

/** Flat white card with a pale 1px border — the base surface of the settings page. */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val palette = LocalSettingsPalette.current
    Surface(
        modifier = modifier.border(
            BorderStroke(
                1.dp,
                if (palette.isDark) Color.White.copy(alpha = 0.16f)
                else palette.outline
            ),
            SettingsCardShape
        ),
        shape = SettingsCardShape,
        color = palette.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content
    )
}

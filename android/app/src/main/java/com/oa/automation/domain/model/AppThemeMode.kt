package com.oa.automation.domain.model

/** User-selected visual mode. The system option remains the default. */
enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    fun usesDarkColors(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }
}

package com.oa.automation.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeModeTest {
    @Test
    fun systemModeFollowsTheDevice() {
        assertTrue(AppThemeMode.SYSTEM.usesDarkColors(true))
        assertFalse(AppThemeMode.SYSTEM.usesDarkColors(false))
    }

    @Test
    fun explicitModesDoNotDependOnTheDevice() {
        assertFalse(AppThemeMode.LIGHT.usesDarkColors(true))
        assertTrue(AppThemeMode.DARK.usesDarkColors(false))
    }
}

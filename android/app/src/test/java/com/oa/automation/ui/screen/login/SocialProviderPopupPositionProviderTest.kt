package com.oa.automation.ui.screen.login

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialProviderPopupPositionProviderTest {

    private val gap = 24
    private val provider = SocialProviderPopupPositionProvider(gap)
    private val window = IntSize(width = 1080, height = 2400)

    private fun position(anchor: IntRect, content: IntSize): IntOffset =
        provider.calculatePosition(
            anchorBounds = anchor,
            windowSize = window,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = content
        )

    @Test
    fun `opens below the anchor when there is room`() {
        val anchor = IntRect(left = 60, top = 1000, right = 1020, bottom = 1120)
        val result = position(anchor, IntSize(width = 960, height = 500))

        assertEquals(60, result.x)
        assertEquals(anchor.bottom + gap, result.y)
    }

    @Test
    fun `flips above the anchor when the bottom cannot fit the content`() {
        val anchor = IntRect(left = 60, top = 2000, right = 1020, bottom = 2120)
        val contentHeight = 500
        val result = position(anchor, IntSize(width = 960, height = contentHeight))

        assertEquals(anchor.top - gap - contentHeight, result.y)
        assertTrue(result.y >= 0)
    }

    @Test
    fun `clamps inside the window when neither side fits`() {
        val anchor = IntRect(left = 60, top = 1200, right = 1020, bottom = 1320)
        val result = position(anchor, IntSize(width = 960, height = 2300))

        assertEquals(window.height - 2300, result.y)
    }

    @Test
    fun `never lets the content overflow the window horizontally`() {
        val anchor = IntRect(left = 900, top = 500, right = 1060, bottom = 620)
        val result = position(anchor, IntSize(width = 960, height = 300))

        assertEquals(window.width - 960, result.x)
    }

    @Test
    fun `keeps a non negative offset for an oversized overlay`() {
        val anchor = IntRect(left = 0, top = 0, right = 1080, bottom = 120)
        val result = position(anchor, IntSize(width = 1200, height = 2600))

        assertEquals(0, result.x)
        assertEquals(0, result.y)
    }
}

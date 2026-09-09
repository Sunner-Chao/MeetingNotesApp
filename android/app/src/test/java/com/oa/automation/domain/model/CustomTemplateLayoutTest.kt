package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomTemplateLayoutTest {

    @Test
    fun `default layout enables every module in asset order`() {
        val layout = CustomTemplateLayout.DEFAULT

        assertEquals(CustomTemplateModule.entries.toList(), layout.order)
        assertEquals(CustomTemplateModule.entries.size, layout.activeModules.size)
        assertEquals(CustomTemplateModule.MEETING_INFO, layout.order.first())
    }

    @Test
    fun `moving a module reorders without dropping any module`() {
        val moved = CustomTemplateLayout.DEFAULT.moved(fromIndex = 0, toIndex = 2)

        assertEquals(CustomTemplateModule.MEETING_INFO, moved.order[2])
        assertEquals(CustomTemplateModule.CORE_SUMMARY, moved.order[0])
        assertEquals(CustomTemplateModule.entries.size, moved.order.size)
        assertEquals(CustomTemplateModule.entries.toSet(), moved.order.toSet())
    }

    @Test
    fun `moving beyond the ends clamps to the list bounds`() {
        val layout = CustomTemplateLayout.DEFAULT

        // Dragging the top row further up is a no-op.
        assertEquals(layout.order, layout.moved(fromIndex = 0, toIndex = -5).order)
        // Dragging it past the bottom lands it last.
        assertEquals(
            CustomTemplateModule.MEETING_INFO,
            layout.moved(fromIndex = 0, toIndex = 99).order.last()
        )
        // An out-of-range source is ignored entirely.
        assertEquals(layout.order, layout.moved(fromIndex = 42, toIndex = 1).order)
    }

    @Test
    fun `toggling a module removes it from the generated document`() {
        val layout = CustomTemplateLayout.DEFAULT.toggled(CustomTemplateModule.ACTIONS)

        assertFalse(layout.isEnabled(CustomTemplateModule.ACTIONS))
        assertFalse(layout.activeModules.contains(CustomTemplateModule.ACTIONS))
        assertFalse(layout.toPromptDocument().contains(CustomTemplateModule.ACTIONS.title))
        assertTrue(layout.toPromptDocument().contains(CustomTemplateModule.RISKS.title))
    }

    @Test
    fun `disabling the last remaining module is refused`() {
        var layout = CustomTemplateLayout.DEFAULT
        CustomTemplateModule.entries.forEach { layout = layout.toggled(it) }

        assertEquals(1, layout.activeModules.size)
    }

    @Test
    fun `document numbers sections in user order`() {
        val layout = CustomTemplateLayout.DEFAULT
            .moved(fromIndex = 5, toIndex = 0) // ACTIONS to the top
            .toggled(CustomTemplateModule.ATTACHMENTS)

        val document = layout.toPromptDocument()

        assertTrue(document.contains("## 1. ${CustomTemplateModule.ACTIONS.title}"))
        assertFalse(document.contains(CustomTemplateModule.ATTACHMENTS.title))
        // The action table must survive into the prompt.
        assertTrue(document.contains("| 事项 | 负责人 | 协同人 | 截止时间 | 验收方式 |"))
    }

    @Test
    fun `serialize round trips order and enablement`() {
        val original = CustomTemplateLayout.DEFAULT
            .moved(fromIndex = 7, toIndex = 0)
            .toggled(CustomTemplateModule.RISKS)
            .toggled(CustomTemplateModule.TOPICS)

        val restored = CustomTemplateLayout.deserialize(original.serialize())

        assertEquals(original.order, restored.order)
        assertEquals(original.enabled, restored.enabled)
    }

    @Test
    fun `blank or unknown payloads fall back to the default layout`() {
        assertEquals(CustomTemplateLayout.DEFAULT, CustomTemplateLayout.deserialize(null))
        assertEquals(CustomTemplateLayout.DEFAULT, CustomTemplateLayout.deserialize(""))
        assertEquals(CustomTemplateLayout.DEFAULT, CustomTemplateLayout.deserialize("NOPE:1|ALSO_NOPE:0"))
    }

    @Test
    fun `modules added by a later version are appended and enabled`() {
        // A payload written before ATTACHMENTS existed.
        val legacy = CustomTemplateModule.entries
            .filterNot { it == CustomTemplateModule.ATTACHMENTS }
            .joinToString("|") { "${it.name}:1" }

        val restored = CustomTemplateLayout.deserialize(legacy)

        assertEquals(CustomTemplateModule.ATTACHMENTS, restored.order.last())
        assertTrue(restored.isEnabled(CustomTemplateModule.ATTACHMENTS))
        assertEquals(CustomTemplateModule.entries.size, restored.order.size)
    }

    @Test
    fun `a malformed flag keeps the module enabled rather than hiding content`() {
        val restored = CustomTemplateLayout.deserialize("MEETING_INFO:x|CORE_SUMMARY")

        assertTrue(restored.isEnabled(CustomTemplateModule.MEETING_INFO))
        assertTrue(restored.isEnabled(CustomTemplateModule.CORE_SUMMARY))
    }
}

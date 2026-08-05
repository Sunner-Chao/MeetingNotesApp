package com.oa.automation.infrastructure.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportDocumentFormatterTest {
    @Test
    fun convertsMarkdownListsToChineseParenthesizedNumbers() {
        val formatted = ReportDocumentFormatter.normalizeLists(
            """
                ## 结论
                - 第一项
                - 第二项

                1. 第三项
                2) 第四项
                | --- | --- |
            """.trimIndent()
        )

        assertTrue(formatted.contains("（1）第一项"))
        assertTrue(formatted.contains("（2）第二项"))
        assertTrue(formatted.contains("（1）第三项"))
        assertTrue(formatted.contains("（2）第四项"))
        assertTrue(formatted.contains("| --- | --- |"))
        assertFalse(formatted.lines().any { it.startsWith("- ") })
        assertEquals("（3）事项", ReportDocumentFormatter.numbered("事项", 2))
    }
}

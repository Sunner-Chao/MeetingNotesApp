package com.oa.automation.ui.screen.community

import org.junit.Assert.assertEquals
import org.junit.Test

class CommunityRichTextTest {
    @Test
    fun parserRemovesMarkdownMarkersAndKeepsSemanticOrder() {
        val blocks = parseCommunityRichText(
            """# 总游记
                |## 核心观察
                |- 现场动线清晰
                |普通段落
            """.trimMargin()
        )

        assertEquals(
            listOf(
                CommunityRichTextBlock(CommunityRichTextKind.HEADING_ONE, "总游记"),
                CommunityRichTextBlock(CommunityRichTextKind.HEADING_TWO, "核心观察"),
                CommunityRichTextBlock(CommunityRichTextKind.BULLET, "现场动线清晰"),
                CommunityRichTextBlock(CommunityRichTextKind.PARAGRAPH, "普通段落")
            ),
            blocks
        )
    }

    @Test
    fun parserPreservesBlankLinesAsReadingSpace() {
        assertEquals(
            CommunityRichTextKind.SPACER,
            parseCommunityRichText("第一段\n\n第二段")[1].kind
        )
    }
}

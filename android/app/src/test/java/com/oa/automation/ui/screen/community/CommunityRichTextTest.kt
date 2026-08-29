package com.oa.automation.ui.screen.community

import com.oa.automation.domain.model.PublicCommunityPost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun articleBodyDropsLeadingMarkdownTitle() {
        val blocks = communityPostBodyBlocks("# 重复标题\n\n第一段自然正文\n\n## 沿途发现")

        assertFalse(blocks.any { it.kind == CommunityRichTextKind.HEADING_ONE })
        assertEquals("第一段自然正文", blocks.first().text)
    }

    @Test
    fun articleExcerptUsesNaturalParagraphWithoutMarkdown() {
        val post = PublicCommunityPost(
            id = "post-1",
            title = "一篇见闻",
            content = "# 一篇见闻\n\n## 第一站\n\n这是一段更适合在信息流中阅读的自然摘要，不再暴露标题和列表标记。\n\n- 观察一",
            publishedAt = 0L
        )

        val excerpt = communityPostExcerpt(post)
        assertTrue(excerpt.startsWith("这是一段更适合"))
        assertFalse(excerpt.contains("#"))
        assertFalse(excerpt.contains("第一站"))
    }
}

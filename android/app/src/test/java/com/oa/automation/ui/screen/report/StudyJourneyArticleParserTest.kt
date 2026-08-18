package com.oa.automation.ui.screen.report

import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.MeetingAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyJourneyArticleParserTest {
    @Test
    fun `parser builds a visual article and removes formal appendix`() {
        val article = parseStudyJourneyArticle(
            rawContent = """
                # 城市更新三日研学

                > 从一座沙盘开始，我们沿着真实街区继续追问城市如何更新。

                **路线**：规划展馆 → 河岸老街 → 数字实验室

                ## 第一站｜规划展馆 · 旧城不是一张白纸

                沙盘保留了烟囱、厂房和铁路轨迹，讲解从这些痕迹展开。

                [照片：图 1｜沙盘中的保留建筑与公共空间]

                ## 第二站｜河岸老街 · 新旧之间留一道缝

                新雨棚没有伪装成旧构件，材料边界清楚可见。

                [照片：图 2｜旧砖墙与新增钢结构雨棚]

                ## 旅程回望

                六个点位最终连成同一条线：真实、连接、反馈和持续。

                ## 实用小贴士

                进入施工现场前确认安全帽和反光背心。

                ## 话题标签

                #研学日记 #城市更新

                ## 事实与待确认附录

                | 待确认内容 | 当前线索 |
                |---|---|
                | 城市名称 | 未提供 |
            """.trimIndent(),
            fallbackTitle = "研学考察"
        )

        assertEquals("城市更新三日研学", article.title)
        assertEquals(listOf("规划展馆", "河岸老街", "数字实验室"), article.routeStops)
        assertEquals(2, article.sections.size)
        assertEquals("规划展馆", article.sections.first().title)
        assertEquals("旧城不是一张白纸", article.sections.first().subtitle)
        assertEquals(1, article.sections.first().blocks.single { it.type == StudyJourneyBlockType.PHOTO }.photoNumber)
        assertEquals(listOf("#研学日记", "#城市更新"), article.tags)
        assertTrue(article.tips.single().contains("安全帽"))
        assertFalse(article.searchableText().contains("待确认"))
        assertFalse(article.searchableText().contains("当前线索"))
    }

    @Test
    fun `media resolution prefers explicit anchors then stage ownership`() {
        val article = parseStudyJourneyArticle(
            rawContent = """
                # 两站研学
                ## 第一站｜展馆
                入口讲解。
                [照片：图 2｜入口]
                ## 第二站｜园区
                园区交流。
            """.trimIndent(),
            fallbackTitle = "研学"
        )
        val attachments = listOf(
            attachment("photo-1", "stage-2", 10L),
            attachment("photo-2", "stage-1", 20L),
            attachment("photo-3", "stage-2", 30L)
        )
        val stages = listOf(
            JourneyStage(id = "stage-1", journeyId = "journey", sequenceNumber = 1, title = "展馆"),
            JourneyStage(id = "stage-2", journeyId = "journey", sequenceNumber = 2, title = "园区")
        )

        val media = resolveStudyJourneySectionMedia(article, attachments, stages)

        assertEquals(listOf("photo-2"), media[0].attachments.map { it.id })
        assertEquals(listOf("photo-1", "photo-3"), media[1].attachments.map { it.id })
    }

    @Test
    fun `route atlas wins for a multi stop journey while photo diary wins dense galleries`() {
        val article = StudyJourneyArticle(
            title = "三日研学路线",
            routeStops = listOf("A", "B", "C", "D"),
            sections = (1..4).map { StudyJourneySection(it, "第 $it 站") }
        )
        val catalog = StudyJourneyStyleCatalog(
            defaultStyleId = "route-atlas",
            styles = listOf(
                StudyJourneyVisualStyle(
                    id = "route-atlas",
                    displayName = "路线图鉴",
                    minimumSections = 3,
                    priority = 8,
                    scoring = StudyJourneyStyleScoring(sectionCountWeight = 7, routeStopWeight = 5)
                ),
                StudyJourneyVisualStyle(
                    id = "photo-diary",
                    displayName = "影像手记",
                    minimumPhotos = 5,
                    priority = 6,
                    photoCadence = "dense",
                    scoring = StudyJourneyStyleScoring(photoCountWeight = 3, photoSurplusWeight = 7)
                )
            )
        )

        assertEquals("route-atlas", selectStudyJourneyStyle(catalog, article, 4).id)
        assertEquals("photo-diary", selectStudyJourneyStyle(catalog, article, 10).id)
    }

    private fun attachment(id: String, stageId: String, createdAt: Long) = MeetingAttachment(
        id = id,
        meetingId = "meeting",
        journeyStageId = stageId,
        displayName = "$id.jpg",
        localPath = "/tmp/$id.jpg",
        mimeType = "image/jpeg",
        createdAt = createdAt
    )
}

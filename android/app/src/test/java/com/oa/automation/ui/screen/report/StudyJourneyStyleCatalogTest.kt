package com.oa.automation.ui.screen.report

import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyJourneyStyleCatalogTest {
    @Test
    fun `style asset exposes reusable cover page and photo materials`() {
        val catalog = Gson().fromJson(styleAsset().readText(), StudyJourneyStyleCatalog::class.java)

        assertEquals(2, catalog.version)
        assertTrue(catalog.styles.size >= 14)
        assertTrue(catalog.materials.coverLayouts.size >= 9)
        assertTrue(catalog.materials.pagePatterns.size >= 16)
        assertTrue(catalog.materials.photoTreatments.size >= 4)

        val coverIds = catalog.materials.coverLayouts.map { it.id }.toSet()
        val pageIds = catalog.materials.pagePatterns.map { it.id }.toSet()
        val treatmentIds = catalog.materials.photoTreatments.map { it.id }.toSet()
        catalog.styles.forEach { style ->
            assertTrue("Unknown cover ${style.coverLayout}", style.coverLayout in coverIds)
            assertTrue("Unknown photo treatment ${style.photoTreatment}", style.photoTreatment in treatmentIds)
            assertTrue(style.pagePatterns.isNotEmpty())
            assertTrue(style.pagePatterns.all { it in pageIds })
            assertTrue(style.carouselExtras.all { it == "tips" })
        }
    }

    @Test
    fun `page patterns fall back when a stage has too few photos`() {
        val style = StudyJourneyVisualStyle(pagePatterns = listOf("photo-strip", "two-photo", "quote-photo", "exhibit-grid"))

        assertEquals("full-photo", resolveStudyJourneyPagePattern(style, stagePageIndex = 1, photoCount = 1))
        assertEquals("two-photo", resolveStudyJourneyPagePattern(style, stagePageIndex = 1, photoCount = 2))
        assertEquals("photo-strip", resolveStudyJourneyPagePattern(style, stagePageIndex = 1, photoCount = 3))
        assertEquals("full-photo", resolveStudyJourneyPagePattern(style, stagePageIndex = 2, photoCount = 1))
        assertEquals("quote-photo", resolveStudyJourneyPagePattern(style, stagePageIndex = 3, photoCount = 1))
        assertEquals("knowledge-note", resolveStudyJourneyPagePattern(style, stagePageIndex = 4, photoCount = 0))
        assertEquals("exhibit-grid", resolveStudyJourneyPagePattern(style, stagePageIndex = 4, photoCount = 1))

        val researchStyle = StudyJourneyVisualStyle(pagePatterns = listOf("field-observation", "detail-lens"))
        assertEquals("knowledge-note", resolveStudyJourneyPagePattern(researchStyle, stagePageIndex = 1, photoCount = 0))
        assertEquals("field-observation", resolveStudyJourneyPagePattern(researchStyle, stagePageIndex = 1, photoCount = 1))
        assertEquals("full-photo", resolveStudyJourneyPagePattern(researchStyle, stagePageIndex = 2, photoCount = 1))
        assertEquals("two-photo", resolveStudyJourneyPagePattern(researchStyle, stagePageIndex = 2, photoCount = 2))
        assertEquals("detail-lens", resolveStudyJourneyPagePattern(researchStyle, stagePageIndex = 2, photoCount = 3))
    }

    @Test
    fun `field observation requires multiple evidence-backed labels`() {
        val observation = parseStudyJourneyFieldObservation(
            listOf(
                StudyJourneyContentBlock(StudyJourneyBlockType.SUBHEADING, "现场环境"),
                StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "石缝与背阴区域。"),
                StudyJourneyContentBlock(StudyJourneyBlockType.SUBHEADING, "可见特征"),
                StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "茎贴近地面延伸。"),
                StudyJourneyContentBlock(StudyJourneyBlockType.SUBHEADING, "继续观察"),
                StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "比较向阳处与背阴处。")
            )
        )

        assertEquals(listOf("现场环境", "可见特征", "继续观察"), observation?.entries?.map { it.label })
        assertEquals(null, parseStudyJourneyFieldObservation(listOf(
            StudyJourneyContentBlock(StudyJourneyBlockType.SUBHEADING, "现场环境"),
            StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "只有一个区块。")
        )))
    }

    @Test
    fun `detail lens keeps overview and related detail captions`() {
        val lens = parseStudyJourneyDetailLens(
            listOf(
                StudyJourneyContentBlock(StudyJourneyBlockType.SUBHEADING, "整体观察"),
                StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "完整立面建立空间关系。"),
                StudyJourneyContentBlock(StudyJourneyBlockType.SUBHEADING, "细节｜材料交接"),
                StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "转角处可见新旧材料。"),
                StudyJourneyContentBlock(StudyJourneyBlockType.SUBHEADING, "构造"),
                StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "入口构件保持原有尺度。")
            )
        )

        assertEquals("完整立面建立空间关系。", lens?.overview)
        assertEquals(2, lens?.details?.size)
        assertEquals(null, parseStudyJourneyDetailLens(listOf(
            StudyJourneyContentBlock(StudyJourneyBlockType.SUBHEADING, "整体观察"),
            StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "缺少局部说明。")
        )))
    }

    @Test
    fun `specific heritage theme outranks generic route layout for the new fixture`() {
        val catalog = Gson().fromJson(styleAsset().readText(), StudyJourneyStyleCatalog::class.java)
        val article = StudyJourneyArticle(
            title = "两日非遗工坊与乡村共创研学",
            routeStops = listOf("造纸工坊", "竹编馆", "茶园", "古村", "乡创工坊", "分享会"),
            sections = listOf(
                StudyJourneySection(1, "古法造纸工坊", blocks = listOf(StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "非遗传承人与手工纸工坊"))),
                StudyJourneySection(2, "竹编非遗馆", blocks = listOf(StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "竹编手艺与匠人"))),
                StudyJourneySection(3, "生态茶园"),
                StudyJourneySection(4, "清溪古村"),
                StudyJourneySection(5, "乡创工坊"),
                StudyJourneySection(6, "返程分享")
            )
        )

        assertEquals("heritage-visit", selectStudyJourneyStyle(catalog, article, attachmentCount = 7).id)
    }

    @Test
    fun `guide and technology content select their dedicated visual systems`() {
        val catalog = Gson().fromJson(styleAsset().readText(), StudyJourneyStyleCatalog::class.java)
        val familyGuide = StudyJourneyArticle(
            title = "亲子校园研学不踩坑攻略",
            routeStops = listOf("主校区", "航天馆"),
            sections = listOf(
                StudyJourneySection(1, "预约须知", blocks = listOf(StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "带娃参观需要预约并留意开放时间"))),
                StudyJourneySection(2, "两条路线怎么选")
            )
        )
        val technologyRoute = StudyJourneyArticle(
            title = "三条 AI 科技研学路线",
            routeStops = listOf("人工智能小镇", "实验室", "机器人研究院"),
            sections = listOf(
                StudyJourneySection(1, "数字城市"),
                StudyJourneySection(2, "机器人实验室")
            )
        )

        assertEquals("family-study-guide", selectStudyJourneyStyle(catalog, familyGuide, attachmentCount = 3).id)
        assertEquals("science-route", selectStudyJourneyStyle(catalog, technologyRoute, attachmentCount = 3).id)
    }

    @Test
    fun `museum missions and industrial processes select their dedicated visual systems`() {
        val catalog = Gson().fromJson(styleAsset().readText(), StudyJourneyStyleCatalog::class.java)
        val museumMission = StudyJourneyArticle(
            title = "博物馆小小观察家任务手册",
            sections = listOf(
                StudyJourneySection(1, "寻找重点展品", blocks = listOf(StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "完成观察任务与研学记录卡"))),
                StudyJourneySection(2, "文物知识卡")
            )
        )
        val factoryProcess = StudyJourneyArticle(
            title = "汽车工厂智造探访",
            sections = listOf(
                StudyJourneySection(1, "参观流程", blocks = listOf(StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "签到后进入压铸车间，现场禁止拍摄"))),
                StudyJourneySection(2, "装配产线")
            )
        )

        assertEquals("museum-explorer", selectStudyJourneyStyle(catalog, museumMission, attachmentCount = 2).id)
        assertEquals("industrial-process", selectStudyJourneyStyle(catalog, factoryProcess, attachmentCount = 1).id)
    }

    @Test
    fun `question thread preserves question answer observation and follow up`() {
        val thread = parseStudyJourneyQuestionThread(
            listOf(
                StudyJourneyContentBlock(StudyJourneyBlockType.SUBHEADING, "问题｜检测如何发现隐蔽异常？"),
                StudyJourneyContentBlock(StudyJourneyBlockType.SUBHEADING, "现场回答"),
                StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "检测结果会与工序记录共同判断。"),
                StudyJourneyContentBlock(StudyJourneyBlockType.SUBHEADING, "观察印证"),
                StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "屏幕持续显示当前检测状态。"),
                StudyJourneyContentBlock(StudyJourneyBlockType.SUBHEADING, "继续探索"),
                StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "异常件如何回溯仍值得继续了解。")
            )
        )

        assertEquals("检测如何发现隐蔽异常？", thread?.question)
        assertEquals("检测结果会与工序记录共同判断。", thread?.answer)
        assertEquals("屏幕持续显示当前检测状态。", thread?.observation)
        assertEquals("异常件如何回溯仍值得继续了解。", thread?.followUp)
        assertEquals(null, parseStudyJourneyQuestionThread(listOf(StudyJourneyContentBlock(StudyJourneyBlockType.PARAGRAPH, "普通讲解段落"))))
    }

    @Test
    fun `pager indicator keeps seven visible positions around current page`() {
        assertEquals((0 until 7).toList(), visibleJourneyPagerIndices(currentPage = 0, pageCount = 12).toList())
        assertEquals((3 until 10).toList(), visibleJourneyPagerIndices(currentPage = 6, pageCount = 12).toList())
        assertEquals((5 until 12).toList(), visibleJourneyPagerIndices(currentPage = 11, pageCount = 12).toList())
        assertEquals((0 until 7).toList(), visibleJourneyPagerIndices(currentPage = 3, pageCount = 7).toList())
    }

    @Test
    fun `route node positions support a seven image cover without overlap in sequence`() {
        val positions = studyJourneyRouteNodePositions(7)

        assertEquals(7, positions.size)
        assertTrue(positions.zipWithNext().all { (left, right) -> right.second > left.second })
        assertTrue(positions.all { (x, y) -> x in 0f..1f && y in 0f..1f })
    }

    private fun styleAsset(): File = listOf(
        File("src/main/assets/study_journey_styles.json"),
        File("app/src/main/assets/study_journey_styles.json"),
        File("android/app/src/main/assets/study_journey_styles.json")
    ).firstOrNull(File::isFile) ?: error("study_journey_styles.json is missing")
}

package com.oa.automation.domain.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTemplateAssetContractTest {
    @Test
    fun shipsCanonicalMeetingAssetsAndKeepsProfessionalLayouts() {
        val assets = assetsDirectory()
        val markdownFiles = assets.listFiles { file -> file.isFile && file.extension == "md" }
            ?.associateBy { it.name }
            .orEmpty()

        assertTrue(markdownFiles.keys.containsAll(setOf(
            "通用会议.md",
            "孔爵团队版表格会议纪要.md",
            "论坛会议.md",
            "自定义会议.md",
            "参观考察游记.md"
        )))
        val generalTemplate = markdownFiles.getValue("通用会议.md").readText()
        assertTrue(generalTemplate.contains("正文（按内容自适应）"))
        assertTrue(generalTemplate.contains("少数意见"))
        assertTrue(generalTemplate.contains("行动项"))
        assertTrue(generalTemplate.contains("智能适配规则"))
        val projectTemplate = markdownFiles.getValue("孔爵团队版表格会议纪要.md").readText()
        assertTrue(projectTemplate.contains("ActionID"))
        assertTrue(projectTemplate.contains("后续研究与储备事项"))
        assertFalse(projectTemplate.contains("后续沉淀事项"))
        assertFalse(projectTemplate.contains("backlog 候选（可沉淀）"))
        val forumTemplate = markdownFiles.getValue("论坛会议.md").readText()
        assertTrue(forumTemplate.contains("主持人"))
        assertTrue(forumTemplate.contains("议程与时间线"))
        assertTrue(forumTemplate.contains("主题演讲"))
        assertTrue(forumTemplate.contains("圆桌讨论"))
        assertTrue(forumTemplate.contains("现场问答"))
        val customTemplate = markdownFiles.getValue("自定义会议.md").readText()
        assertTrue(customTemplate.contains("按需组合模块"))
        // The asset is now only the un-arranged default; the shipped editor
        // supplies the real order, so the copy must not promise a future one.
        assertFalse(customTemplate.contains("拖拽编排稍后开放"))
        assertTrue(customTemplate.contains("拖拽编排顺序与启停"))
        assertTrue(customTemplate.contains("保留转写内容能够支撑的模块"))
        // Every module the editor can enable must exist in the fallback asset.
        CustomTemplateModule.entries.forEach { module ->
            assertTrue(
                "自定义会议.md is missing module ${module.title}",
                customTemplate.contains(module.title)
            )
        }
        val visitTemplate = markdownFiles.getValue("参观考察游记.md").readText()
        assertTrue(visitTemplate.contains("第一站｜"))
        assertTrue(visitTemplate.contains("体验画面 + 讲解精要 + 互动发现"))
        assertTrue(visitTemplate.contains("旅程回望"))
        assertTrue(visitTemplate.contains("封面标题建议"))
        assertTrue(visitTemplate.contains("话题标签"))
        assertFalse(visitTemplate.contains("## 事实与待确认附录"))
        assertTrue(visitTemplate.contains("禁止出现“会议主题”“关键要点”“事实与待确认”"))
        assertTrue(visitTemplate.contains("每 1-3 个短段落至少安排一张图"))
        assertTrue(visitTemplate.contains("轮播内容页"))
        assertTrue(visitTemplate.contains("田野观察板"))
        assertTrue(visitTemplate.contains("整体与细节页"))
        assertTrue(visitTemplate.contains("每一站只保留一个"))
        assertTrue(visitTemplate.contains("现场事实"))
        assertTrue(visitTemplate.contains("多个阶段组成"))
        assertTrue(visitTemplate.contains("实用小贴士"))
        assertTrue(visitTemplate.contains("图文社区与旅行攻略"))
        assertTrue(visitTemplate.contains("[照片：图 N｜事实型图注]"))
        assertFalse(visitTemplate.contains("## 0. 旅程与篇章状态"))
        assertFalse(visitTemplate.contains("## 3. 行程总览"))
        assertFalse(visitTemplate.contains("## 5. 图片叙事索引"))
        assertFalse(visitTemplate.contains("姓名/单位/职务"))
        assertFalse(visitTemplate.contains("ActionID"))
        assertFalse(visitTemplate.contains("预期成果/验收标准"))
        assertTrue(File(assets, "study_journey_styles.json").isFile)
        val constructionTemplate = markdownFiles.getValue("工程建筑施工设计日志.md").readText()
        assertTrue(constructionTemplate.contains("上午天气"))
        assertTrue(constructionTemplate.contains("隐蔽工程验收"))
        assertTrue(constructionTemplate.contains("设计阶段"))
        val supervisionTemplate = markdownFiles.getValue("监理会例会日志.md").readText()
        assertTrue(supervisionTemplate.contains("上次问题整改闭合"))
        assertTrue(supervisionTemplate.contains("见证取样"))
        assertTrue(supervisionTemplate.contains("责任单位"))
        assertTrue(supervisionTemplate.contains("完成时限"))
    }

    private fun assetsDirectory(): File {
        return listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("android/app/src/main/assets")
        ).firstOrNull { it.isDirectory }
            ?: error("Android assets directory is missing")
    }
}

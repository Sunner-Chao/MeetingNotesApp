package com.oa.automation.domain.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTemplateAssetContractTest {
    @Test
    fun shipsThreeCanonicalMeetingCategoriesAndKeepsProfessionalLayouts() {
        val assets = assetsDirectory()
        val markdownFiles = assets.listFiles { file -> file.isFile && file.extension == "md" }
            ?.associateBy { it.name }
            .orEmpty()

        assertTrue(markdownFiles.keys.containsAll(setOf(
            "通用会议.md",
            "孔爵团队版表格会议纪要.md",
            "参观考察游记.md"
        )))
        val generalTemplate = markdownFiles.getValue("通用会议.md").readText()
        assertTrue(generalTemplate.contains("议题与讨论"))
        assertTrue(generalTemplate.contains("分歧或少数观点"))
        assertTrue(generalTemplate.contains("行动项"))
        assertTrue(markdownFiles.getValue("孔爵团队版表格会议纪要.md").readText().contains("ActionID"))
        val visitTemplate = markdownFiles.getValue("参观考察游记.md").readText()
        assertTrue(visitTemplate.contains("旅程与篇章状态"))
        assertTrue(visitTemplate.contains("暂存本段"))
        assertTrue(visitTemplate.contains("追加新阶段"))
        assertTrue(visitTemplate.contains("行程总览"))
        assertTrue(visitTemplate.contains("现场角色与讲解"))
        assertTrue(visitTemplate.contains("图片叙事索引"))
        assertTrue(visitTemplate.contains("实用信息"))
        assertTrue(visitTemplate.contains("小红书/携程游记"))
        assertTrue(visitTemplate.contains("数量不设应用层张数上限"))
        assertTrue(visitTemplate.contains("后续转化与行动项"))
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

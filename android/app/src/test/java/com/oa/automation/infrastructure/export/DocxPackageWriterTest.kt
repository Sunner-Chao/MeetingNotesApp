package com.oa.automation.infrastructure.export

import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.ReportTemplateConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class DocxPackageWriterTest {
    @Test
    fun writesStructuredDocxWithTableAndImageRelationship() {
        val output = Files.createTempFile("meeting-report-", ".docx").toFile()
        try {
            val report = Report(
                meetingId = "meeting-1",
                rawContent = """
                    # 项目专题会会议纪要

                    ## 1. 核心结论
                    - 已确认第一项工作

                    ## 2. 行动项
                    | 事项 | 负责人 | 状态 |
                    | --- | --- | --- |
                    | 完成方案 | 小孙 | 待执行 |
                """.trimIndent(),
                templateName = "孔爵团队版表格会议纪要",
                generatedAt = 1_700_000_000_000
            )
            val imageBytes = javaClass.classLoader
                ?.getResourceAsStream("meeting-photo-fixture.jpg")
                ?.use { it.readBytes() }
                ?: error("Meeting photo fixture is missing")
            val image = DocxImage(
                bytes = imageBytes,
                widthPx = 1600,
                heightPx = 900,
                caption = "白板&现场.jpg"
            )

            DocxPackageWriter(report, listOf(image), kongjueTemplateBytes()).write(output)

            (System.getenv("MEETINGNOTES_DOCX_QA_OUTPUT")
                ?: System.getProperty("meetingnotes.docx.qa.output"))
                ?.takeIf { it.isNotBlank() }?.let { path ->
                val qaOutput = java.io.File(path)
                qaOutput.parentFile?.mkdirs()
                output.copyTo(qaOutput, overwrite = true)
            }

            ZipFile(output).use { zip ->
                assertNotNull(zip.getEntry("[Content_Types].xml"))
                assertNotNull(zip.getEntry("word/document.xml"))
                assertNotNull(zip.getEntry("word/styles.xml"))
                assertNotNull(zip.getEntry("word/theme/theme1.xml"))
                assertNotNull(zip.getEntry("word/media/image1.jpg"))

                val documentXml = zip.getInputStream(zip.getEntry("word/document.xml"))
                    .bufferedReader().use { it.readText() }
                val relationships = zip.getInputStream(zip.getEntry("word/_rels/document.xml.rels"))
                    .bufferedReader().use { it.readText() }

                assertTrue(documentXml.contains("项目专题会会议纪要"))
                assertEquals(5, Regex("<w:tbl>").findAll(documentXml).count())
                assertTrue(documentXml.contains("已达成共识"))
                assertTrue(documentXml.contains("未解决问题"))
                assertTrue(documentXml.contains("BacklogID"))
                assertTrue(documentXml.contains("风险编号"))
                assertTrue(documentXml.indexOf("ACT-001") < documentXml.indexOf("完成方案"))
                assertTrue(documentXml.indexOf("完成方案") < documentXml.indexOf("小孙"))
                assertTrue(documentXml.indexOf("小孙") < documentXml.indexOf("待执行"))
                assertTrue(documentXml.contains("会议影像资料"))
                assertTrue(documentXml.contains("白板&amp;现场.jpg"))
                assertTrue(documentXml.contains("rIdImage1"))
                assertTrue(relationships.contains("media/image1.jpg"))
            }
            assertEquals("孔爵团队版表格会议纪要", ReportTemplateConfig().selectedName)
        } finally {
            output.delete()
        }
    }

    private fun kongjueTemplateBytes(): ByteArray {
        val relativePath = "src/main/assets/kongjue-team-table-v1.docx"
        val candidates = listOf(
            java.io.File(relativePath),
            java.io.File("app/$relativePath"),
            java.io.File("android/app/$relativePath")
        )
        return candidates.firstOrNull { it.isFile }?.readBytes()
            ?: error("Kongjue DOCX template asset is missing")
    }

}

package com.oa.automation.infrastructure.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.Report
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DocxExportSmokeTest {
    @Test
    fun exportsReadablePackageWithMeetingImage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val imageFile = File(context.cacheDir, "docx-smoke-photo.jpg")
        val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(235, 241, 246))
            drawText(
                "MEETING PHOTO",
                120f,
                290f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(23, 54, 93)
                    textSize = 72f
                }
            )
        }
        imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()

        val report = Report(
            meetingId = "docx-smoke",
            rawContent = """
                # 项目推进专题会会议纪要

                ## 1. 核心结论
                - 已确认下一阶段工作目标。

                ## 2. 行动项跟踪表
                | 事项 | 负责人 | 状态 |
                | --- | --- | --- |
                | 完成实施方案 | 项目组 | 待执行 |
            """.trimIndent(),
            templateName = "团队版会议纪要"
        )
        val attachment = MeetingAttachment(
            id = "photo-1",
            meetingId = report.meetingId,
            displayName = "会议现场.jpg",
            localPath = imageFile.absolutePath,
            mimeType = "image/jpeg",
            createdAt = System.currentTimeMillis()
        )

        val output = DocxReportExporter.export(context, report, listOf(attachment))

        assertTrue(output.isFile)
        assertTrue(output.length() > 5_000)
        ZipFile(output).use { zip ->
            assertNotNull(zip.getEntry("word/document.xml"))
            assertNotNull(zip.getEntry("word/media/image1.jpg"))
            assertNotNull(zip.getEntry("word/_rels/document.xml.rels"))
        }
    }
}

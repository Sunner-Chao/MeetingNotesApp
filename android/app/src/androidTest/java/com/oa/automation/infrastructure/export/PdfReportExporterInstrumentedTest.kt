package com.oa.automation.infrastructure.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.Report
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfReportExporterInstrumentedTest {
    @Test
    fun exportsMeetingImageAndDescriptiveLocalTimeFileName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "pdf-export-test.png")
        val sourceBitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            sourceBitmap.eraseColor(Color.rgb(20, 190, 80))
            FileOutputStream(source).use { output ->
                assertTrue(sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            sourceBitmap.recycle()
        }
        val report = Report(
            meetingId = "pdf-export-test",
            templateName = "项目管理",
            rawContent = "# 周例会\n\n## 会议表格\n\n" +
                "| 事项 | 负责人 | 状态 |\n" +
                "| --- | --- | --- |\n" +
                "| PDF 图片 | 小王 | 已完成 |\n\n" +
                "完成 PDF 图片导出验证。"
        )
        val attachment = MeetingAttachment(
            id = "image-1",
            meetingId = report.meetingId,
            displayName = "现场照片.png",
            localPath = source.absolutePath,
            mimeType = "image/png",
            createdAt = System.currentTimeMillis()
        )

        val pdf = ReportExporter.exportToPdf(context, report, listOf(attachment), "备用标题")
        assertTrue(pdf.name.matches(Regex("项目管理-周例会-\\d{8}-\\d{6}\\.pdf")))

        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertTrue(renderer.pageCount >= 2)
                renderer.openPage(0).use { page ->
                    val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        rendered.eraseColor(Color.WHITE)
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        var headerPixels = 0
                        for (y in 0 until rendered.height step 4) {
                            for (x in 0 until rendered.width step 4) {
                                val pixel = rendered.getPixel(x, y)
                                if (Color.blue(pixel) > 150 && Color.red(pixel) < 80) headerPixels++
                            }
                        }
                        assertTrue("PDF 表格应包含规范的表头底色", headerPixels > 200)
                    } finally {
                        rendered.recycle()
                    }
                }
                renderer.openPage(renderer.pageCount - 1).use { page ->
                    val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        rendered.eraseColor(Color.WHITE)
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        var greenPixels = 0
                        for (y in 0 until rendered.height step 4) {
                            for (x in 0 until rendered.width step 4) {
                                val pixel = rendered.getPixel(x, y)
                                if (Color.green(pixel) > 120 &&
                                    Color.green(pixel) > Color.red(pixel) * 2 &&
                                    Color.green(pixel) > Color.blue(pixel) * 2
                                ) {
                                    greenPixels++
                                }
                            }
                        }
                        assertTrue("PDF 最后一页应包含导出的绿色会议图片", greenPixels > 100)
                    } finally {
                        rendered.recycle()
                    }
                }
            }
        }

        source.delete()
        pdf.delete()
    }
}

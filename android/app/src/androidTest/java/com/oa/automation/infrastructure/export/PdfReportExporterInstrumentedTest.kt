package com.oa.automation.infrastructure.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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

    @Test
    fun exportsStudyJournalWithInlineImagesAndEditorialSurfaces() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repeat(8) { index ->
            File(context.filesDir, "study-tour-editorial-page-${index + 1}.png").delete()
        }
        val imageFiles = listOf(
            createQaImage(context.cacheDir, "study-stage-1.jpg", Color.rgb(60, 130, 210), "STAGE 01"),
            createQaImage(context.cacheDir, "study-stage-2.jpg", Color.rgb(52, 168, 130), "STAGE 02"),
            createQaImage(context.cacheDir, "study-stage-3.jpg", Color.rgb(230, 150, 55), "STAGE 03")
        )
        val report = Report(
            meetingId = "study-editorial-test",
            templateName = "研学考察",
            rawContent = """
                # 城市更新研学｜从展厅走到街区现场

                > 从一张街区模型出发，讲解逐渐落到材料、空间与日常使用。三段路线彼此衔接，也让抽象的更新策略有了可以观察的现场细节。

                **路线**：更新展厅 → 街区样板段 → 社区交流空间

                **同行与讲解**：研学团队；展厅讲解员；社区接待人员

                ## 第一站｜更新展厅 · 先从模型读懂街区

                入口处的模型把道路、公共空间和保留建筑放在同一张底图上。团队围着模型确认参观动线，讲解员随后介绍不同片区的更新顺序。

                与其把更新理解为一次整体翻新，这一站更清楚地呈现了分区推进的逻辑：先解决连通，再逐步补充公共服务。

                [照片：图 1｜讲解员在街区模型前介绍更新范围]

                ## 第二站｜街区样板段 · 细节开始落到脚下

                走到样板段后，大家观察了铺装、排水和沿街界面的衔接。讲解内容不再停留在方案图，而是对应到可以触摸和比较的材料节点。

                同行者追问雨天积水问题，现场人员结合排水口位置作了说明。这段交流让“好看”之外的使用条件也被记录下来。

                [照片：图 2｜样板段铺装与排水节点的现场对照]

                ## 第三站｜社区交流空间 · 更新最终回到人的使用

                最后一站来到社区交流空间。墙面展示了居民活动照片，接待人员介绍空间开放后的使用方式；团队把前两站的空间观察和这里的日常活动联系起来。

                [照片：图 3｜社区交流空间内的活动展示墙]

                ## 旅程回望

                三站串起来之后，最鲜明的感受不是某一个单独亮点，而是规划、施工细节和真实使用之间需要不断互相校验。模型提供全局视角，样板段回应落地问题，社区空间则补上了人的尺度。

                ## 实用小贴士

                现场记录显示，展厅与样板段连续参观更容易理解方案和实物之间的关系；具体开放时间与预约方式仍需向接待方确认。

                ## 封面标题建议

                城市更新研学｜从一张模型走进真实街区

                ## 话题标签

                #研学日记 #城市更新 #街区观察 #空间体验

                ## 事实与待确认附录

                ### 已确认信息

                | 对应行程段 | 已确认信息 | 依据 |
                | --- | --- | --- |
                | 第一站 | 展厅展示街区模型 | 转写与图片可见事实 |
                | 第二站 | 现场观察铺装与排水节点 | 转写与图片可见事实 |
                | 第三站 | 空间内展示居民活动照片 | 图片可见事实 |

                ### 仍待确认

                | 对应行程段 | 待确认内容 | 当前线索 |
                | --- | --- | --- |
                | 全程 | 开放时间与预约方式 | 需向接待方确认 |
            """.trimIndent(),
            generatedAt = 1_700_000_000_000
        )
        val attachments = imageFiles.mapIndexed { index, file ->
            MeetingAttachment(
                id = "study-image-${index + 1}",
                meetingId = report.meetingId,
                displayName = "第${index + 1}站现场.jpg",
                localPath = file.absolutePath,
                mimeType = "image/jpeg",
                createdAt = report.generatedAt + index
            )
        }

        val pdf = ReportExporter.exportToPdf(context, report, attachments, "城市更新研学")
        assertTrue(pdf.length() > 50_000)

        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertTrue(renderer.pageCount in 3..8)
                renderer.openPage(0).use { page ->
                    val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        rendered.eraseColor(Color.WHITE)
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        var editorialBluePixels = 0
                        for (y in 0 until rendered.height step 4) {
                            for (x in 0 until rendered.width step 4) {
                                val pixel = rendered.getPixel(x, y)
                                if (Color.blue(pixel) > 210 && Color.red(pixel) in 210..245) {
                                    editorialBluePixels++
                                }
                            }
                        }
                        assertTrue("研学 PDF 首屏应包含轻量蓝色导语与章节表面", editorialBluePixels > 120)
                    } finally {
                        rendered.recycle()
                    }
                }
                repeat(minOf(renderer.pageCount, 6)) { pageIndex ->
                    val preview = File(context.filesDir, "study-tour-editorial-page-${pageIndex + 1}.png")
                    renderer.openPage(pageIndex).use { page ->
                        val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            rendered.eraseColor(Color.WHITE)
                            page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            FileOutputStream(preview).use { output ->
                                assertTrue(rendered.compress(Bitmap.CompressFormat.PNG, 100, output))
                            }
                        } finally {
                            rendered.recycle()
                        }
                    }
                }
            }
        }

        pdf.copyTo(File(context.filesDir, "study-tour-editorial-qa.pdf"), overwrite = true)
        imageFiles.forEach(File::delete)
        pdf.delete()
    }

    private fun createQaImage(directory: File, name: String, color: Int, label: String): File {
        val file = File(directory, name)
        val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(color)
            val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.argb(155, 15, 35, 55)
            }
            canvas.drawRect(80f, 670f, 1520f, 920f, overlay)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textSize = 112f
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(label, 150f, 830f, textPaint)
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}

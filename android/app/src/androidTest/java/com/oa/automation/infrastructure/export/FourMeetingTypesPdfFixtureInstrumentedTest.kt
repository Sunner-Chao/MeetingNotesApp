package com.oa.automation.infrastructure.export

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.Report
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Produces four reviewable PDFs from the current four meeting types.
 * Images and Markdown are loaded from the repository's test-materials assets,
 * so the fixture does not depend on a developer machine path or network.
 */
@RunWith(AndroidJUnit4::class)
class FourMeetingTypesPdfFixtureInstrumentedTest {
    private data class Fixture(
        val templateName: String,
        val meetingTitle: String,
        val markdownAsset: String,
        val imageFolder: String,
        val imageNames: List<String>
    )

    @Test
    fun exportsAllFourMeetingTypesToReviewDirectory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val outputDirectory = context.getExternalFilesDir("generated-reports")
            ?: error("Android external files directory is unavailable")
        outputDirectory.deleteRecursively()
        outputDirectory.mkdirs()

        val fixtures = listOf(
            Fixture(
                templateName = "通用会议",
                meetingTitle = "产品需求评审",
                markdownAsset = "general.md",
                imageFolder = "通用会议",
                imageNames = listOf(
                    "01-Staff meeting (3).jpg",
                    "02-Community meeting.jpg",
                    "03-Xinhua Conference Hall.jpg"
                )
            ),
            Fixture(
                templateName = "项目管理",
                meetingTitle = "滨江综合体项目周例会",
                markdownAsset = "project.md",
                imageFolder = "项目管理",
                imageNames = listOf(
                    "01-A modern building under construction.jpg",
                    "02-Construction site 2016-06 1465027257.jpg",
                    "03-Construction de la fondation d'un immeuble.jpg"
                )
            ),
            Fixture(
                templateName = "论坛会议",
                meetingTitle = "工程数字化与现场协同论坛",
                markdownAsset = "forum.md",
                imageFolder = "论坛会议",
                imageNames = listOf(
                    "01-Digital Cities Conference Opening Panel.jpg",
                    "02-Panel Veje til Vaekst 20121128 N8B3868 (8291275430).jpg",
                    "03-Interior view of the main hall at Inbound 2018.jpg"
                )
            ),
            Fixture(
                templateName = "研学考察",
                meetingTitle = "施工测绘与湿地观察研学",
                markdownAsset = "study.md",
                imageFolder = "研学考察",
                imageNames = listOf(
                    "01-United States Marine Corps Construction Site.jpg",
                    "02-Construction of bridge.jpg",
                    "03-Land Surveyor.jpg",
                    "04-Land Survey (14207228023).jpg",
                    "05-Zhongshan Ancient Town, Jiangjin, Chongqing.jpg",
                    "06-Nanxun - Ancient water town - 0081.jpg",
                    "07-Nakaikemi Wetland (boardwalk).jpg",
                    "08-Boardwalk over wetland, Sherwood Reserve.jpg"
                )
            )
        )

        val manifest = StringBuilder()
        fixtures.forEachIndexed { fixtureIndex, fixture ->
            val meetingId = "four-types-fixture-${fixtureIndex + 1}"
            val markdown = assets.open(fixture.markdownAsset).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val copiedImages = fixture.imageNames.mapIndexed { imageIndex, imageName ->
                val localFile = File(context.cacheDir, "four-pdf-images/$fixtureIndex/$imageIndex.jpg")
                localFile.parentFile?.mkdirs()
                assets.open("${fixture.imageFolder}/$imageName").use { input ->
                    FileOutputStream(localFile).use { output -> input.copyTo(output) }
                }
                localFile
            }
            val report = Report(
                meetingId = meetingId,
                templateName = fixture.templateName,
                rawContent = markdown,
                generatedAt = 1_755_465_600_000L
            )
            val attachments = copiedImages.mapIndexed { imageIndex, file ->
                MeetingAttachment(
                    id = "$meetingId-image-${imageIndex + 1}",
                    meetingId = meetingId,
                    displayName = fixture.imageNames[imageIndex],
                    localPath = file.absolutePath,
                    mimeType = "image/jpeg",
                    createdAt = report.generatedAt + imageIndex,
                    markerTranscriptAnchor = "图片锚点 ${imageIndex + 1}"
                )
            }

            val exported = ReportExporter.exportToPdf(
                context = context,
                report = report,
                attachments = attachments,
                meetingTitle = fixture.meetingTitle
            )
            val retained = File(outputDirectory, exported.name)
            exported.copyTo(retained, overwrite = true)
            val pageCount = ParcelFileDescriptor.open(
                retained,
                ParcelFileDescriptor.MODE_READ_ONLY
            ).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
            }

            assertTrue("${fixture.templateName} PDF 不能为空", retained.length() > 80_000)
            assertTrue("${fixture.templateName} PDF 至少应有一页", pageCount >= 1)
            manifest.append(fixture.templateName)
                .append("\t")
                .append(retained.name)
                .append("\t页数=")
                .append(pageCount)
                .append("\t字节数=")
                .append(retained.length())
                .append('\n')
        }

        File(outputDirectory, "MANIFEST.txt").writeText(
            manifest.toString(),
            StandardCharsets.UTF_8
        )
    }
}

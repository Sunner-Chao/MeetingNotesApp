package com.oa.automation.infrastructure.export

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.extractForumParticipants
import com.oa.automation.domain.model.isForumMeetingTemplate
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in acceptance export; private meeting text stays outside test assets/source control. */
@RunWith(AndroidJUnit4::class)
class ExternalReportsPdfInstrumentedTest {
    @Test
    fun exportsProvidedReportsWithTheProductionRenderer() {
        val arguments = InstrumentationRegistry.getArguments()
        val validationId = arguments.getString("reportValidationId").orEmpty()
        assumeTrue("Supply reportValidationId to export external acceptance reports", validationId.isNotBlank())
        require(validationId.matches(Regex("[a-zA-Z0-9_-]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Image fixtures live in the production app asset bundle, not the
        // instrumentation APK. Reading the target assets mirrors the app's
        // real export path and keeps external-report validation end to end.
        val assets = context.assets
        val base = File(context.filesDir, "report-validation/$validationId")
        val input = File(base, "input")
        val output = File(base, "pdf").apply { mkdirs() }
        val manifest = JSONObject(File(input, "manifest.json").readText()).getJSONArray("reports")
        require(manifest.length() > 0)
        val exported = StringBuilder()
        for (index in 0 until manifest.length()) {
            val item = manifest.getJSONObject(index)
            val template = item.getString("template")
            val fileName = item.getString("file")
            require(File(fileName).name == fileName)
            val report = Report(
                meetingId = "$validationId-$index",
                templateName = template,
                rawContent = File(input, fileName).readText(),
                participants = File(input, fileName).readText()
                    .takeIf { template.isForumMeetingTemplate() }
                    ?.let { extractForumParticipants(it) }
                    .orEmpty()
            )
            val imageFiles = item.optJSONArray("images")?.let { imageNames ->
                (0 until imageNames.length()).map { imageIndex ->
                    val assetName = imageNames.getString(imageIndex)
                    val localFile = File(
                        context.cacheDir,
                        "report-validation/$validationId/images/$index-$imageIndex.jpg"
                    ).apply { parentFile?.mkdirs() }
                    assets.open(assetName).use { source ->
                        FileOutputStream(localFile).use { target -> source.copyTo(target) }
                    }
                    MeetingAttachment(
                        id = "$validationId-$index-image-$imageIndex",
                        meetingId = report.meetingId,
                        displayName = "排版测试配图-${imageIndex + 1}.jpg",
                        localPath = localFile.absolutePath,
                        mimeType = "image/jpeg",
                        createdAt = report.generatedAt + imageIndex,
                        markerTranscriptAnchor = "Lite 图片排版测试 ${imageIndex + 1}"
                    )
                }
            }.orEmpty()
            val result = ReportExporter.exportToPdf(
                context, report, imageFiles, item.optString("title", template)
            )
            val retained = File(output, result.name)
            result.copyTo(retained, overwrite = true)
            val pages = ParcelFileDescriptor.open(retained, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            }
            assertTrue("$template has no PDF pages", pages > 0)
            assertTrue("$template PDF is empty", retained.length() > 1000)
            if (imageFiles.isNotEmpty()) {
                assertTrue("$template 图片未进入 PDF", retained.length() > 80_000)
            }
            exported.append(template).append('\t').append(pages).append('\t').append(retained.length()).append('\n')
        }
        File(output, "MANIFEST.txt").writeText(exported.toString())
    }
}

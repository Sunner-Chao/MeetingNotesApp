package com.oa.automation.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.oa.automation.BuildConfig
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.MeetingAttachment
import java.io.File
import java.io.FileOutputStream

/** Creates original, project-owned debug artwork for repeatable journey UI review. */
internal class DevelopmentStudyTourMediaFactory(
    private val context: Context
) {
    fun ensure(
        baseTime: Long,
        stages: List<JourneyStage>
    ): List<MeetingAttachment> {
        check(BuildConfig.DEBUG) { "演示影像仅允许在 debug 构建中使用" }
        val outputDirectory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
        return specifications.mapIndexed { index, specification ->
            val file = File(outputDirectory, "journey-${index + 1}.jpg")
            if (!file.isFile || file.length() == 0L) {
                drawArtwork(file, specification)
            }
            val stage = stages.getOrNull(specification.stageIndex)
                ?: error("Study-tour debug stage is missing")
            MeetingAttachment(
                id = "${DevelopmentStudyTourFixture.MEETING_ID}-photo-${index + 1}",
                meetingId = DevelopmentStudyTourFixture.MEETING_ID,
                journeyStageId = stage.id,
                displayName = "${specification.slug}.jpg",
                localPath = file.absolutePath,
                mimeType = "image/jpeg",
                createdAt = baseTime + specification.offsetMs,
                latitude = 31.2304 + specification.stageIndex * .0012,
                longitude = 121.4737 + specification.stageIndex * .0015,
                accuracyMeters = 18f,
                locationCapturedAt = baseTime + specification.offsetMs,
                locationSource = "debug-fixture",
                recordingMarkerId = "debug-marker-${index + 1}",
                markerTimestampMs = specification.markerTimestampMs,
                markerTranscriptAnchor = specification.anchor
            )
        }
    }

    private fun drawArtwork(file: File, specification: ArtworkSpecification) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f,
            0f,
            WIDTH.toFloat(),
            HEIGHT.toFloat(),
            specification.topColor,
            specification.bottomColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null
        when (specification.scene) {
            Scene.PAPER -> drawPaperWorkshop(canvas, paint)
            Scene.DRYING -> drawPaperDrying(canvas, paint)
            Scene.BAMBOO -> drawBambooWeaving(canvas, paint)
            Scene.TEA -> drawTeaTerraces(canvas, paint)
            Scene.VILLAGE -> drawWaterVillage(canvas, paint)
            Scene.WORKSHOP -> drawCreativeWorkshop(canvas, paint)
            Scene.SHARE -> drawSharingCircle(canvas, paint)
        }
        drawCaption(canvas, paint, specification)
        FileOutputStream(file).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                "Unable to write study-tour debug artwork"
            }
        }
        bitmap.recycle()
    }

    private fun drawPlan(canvas: Canvas, paint: Paint) {
        paint.color = Color.argb(225, 240, 247, 252)
        canvas.drawRoundRect(RectF(80f, 230f, 820f, 930f), 34f, 34f, paint)
        val colors = listOf(Color.rgb(45, 125, 154), Color.rgb(58, 150, 221), Color.rgb(107, 132, 151))
        repeat(16) { index ->
            val row = index / 4
            val column = index % 4
            paint.color = colors[index % colors.size]
            val left = 125f + column * 165f + if (row % 2 == 0) 0f else 32f
            val top = 300f + row * 135f
            canvas.drawRoundRect(RectF(left, top, left + 104f, top + 72f), 12f, 12f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 9f
        paint.color = Color.rgb(16, 110, 190)
        val path = Path().apply {
            moveTo(130f, 820f)
            cubicTo(300f, 690f, 470f, 890f, 760f, 520f)
        }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawStreet(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(176, 101, 73)
        canvas.drawRect(80f, 390f, 405f, 1020f, paint)
        paint.color = Color.rgb(130, 71, 55)
        repeat(7) { row ->
            repeat(4) { column ->
                val left = 100f + column * 74f + if (row % 2 == 0) 0f else 32f
                val top = 430f + row * 72f
                canvas.drawRect(left, top, left + 58f, top + 44f, paint)
            }
        }
        paint.color = Color.rgb(65, 86, 101)
        canvas.drawRect(445f, 520f, 820f, 1020f, paint)
        paint.color = Color.rgb(210, 232, 245)
        repeat(3) { index ->
            canvas.drawRect(500f + index * 105f, 600f, 565f + index * 105f, 820f, paint)
        }
        paint.color = Color.rgb(244, 193, 92)
        canvas.drawRect(395f, 500f, 460f, 1020f, paint)
    }

    private fun drawRiver(canvas: Canvas, paint: Paint) {
        paint.shader = LinearGradient(0f, 620f, 0f, 1180f, Color.rgb(74, 155, 194), Color.rgb(22, 92, 148), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 620f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null
        paint.color = Color.rgb(70, 111, 80)
        canvas.drawPath(Path().apply {
            moveTo(0f, 730f)
            cubicTo(230f, 590f, 440f, 720f, 900f, 470f)
            lineTo(900f, 0f)
            lineTo(0f, 0f)
            close()
        }, paint)
        paint.color = Color.rgb(224, 225, 205)
        canvas.drawRoundRect(RectF(90f, 570f, 770f, 675f), 46f, 46f, paint)
        repeat(5) { index ->
            paint.color = Color.rgb(39, 91 + index * 5, 72)
            canvas.drawCircle(130f + index * 160f, 470f - (index % 2) * 42f, 58f, paint)
        }
    }

    private fun drawLab(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(10, 34, 58)
        canvas.drawRoundRect(RectF(70f, 230f, 830f, 1010f), 36f, 36f, paint)
        repeat(3) { index ->
            paint.color = Color.rgb(25, 70 + index * 20, 112 + index * 18)
            canvas.drawRoundRect(RectF(115f, 300f + index * 210f, 785f, 455f + index * 210f), 18f, 18f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 7f
            paint.color = Color.rgb(96, 205, 255)
            val path = Path().apply {
                moveTo(150f, 410f + index * 210f)
                cubicTo(310f, 320f + index * 210f, 470f, 445f + index * 210f, 745f, 345f + index * 210f)
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawSite(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(220, 226, 230)
        canvas.drawRect(0f, 850f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.color = Color.rgb(241, 184, 48)
        canvas.drawRect(180f, 250f, 218f, 930f, paint)
        canvas.drawRect(180f, 250f, 720f, 288f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 16f
        canvas.drawLine(200f, 280f, 480f, 600f, paint)
        canvas.drawLine(480f, 600f, 690f, 280f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(74, 93, 107)
        repeat(4) { index ->
            canvas.drawRect(370f + index * 92f, 610f - index * 38f, 444f + index * 92f, 920f, paint)
        }
        paint.color = Color.rgb(16, 110, 190)
        canvas.drawRect(690f, 290f, 704f, 640f, paint)
        canvas.drawRect(655f, 630f, 740f, 690f, paint)
    }

    private fun drawEcology(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(53, 123, 104)
        canvas.drawPath(Path().apply {
            moveTo(0f, 760f)
            cubicTo(230f, 590f, 530f, 760f, 900f, 530f)
            lineTo(900f, 1200f)
            lineTo(0f, 1200f)
            close()
        }, paint)
        paint.color = Color.rgb(64, 153, 192)
        canvas.drawPath(Path().apply {
            moveTo(0f, 810f)
            cubicTo(260f, 680f, 560f, 920f, 900f, 690f)
            lineTo(900f, 1200f)
            lineTo(0f, 1200f)
            close()
        }, paint)
        repeat(7) { index ->
            paint.color = if (index % 2 == 0) Color.rgb(45, 100, 71) else Color.rgb(76, 132, 80)
            val x = 90f + index * 125f
            canvas.drawRect(x, 420f, x + 22f, 760f, paint)
            canvas.drawCircle(x + 11f, 385f, 78f, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = Color.WHITE
        canvas.drawArc(RectF(600f, 250f, 690f, 320f), 200f, 110f, false, paint)
        canvas.drawArc(RectF(680f, 230f, 770f, 300f), 200f, 110f, false, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawForum(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(17, 56, 87)
        canvas.drawRoundRect(RectF(70f, 260f, 830f, 830f), 36f, 36f, paint)
        paint.color = Color.rgb(96, 205, 255)
        canvas.drawRoundRect(RectF(150f, 330f, 750f, 555f), 22f, 22f, paint)
        paint.color = Color.rgb(245, 248, 251)
        repeat(5) { index ->
            canvas.drawCircle(170f + index * 140f, 885f, 48f, paint)
            canvas.drawRoundRect(RectF(130f + index * 140f, 930f, 210f + index * 140f, 1080f), 20f, 20f, paint)
        }
        paint.color = Color.rgb(58, 150, 221)
        canvas.drawCircle(450f, 650f, 72f, paint)
        canvas.drawRoundRect(RectF(385f, 720f, 515f, 900f), 28f, 28f, paint)
    }

    private fun drawPaperWorkshop(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(96, 70, 48)
        canvas.drawRect(70f, 250f, 830f, 1010f, paint)
        paint.color = Color.rgb(111, 166, 168)
        canvas.drawRoundRect(RectF(120f, 480f, 780f, 930f), 34f, 34f, paint)
        paint.color = Color.rgb(232, 225, 192)
        canvas.drawOval(RectF(215f, 565f, 685f, 825f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 18f
        paint.color = Color.rgb(202, 170, 113)
        canvas.drawRoundRect(RectF(275f, 490f, 625f, 875f), 14f, 14f, paint)
        repeat(9) { index ->
            val x = 300f + index * 38f
            canvas.drawLine(x, 510f, x, 850f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawPaperDrying(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(77, 73, 61)
        canvas.drawRect(70f, 180f, 830f, 1040f, paint)
        repeat(4) { row ->
            repeat(3) { column ->
                paint.color = if ((row + column) % 2 == 0) Color.rgb(245, 238, 208) else Color.rgb(225, 218, 184)
                val left = 115f + column * 235f
                val top = 235f + row * 190f
                canvas.drawRect(left, top, left + 180f, top + 138f, paint)
            }
        }
        paint.color = Color.argb(80, 255, 248, 212)
        canvas.drawCircle(720f, 270f, 190f, paint)
    }

    private fun drawBambooWeaving(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(238, 221, 177)
        canvas.drawRoundRect(RectF(95f, 240f, 805f, 1020f), 42f, 42f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 16f
        repeat(9) { index ->
            paint.color = if (index % 2 == 0) Color.rgb(119, 128, 65) else Color.rgb(177, 139, 72)
            val inset = 115f + index * 34f
            canvas.drawOval(RectF(inset, inset + 115f, WIDTH - inset, HEIGHT - inset + 45f), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(76, 92, 52)
        canvas.drawCircle(450f, 630f, 52f, paint)
    }

    private fun drawTeaTerraces(canvas: Canvas, paint: Paint) {
        val greens = listOf(
            Color.rgb(43, 105, 72),
            Color.rgb(66, 132, 82),
            Color.rgb(91, 151, 91),
            Color.rgb(119, 169, 103)
        )
        repeat(7) { index ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 70f
            paint.color = greens[index % greens.size]
            canvas.drawArc(RectF(-180f + index * 55f, 250f + index * 80f, 1030f - index * 15f, 1150f), 190f, 155f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(241, 226, 152)
        canvas.drawCircle(690f, 250f, 78f, paint)
    }

    private fun drawWaterVillage(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(100, 140, 159)
        canvas.drawPath(Path().apply {
            moveTo(0f, 780f)
            cubicTo(250f, 680f, 520f, 900f, 900f, 720f)
            lineTo(900f, 1200f)
            lineTo(0f, 1200f)
            close()
        }, paint)
        repeat(4) { index ->
            val left = 90f + index * 205f
            paint.color = Color.rgb(235, 226, 202)
            canvas.drawRect(left, 500f - index * 35f, left + 150f, 790f - index * 35f, paint)
            paint.color = Color.rgb(76, 68, 62)
            canvas.drawPath(Path().apply {
                moveTo(left - 24f, 510f - index * 35f)
                lineTo(left + 75f, 420f - index * 35f)
                lineTo(left + 174f, 510f - index * 35f)
                close()
            }, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 20f
        paint.color = Color.rgb(105, 99, 85)
        canvas.drawArc(RectF(300f, 570f, 610f, 870f), 190f, 160f, false, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawCreativeWorkshop(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(226, 213, 190)
        canvas.drawRoundRect(RectF(80f, 300f, 820f, 1000f), 36f, 36f, paint)
        paint.color = Color.rgb(121, 88, 57)
        canvas.drawRect(120f, 710f, 780f, 920f, paint)
        val productColors = listOf(Color.rgb(232, 221, 187), Color.rgb(109, 127, 78), Color.rgb(153, 105, 66))
        repeat(5) { index ->
            paint.color = productColors[index % productColors.size]
            val left = 145f + index * 125f
            canvas.drawRoundRect(RectF(left, 530f - (index % 2) * 45f, left + 92f, 760f), 18f, 18f, paint)
        }
        paint.color = Color.rgb(57, 93, 82)
        canvas.drawCircle(450f, 400f, 88f, paint)
    }

    private fun drawSharingCircle(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(38, 54, 65)
        canvas.drawRoundRect(RectF(60f, 220f, 840f, 1040f), 42f, 42f, paint)
        repeat(7) { index ->
            val angle = Math.toRadians(index * (360.0 / 7.0) - 90.0)
            val x = 450f + kotlin.math.cos(angle).toFloat() * 265f
            val y = 630f + kotlin.math.sin(angle).toFloat() * 270f
            paint.color = if (index % 2 == 0) Color.rgb(230, 213, 164) else Color.rgb(143, 177, 163)
            canvas.drawCircle(x, y, 52f, paint)
        }
        paint.color = Color.rgb(193, 111, 65)
        canvas.drawCircle(450f, 630f, 105f, paint)
        paint.color = Color.rgb(247, 225, 157)
        canvas.drawCircle(450f, 630f, 58f, paint)
    }

    private fun drawCaption(canvas: Canvas, paint: Paint, specification: ArtworkSpecification) {
        paint.color = Color.argb(210, 8, 28, 46)
        canvas.drawRect(0f, HEIGHT - 180f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.color = Color.WHITE
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = 54f
        canvas.drawText(specification.label, 62f, HEIGHT - 95f, paint)
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = 27f
        paint.color = Color.argb(220, 255, 255, 255)
        canvas.drawText("ZHIWUBEN STUDY JOURNEY", 64f, HEIGHT - 48f, paint)
    }

    private data class ArtworkSpecification(
        val slug: String,
        val label: String,
        val scene: Scene,
        val stageIndex: Int,
        val offsetMs: Long,
        val markerTimestampMs: Long,
        val anchor: String,
        val topColor: Int,
        val bottomColor: Int
    )

    private enum class Scene { PAPER, DRYING, BAMBOO, TEA, VILLAGE, WORKSHOP, SHARE }

    companion object {
        private const val DIRECTORY_NAME = "debug-study-tour-media-v2"
        private const val WIDTH = 900
        private const val HEIGHT = 1200
        private val specifications = listOf(
            ArtworkSpecification("paper-making", "PAPER MAKING", Scene.PAPER, 0, 900_000L, 900_000L, "竹帘从纸槽中抄起均匀纸浆", Color.rgb(205, 222, 205), Color.rgb(85, 125, 119)),
            ArtworkSpecification("paper-drying", "PAPER DRYING", Scene.DRYING, 0, 1_800_000L, 1_800_000L, "院落中逐张晾晒的手工纸", Color.rgb(242, 226, 181), Color.rgb(143, 112, 77)),
            ArtworkSpecification("bamboo-weaving", "BAMBOO WEAVING", Scene.BAMBOO, 1, 7_900_000L, 4_700_000L, "竹篾交错形成器物的起底纹理", Color.rgb(231, 220, 174), Color.rgb(115, 132, 78)),
            ArtworkSpecification("tea-terraces", "TEA TERRACES", Scene.TEA, 2, 72_900_000L, 8_200_000L, "茶垄沿山势延伸并保留地表植被", Color.rgb(190, 222, 174), Color.rgb(68, 126, 78)),
            ArtworkSpecification("water-village", "WATER VILLAGE", Scene.VILLAGE, 3, 80_100_000L, 11_900_000L, "石桥、巷道与穿村水渠形成日常路径", Color.rgb(193, 218, 220), Color.rgb(102, 140, 154)),
            ArtworkSpecification("creative-workshop", "VILLAGE WORKSHOP", Scene.WORKSHOP, 4, 91_200_000L, 15_600_000L, "手工纸、竹编包装与产品样品", Color.rgb(224, 211, 180), Color.rgb(132, 106, 77)),
            ArtworkSpecification("sharing-circle", "JOURNEY SHARING", Scene.SHARE, 5, 109_200_000L, 19_200_000L, "按路线展开照片、笔记与采集样本", Color.rgb(123, 157, 151), Color.rgb(39, 64, 70))
        )
    }
}

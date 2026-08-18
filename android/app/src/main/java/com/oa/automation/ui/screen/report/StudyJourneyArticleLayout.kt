package com.oa.automation.ui.screen.report

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.MeetingAttachment
import com.oa.automation.domain.model.Report
import com.oa.automation.infrastructure.image.OrientedImageDecoder
import com.oa.automation.ui.component.FlowingProgressBorder
import java.io.File

private data class StudyJourneyColors(
    val paper: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val soft: Color
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun StudyJourneyArticleExperience(
    report: Report,
    meetingTitle: String,
    attachments: List<MeetingAttachment>,
    journeyStages: List<JourneyStage>,
    isProcessing: Boolean,
    onDeleteAttachment: (MeetingAttachment) -> Unit,
    onAddImages: () -> Unit,
    onCaptureImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val article = remember(report.rawContent, meetingTitle) {
        parseStudyJourneyArticle(report.rawContent, meetingTitle)
    }
    val catalog = remember(context) { StudyJourneyStyleCatalogLoader.load(context) }
    val automaticStyle = remember(catalog, article, attachments.size) {
        selectStudyJourneyStyle(catalog, article, attachments.size)
    }
    var selectedStyleId by rememberSaveable(report.id, automaticStyle.id) {
        mutableStateOf(automaticStyle.id)
    }
    val style = catalog.styles.firstOrNull { it.id == selectedStyleId } ?: automaticStyle
    val colors = remember(style.palette) { style.palette.toStudyJourneyColors() }
    val sectionMedia = remember(article, attachments, journeyStages) {
        resolveStudyJourneySectionMedia(article, attachments, journeyStages)
    }
    var galleryIndex by remember { mutableStateOf<Int?>(null) }
    galleryIndex?.let { index ->
        ReferenceImageGalleryDialog(
            attachments = attachments,
            initialIndex = index,
            onDelete = onDeleteAttachment,
            isStudyReport = true,
            title = "旅程影像",
            onDismiss = { galleryIndex = null }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StudyJourneyCarousel(
            article = article,
            attachments = attachments,
            sectionMedia = sectionMedia,
            catalog = catalog,
            style = style,
            colors = colors,
            isProcessing = isProcessing,
            onSelectStyle = { selectedStyleId = it },
            onOpenAttachment = { attachment ->
                galleryIndex = attachments.indexOfFirst { it.id == attachment.id }.takeIf { it >= 0 }
            }
        )
        StudyJourneyArticleBody(
            article = article,
            attachments = attachments,
            sectionMedia = sectionMedia,
            colors = colors,
            photoTreatment = style.photoTreatment,
            onOpenAttachment = { attachment ->
                galleryIndex = attachments.indexOfFirst { it.id == attachment.id }.takeIf { it >= 0 }
            },
            onAddImages = onAddImages,
            onCaptureImage = onCaptureImage
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun StudyJourneyCarousel(
    article: StudyJourneyArticle,
    attachments: List<MeetingAttachment>,
    sectionMedia: List<StudyJourneySectionMedia>,
    catalog: StudyJourneyStyleCatalog,
    style: StudyJourneyVisualStyle,
    colors: StudyJourneyColors,
    isProcessing: Boolean,
    onSelectStyle: (String) -> Unit,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    val carouselExtras = remember(style.carouselExtras, article.tips) {
        style.carouselExtras.filter { extra -> extra != "tips" || article.tips.isNotEmpty() }
    }
    val pageCount = (sectionMedia.size + carouselExtras.size + 1).coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { pageCount })
    var showStyleMenu by remember { mutableStateOf(false) }
    FlowingProgressBorder(
        active = isProcessing,
        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
        cornerRadius = 8.dp,
        inset = 1.dp,
        strokeWidth = 1.6.dp,
        colors = listOf(colors.primary, colors.secondary, colors.accent)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(8.dp),
            color = colors.paper
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    when {
                    page == 0 -> {
                        StudyJourneyCoverPage(
                            article = article,
                            coverAttachments = attachments,
                            style = style,
                            colors = colors,
                            onOpenAttachment = onOpenAttachment
                        )
                    }
                    page <= sectionMedia.size -> {
                        val media = sectionMedia[page - 1]
                        val pattern = resolveStudyJourneyPagePattern(style, page, media.attachments.size)
                        StudyJourneyStagePage(
                            media = media,
                            pattern = pattern,
                            photoTreatment = style.photoTreatment,
                            colors = colors,
                            onOpenAttachment = onOpenAttachment
                        )
                    }
                    else -> {
                        when (carouselExtras[page - sectionMedia.size - 1]) {
                            "tips" -> StudyJourneyTipsCarouselPage(article.tips, colors)
                            else -> StudyJourneyTipsCarouselPage(emptyList(), colors)
                        }
                    }
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = colors.surface.copy(alpha = .90f)
                    ) {
                        Text(
                            text = "${pagerState.currentPage + 1}/$pageCount",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            color = colors.ink,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Box {
                        Surface(
                            modifier = Modifier
                                .height(30.dp)
                                .clickable { showStyleMenu = true },
                            shape = RoundedCornerShape(6.dp),
                            color = colors.surface.copy(alpha = .90f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Palette, null, tint = colors.primary, modifier = Modifier.size(14.dp))
                                Text(style.displayName, color = colors.ink, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        DropdownMenu(
                            expanded = showStyleMenu,
                            onDismissRequest = { showStyleMenu = false }
                        ) {
                            catalog.styles.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(candidate.displayName) },
                                    leadingIcon = {
                                        Icon(
                                            if (candidate.id == style.id) Icons.Default.CheckCircle else Icons.Default.Palette,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        onSelectStyle(candidate.id)
                                        showStyleMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                val indicatorRange = remember(pagerState.currentPage, pageCount) {
                    visibleJourneyPagerIndices(pagerState.currentPage, pageCount)
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    indicatorRange.forEach { index ->
                        Box(
                            modifier = Modifier
                                .width(if (index == pagerState.currentPage) 20.dp else 6.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (index == pagerState.currentPage) colors.primary
                                    else colors.surface.copy(alpha = .72f)
                                )
                        )
                    }
                }
            }
        }
    }
}

internal fun visibleJourneyPagerIndices(currentPage: Int, pageCount: Int, maximumVisible: Int = 7): IntRange {
    if (pageCount <= maximumVisible) return 0 until pageCount
    val half = maximumVisible / 2
    val start = (currentPage - half).coerceIn(0, pageCount - maximumVisible)
    return start until start + maximumVisible
}

@Composable
private fun StudyJourneyCoverPage(
    article: StudyJourneyArticle,
    coverAttachments: List<MeetingAttachment>,
    style: StudyJourneyVisualStyle,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    val usesDarkPhotoCover = style.coverLayout in setOf(
        "photo-collage",
        "editorial-poster",
        "field-board",
        "four-grid-story",
        "guide-cover"
    ) &&
        coverAttachments.isNotEmpty()
    Box(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        when (style.coverLayout) {
            "photo-collage" -> StudyJourneyCoverCollage(coverAttachments, colors, onOpenAttachment)
            "editorial-poster" -> StudyJourneyEditorialCover(coverAttachments.firstOrNull(), colors, onOpenAttachment)
            "field-board" -> StudyJourneyFieldBoardCover(coverAttachments.firstOrNull(), colors, onOpenAttachment)
            "notebook-cover" -> StudyJourneyNotebookCover(coverAttachments.firstOrNull(), colors, onOpenAttachment)
            "architecture-grid" -> StudyJourneyArchitectureCover(coverAttachments, colors, onOpenAttachment)
            "four-grid-story" -> StudyJourneyFourGridCover(coverAttachments, colors, onOpenAttachment)
            "guide-cover" -> StudyJourneyEditorialCover(coverAttachments.firstOrNull(), colors, onOpenAttachment)
            "schedule-cover" -> StudyJourneyRouteArtwork(
                routeStops = article.routeStops,
                attachments = coverAttachments,
                colors = colors,
                modifier = Modifier.fillMaxSize(),
                onOpenAttachment = onOpenAttachment
            )
            else -> StudyJourneyRouteArtwork(
                routeStops = article.routeStops,
                attachments = coverAttachments,
                colors = colors,
                modifier = Modifier.fillMaxSize(),
                onOpenAttachment = onOpenAttachment
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 22.dp, top = 54.dp, end = 22.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "STUDY JOURNEY",
                    color = if (usesDarkPhotoCover) Color.White.copy(alpha = .82f) else colors.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = article.coverTitles.firstOrNull().orEmpty().ifBlank { article.title },
                    color = if (usesDarkPhotoCover) Color.White else colors.ink,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (article.routeStops.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Default.Route,
                            contentDescription = null,
                            tint = if (usesDarkPhotoCover) Color.White else colors.primary,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = article.routeStops.take(7).joinToString(" · "),
                            color = if (usesDarkPhotoCover) Color.White.copy(alpha = .92f) else colors.muted,
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = "${article.sections.size} 个行程段 · ${style.displayName}",
                    color = if (usesDarkPhotoCover) Color.White.copy(alpha = .78f) else colors.muted,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun StudyJourneyRouteArtwork(
    routeStops: List<String>,
    attachments: List<MeetingAttachment>,
    colors: StudyJourneyColors,
    modifier: Modifier = Modifier,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    val stops = routeStops.ifEmpty { attachments.indices.map { index -> "第 ${index + 1} 站" } }.take(7)
    val nodeCount = maxOf(stops.size, attachments.size.coerceAtMost(7)).coerceIn(4, 7)
    val positions = remember(nodeCount) { studyJourneyRouteNodePositions(nodeCount) }
    BoxWithConstraints(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(colors.paper, colors.soft.copy(alpha = .76f), colors.surface),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
            )
            val points = positions.map { position ->
                Offset(size.width * position.first, size.height * position.second)
            }
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { point -> lineTo(point.x, point.y) }
            }
            drawPath(
                path = path,
                color = colors.primary.copy(alpha = .50f),
                style = Stroke(
                    width = 3f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 9f))
                )
            )
            points.forEachIndexed { index, point ->
                drawCircle(colors.surface, radius = 12f, center = point)
                drawCircle(if (index == 0) colors.accent else colors.primary, radius = 7f, center = point)
            }
        }
        positions.forEachIndexed { index, position ->
            val attachment = attachments.getOrNull(index)
            val stop = stops.getOrNull(index).orEmpty().ifBlank { "第 ${index + 1} 站" }
            Column(
                modifier = Modifier
                    .offset(
                        x = maxWidth * position.first - 38.dp,
                        y = maxHeight * position.second - 54.dp
                    )
                    .width(76.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (attachment != null) {
                    StudyJourneyPhoto(
                        attachment = attachment,
                        modifier = Modifier.width(56.dp).height(39.dp),
                        onOpen = { onOpenAttachment(attachment) }
                    )
                }
                Surface(shape = RoundedCornerShape(4.dp), color = colors.surface.copy(alpha = .94f)) {
                    Text(
                        text = stop,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                        color = colors.ink,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

internal fun studyJourneyRouteNodePositions(count: Int): List<Pair<Float, Float>> {
    val safeCount = count.coerceIn(1, 7)
    return List(safeCount) { index ->
        val progress = if (safeCount == 1) 0f else index / (safeCount - 1f)
        Pair(if (index % 2 == 0) .24f else .72f, .34f + progress * .47f)
    }
}

@Composable
private fun StudyJourneyCoverCollage(
    attachments: List<MeetingAttachment>,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    val photos = attachments.take(3)
    if (photos.isEmpty()) {
        StudyJourneySectionArtwork(colors, Modifier.fillMaxSize())
        return
    }
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        StudyJourneyPhoto(
            attachment = photos.first(),
            modifier = Modifier.weight(1.25f).fillMaxHeight(),
            onOpen = { onOpenAttachment(photos.first()) }
        )
        Column(modifier = Modifier.weight(.75f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            photos.drop(1).ifEmpty { listOf(photos.first()) }.take(2).forEach { attachment ->
                StudyJourneyPhoto(
                    attachment = attachment,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onOpen = { onOpenAttachment(attachment) }
                )
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .05f), Color.Black.copy(alpha = .72f)))))
}

@Composable
private fun StudyJourneyFourGridCover(
    attachments: List<MeetingAttachment>,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    val photos = attachments.take(4)
    if (photos.size < 4) {
        StudyJourneyCoverCollage(attachments, colors, onOpenAttachment)
        return
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        photos.chunked(2).forEach { rowPhotos ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                rowPhotos.forEach { attachment ->
                    StudyJourneyPhoto(
                        attachment = attachment,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        treatment = "editorial",
                        onOpen = { onOpenAttachment(attachment) }
                    )
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .02f), Color.Black.copy(alpha = .70f)))))
}

@Composable
private fun StudyJourneyEditorialCover(
    attachment: MeetingAttachment?,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    StudyJourneyPhotoCoverBackground(attachment, colors, onOpenAttachment)
    Canvas(Modifier.fillMaxSize()) {
        drawLine(colors.surface.copy(alpha = .65f), Offset(size.width * .08f, size.height * .28f), Offset(size.width * .42f, size.height * .28f), 3f)
        drawLine(colors.surface.copy(alpha = .42f), Offset(size.width * .58f, size.height * .78f), Offset(size.width * .92f, size.height * .78f), 2f)
    }
}

@Composable
private fun StudyJourneyFieldBoardCover(
    attachment: MeetingAttachment?,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    StudyJourneyPhotoCoverBackground(attachment, colors, onOpenAttachment)
    Box(Modifier.fillMaxSize().padding(14.dp)) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Color.Transparent)
        )
        Text(
            text = "FIELD NOTES",
            modifier = Modifier.align(Alignment.CenterEnd),
            color = Color.White.copy(alpha = .68f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StudyJourneyNotebookCover(
    attachment: MeetingAttachment?,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Box(Modifier.fillMaxSize().background(colors.paper)) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(12) { index ->
                val y = size.height * (.17f + index * .065f)
                drawLine(colors.soft, Offset(0f, y), Offset(size.width, y), 1f)
            }
            drawLine(colors.accent.copy(alpha = .32f), Offset(size.width * .12f, 0f), Offset(size.width * .12f, size.height), 2f)
        }
        attachment?.let {
            StudyJourneyPhoto(
                attachment = it,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(.72f).aspectRatio(4f / 3f),
                onOpen = { onOpenAttachment(it) }
            )
        }
    }
}

@Composable
private fun StudyJourneyArchitectureCover(
    attachments: List<MeetingAttachment>,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Box(Modifier.fillMaxSize().background(colors.paper)) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(5) { index ->
                val x = size.width * (index + 1) / 6f
                drawLine(colors.soft, Offset(x, 0f), Offset(x, size.height), 1f)
            }
            repeat(7) { index ->
                val y = size.height * (index + 1) / 8f
                drawLine(colors.soft, Offset(0f, y), Offset(size.width, y), 1f)
            }
        }
        Row(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(180.dp).padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            attachments.take(3).forEachIndexed { index, attachment ->
                StudyJourneyPhoto(
                    attachment = attachment,
                    modifier = Modifier.weight(if (index == 1) 1.25f else .85f).fillMaxHeight(),
                    onOpen = { onOpenAttachment(attachment) }
                )
            }
        }
    }
}

@Composable
private fun StudyJourneyPhotoCoverBackground(
    attachment: MeetingAttachment?,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    if (attachment == null) {
        StudyJourneySectionArtwork(colors, Modifier.fillMaxSize())
        return
    }
    StudyJourneyPhoto(
        attachment = attachment,
        modifier = Modifier.fillMaxSize(),
        onOpen = { onOpenAttachment(attachment) }
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .08f), Color.Black.copy(alpha = .70f)))))
}

@Composable
private fun StudyJourneyStagePage(
    media: StudyJourneySectionMedia,
    pattern: String,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    val textBlocks = media.section.blocks.filter {
        it.type == StudyJourneyBlockType.PARAGRAPH || it.type == StudyJourneyBlockType.QUOTE
    }
    val structuredBlocks = media.section.blocks.filter { it.type != StudyJourneyBlockType.PHOTO }
    val fieldObservation = parseStudyJourneyFieldObservation(structuredBlocks)
    if (fieldObservation != null && media.attachments.isNotEmpty()) {
        StudyJourneyFieldObservationPage(media, fieldObservation, photoTreatment, colors, onOpenAttachment)
        return
    }
    val detailLens = parseStudyJourneyDetailLens(structuredBlocks)
    if (detailLens != null && media.attachments.size >= 3) {
        StudyJourneyDetailLensPage(media, detailLens, photoTreatment, colors, onOpenAttachment)
        return
    }
    val questionThread = parseStudyJourneyQuestionThread(structuredBlocks)
    if (questionThread != null) {
        StudyJourneyQuestionThreadPage(media, questionThread, photoTreatment, colors, onOpenAttachment)
        return
    }
    when (pattern) {
        "quote-photo" -> StudyJourneyQuotePage(media, textBlocks, photoTreatment, colors, onOpenAttachment)
        "split-note" -> StudyJourneySplitPage(media, textBlocks, photoTreatment, colors, onOpenAttachment)
        "knowledge-note" -> StudyJourneyKnowledgePage(media, textBlocks, photoTreatment, colors, onOpenAttachment)
        "tip-note" -> StudyJourneyTipPage(media, textBlocks, photoTreatment, colors, onOpenAttachment)
        "photo-strip" -> StudyJourneyPhotoStripPage(media, textBlocks, photoTreatment, colors, onOpenAttachment)
        "closing-photo" -> StudyJourneyClosingPage(media, textBlocks, photoTreatment, colors, onOpenAttachment)
        "checklist-note" -> StudyJourneyChecklistPage(media, textBlocks, photoTreatment, colors, onOpenAttachment)
        "route-board" -> StudyJourneyRouteBoardPage(media, textBlocks, photoTreatment, colors, onOpenAttachment)
        "mission-card" -> StudyJourneyMissionCardPage(media, structuredBlocks, photoTreatment, colors, onOpenAttachment)
        "exhibit-grid" -> StudyJourneyExhibitGridPage(media, structuredBlocks, photoTreatment, colors, onOpenAttachment)
        "process-flow" -> StudyJourneyProcessFlowPage(media, structuredBlocks, photoTreatment, colors, onOpenAttachment)
        "question-thread" -> StudyJourneyKnowledgePage(media, textBlocks, photoTreatment, colors, onOpenAttachment)
        "field-observation" -> StudyJourneyKnowledgePage(media, textBlocks, photoTreatment, colors, onOpenAttachment)
        "detail-lens" -> StudyJourneyDetailLensPage(
            media = media,
            lens = StudyJourneyDetailLens(
                overview = textBlocks.firstOrNull()?.text.orEmpty(),
                details = textBlocks.drop(1).take(3).mapIndexed { index, block ->
                    StudyJourneyLabeledEntry("细节 ${index + 1}", block.text)
                }
            ),
            photoTreatment = photoTreatment,
            colors = colors,
            onOpenAttachment = onOpenAttachment
        )
        else -> StudyJourneyStandardPage(media, textBlocks, pattern, photoTreatment, colors, onOpenAttachment)
    }
}

@Composable
private fun StudyJourneyStandardPage(
    media: StudyJourneySectionMedia,
    textBlocks: List<StudyJourneyContentBlock>,
    pattern: String,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        if (media.attachments.size >= 2 && pattern == "two-photo") {
            StudyJourneyPhotoRow(media.attachments.take(2), 196, photoTreatment, onOpenAttachment)
        } else {
            StudyJourneyPrimaryPhoto(media.attachments.firstOrNull(), 204, photoTreatment, colors, onOpenAttachment)
        }
        StudyJourneyStageText(textBlocks, colors, maximumBlocks = 2)
    }
}

@Composable
private fun StudyJourneySplitPage(
    media: StudyJourneySectionMedia,
    textBlocks: List<StudyJourneyContentBlock>,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StudyJourneyPrimaryPhoto(
                media.attachments.firstOrNull(),
                height = 300,
                photoTreatment = photoTreatment,
                colors = colors,
                onOpenAttachment = onOpenAttachment,
                modifier = Modifier.weight(1.05f).fillMaxHeight()
            )
            Column(modifier = Modifier.weight(.95f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudyJourneyStageText(textBlocks, colors, maximumBlocks = 3)
            }
        }
    }
}

@Composable
private fun StudyJourneyQuotePage(
    media: StudyJourneySectionMedia,
    textBlocks: List<StudyJourneyContentBlock>,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    val attachment = media.attachments.firstOrNull()
    Box(Modifier.fillMaxSize().background(colors.ink)) {
        StudyJourneyPrimaryPhoto(
            attachment,
            height = 500,
            photoTreatment = photoTreatment,
            colors = colors,
            onOpenAttachment = onOpenAttachment,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .08f), Color.Black.copy(alpha = .74f)))))
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp, 48.dp, 20.dp, 42.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                text = media.section.sequenceNumber.toString().padStart(2, '0'),
                color = Color.White.copy(alpha = .72f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(media.section.title, color = Color.White, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
            textBlocks.firstOrNull()?.let { block ->
                Text(
                    text = block.text,
                    color = Color.White.copy(alpha = .92f),
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StudyJourneyKnowledgePage(
    media: StudyJourneySectionMedia,
    textBlocks: List<StudyJourneyContentBlock>,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        StudyJourneyPrimaryPhoto(media.attachments.firstOrNull(), 185, photoTreatment, colors, onOpenAttachment)
        Surface(shape = RoundedCornerShape(6.dp), color = colors.soft.copy(alpha = .72f)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("现场解读", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                StudyJourneyStageText(textBlocks, colors, maximumBlocks = 2)
            }
        }
    }
}

@Composable
private fun StudyJourneyTipPage(
    media: StudyJourneySectionMedia,
    textBlocks: List<StudyJourneyContentBlock>,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        StudyJourneyPrimaryPhoto(media.attachments.firstOrNull(), 170, photoTreatment, colors, onOpenAttachment)
        textBlocks.take(3).forEachIndexed { index, block ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
                Surface(shape = CircleShape, color = colors.primary) {
                    Text(
                        text = "${index + 1}",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(block.text, modifier = Modifier.weight(1f), color = colors.ink, fontSize = 11.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StudyJourneyPhotoStripPage(
    media: StudyJourneySectionMedia,
    textBlocks: List<StudyJourneyContentBlock>,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        Row(modifier = Modifier.fillMaxWidth().height(230.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            media.attachments.take(3).forEachIndexed { index, attachment ->
                StudyJourneyPhoto(
                    attachment = attachment,
                    modifier = Modifier.weight(if (index == 1) 1.18f else .91f).fillMaxHeight(),
                    treatment = photoTreatment,
                    onOpen = { onOpenAttachment(attachment) }
                )
            }
        }
        StudyJourneyStageText(textBlocks, colors, maximumBlocks = 2)
    }
}

@Composable
private fun StudyJourneyClosingPage(
    media: StudyJourneySectionMedia,
    textBlocks: List<StudyJourneyContentBlock>,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Box(Modifier.fillMaxSize().background(colors.paper)) {
        StudyJourneyPrimaryPhoto(
            media.attachments.firstOrNull(),
            height = 500,
            photoTreatment = photoTreatment,
            colors = colors,
            onOpenAttachment = onOpenAttachment,
            modifier = Modifier.fillMaxSize().padding(bottom = 112.dp)
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(colors.surface).padding(20.dp, 16.dp, 20.dp, 38.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(media.section.title, color = colors.ink, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            textBlocks.firstOrNull()?.let { block ->
                Text(block.text, color = colors.muted, fontSize = 11.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StudyJourneyChecklistPage(
    media: StudyJourneySectionMedia,
    textBlocks: List<StudyJourneyContentBlock>,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        StudyJourneyPrimaryPhoto(media.attachments.firstOrNull(), 142, photoTreatment, colors, onOpenAttachment)
        textBlocks.take(4).forEach { block ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(13.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = Color.White, fontSize = 8.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = block.text,
                    modifier = Modifier.weight(1f),
                    color = colors.ink,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StudyJourneyRouteBoardPage(
    media: StudyJourneySectionMedia,
    textBlocks: List<StudyJourneyContentBlock>,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.width(124.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val photos = media.attachments.take(2)
                if (photos.isEmpty()) {
                    StudyJourneySectionArtwork(colors, Modifier.fillMaxSize())
                } else {
                    photos.forEach { attachment ->
                        StudyJourneyPhoto(
                            attachment = attachment,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            treatment = photoTreatment,
                            onOpen = { onOpenAttachment(attachment) }
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                textBlocks.take(4).forEachIndexed { index, block ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                        Text(
                            text = (index + 1).toString().padStart(2, '0'),
                            color = colors.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = block.text,
                            modifier = Modifier.weight(1f),
                            color = if (index == 0) colors.ink else colors.muted,
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyJourneyMissionCardPage(
    media: StudyJourneySectionMedia,
    contentBlocks: List<StudyJourneyContentBlock>,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        media.attachments.firstOrNull()?.let { attachment ->
            StudyJourneyPhoto(
                attachment = attachment,
                modifier = Modifier.fillMaxWidth().height(126.dp),
                treatment = photoTreatment,
                onOpen = { onOpenAttachment(attachment) }
            )
        }
        Surface(shape = RoundedCornerShape(6.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("本段观察任务", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                contentBlocks.take(5).forEachIndexed { index, block ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Surface(shape = CircleShape, color = colors.soft) {
                            Text(
                                text = "${index + 1}",
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                color = colors.primary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = block.text,
                            modifier = Modifier.weight(1f),
                            color = if (block.type == StudyJourneyBlockType.SUBHEADING) colors.primary else colors.ink,
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyJourneyExhibitGridPage(
    media: StudyJourneySectionMedia,
    contentBlocks: List<StudyJourneyContentBlock>,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        val photos = media.attachments.take(2)
        if (photos.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEachIndexed { index, attachment ->
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(colors.surface).padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        StudyJourneyPhoto(
                            attachment = attachment,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            treatment = photoTreatment,
                            onOpen = { onOpenAttachment(attachment) }
                        )
                        Text(
                            text = contentBlocks.getOrNull(index)?.text ?: "现场展品 ${index + 1}",
                            color = colors.ink,
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            contentBlocks.drop(photos.size).firstOrNull()?.let { block ->
                Text(block.text, color = colors.muted, fontSize = 11.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        } else {
            StudyJourneySectionArtwork(colors, Modifier.fillMaxWidth().height(190.dp))
            StudyJourneyStageText(contentBlocks, colors, maximumBlocks = 3)
        }
    }
}

@Composable
private fun StudyJourneyProcessFlowPage(
    media: StudyJourneySectionMedia,
    contentBlocks: List<StudyJourneyContentBlock>,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    val restricted = contentBlocks.any { block ->
        listOf("禁止拍摄", "不能拍照", "保密", "手机袋").any { keyword -> block.text.contains(keyword) }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        val attachment = media.attachments.firstOrNull()
        if (attachment != null) {
            StudyJourneyPhoto(
                attachment = attachment,
                modifier = Modifier.fillMaxWidth().height(128.dp),
                treatment = photoTreatment,
                onOpen = { onOpenAttachment(attachment) }
            )
        } else {
            Surface(shape = RoundedCornerShape(6.dp), color = colors.soft, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (restricted) Icons.Default.Lock else Icons.Default.AutoStories, null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    Text(
                        text = if (restricted) "现场限制拍摄，以讲解与流程记录为准" else "本段未关联现场照片",
                        color = colors.ink,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            contentBlocks.take(5).forEachIndexed { index, block ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(18.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (index == 0) colors.accent else colors.primary))
                        if (index < contentBlocks.take(5).lastIndex) {
                            Box(Modifier.width(2.dp).height(42.dp).background(colors.primary.copy(alpha = .25f)))
                        }
                    }
                    Column(modifier = Modifier.weight(1f).padding(bottom = 8.dp)) {
                        Text("流程 ${index + 1}", color = colors.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = block.text,
                            color = if (block.type == StudyJourneyBlockType.SUBHEADING) colors.primary else colors.ink,
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

internal data class StudyJourneyLabeledEntry(
    val label: String,
    val text: String
)

internal data class StudyJourneyFieldObservation(
    val entries: List<StudyJourneyLabeledEntry>
)

internal fun parseStudyJourneyFieldObservation(
    contentBlocks: List<StudyJourneyContentBlock>
): StudyJourneyFieldObservation? {
    val entries = mutableListOf<StudyJourneyLabeledEntry>()
    var activeLabel: String? = null
    val activeText = mutableListOf<String>()

    fun flush() {
        val label = activeLabel ?: return
        val text = activeText.joinToString(" ").trim()
        if (text.isNotBlank()) entries += StudyJourneyLabeledEntry(label, text)
        activeText.clear()
    }

    contentBlocks.forEach { block ->
        if (block.type == StudyJourneyBlockType.SUBHEADING) {
            val label = when {
                block.text.contains("观察对象") -> "观察对象"
                block.text.contains("环境") || block.text.contains("生境") || block.text.contains("位置") -> "现场环境"
                block.text.contains("特征") || block.text.contains("形态") || block.text.contains("所见") -> "可见特征"
                block.text.contains("资源") || block.text.contains("威胁") || block.text.contains("条件") || block.text.contains("影响") -> "资源或威胁"
                block.text.contains("继续观察") || block.text.contains("下一步") || block.text.contains("观察问题") -> "继续观察"
                else -> null
            }
            flush()
            activeLabel = label
        } else if (activeLabel != null && block.text.isNotBlank()) {
            activeText += block.text
        }
    }
    flush()

    val distinctEntries = entries.distinctBy { it.label }.take(4)
    return distinctEntries.takeIf { it.size >= 2 }?.let(::StudyJourneyFieldObservation)
}

internal data class StudyJourneyDetailLens(
    val overview: String,
    val details: List<StudyJourneyLabeledEntry>
)

internal fun parseStudyJourneyDetailLens(
    contentBlocks: List<StudyJourneyContentBlock>
): StudyJourneyDetailLens? {
    var activeLabel: String? = null
    val activeText = mutableListOf<String>()
    val entries = mutableListOf<StudyJourneyLabeledEntry>()

    fun flush() {
        val label = activeLabel ?: return
        val text = activeText.joinToString(" ").trim()
        if (text.isNotBlank()) entries += StudyJourneyLabeledEntry(label, text)
        activeText.clear()
    }

    contentBlocks.forEach { block ->
        if (block.type == StudyJourneyBlockType.SUBHEADING) {
            val label = when {
                block.text.contains("整体") || block.text.contains("全景") -> "整体观察"
                block.text.contains("细节") || block.text.contains("局部") -> block.text
                block.text.contains("材料") -> "材料细节"
                block.text.contains("构造") || block.text.contains("结构") -> "构造细节"
                block.text.contains("纹理") || block.text.contains("痕迹") -> "表面细节"
                else -> null
            }
            flush()
            activeLabel = label
        } else if (activeLabel != null && block.text.isNotBlank()) {
            activeText += block.text
        }
    }
    flush()

    val overview = entries.firstOrNull { it.label == "整体观察" }?.text ?: return null
    val details = entries.filterNot { it.label == "整体观察" }.take(3)
    return details.takeIf { it.isNotEmpty() }?.let { StudyJourneyDetailLens(overview, it) }
}

@Composable
private fun StudyJourneyFieldObservationPage(
    media: StudyJourneySectionMedia,
    observation: StudyJourneyFieldObservation,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        StudyJourneyPhoto(
            attachment = media.attachments.first(),
            modifier = Modifier.fillMaxWidth().height(if (media.attachments.size > 1) 142.dp else 198.dp),
            treatment = photoTreatment,
            onOpen = { onOpenAttachment(media.attachments.first()) }
        )
        if (media.attachments.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                media.attachments.drop(1).take(3).forEach { attachment ->
                    StudyJourneyPhoto(
                        attachment = attachment,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        treatment = photoTreatment,
                        onOpen = { onOpenAttachment(attachment) }
                    )
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            observation.entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = colors.primary.copy(alpha = .12f))
                Text(entry.label, color = colors.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(entry.text, color = colors.ink, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun StudyJourneyDetailLensPage(
    media: StudyJourneySectionMedia,
    lens: StudyJourneyDetailLens,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        StudyJourneyPhoto(
            attachment = media.attachments.first(),
            modifier = Modifier.fillMaxWidth().height(176.dp),
            treatment = photoTreatment,
            onOpen = { onOpenAttachment(media.attachments.first()) }
        )
        if (lens.overview.isNotBlank()) {
            Text(
                text = lens.overview,
                color = colors.ink,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            media.attachments.drop(1).take(3).forEachIndexed { index, attachment ->
                val entry = lens.details.getOrNull(index)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    StudyJourneyPhoto(
                        attachment = attachment,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        treatment = photoTreatment,
                        onOpen = { onOpenAttachment(attachment) }
                    )
                    Text(
                        text = entry?.label ?: "局部 ${index + 1}",
                        color = colors.primary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    entry?.text?.let { detailText ->
                        Text(
                            text = detailText,
                            color = colors.muted,
                            fontSize = 9.sp,
                            lineHeight = 13.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

internal data class StudyJourneyQuestionThread(
    val question: String,
    val answer: String?,
    val observation: String?,
    val followUp: String?
)

internal fun parseStudyJourneyQuestionThread(
    contentBlocks: List<StudyJourneyContentBlock>
): StudyJourneyQuestionThread? {
    val blocks = contentBlocks.filter { block ->
        block.type != StudyJourneyBlockType.PHOTO && block.text.isNotBlank()
    }
    val questionIndex = blocks.indexOfFirst { block ->
        block.text.contains('？') || block.text.contains('?')
    }
    if (questionIndex < 0) return null

    val question = blocks[questionIndex].text
        .replace(Regex("^(?:问题|现场问题|提问)\\s*[｜|:：-]\\s*"), "")
        .trim()
        .takeIf(String::isNotBlank)
        ?: return null
    val answerParts = mutableListOf<String>()
    val observationParts = mutableListOf<String>()
    val followUpParts = mutableListOf<String>()
    var activeNode = "answer"

    blocks.drop(questionIndex + 1).forEach { block ->
        if (block.type == StudyJourneyBlockType.SUBHEADING) {
            activeNode = when {
                block.text.contains("观察") || block.text.contains("印证") -> "observation"
                block.text.contains("继续") || block.text.contains("追问") || block.text.contains("未解") -> "follow-up"
                block.text.contains("回答") || block.text.contains("讲解") -> "answer"
                else -> activeNode
            }
        } else {
            when (activeNode) {
                "observation" -> observationParts += block.text
                "follow-up" -> followUpParts += block.text
                else -> answerParts += block.text
            }
        }
    }

    if (answerParts.isEmpty() && observationParts.isEmpty()) return null
    return StudyJourneyQuestionThread(
        question = question,
        answer = answerParts.joinToString("\n").takeIf(String::isNotBlank),
        observation = observationParts.joinToString("\n").takeIf(String::isNotBlank),
        followUp = followUpParts.joinToString("\n").takeIf(String::isNotBlank)
    )
}

@Composable
private fun StudyJourneyQuestionThreadPage(
    media: StudyJourneySectionMedia,
    thread: StudyJourneyQuestionThread,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(20.dp, 48.dp, 20.dp, 38.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StudyJourneyStageHeader(media.section, colors)
        Text("现场问题", color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            text = thread.question,
            color = colors.ink,
            fontSize = 20.sp,
            lineHeight = 27.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        HorizontalDivider(color = colors.primary.copy(alpha = .18f))
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            val nodes = buildList {
                thread.answer?.let { add("现场回答" to it) }
                thread.observation?.let { add("观察印证" to it) }
                thread.followUp?.let { add("继续探索" to it) }
            }
            nodes.forEachIndexed { index, (label, text) ->
                StudyJourneyQuestionThreadNode(
                    label = label,
                    text = text,
                    isLast = index == nodes.lastIndex && media.attachments.isEmpty(),
                    colors = colors
                )
                if (label == "观察印证") {
                    media.attachments.firstOrNull()?.let { attachment ->
                        StudyJourneyPhoto(
                            attachment = attachment,
                            modifier = Modifier.fillMaxWidth().height(126.dp).padding(start = 24.dp, bottom = 10.dp),
                            treatment = photoTreatment,
                            onOpen = { onOpenAttachment(attachment) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyJourneyQuestionThreadNode(
    label: String,
    text: String,
    isLast: Boolean,
    colors: StudyJourneyColors
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(14.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(colors.primary))
            if (!isLast) {
                Box(Modifier.width(2.dp).height(54.dp).background(colors.primary.copy(alpha = .22f)))
            }
        }
        Column(modifier = Modifier.weight(1f).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = colors.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(text, color = colors.ink, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun StudyJourneyTipsCarouselPage(
    tips: List<String>,
    colors: StudyJourneyColors
) {
    Column(
        modifier = Modifier.fillMaxSize().background(colors.paper).padding(22.dp, 54.dp, 22.dp, 42.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("出发前，记住这些", color = colors.ink, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
        Text("轻装出发，也把重要提醒带在身边。", color = colors.muted, fontSize = 11.sp, lineHeight = 17.sp)
        Surface(shape = RoundedCornerShape(6.dp), color = colors.surface) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tips.take(7).forEach { tip ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(16.dp))
                        Text(tip, modifier = Modifier.weight(1f), color = colors.ink, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyJourneyStageHeader(section: StudyJourneySection, colors: StudyJourneyColors) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = section.sequenceNumber.toString().padStart(2, '0'),
            color = colors.primary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.title,
                color = colors.ink,
                fontSize = 19.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (section.subtitle.isNotBlank()) {
                Text(section.subtitle, color = colors.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StudyJourneyStageText(
    textBlocks: List<StudyJourneyContentBlock>,
    colors: StudyJourneyColors,
    maximumBlocks: Int
) {
    textBlocks.take(maximumBlocks).forEachIndexed { index, block ->
        Text(
            text = block.text,
            color = if (block.type == StudyJourneyBlockType.QUOTE) colors.primary else if (index == 0) colors.ink else colors.muted,
            fontSize = if (index == 0) 12.sp else 11.sp,
            lineHeight = if (index == 0) 19.sp else 17.sp,
            maxLines = if (index == 0) 4 else 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StudyJourneyPhotoRow(
    attachments: List<MeetingAttachment>,
    height: Int,
    photoTreatment: String,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().height(height.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { attachment ->
            StudyJourneyPhoto(
                attachment = attachment,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                treatment = photoTreatment,
                onOpen = { onOpenAttachment(attachment) }
            )
        }
    }
}

@Composable
private fun StudyJourneyPrimaryPhoto(
    attachment: MeetingAttachment?,
    height: Int,
    photoTreatment: String,
    colors: StudyJourneyColors,
    onOpenAttachment: (MeetingAttachment) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().height(height.dp)
) {
    if (attachment == null) {
        StudyJourneySectionArtwork(colors, modifier)
    } else {
        StudyJourneyPhoto(
            attachment = attachment,
            modifier = modifier,
            treatment = photoTreatment,
            onOpen = { onOpenAttachment(attachment) }
        )
    }
}

@Composable
private fun StudyJourneySectionArtwork(colors: StudyJourneyColors, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(6.dp)).background(colors.soft)) {
        Canvas(Modifier.fillMaxSize()) {
            val centerY = size.height * .58f
            drawLine(colors.primary.copy(alpha = .35f), Offset(size.width * .12f, centerY), Offset(size.width * .88f, centerY), 4f)
            repeat(4) { index ->
                val x = size.width * (.18f + index * .21f)
                drawCircle(colors.surface, 15f, Offset(x, centerY))
                drawCircle(if (index == 0) colors.accent else colors.primary, 9f, Offset(x, centerY))
            }
        }
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp).size(32.dp)
        )
    }
}

@Composable
private fun StudyJourneyArticleBody(
    article: StudyJourneyArticle,
    attachments: List<MeetingAttachment>,
    sectionMedia: List<StudyJourneySectionMedia>,
    colors: StudyJourneyColors,
    photoTreatment: String,
    onOpenAttachment: (MeetingAttachment) -> Unit,
    onAddImages: () -> Unit,
    onCaptureImage: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.surface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoStories, null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("研学游记", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${attachments.size} 张现场照片", color = colors.muted, fontSize = 10.sp)
                    IconButton(onClick = onCaptureImage, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.CameraAlt, "拍摄旅程照片", tint = colors.primary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onAddImages, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.AddPhotoAlternate, "添加旅程照片", tint = colors.primary, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    text = article.title,
                    color = colors.ink,
                    fontSize = 24.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Bold
                )
                article.lead.filter { it.type != StudyJourneyBlockType.PHOTO }.take(3).forEach { block ->
                    Text(
                        text = block.text,
                        color = if (block.type == StudyJourneyBlockType.QUOTE) colors.primary else colors.muted,
                        fontSize = 13.sp,
                        lineHeight = 21.sp
                    )
                }
            }

            if (article.routeStops.isNotEmpty()) {
                StudyJourneyRouteBand(article.routeStops, colors)
            }

            sectionMedia.forEach { media ->
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    color = colors.soft
                )
                StudyJourneyBodySection(
                    media = media,
                    allAttachments = attachments,
                    colors = colors,
                    photoTreatment = photoTreatment,
                    onOpenAttachment = onOpenAttachment
                )
            }

            if (article.reflection.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 18.dp), color = colors.soft)
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Default.Lightbulb, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        Text("这一程，留下什么", color = colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    article.reflection.filter { it.type != StudyJourneyBlockType.PHOTO }.forEach { block ->
                        Text(block.text, color = colors.muted, fontSize = 13.sp, lineHeight = 21.sp)
                    }
                }
            }

            if (article.tips.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.soft.copy(alpha = .58f))
                        .padding(horizontal = 18.dp, vertical = 15.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("带上这些再出发", color = colors.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    article.tips.take(6).forEachIndexed { index, tip ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Text("${index + 1}", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(tip, color = colors.ink, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }

            if (article.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    article.tags.forEach { tag ->
                        Text(
                            text = tag,
                            color = colors.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyJourneyRouteBand(routeStops: List<String>, colors: StudyJourneyColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.paper)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Route, null, tint = colors.primary, modifier = Modifier.size(17.dp))
            Text("这次怎么走", color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            routeStops.forEachIndexed { index, stop ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (index == 0) colors.accent else colors.primary)
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = stop,
                        color = colors.muted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (index < routeStops.lastIndex) {
                    Box(Modifier.width(24.dp).height(2.dp).background(colors.primary.copy(alpha = .32f)))
                }
            }
        }
    }
}

@Composable
private fun StudyJourneyBodySection(
    media: StudyJourneySectionMedia,
    allAttachments: List<MeetingAttachment>,
    colors: StudyJourneyColors,
    photoTreatment: String,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    val explicitPhotoNumbers = media.section.blocks.mapNotNull(StudyJourneyContentBlock::photoNumber).toSet()
    val explicitIds = explicitPhotoNumbers.mapNotNull { number -> allAttachments.getOrNull(number - 1)?.id }.toSet()
    val supplemental = media.attachments.filterNot { it.id in explicitIds }
    Column(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = media.section.sequenceNumber.toString().padStart(2, '0'),
                color = colors.primary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(media.section.title, color = colors.ink, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
                if (media.section.subtitle.isNotBlank()) {
                    Text(media.section.subtitle, color = colors.muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }

        var insertedSupplemental = false
        media.section.blocks.forEachIndexed { index, block ->
            when (block.type) {
                StudyJourneyBlockType.PHOTO -> {
                    block.photoNumber?.let { number ->
                        allAttachments.getOrNull(number - 1)?.let { attachment ->
                            StudyJourneyInlineMedia(
                                attachments = listOf(attachment),
                                caption = block.caption,
                                photoTreatment = photoTreatment,
                                onOpenAttachment = onOpenAttachment
                            )
                        }
                    }
                }
                StudyJourneyBlockType.SUBHEADING -> {
                    Text(block.text, color = colors.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                StudyJourneyBlockType.QUOTE -> {
                    Text(
                        text = block.text,
                        color = colors.primary,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
                StudyJourneyBlockType.PARAGRAPH -> {
                    Text(block.text, color = colors.ink, fontSize = 13.sp, lineHeight = 22.sp)
                }
            }
            if (!insertedSupplemental && explicitPhotoNumbers.isEmpty() && supplemental.isNotEmpty() && index == 0) {
                StudyJourneyInlineMedia(
                    attachments = supplemental.take(2),
                    photoTreatment = photoTreatment,
                    onOpenAttachment = onOpenAttachment
                )
                insertedSupplemental = true
            }
        }
        if (!insertedSupplemental && supplemental.isNotEmpty()) {
            StudyJourneyInlineMedia(
                attachments = supplemental.take(2),
                photoTreatment = photoTreatment,
                onOpenAttachment = onOpenAttachment
            )
        }
    }
}

@Composable
private fun StudyJourneyInlineMedia(
    attachments: List<MeetingAttachment>,
    caption: String = "",
    photoTreatment: String,
    onOpenAttachment: (MeetingAttachment) -> Unit
) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (attachments.size == 1) {
            val attachment = attachments.first()
            StudyJourneyPhoto(
                attachment = attachment,
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                treatment = photoTreatment,
                onOpen = { onOpenAttachment(attachment) }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(170.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                attachments.take(2).forEach { attachment ->
                    StudyJourneyPhoto(
                        attachment = attachment,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        treatment = photoTreatment,
                        onOpen = { onOpenAttachment(attachment) }
                    )
                }
            }
        }
        if (caption.isNotBlank()) {
            Text(
                text = caption,
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF68788A),
                fontSize = 10.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StudyJourneyPhoto(
    attachment: MeetingAttachment,
    modifier: Modifier = Modifier,
    treatment: String = "clean",
    onOpen: () -> Unit
) {
    val bitmap = remember(attachment.localPath) {
        OrientedImageDecoder.decode(File(attachment.localPath), maximumDimension = 1_200)
    }
    val cornerRadius = when (treatment) {
        "editorial" -> 2.dp
        "notebook" -> 3.dp
        "soft-frame" -> 8.dp
        else -> 5.dp
    }
    val imagePadding = if (treatment == "notebook") 4.dp else 0.dp
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(if (treatment == "notebook") Color.White else Color(0xFFE8EEF4))
            .padding(imagePadding)
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            Icon(Icons.Default.AddPhotoAlternate, null, tint = Color(0xFF7A8C9D), modifier = Modifier.size(28.dp))
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape((cornerRadius - imagePadding).coerceAtLeast(0.dp)))
            )
        }
    }
}

private fun StudyJourneyPalette.toStudyJourneyColors(): StudyJourneyColors = StudyJourneyColors(
    paper = paper.toJourneyColor(Color(0xFFF4F8FC)),
    surface = surface.toJourneyColor(Color.White),
    ink = ink.toJourneyColor(Color(0xFF17324D)),
    muted = muted.toJourneyColor(Color(0xFF5F7388)),
    primary = primary.toJourneyColor(Color(0xFF106EBE)),
    secondary = secondary.toJourneyColor(Color(0xFF3A96DD)),
    accent = accent.toJourneyColor(Color(0xFF2D7D9A)),
    soft = soft.toJourneyColor(Color(0xFFDDEBF7))
)

private fun String.toJourneyColor(fallback: Color): Color = runCatching {
    Color(AndroidColor.parseColor(this))
}.getOrDefault(fallback)

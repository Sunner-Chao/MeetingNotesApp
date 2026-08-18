package com.oa.automation.ui.screen.report

import android.content.Context
import com.google.gson.Gson

internal data class StudyJourneyStyleCatalog(
    val version: Int = 2,
    val defaultStyleId: String = "route-atlas",
    val materials: StudyJourneyMaterialLibrary = StudyJourneyMaterialLibrary(),
    val styles: List<StudyJourneyVisualStyle> = emptyList()
)

internal data class StudyJourneyMaterialLibrary(
    val coverLayouts: List<StudyJourneyMaterialDefinition> = emptyList(),
    val pagePatterns: List<StudyJourneyMaterialDefinition> = emptyList(),
    val photoTreatments: List<StudyJourneyMaterialDefinition> = emptyList()
)

internal data class StudyJourneyMaterialDefinition(
    val id: String = "",
    val displayName: String = "",
    val description: String = ""
)

internal data class StudyJourneyVisualStyle(
    val id: String = "route-atlas",
    val displayName: String = "路线图鉴",
    val minimumSections: Int = 1,
    val minimumPhotos: Int = 0,
    val priority: Int = 0,
    val keywords: List<String> = emptyList(),
    val coverLayout: String = "route-map",
    val photoCadence: String = "stage-first",
    val photoTreatment: String = "clean",
    val pagePatterns: List<String> = listOf("full-photo", "split-note", "two-photo"),
    val carouselExtras: List<String> = emptyList(),
    val scoring: StudyJourneyStyleScoring = StudyJourneyStyleScoring(),
    val palette: StudyJourneyPalette = StudyJourneyPalette()
)

internal data class StudyJourneyStyleScoring(
    val keywordWeight: Int = 6,
    val sectionCountWeight: Int = 0,
    val routeStopWeight: Int = 0,
    val photoCountWeight: Int = 0,
    val photoSurplusWeight: Int = 0
)

internal data class StudyJourneyPalette(
    val paper: String = "#F4F8FC",
    val surface: String = "#FFFFFF",
    val ink: String = "#17324D",
    val muted: String = "#5F7388",
    val primary: String = "#106EBE",
    val secondary: String = "#3A96DD",
    val accent: String = "#2D7D9A",
    val soft: String = "#DDEBF7"
)

internal object StudyJourneyStyleCatalogLoader {
    private const val ASSET_NAME = "study_journey_styles.json"

    fun load(context: Context): StudyJourneyStyleCatalog = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
            Gson().fromJson(reader, StudyJourneyStyleCatalog::class.java)
        }
    }.getOrNull()
        ?.takeIf { it.styles.isNotEmpty() }
        ?: StudyJourneyStyleCatalog(styles = listOf(StudyJourneyVisualStyle()))
}

internal fun selectStudyJourneyStyle(
    catalog: StudyJourneyStyleCatalog,
    article: StudyJourneyArticle,
    attachmentCount: Int
): StudyJourneyVisualStyle {
    val candidates = catalog.styles.filter { style ->
        article.sections.size >= style.minimumSections && attachmentCount >= style.minimumPhotos
    }
    val defaultStyle = catalog.styles.firstOrNull { it.id == catalog.defaultStyleId }
        ?: catalog.styles.firstOrNull()
        ?: StudyJourneyVisualStyle()
    if (candidates.isEmpty()) return defaultStyle

    val searchable = article.searchableText()
    return candidates.maxByOrNull { style ->
        val rules = style.scoring
        style.priority +
            style.keywords.count { keyword -> searchable.contains(keyword, ignoreCase = true) } * rules.keywordWeight +
            article.sections.size * rules.sectionCountWeight +
            article.routeStops.size * rules.routeStopWeight +
            attachmentCount * rules.photoCountWeight +
            (attachmentCount - article.sections.size).coerceAtLeast(0) * rules.photoSurplusWeight
    } ?: defaultStyle
}

internal fun resolveStudyJourneyPagePattern(
    style: StudyJourneyVisualStyle,
    stagePageIndex: Int,
    photoCount: Int
): String {
    val patterns = style.pagePatterns.ifEmpty { listOf("full-photo") }
    val requested = patterns[(stagePageIndex - 1).coerceAtLeast(0) % patterns.size]
    return when {
        requested == "two-photo" && photoCount < 2 -> "full-photo"
        requested == "photo-strip" && photoCount < 3 -> if (photoCount >= 2) "two-photo" else "full-photo"
        requested == "exhibit-grid" && photoCount == 0 -> "knowledge-note"
        requested == "field-observation" && photoCount == 0 -> "knowledge-note"
        requested == "detail-lens" && photoCount < 3 -> if (photoCount >= 2) "two-photo" else "full-photo"
        else -> requested
    }
}

package com.oa.automation.domain.model

import com.google.gson.annotations.SerializedName

data class CommunityReview(
    val status: String = "pending",
    val reason: String = "",
    @SerializedName("reviewed_at") val reviewedAt: Long? = null
)

data class PublicCommunityPost(
    val id: String,
    val title: String,
    val content: String,
    @SerializedName("ai_assisted") val aiAssisted: Boolean = false,
    @SerializedName("redacted_coordinate_count") val redactedCoordinateCount: Int = 0,
    @SerializedName("published_at") val publishedAt: Long,
    @SerializedName("author_label") val authorLabel: String = "研学同行者",
    val media: List<PublicCommunityMedia> = emptyList()
)

data class PublicCommunityMedia(
    val id: String,
    @SerializedName("thumbnail_url") val thumbnailUrl: String,
    @SerializedName("content_url") val contentUrl: String,
    @SerializedName("mime_type") val mimeType: String
)

data class MyCommunityPost(
    val id: String,
    val title: String,
    val content: String,
    val status: String,
    @SerializedName("moderation_status") val moderationStatus: String,
    val review: CommunityReview = CommunityReview(),
    @SerializedName("updated_at") val updatedAt: Long,
    @SerializedName("published_at") val publishedAt: Long? = null
)

data class CommunityPostPage<T>(
    val items: List<T> = emptyList(),
    @SerializedName("next_cursor") val nextCursor: String? = null
)

data class CommunityReport(
    val id: String,
    @SerializedName("post_id") val postId: String,
    val category: String,
    val reason: String = "",
    val status: String = "open",
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("updated_at") val updatedAt: Long = 0
)

data class CommunityModerationReport(
    val category: String,
    val reason: String = "",
    @SerializedName("created_at") val createdAt: Long = 0
)

data class CommunityModerationItem(
    val id: String,
    val title: String,
    val content: String,
    @SerializedName("published_at") val publishedAt: Long = 0,
    val review: CommunityReview = CommunityReview(),
    @SerializedName("open_report_count") val openReportCount: Int = 0,
    val reports: List<CommunityModerationReport> = emptyList()
)

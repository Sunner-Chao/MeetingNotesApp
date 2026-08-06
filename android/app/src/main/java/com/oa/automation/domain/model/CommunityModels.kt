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
    val media: List<PublicCommunityMedia> = emptyList(),
    val destination: String = "",
    @SerializedName("travel_date") val travelDate: String = "",
    @SerializedName("travel_days") val travelDays: Int = 0,
    val stages: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val pois: List<String> = emptyList(),
    @SerializedName("like_count") val likeCount: Int = 0,
    @SerializedName("comment_count") val commentCount: Int = 0
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
    @SerializedName("next_cursor") val nextCursor: String? = null,
    val facets: CommunityFacets? = null
)

data class CommunityFacets(
    val destinations: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val pois: List<String> = emptyList()
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

data class CommunityInteractionState(
    @SerializedName("post_id") val postId: String,
    val liked: Boolean = false,
    val bookmarked: Boolean = false,
    @SerializedName("like_count") val likeCount: Int = 0,
    @SerializedName("comment_count") val commentCount: Int = 0
)

data class CommunityComment(
    val id: String,
    @SerializedName("post_id") val postId: String,
    val content: String,
    @SerializedName("author_label") val authorLabel: String = "研学同行者",
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("can_delete") val canDelete: Boolean = false
)

data class CommunityDeleteResult(
    val id: String,
    val status: String
)

data class CommunityCommentReport(
    val id: String,
    @SerializedName("comment_id") val commentId: String,
    val category: String,
    val reason: String = "",
    val status: String = "open",
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("updated_at") val updatedAt: Long = 0
)

data class CommunityCommentReportQueueItem(
    val id: String,
    @SerializedName("comment_id") val commentId: String,
    @SerializedName("post_id") val postId: String,
    @SerializedName("post_title") val postTitle: String,
    val content: String,
    @SerializedName("comment_status") val commentStatus: String,
    val category: String,
    val reason: String = "",
    val status: String = "open",
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("updated_at") val updatedAt: Long = 0
)

data class CommunityOperationsSummary(
    @SerializedName("window_hours") val windowHours: Int = 24,
    @SerializedName("generated_at") val generatedAt: Long = 0,
    @SerializedName("allowed_action_count") val allowedActionCount: Int = 0,
    @SerializedName("limited_action_count") val limitedActionCount: Int = 0,
    @SerializedName("pending_post_count") val pendingPostCount: Int = 0,
    @SerializedName("reported_post_count") val reportedPostCount: Int = 0,
    @SerializedName("open_comment_report_count") val openCommentReportCount: Int = 0
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

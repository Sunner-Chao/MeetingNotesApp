package com.oa.automation.domain.model

import com.google.gson.annotations.SerializedName

data class CommunityAvailability(
    @SerializedName("read_enabled") val readEnabled: Boolean = true,
    @SerializedName("write_enabled") val writeEnabled: Boolean = true
)

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
    @SerializedName("comment_count") val commentCount: Int = 0,
    @SerializedName("curation_note") val curationNote: String = "",
    @SerializedName("collection_position") val collectionPosition: Int? = null
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

data class CommunityCollection(
    val id: String,
    val title: String,
    val description: String = "",
    val destination: String = "",
    val theme: String = "",
    @SerializedName("display_order") val displayOrder: Int = 0,
    val status: String = "published",
    @SerializedName("assigned_post_count") val assignedPostCount: Int = 0,
    @SerializedName("visible_post_count") val visiblePostCount: Int = 0,
    @SerializedName("post_count") val postCount: Int = 0,
    @SerializedName("bookmark_count") val bookmarkCount: Int = 0,
    @SerializedName("cover_post_id") val coverPostId: String = "",
    @SerializedName("cover_thumbnail_url") val coverThumbnailUrl: String = "",
    @SerializedName("preview_posts") val previewPosts: List<CommunityCollectionPreview> = emptyList(),
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("updated_at") val updatedAt: Long = 0,
    @SerializedName("published_at") val publishedAt: Long? = null
)

data class CommunityCollectionPreview(
    val id: String,
    val title: String,
    val destination: String = "",
    @SerializedName("curation_note") val curationNote: String = "",
    @SerializedName("thumbnail_url") val thumbnailUrl: String = ""
)

data class CommunityCollectionFacets(
    val destinations: List<String> = emptyList(),
    val themes: List<String> = emptyList()
)

data class CommunityCollectionPage(
    val items: List<CommunityCollection> = emptyList(),
    @SerializedName("next_cursor") val nextCursor: String? = null,
    val facets: CommunityCollectionFacets = CommunityCollectionFacets(),
    @SerializedName("sort_mode") val sortMode: String = "curated",
    @SerializedName("sort_explanation") val sortExplanation: String = ""
)

data class CommunityCollectionInteractionState(
    @SerializedName("collection_id") val collectionId: String,
    val bookmarked: Boolean = false,
    @SerializedName("bookmark_count") val bookmarkCount: Int = 0
)

data class CommunityCollectionShare(
    @SerializedName("collection_id") val collectionId: String,
    val title: String,
    val description: String = "",
    val destination: String = "",
    val theme: String = "",
    @SerializedName("post_count") val postCount: Int = 0,
    @SerializedName("canonical_path") val canonicalPath: String = ""
)

data class CommunityCollectionPost(
    @SerializedName("collection_id") val collectionId: String,
    @SerializedName("post_id") val postId: String,
    val title: String,
    val position: Int = 0,
    @SerializedName("curation_note") val curationNote: String = "",
    @SerializedName("post_status") val postStatus: String = "published",
    @SerializedName("review_status") val reviewStatus: String = "pending",
    val visible: Boolean = false,
    @SerializedName("has_media") val hasMedia: Boolean = false,
    @SerializedName("added_at") val addedAt: Long = 0,
    @SerializedName("updated_at") val updatedAt: Long = 0
)

data class CommunityCollectionBatchResult(
    @SerializedName("collection_id") val collectionId: String,
    val items: List<CommunityCollectionPost> = emptyList()
)

data class CommunityCollectionAdminDetail(
    val id: String,
    val title: String,
    val description: String = "",
    val destination: String = "",
    val theme: String = "",
    @SerializedName("display_order") val displayOrder: Int = 0,
    val status: String = "draft",
    @SerializedName("cover_post_id") val coverPostId: String = "",
    @SerializedName("assigned_post_count") val assignedPostCount: Int = 0,
    @SerializedName("visible_post_count") val visiblePostCount: Int = 0,
    val posts: List<CommunityCollectionPost> = emptyList()
)

data class CommunityCollectionOperationsSummary(
    @SerializedName("generated_at") val generatedAt: Long = 0,
    @SerializedName("total_collection_count") val totalCollectionCount: Int = 0,
    @SerializedName("draft_collection_count") val draftCollectionCount: Int = 0,
    @SerializedName("published_collection_count") val publishedCollectionCount: Int = 0,
    @SerializedName("unpublished_collection_count") val unpublishedCollectionCount: Int = 0,
    @SerializedName("assigned_post_count") val assignedPostCount: Int = 0,
    @SerializedName("visible_post_count") val visiblePostCount: Int = 0,
    @SerializedName("hidden_assignment_count") val hiddenAssignmentCount: Int = 0,
    @SerializedName("published_empty_count") val publishedEmptyCount: Int = 0,
    @SerializedName("collection_bookmark_count") val collectionBookmarkCount: Int = 0
)

data class CommunityCollectionAuditEntry(
    val id: String,
    @SerializedName("collection_id") val collectionId: String,
    @SerializedName("actor_user_id") val actorUserId: String,
    val action: String,
    @SerializedName("post_id") val postId: String = "",
    val detail: String = "",
    @SerializedName("created_at") val createdAt: Long = 0
)

data class CommunityCollectionRemoval(
    @SerializedName("collection_id") val collectionId: String,
    @SerializedName("post_id") val postId: String,
    val status: String
)

data class CommunityCollectionDetail(
    val collection: CommunityCollection,
    val items: List<PublicCommunityPost> = emptyList(),
    @SerializedName("next_cursor") val nextCursor: String? = null,
    val posts: List<CommunityCollectionPost> = emptyList()
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

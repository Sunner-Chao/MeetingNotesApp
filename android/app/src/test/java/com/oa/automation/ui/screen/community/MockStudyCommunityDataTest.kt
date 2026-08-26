package com.oa.automation.ui.screen.community

import java.io.File
import java.net.URI
import com.oa.automation.domain.model.CommunityPostPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockStudyCommunityDataTest {
    @Test
    fun sampleFeedIsRichLicensedAndNavigable() {
        val posts = MockStudyCommunityData.posts
        assertTrue(posts.size >= 8)
        assertTrue(MockStudyCommunityData.collections.size >= 4)
        assertTrue(posts.all { it.media.isNotEmpty() })
        assertTrue(posts.all { it.stages.size >= 4 })
        assertTrue(posts.all { it.content.length >= 300 })
        assertTrue(posts.any { it.media.size >= 4 })
        assertTrue(posts.any { it.travelDays >= 2 })

        val assets = assetsDirectory()
        val attribution = File(assets, "community/mock/ATTRIBUTION.md")
        assertTrue(attribution.isFile)
        val attributionText = attribution.readText()
        posts.flatMap { it.media }.forEach { media ->
            assertTrue(media.thumbnailUrl.startsWith("asset:///community/mock/"))
            val assetPath = media.thumbnailUrl.removePrefix("asset:///")
            val asset = File(assets, assetPath)
            assertTrue("Missing sample image: $assetPath", asset.isFile)
            assertTrue("Empty sample image: $assetPath", asset.length() > 0)
            assertTrue(
                "Missing attribution for: ${asset.name}",
                attributionText.contains(asset.name)
            )
        }

        MockStudyCommunityData.collections.forEach { collection ->
            assertNotNull(MockStudyCommunityData.collection(collection.id))
            val collectionPosts = MockStudyCommunityData.postsForCollection(collection.id)
            assertTrue(collectionPosts.size >= 2)
            assertEquals(collection.postCount, collectionPosts.size)
        }
    }

    @Test
    fun samplePortfolioCentersDomesticTravelSceneryAndLearning() {
        val allCopy = MockStudyCommunityData.posts.joinToString("\n") { "${it.title}\n${it.content}" }
        listOf(
            "西湖",
            "良渚",
            "园林",
            "张家界",
            "黄山",
            "漓江",
            "三星堆",
            "兵马俑",
            "婺源",
            "青海湖",
            "Day 1",
            "讲解员"
        ).forEach { marker ->
            assertTrue("Missing structural category: $marker", allCopy.contains(marker))
        }
        listOf("工厂", "施工现场", "实验室开放日", "生产线", "测绘实训").forEach { marker ->
            assertFalse("Study-tour samples must not drift into industry reports: $marker", allCopy.contains(marker))
        }
        listOf("美国", "英国", "法国", "德国", "日本", "韩国", "海外", "国外").forEach { marker ->
            assertFalse("Study-tour samples must stay domestic in the mock feed: $marker", allCopy.contains(marker))
        }
        assertTrue(MockStudyCommunityData.posts.all { post ->
            post.tags.any { tag -> tag.contains("旅行") || tag.contains("景观") || tag.contains("研学") }
        })
        assertTrue(MockStudyCommunityData.posts.flatMap { it.tags }.distinct().size >= 20)
        assertTrue(MockStudyCommunityData.posts.map { it.destination }.distinct().size >= 8)
        val domesticDestinations = setOf(
            "杭州西湖湖岸线",
            "杭州良渚博物院",
            "苏州园林与平江路",
            "张家界国家森林公园",
            "黄山风景区",
            "桂林漓江山水线",
            "广汉三星堆博物馆",
            "西安秦始皇帝陵博物院",
            "婺源篁岭与古村落",
            "青海湖环湖景观线",
            "杭州—苏州—婺源—黄山"
        )
        assertTrue(MockStudyCommunityData.posts.all { it.destination in domesticDestinations })
    }

    @Test
    fun sampleCopyPassesDeSloppifyBoundary() {
        val allCopy = MockStudyCommunityData.posts.joinToString("\n") { "${it.title}\n${it.content}" }
        listOf(
            "绝绝子",
            "宝藏打卡",
            "不容错过",
            "[图片占位]",
            "TODO",
            "事实与待确认",
            "我们参观了",
            "为了贯彻",
            "首先其次"
        ).forEach {
            assertFalse("Sample copy must not contain generic filler: $it", allCopy.contains(it))
        }
    }

    @Test
    fun sampleFiltersMatchTheRealCommunityContract() {
        val museum = MockStudyCommunityData.filteredPosts(
            query = "",
            destination = "",
            tag = "博物馆研学",
            poi = "",
            minDays = 0,
            maxDays = 0,
            hasMedia = true
        )
        assertEquals(1, museum.size)
        assertTrue(museum.single().id.endsWith("liangzhu-clue-route"))
        assertTrue(museum.single().destination.contains("良渚博物院"))

        val landscape = MockStudyCommunityData.filteredPosts(
            query = "观景台",
            destination = "",
            tag = "",
            poi = "",
            minDays = 1,
            maxDays = 1,
            hasMedia = true
        )
        assertTrue(landscape.any { it.id.endsWith("zhangjiajie-landform") })

        val multiDay = MockStudyCommunityData.filteredPosts(
            query = "",
            destination = "",
            tag = "",
            poi = "",
            minDays = 2,
            maxDays = 0,
            hasMedia = true
        )
        assertEquals(listOf("sample-study-multi-day-landscape-camp"), multiDay.map { it.id })
    }

    @Test
    fun mediaResolverKeepsLocalAndRemoteSourcesSeparate() {
        assertEquals(
            "asset:///community/mock/west-lake.jpg",
            resolveCommunityMediaUrl("https://example.invalid", "asset:///community/mock/west-lake.jpg")
        )
        assertEquals(
            "https://example.invalid/media/1.jpg",
            resolveCommunityMediaUrl("https://example.invalid", "/media/1.jpg")
        )
    }

    @Test
    fun publicReferencesAreDomesticTraceableAndConnectedToSamples() {
        val references = MockStudyCommunityData.publicReferences
        assertTrue(references.size >= 6)
        assertEquals(references.size, references.map { it.id }.distinct().size)
        assertTrue(references.all { it.sourceUrl.startsWith("https://") })
        assertTrue(references.all { it.sourceUrl.contains("dili360.com") })
        assertTrue(references.all { it.sourceLabel == "中国国家地理" })
        assertTrue(references.all { it.summary.isNotBlank() && it.referenceLabel == "国内资料参考" })
        assertTrue(references.all { MockStudyCommunityData.post(it.relatedPostId) != null })
        assertTrue(references.all { MockStudyCommunityData.referenceForPost(it.relatedPostId) == it })
        assertTrue(references.none { reference ->
            listOf(reference.title, reference.summary, reference.sourceLabel).any { it.contains("小红书") }
        })
    }

    @Test
    fun activityNoticesArePublicSnapshotsWithTraceableSources() {
        val notices = MockStudyCommunityData.activityNotices
        assertTrue(notices.size >= 4)
        assertEquals(notices.size, notices.map { it.id }.distinct().size)
        assertTrue(notices.all { it.title.isNotBlank() && it.summary.isNotBlank() })
        assertTrue(notices.all { it.dateLabel.startsWith("2026年") })
        assertTrue(notices.count { it.locationLabel.startsWith("浙江") } >= 4)
        assertTrue(notices.all { it.sourceLabel == "互动吧活动详情" })
        assertTrue(notices.all { it.sourceUrl.startsWith("https://") })
        assertTrue(notices.all { notice ->
            val uri = URI(notice.sourceUrl)
            uri.host == "party.hudongba.com" &&
                uri.path.matches(Regex("/party/[a-z0-9]+\\.html")) &&
                uri.query == null &&
                uri.fragment == null
        })
        assertEquals(notices.size, notices.map { it.sourceUrl }.distinct().size)
        assertTrue(notices.all { it.verifiedOn == MockStudyCommunityData.ACTIVITY_SNAPSHOT_DATE })
        assertTrue(notices.none { notice ->
            listOf(notice.title, notice.summary).any { it.contains("智悟本主办") }
        })
    }

    @Test
    fun bundledPostsDoNotFabricateInteractionsAndUseDomesticReferences() {
        assertTrue(MockStudyCommunityData.posts.all { it.likeCount == 0 && it.commentCount == 0 })
        assertTrue(MockStudyCommunityData.posts.all { MockStudyCommunityData.comments(it.id).isEmpty() })
        assertTrue(MockStudyCommunityData.collections.all { it.bookmarkCount == 0 })
        assertTrue(MockStudyCommunityData.publicReferences.all {
            it.referenceLabel == "国内资料参考" &&
                it.sourceUrl.startsWith("https://www.dili360.com/") &&
                !it.summary.contains("已核验") &&
                !it.summary.contains("真实案例")
        })
    }

    @Test
    fun multiDayRouteMediaMatchesItsFourDestinations() {
        val post = MockStudyCommunityData.post("sample-study-multi-day-landscape-camp")
            ?: error("multi-day sample is missing")
        assertEquals(
            listOf(
                "west-lake.jpg",
                "west-lake-bridge.jpg",
                "west-lake-skyline.jpg",
                "suzhou-museum.jpg",
                "wuyuan-village.jpg",
                "wuyuan-village-wide.jpg",
                "huangshan.jpg"
            ),
            post.media.map { it.thumbnailUrl.substringAfterLast('/') }
        )
        assertTrue(
            MockStudyCommunityData.post("sample-study-suzhou-garden-street")!!
                .content.contains("苏州博物馆西馆")
        )
    }

    @Test
    fun bundledShareTextContainsOnlyUsefulContent() {
        val post = MockStudyCommunityData.posts.first()
        assertTrue(communityPostShareText(post).startsWith(post.title))
        assertFalse(communityPostShareText(post).contains("非真实用户投稿"))
        val collection = MockStudyCommunityData.collections.first()
        val state = CommunityCollectionDetailUiState(collection = collection)
        assertTrue(communityCollectionShareText(state).startsWith(collection.title))
        assertFalse(communityCollectionShareText(state).contains("非真实用户投稿"))
    }

    @Test
    fun sampleFallbackRequiresUnfilteredConfirmedEmptyPage() {
        val clean = CommunityUiState()
        assertTrue(
            shouldUseCommunitySamples(
                clean,
                CommunityPostPage(items = emptyList(), nextCursor = null),
                append = false
            )
        )
        val filtered = clean.copy(searchQuery = "不存在的地点")
        assertFalse(
            shouldUseCommunitySamples(
                filtered,
                CommunityPostPage(items = emptyList(), nextCursor = null),
                append = false
            )
        )
        assertFalse(
            shouldUseCommunitySamples(
                clean,
                CommunityPostPage(items = emptyList(), nextCursor = "next"),
                append = false
            )
        )
        assertFalse(
            shouldUseCommunitySamples(
                clean,
                CommunityPostPage(items = emptyList(), nextCursor = null),
                append = true
            )
        )
        assertFalse(
            shouldUseCommunitySamples(
                clean.copy(collectionThemeFilter = "文化线索"),
                CommunityPostPage(items = emptyList(), nextCursor = null),
                append = false
            )
        )
    }

    private fun assetsDirectory(): File = listOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
        File("android/app/src/main/assets")
    ).firstOrNull(File::isDirectory) ?: error("Android assets directory is missing")
}

# Community sample-to-live bridge

## Candidate identity

- Candidate ID: `community-sample-live-bridge`
- Linked prototypes: `community-sample-live-bridge`, `community-featured-story-window`, `community-route-collections`
- Status: `integrated`
- Intended screen: Android `StudyCommunityScreen`, discover tab

## User moment

This candidate serves a first-time visitor who opens 社区 before the public API contains posts. The visitor should immediately see useful, readable study-tour material, understand that it is sample content, and later receive real content without a second navigation path.

## Applicability

Use it when the discover endpoint returns an empty page or fails before the first non-empty response. Keep it out of the 发布 and 收藏 tabs, where authentication and server state are authoritative. When the endpoint returns real posts, replace sample posts and sample collections in the same state update boundary.

## Content skeleton

1. Discover header and search surface.
2. A compact sample-content notice with the replacement rule.
3. A featured image window with no more than seven pages, each bound to an existing post.
4. Topic filters derived from the current sample or server facets.
5. Three route collections with cover, destination, theme and count.
6. A compact two-column feed with multiple images per post.

## Visual and motion rules

- Use existing Material 3 and Microsoft-oriented app color tokens; do not introduce a new palette for the samples.
- Use 8dp card corners and fixed image bounds so loading and long Chinese titles cannot resize the feed.
- Use a 214dp featured window on phone widths. Text may wrap to two lines; image captions may not cover the post action target.
- Rotate featured pages every 4.2 seconds only when there is more than one page. Manual swiping remains available, and dragging prevents a competing page transition.
- Prefer direct image loading from the local asset abstraction; remote media continues through the configured community base URL.
- Keep the feed as the main scroll container. Horizontal interaction is limited to the featured pager, topic strip and route strip.

## Evidence and copy rules

- Sample copy is original field-note prose and describes observations rather than invented travel facts.
- Every local photo path maps to a row in `android/app/src/main/assets/community/mock/ATTRIBUTION.md` and `docs/test-materials/images/curated/README.md`.
- The UI calls the content “研学示例内容”; it does not imply that sample likes, comments or counts are live community analytics.
- The sample detail pages are read-only. Reporting, publishing and authenticated mutations remain unavailable for sample IDs.
- No Xiaohongshu screenshot, author text, sticker, font, logo, proprietary code or access token is shipped.

## Implementation mapping

- State fallback and replacement: `android/app/src/main/java/com/oa/automation/ui/screen/community/CommunityViewModel.kt`
- Original posts, collections and facets: `android/app/src/main/java/com/oa/automation/ui/screen/community/MockStudyCommunityData.kt`
- Local/remote media abstraction: `android/app/src/main/java/com/oa/automation/ui/screen/community/CommunityImageSource.kt`
- Feed, carousel and route composition: `android/app/src/main/java/com/oa/automation/ui/screen/community/StudyCommunityScreen.kt`
- Detail fallback: `android/app/src/main/java/com/oa/automation/ui/screen/community/CommunityScreens.kt`

## Representative fixture and validation

- Fixture: `fixtures/community-sample-live-bridge.json`
- Carousel fixture: `fixtures/community-featured-carousel.json`
- Route fixture: `fixtures/community-route-collections.json`
- Review: `reviews/community-sample-live-bridge.md`
- Test command: `cd android; .\\gradlew.bat testDebugUnitTest --tests "com.oa.automation.ui.screen.community.MockStudyCommunityDataTest" --tests "com.oa.automation.ui.screen.community.CommunityQuickTopicsTest" assembleDebug`
- Result on 2026-08-18: `BUILD SUCCESSFUL`

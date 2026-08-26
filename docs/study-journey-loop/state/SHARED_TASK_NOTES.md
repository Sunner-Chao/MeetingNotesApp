# Shared Task Notes

## Current State

- Active run: `community-mock-20260818`
- Current stage: verified; integrated into the Android community discover screen
- User approval required: manual visual review of the first community screen before production API publication

## Durable Decisions

| Date | Decision | Evidence | Applies to |
|---|---|---|---|
| 2026-08-18 | Use original, licensed field photos as replaceable sample media; study Xiaohongshu for information rhythm only | `runs/community-mock-20260818/input-manifest.json`, `android/app/src/main/assets/community/mock/ATTRIBUTION.md` | Community discover feed |
| 2026-08-18 | Show sample posts immediately, then replace them only when a non-empty real response arrives | `CommunityViewModel.kt`, `MockStudyCommunityDataTest.kt` | Public community data boundary |
| 2026-08-18 | Keep featured media to one stable-height carousel and keep the feed below it | `inspiration-report.md`, `CommunityFeaturedCarousel` | Community discover UI |

## Failed Experiments

| Date | Attempt | Failure reason | Do not retry until |
|---|---|---|---|

## Open Questions

| Question | Minimum input needed | Owner |
|---|---|---|

## Next Run

- Highest-value research gap: real user-created journey posts and authorization states after the community API is populated
- Existing assets to reuse first: `community-featured-carousel-v1`, `community-sample-live-bridge-v1`
- Validation debt: manual review on a small Android screen; production endpoint replacement and pagination still need end-to-end verification
# Community study loop: 2026-08-19

## Completed

- Replaced the repetitive six-post sample feed with ten original activity pages.
- Added four activity-oriented collections: engineering, city/rural, nature/science and multi-day camp.
- Reused the approved carousel, collection strip and read-only sample bridge.
- Added Wikimedia Commons offline assets for factory, museum, village, laboratory, field notebook and craft scenes.
- Recorded source URLs, authors and licenses in the app asset registry and curated image README.
- Browser research observed Xiaohongshu route-guide, task, museum, multi-day and practical-list structures only; proprietary page assets remain non-shippable.

## Decisions

- Content variety comes from information structure, not theme colors, stickers or copied social chrome.
- Mock posts do not claim venue schedules, fees, bookings, rankings, identities or experimental conclusions.
- Counts and comments are clearly sample data and disappear when a non-empty public API response arrives.

## Verification

- `MockStudyCommunityDataTest` passed on 2026-08-19.
- Full Kotlin compile and debug APK build remain the final gate for this run.

## Next Input

- User review of community detail pages and the four collection shelves.
- Real approved community posts and per-media rights metadata before enabling public write/publish.

# Community travel-theme correction: 2026-08-19

## Correction

- The previous sample portfolio treated study tours too much like industrial or specialist visits.
- The durable product rule is now: **destination and scenery first; learning is embedded at each real travel stop**.
- Community study-tour samples must visibly contain all three elements: domestic travel, scenic/cultural place and a grounded learning action.
- Factory, construction, laboratory, surveying and production-line narratives belong to their specialist meeting categories, not the default study-tour community feed.

## Research Evidence

- Xiaohongshu search results were used only to observe domestic route titles, stop-by-stop narrative, scenic image rhythm, guide explanations, learning cards and practical endings.
- No Xiaohongshu image, screenshot, author text, logo, sticker or font was copied into the product.
- Thirteen licensed Wikimedia domestic landscape and cultural-place images replace the foreign/industry-first community batch.

## Implementation

- Replaced ten community mock posts with destination-led pages for West Lake, Liangzhu, Suzhou, Zhangjiajie, Huangshan, Guilin, Sanxingdui, Xi'an, Wuyuan, Qinghai Lake and a four-day route.
- Replaced the collection shelves with mountain/water scenery, Chinese museums, villages/city walks and multi-day travel.
- Added focused regression checks that reject industry-report drift from the community sample copy.

## Publication Boundary

- Do not publish a new OTA until focused tests, build validation and owner visual review pass.

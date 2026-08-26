# Inspiration Report: community-mock-20260818

## Input Scope

- Sources: 5, recorded in `input-manifest.json`
- Mode: `integrate`
- Principle: `Simple and effective`
- Rights boundary: Xiaohongshu and UI Notes inform structure only. The shipped photos are from the separately documented Wikimedia Commons set.

## Evidence Method

The competitor notes and public pages are treated as observations about information hierarchy, not as instructions to copy a page. The owner brief is the product requirement. Claims about the current Android implementation are checked against the Compose screen and the focused unit tests. Where the sources do not prove a fact, this report labels the conclusion as an inference.

## Observed Patterns

1. **Media-first discovery**: public travel-content interfaces put a large image and a short title before dense metadata. This is directly observed in the internal competitor research note and public research links. The product inference is that a study-tour community should let users recognize a route or scene before reading the full body.
2. **Multi-image continuity**: a post is read as a sequence of images and short sections, rather than as one isolated cover. The product inference is to use several licensed images per mock post and retain stage labels for later detail reading.
3. **Collection and route grouping**: research notes identify collections, destinations, stages, map and timeline organization as useful ways to lower navigation cost. The product inference is to expose a small “路线专题” strip after the featured content, without turning the home screen into a dashboard.
4. **Light interaction feedback**: likes, comments, bookmarks and topic filters help discovery, but they are secondary to reading. The product rule is to keep mock interactions read-only and avoid simulating social proof as if it were server truth.
5. **Visual restraint**: the research supports image-led rhythm, not a requirement for copied stickers, brand colors, fonts or slogans. The implementation therefore uses the existing Fluent/Microsoft-oriented tokens, 8dp corners, stable dimensions and original Chinese copy.

## Prototype 1: Sample-to-live community bridge

- Problem: a new community tab with no server posts looks empty and gives no useful impression of the eventual product.
- New structure or interaction: initialize the discover state with six original study-tour posts, three route collections and filter facets; perform the real API request immediately in the background. A non-empty real response replaces sample posts, while an empty response or failure keeps filtered samples visible.
- Narrative rhythm: one-line discovery title, a large featured scene, a compact sample notice, topics, route grouping, then the two-column post feed.
- Visual composition: content-first full-width carousel and compact feed cards; no nested decorative card stack and no competitor artwork.
- Evidence boundary: sample copy is original and avoids unsupported weather, prices, rankings, identities or fabricated travel claims. Photos are loaded from bundled licensed assets with attribution.
- Source IDs: `owner-brief-community-mock-20260818`, `internal-competitor-research-20260807`, `wikimedia-curated-community-images`.
- Existing-component comparison: extends `CommunityViewModel` fallback behavior and `StudyCommunityScreen`; it does not change the remote API contract or local Journey domain.
- Fixture and validation: `fixtures/community-sample-live-bridge.json`; `MockStudyCommunityDataTest` checks post richness, asset existence, filtering and copy boundaries.
- Recommendation: `new-candidate`

## Prototype 2: Featured story window

- Problem: a long staggered feed can hide the fact that a post contains a multi-stage visual story.
- New structure or interaction: show up to seven image pages from the current discover set in a timed `HorizontalPager`; each page keeps a post title, destination/stage cue and page counter, and tapping opens the source post.
- Narrative rhythm: one scene every 4.2 seconds, with manual swipe always available. The timer pauses when the pager is being dragged.
- Visual composition: fixed 214dp image window, readable bottom caption band, small counter, restrained dots, and no decorative gradient layer.
- Evidence boundary: the carousel never invents captions beyond the post title, destination and recorded stage; missing media means no carousel page.
- Source IDs: `owner-brief-community-mock-20260818`, `internal-competitor-research-20260807`, `wikimedia-curated-community-images`.
- Existing-component comparison: a new composition inside the existing community screen, reusing `StudyCommunityImage` and existing navigation callbacks.
- Fixture and validation: `fixtures/community-featured-carousel.json`; build and focused community tests cover media path resolution and the seven-page cap.
- Recommendation: `merge-existing`

## Prototype 3: Route collections after discovery

- Problem: individual posts are useful for browsing, but a learner also needs a low-effort way to enter a coherent field route.
- New structure or interaction: keep three compact collections after topics, each with a destination, theme, post count and licensed cover; collection detail is supported for sample IDs and remains read-only until server write access exists.
- Narrative rhythm: route title and one-line purpose, followed by a short horizontal collection strip; the feed remains the primary reading surface.
- Visual composition: one horizontal strip with stable thumbnail bounds and text that can wrap; no second full-size hero competing with the featured carousel.
- Evidence boundary: collection descriptions summarize the original mock posts; counts are explicitly sample counts and are not presented as server analytics.
- Source IDs: `owner-brief-community-mock-20260818`, `internal-competitor-research-20260807`, `wikimedia-curated-community-images`.
- Existing-component comparison: reuses the existing collection model, route strip and detail navigation; only the sample fallback data is new.
- Fixture and validation: `fixtures/community-route-collections.json`; unit tests verify every collection resolves and contains posts.
- Recommendation: `merge-existing`

## Prototype 4: Copying social-media chrome

- Problem: the temptation to make the community feel rich by adding copied stickers, author phrases, brand marks, fake engagement or a full proprietary screenshot layout.
- New structure or interaction: none. This is deliberately rejected; it would add visual noise, copyright risk and misleading server-like numbers without improving the record-to-reading task.
- Narrative rhythm: none.
- Visual composition: rejected as a cosmetic imitation.
- Evidence boundary: proprietary screenshots, author copy, stickers, fonts, brand marks and unlicensed code remain non-shippable.
- Source IDs: `xiaohongshu-public-patterns`, `uinotes-xhs-archive`, `internal-competitor-research-20260807`.
- Existing-component comparison: existing Fluent tokens and original content are the safer reusable base.
- Fixture and validation: no candidate fixture; the De-Sloppify review records the rejection.
- Recommendation: `reject`

## Portfolio Decision

Prototype 1 is the integrated candidate because it solves the empty-first-run problem while preserving a clean replacement boundary. Prototypes 2 and 3 are composition-level extensions that fit the existing Community API and domain models, so they are merged into the same candidate rather than becoming separate templates. Prototype 4 is rejected because it changes surface decoration without improving evidence, navigation or action. The resulting experience is image-led enough to feel alive, but still recognizably ZhiWuBen: restrained Microsoft/Fluent color tokens, original copy, licensed media, and a clear path from sample content to real community data.

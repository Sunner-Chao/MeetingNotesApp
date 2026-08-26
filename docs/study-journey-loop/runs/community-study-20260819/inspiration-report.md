# Inspiration Report: community-study-20260819

## Input Scope

- Sources: 7
- Mode: integrate
- Principle: Simple and effective
- Rights boundary: Xiaohongshu pages are research evidence only. No author copy, screenshots, logos, stickers, fonts or proprietary media enter the build.

## Evidence Method

The browser research observed public information hierarchy and interaction patterns. It did not treat popularity, author claims or comments as verified product facts. The shipped mock copy is original ZhiWuBen material. Bundled photos come from the separately documented Wikimedia Commons batch and remain usable offline.

## Observed Patterns

1. **A useful post solves one reading task.** The search set contains route guides, task lists, museum notes, multi-day journals and preparation lists. Observation: their value comes from different structures, not from different colors. Inference: the community sample portfolio must dispatch by activity shape instead of repeating one reflective essay.
2. **Route content becomes collectible when every stop has a purpose.** The technology route sample uses time points and activity descriptions. The campus sample separates audience, core highlight and execution idea. Inference: route posts should expose stages plus a concrete output or question at each stage.
3. **Comments reveal missing practical information.** Public comments frequently ask about booking, age range and availability. This is an observation about reader intent, not proof that any specific venue accepts bookings. Inference: future real posts may show verified practical fields, but sample posts must not invent opening times, fees or reservations.
4. **Media rhythm should support the content structure.** The approved ZhiWuBen carousel already communicates multi-image continuity. Inference: reuse it and enrich licensed media; do not add copied social-media chrome or another competing hero.
5. **Evidence boundaries remain visible through omission.** The internal UI library requires uncertain identities, dates, locations, experimental conclusions and private images to be omitted or kept in evidence metadata. The sample copy therefore models questions, tasks and safe publication checks without fabricating answers.

## Prototype 1: Activity-shape portfolio

- Problem: the existing six posts repeat the same “observe before concluding” thesis and feel like wording variants.
- New structure or interaction: replace them with ten original activity sets covering industrial process, construction route, surveying mission, museum clue trail, wetland observation board, village research, architecture detail lens, laboratory tasks, multi-day camp and forum-to-field verification.
- Narrative rhythm: every post chooses one dominant shape and one concrete takeaway; no universal section order is imposed.
- Visual composition: reuse the existing featured carousel, topic strip, collection row and staggered feed. Each feature post receives three to six licensed images matched to its activity type.
- Evidence boundary: all venue names are coarse sample labels; no fees, opening times, rankings, real participant identities or unsupported outcomes are asserted.
- Source IDs: `owner-brief-community-study-20260819`, `xhs-study-search-20260819`, `zwb-ui-library-20260815`, `wikimedia-community-batch-20260819`.
- Existing-component comparison: extends `MockStudyCommunityData`; does not change the Community API, Journey domain or live-data replacement boundary.
- Fixture and validation: `fixtures/community-post-portfolio.json`; focused unit tests verify category markers, copy quality, media richness and asset existence.
- Recommendation: `new-candidate`

## Prototype 2: Route purpose card

- Problem: a route with only place names encourages users to skim or photograph everything.
- New structure or interaction: every stage answers what to look for, what to record or what to take away. The same rule supports construction, manufacturing and multi-day camp content.
- Narrative rhythm: context, stage purpose, field action, compact deliverable.
- Visual composition: stage names remain in the existing timeline; no extra dashboard is added.
- Evidence boundary: practical venue fields render only when real source material supplies them; mock routes avoid invented booking and availability claims.
- Source IDs: `xhs-tech-route-69d4d633`, `xhs-campus-route-6a366555`, `zwb-ui-library-20260815`.
- Existing-component comparison: merged into post copy, `stages` and `curationNote`; no new Compose component.
- Fixture and validation: construction, factory and multi-day fixtures; filter and collection tests.
- Recommendation: `merge-existing`

## Prototype 3: Evidence-linked field task

- Problem: museum, wetland and laboratory visits often leave many photos but no retrievable question.
- New structure or interaction: use clue cards, observation boards or task cards according to the evidence type; each task records a real action and defines what must be omitted when evidence is missing.
- Narrative rhythm: question, observation, comparison, unresolved item.
- Visual composition: headings and bullets use the existing rich-text renderer; media stays in the approved swipeable pager.
- Evidence boundary: unknown species are described rather than named, demonstrations are not written as formal experiment conclusions, and unverified object histories are omitted.
- Source IDs: `xhs-study-search-20260819`, `zwb-ui-library-20260815`, `wikimedia-community-batch-20260819`.
- Existing-component comparison: configures approved task, exhibit, field-observation and detail-lens patterns as community content.
- Fixture and validation: museum, wetland, laboratory and architecture fixtures; De-Sloppify review.
- Recommendation: `merge-existing`

## Prototype 4: Four curated learning shelves

- Problem: ten posts without grouping still create a flat, high-effort feed.
- New structure or interaction: group the samples into `工程现场怎么学`, `城市与乡土观察`, `自然与科学任务线` and `两天以上研学营`.
- Narrative rhythm: one-line purpose, cover, two or three resolved posts.
- Visual composition: reuse the approved horizontal collection strip; keep the feed primary.
- Evidence boundary: counts are sample content counts and never presented as live analytics.
- Source IDs: `owner-brief-community-study-20260819`, `approved-community-assets-v1`, `zwb-ui-library-20260815`.
- Existing-component comparison: expands the existing collection map from three repetitive shelves to four activity-oriented shelves.
- Fixture and validation: `fixtures/community-collections.json`; every collection must resolve and report an accurate count.
- Recommendation: `merge-existing`

## Prototype 5: Copied social-media shell

- Problem: visual richness could be misread as a request to copy Xiaohongshu cards, stickers, author phrasing and engagement presentation.
- New structure or interaction: none; reject the copied shell.
- Narrative rhythm: rejected because it adds imitation without improving learning retrieval.
- Visual composition: retain ZhiWuBen Fluent blue/gray tokens and approved media components.
- Evidence boundary: proprietary screenshots, author text, brand marks, fonts and media remain non-shippable.
- Source IDs: `xhs-study-search-20260819`, `xhs-tech-route-69d4d633`, `xhs-campus-route-6a366555`.
- Existing-component comparison: existing ZhiWuBen components are sufficient.
- Fixture and validation: rejection recorded in this report and candidate review.
- Recommendation: `reject`

## Portfolio Decision

Prototype 1 is integrated as one content portfolio candidate. Prototypes 2, 3 and 4 are configuration-level rules merged into that candidate because they reuse existing Community components. Prototype 5 is rejected. The result is structurally varied without creating new screens, copied chrome or decorative component variants.

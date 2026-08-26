# Community Study Portfolio Candidate

## Purpose

Provide offline sample activity pages while the public community has no live posts. Each page chooses one dominant structure: process route, safety route, team mission, clue trail, observation board, village study, detail lens, lab tasks, multi-day chapters or forum-to-field verification.

## Content Contract

- Keep title, destination, date, stage labels, tags and media independent so filters and collection pages remain useful.
- Use first-person original narration only when the fixture supplies the observation.
- Describe unknowns as questions; do not invent names, schedules, prices, rankings, identities or conclusions.
- Bind every bundled image to the local attribution registry.
- Keep the sample boundary visible until a non-empty live response replaces it.

## Visual Contract

- Reuse `community-featured-carousel-v1` and `community-sample-live-bridge-v1`.
- Keep Microsoft/Fluent blue-gray tokens, 8dp corners, stable image bounds and a single reading hierarchy.
- Let content shape vary through headings, bullets, stage count and media rhythm; do not add color-only templates.

## Representative Fixture

`fixtures/community-post-portfolio.json` and `fixtures/community-collections.json` cover ten posts, four collections, 3-6 images per post, one two-day activity and multiple task structures.

## Validation

`MockStudyCommunityDataTest` checks post count, category markers, long copy, image existence, attribution coverage, collection resolution, multi-day filtering and banned filler phrases.

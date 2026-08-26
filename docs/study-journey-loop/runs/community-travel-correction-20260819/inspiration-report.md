# Inspiration Report: community-travel-correction-20260819

## Input Scope

- Sources: 5
- Mode: integrate
- Principle: Simple and effective
- Correction: move from industry-visit reporting to domestic travel + scenic destinations + field learning.
- Rights boundary: Xiaohongshu pages are research evidence only. No author copy, screenshots, logos, stickers, fonts or proprietary media enter the build.

## Diagnosis

The previous portfolio was structurally varied but had the wrong center of gravity. Factory, construction, surveying and laboratory samples made “研学考察” read like an industry research visit. Several bundled images also showed foreign or non-domestic contexts, which weakened the intended Chinese travel and scenic-exploration identity.

The corrected product rule is:

> First make the destination and scenery legible; then attach one or two grounded learning questions to each stop. A study tour is a journey with learning inside it, not an industry report with photos.

## Evidence Method

The browser research observed public search-result structures for domestic campus routes, West Lake and Liangzhu visits, museum routes, city study, red-history routes, multi-day travel and preparation lists. These observations inform information architecture only. Popularity, author claims, venue schedules and comments are not treated as verified facts.

## Observed Patterns

1. **The destination is the first reading anchor.** Domestic study-tour notes lead with a recognizable city, scenic area, campus, museum or historic route. Inference: every sample title and cover must tell the reader where the journey is going before presenting the task.
2. **Route order creates the travel feeling.** Useful posts move through stops such as entrance, viewpoint, exhibit, street or return route. Inference: the existing stage list should read like a walkable itinerary rather than a process checklist.
3. **Learning appears as a small question at a real place.** A scenic viewpoint can support a question about landform, a museum can support a clue card, and an old town can support an observation about architecture and daily use. Inference: keep learning concrete and local to the stop; do not turn the whole page into a report.
4. **Images alternate between atmosphere and evidence.** A scenic full view establishes place, detail shows what to notice, and a people/path image restores the feeling of travel. Inference: use the approved seven-image carousel rhythm where assets permit, with no duplicated scenic frame.
5. **Practical value closes the note.** Route notes often end with a compact packing, observation or photo tip. Inference: a study-tour post may contain a small “带走什么” block, but must not invent prices, opening times, booking rules or rankings.

### Additional Xiaohongshu review: travel-first signals

The logged-in search pages for `研学考察` and `研学旅行 景点 游记` were reviewed again on 2026-08-19. The visible domestic results repeatedly used three strong signals: a named city or attraction in the title, a route or day-by-day visual in the first image, and a small practical or learning hook in the body. Examples included city routes around Suzhou, Hangzhou, Shanghai, Luoyang, Jiaxing and Shaoxing, as well as landscape and heritage destinations such as West Lake, museums, ancient towns and the Great Wall.

The useful product inference is deliberately narrow: the community sample must begin with a recognizable domestic destination, show the scenery or cultural site before the explanation, and let each stop answer one human-scale question. Hand-drawn route maps, photo collages and day cards are inspiration for composition only. Xiaohongshu screenshots, post copy, stickers, logos, fonts and creator images remain excluded from the shipped app.

## Prototype 1: Scenic Route + Learning Stops

- Problem: industry-first samples obscure the fact that users are traveling through a visible destination.
- New structure or interaction: cover destination and scenic image -> route stages -> one learning question per stop -> compact takeaway card.
- Narrative rhythm: arrive, look around, listen, try a small task, continue, remember.
- Visual composition: scenic cover first; media carousel alternates overview, detail and route context; existing Fluent blue-gray UI remains unchanged.
- Evidence boundary: only supplied destination labels and licensed images; no invented itinerary logistics or claims about actual organized events.
- Source IDs: `owner-brief-travel-learning-correction-20260819`, `xhs-domestic-study-search-20260819`, `xhs-scenery-learning-patterns-20260819`, `wikimedia-domestic-scenery-batch-20260819`.
- Existing-component comparison: replaces sample data only; reuses existing featured carousel, stages, tags and collection strip.
- Fixture and validation: West Lake, Zhangjiajie, Huangshan and Li River samples; verify scenic tags, stage order, media richness and no industry drift.
- Recommendation: `new-candidate`

## Prototype 2: Museum + Outdoor Context

- Problem: a museum-only page can feel like a formal catalog and lose the travel setting.
- New structure or interaction: building or landscape arrival -> exhibit clue -> guide explanation -> outdoor return view.
- Narrative rhythm: arrive, choose one object, explain it in plain language, connect it back to place.
- Visual composition: one architecture or exterior frame, two to three exhibit frames, one surrounding landscape frame; no copied social-media shell.
- Evidence boundary: describe what is seen and heard; omit unverified dates, identities and historical conclusions.
- Source IDs: `xhs-domestic-study-search-20260819`, `xhs-scenery-learning-patterns-20260819`, `wikimedia-domestic-scenery-batch-20260819`.
- Existing-component comparison: configures the existing rich-text and carousel surfaces; no new screen.
- Fixture and validation: Liangzhu, Sanxingdui and Terracotta Army samples.
- Recommendation: `merge-existing`

## Prototype 3: Landscape Observation Card

- Problem: scenic notes often stop at “很美”, leaving no memorable learning action.
- New structure or interaction: panoramic view -> one visible landscape feature -> guide question -> personal one-sentence takeaway.
- Narrative rhythm: see, name, compare, say it back.
- Visual composition: overview image has the largest visual weight; detail and path images provide context; card copy stays short.
- Evidence boundary: direct observations and supplied explanatory text only; unknown species, geology or weather effects remain questions.
- Source IDs: `xhs-scenery-learning-patterns-20260819`, `zwb-ui-library-20260815`, `wikimedia-domestic-scenery-batch-20260819`.
- Existing-component comparison: merges into `stages`, `curationNote` and post body.
- Fixture and validation: Zhangjiajie, Huangshan, Guilin and Qinghai Lake samples.
- Recommendation: `merge-existing`

## Prototype 4: Multi-day Scenic Journey

- Problem: long travel notes become a flat stream of photos.
- New structure or interaction: one destination line per day, one scenic anchor, one learning card, one transition sentence.
- Narrative rhythm: Day 1 water and city -> Day 2 garden and museum -> Day 3 village and architecture -> Day 4 mountain and return.
- Visual composition: seven-image roller remains the maximum; every image must belong to a day or a clear transition.
- Evidence boundary: sample route is clearly mock content; no transit time, price or reservation promise.
- Source IDs: `xhs-domestic-study-search-20260819`, `xhs-scenery-learning-patterns-20260819`, `zwb-ui-library-20260815`.
- Existing-component comparison: uses existing multi-stage article parser, media pager and collection shelf.
- Fixture and validation: four-day Hangzhou-Suzhou-Wuyuan-Huangshan sample and min-day filter.
- Recommendation: `merge-existing`

## Prototype 5: Industry Report Disguise

- Problem: “研学” can drift back toward factory, construction, laboratory or surveying reports.
- New structure or interaction: rejected.
- Narrative rhythm: rejected because process checkpoints are not the default travel narrative.
- Visual composition: rejected for the community study-tour shelf; those materials belong to project-management or specialist visit templates.
- Evidence boundary: industry assets may remain in separate test fixtures only when needed for their own meeting type, never in the public study-tour mock feed.
- Source IDs: `owner-brief-travel-learning-correction-20260819`, `zwb-ui-library-20260815`.
- Existing-component comparison: no new community component.
- Recommendation: `reject`

## Portfolio Decision

Prototype 1 is integrated as the new center of the community sample portfolio. Prototypes 2, 3 and 4 are merged into the existing content model and UI components. Prototype 5 is explicitly rejected. The corrected set contains ten domestic destination-led sample pages covering lake, museum, garden, mountain, river, heritage, village, plateau and multi-day routes.

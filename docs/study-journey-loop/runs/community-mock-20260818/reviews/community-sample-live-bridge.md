# De-Sloppify Review: community-sample-live-bridge

## Decision

Pass. The candidate is integrated for manual product review and does not publish any third-party proprietary asset.

## Blockers

None.

## Review findings

- The sample notice states the data boundary plainly and does not pretend that the content is live.
- The six posts use original field-note copy, real stage labels and a documented local photo source. No placeholder, empty section or generic social-media slogan is required for the layout to work.
- The carousel, topic strip, route strip and feed each support discovery, reading or navigation. The rejected social-media imitation prototype is not present in the candidate.
- Counts and comments are sample values attached to sample IDs. Authenticated write actions and reporting are hidden for those IDs.
- The fixed carousel height and bounded text lines address small-screen stability. Long post content remains in the detail reader rather than expanding the home feed card without limit.
- The remote API remains the source of truth once a non-empty response arrives. Empty and failure responses preserve a usable filtered sample state.

## Acceptance score

| Dimension | Score | Reason |
|---|---:|---|
| Structural novelty | 2 | Combines replaceable fallback state with a featured multi-stage window and route collections. |
| User value | 2 | The community tab is useful before the server has public content. |
| Evidence integrity | 2 | Copy, media provenance and sample/live boundary are explicit. |
| Visual-content unity | 2 | Large images and stage/route metadata match the study-tour reading task. |
| Simplicity | 2 | One discover flow; secondary controls remain below the main story window. |
| Maintainability | 2 | Mock data and media resolution are isolated from the API contract. |
| Responsive behavior | 1 | Phone constraints are covered; tablet and accessibility-specific motion review remain manual follow-ups. |
| Testability | 2 | Focused tests cover assets, filters, navigation data and source resolution. |
| **Total** | **15/16** | Pass threshold is 13 with no zero in evidence, simplicity or maintainability. |

## Optional follow-up

Add an automated Compose preview test for a narrow font-scaled device and a reduced-motion preference before the community surface becomes a production default. This is polish, not a blocker for the sample fallback.

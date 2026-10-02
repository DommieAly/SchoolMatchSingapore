# PSLE ranges, SAFE / MATCH / REACH, plan warnings and recommendation scoring

**Status: proposed — team to confirm** (DC-20, DC-22 in [`design-changes.md`](design-changes.md)).
Before accepting, check the rules by hand against 3 real schools. The recommendation numbers are settings under `app.recommendation.*`, so the team can change them without changing code. The plan numbers (SAFE margin, plan size, REACH warning limit) are constants on the entities, because an entity cannot read settings (DC-20). Section 6 lists where each number lives.

PSLE scores here are Achievement Level (AL) totals from 4 to 32. **Lower is better.**
PSLE ranges are historical (the scores of students admitted in a past year), not a guarantee; every page that shows a range or a label says so (NFR-DATA-03).

## 1. Which range applies (DC-22)

A school has one `IndicativePsleScoreRange` per admission year, posting group (PG1/PG2/PG3) and track (affiliated or not). For a given student:

1. Use the ranges of the student's posting group.
2. Use the **affiliated** range only when the user is logged in, their profile's `primarySchool` is one of the school's `affiliatedPrimarySchools`, and the school has an affiliated range. Otherwise, and for every guest, use the **non-affiliated** range.
3. Use the **latest admission year** among those.
4. No such range → the result is "Not available". Never guess.

Code: `School.getScoreRange(postingGroup, affiliated)`. With `affiliated = true` it falls back to the non-affiliated range when no affiliated range exists.

The **summary card** (search results and shortlist) shows the range the PSLE filter used: the filter's posting group, affiliated or not by the rules above. Without a PSLE filter it shows the non-affiliated PG3 / PG2 / PG1 ranges on one line, also for a logged-in member whose profile has a posting group (the profile is read only when a PSLE score is given, DC-58, DC-67).

Integrated Programme ranges do not fit the affiliated/non-affiliated split; they appear as a text note on the details page (`School.ipRangeNote`, DC-18).

## 2. SAFE / MATCH / REACH (DC-20)

`SchoolChoice.assess(score, postingGroup)` with `U` = `upperScore` of the applicable range (the score of the last student admitted) and `m` = `SchoolChoice.SAFE_MARGIN` (2):

| Label | Rule | Meaning |
|:--:|:--:|:--:|
| `SAFE` | `score ≤ U − m` | clearly better than the last admitted score |
| `MATCH` | `U − m < score ≤ U` | at or just inside the last admitted score |
| `REACH` | `score > U` | worse than the last admitted score |
| `null` ("Not available") | no applicable range | – |

Example with `U = 12`, `m = 2`:

| Student score | 6 | 10 | 11 | 12 | 13 | 14 | 15 |
|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| Label | SAFE | SAFE | MATCH | MATCH | REACH | REACH | REACH |

The lower bound of the range is not used: a score better than `lowerScore` is still SAFE.

## 3. PSLE score filter (DC-22)

`PsleScoreFilter` keeps a school when `score ≤ U` for the applicable range, i.e. the SAFE and MATCH schools. Schools with no applicable range are left out.

Boundary test cases (Lab 4): `score < lowerScore`, `score = lowerScore`, `score = U` (kept), `score = U + 1` (dropped); a guest gets the non-affiliated range; a logged-in affiliated user gets the affiliated range.

## 4. Plan warnings

`ChoicePlan.getRiskWarnings()` / `ChoicePlanController.assessPlan(plan)` return one message per rule that fires:

| Rule | Suggested message |
|:--:|:--:|
| No choice is SAFE | "None of your choices is SAFE. Add at least one school where your score is clearly inside the range." |
| More than 3 choices are REACH | "More than 3 of your choices are REACH." |
| Fewer than 6 choices | "You have N of 6 choices. Fill all 6 to lower the risk of being posted to a school you did not choose." |
| A choice has no range data | "<school>: no PSLE range data, so no SAFE/MATCH/REACH label." |
| `basedOnScore` differs from the profile's PSLE score | "This plan was made for a score of X, but your profile now says Y." (read through `ProfileController`, DC-14) |

## 5. Recommendations (`RecommendationController.recommend`)

**Candidates:** schools that have a range for the student's posting group. Until more ranges are curated this is about 40 schools, and the results page says so.

**Factor scores** (each from 0 to 1, stored as a `ScoreComponent` with a reason):

| Factor | Score | Example reason |
|:--:|:--:|:--:|
| `PSLE_FIT` | MATCH 1.0, SAFE 0.7, REACH with `score ≤ U + 2` 0.3; any other school is **excluded** | "PSLE 11 is inside the 2025 PG3 range 8–12 (MATCH)" |
| `CCA` | share of the preferred CCAs the school offers | "Offers 2 of your 3 CCAs: Basketball, Choir" |
| `PROGRAMME` | share of the preferred programmes the school offers | "Offers 1 of your 2 programmes" |
| `COMMUTE` | `1 − min(t / maxCommuteMin, 1)`, `t` = travel time in minutes, rounded up to the next whole minute (the same rounding as the travel-time filter); a school whose travel time in seconds is more than `maxCommuteMin × 60` is **excluded** | "About 25 min by public transport" |

**Total score:** `total = Σ (weight × score) ÷ Σ weight`, over the factors that count. Default weights: PSLE_FIT 0.4, COMMUTE 0.3, CCA 0.15, PROGRAMME 0.15.
A factor with no stated preference (no preferred CCAs, no preferred programmes) gets weight 0, and dividing by the sum of the remaining weights rescales them.

Example: MATCH (1.0), 20 min with a 40 min limit (1 − 0.5 = 0.5), 2 of 3 CCAs (0.667), 1 of 2 programmes (0.5):
`0.4 × 1.0 + 0.3 × 0.5 + 0.15 × 0.667 + 0.15 × 0.5 = 0.725` (the weights sum to 1, so no rescaling).

**Steps:**

1. Score every candidate on PSLE_FIT, CCA and PROGRAMME; drop the excluded ones.
2. Pre-rank them by that score, using straight-line distance from the start location as a tie-break, and keep the top `commuteCandidates` (20).
3. Get travel times for those 20 with **one** route-matrix call (20 elements), then add COMMUTE and drop schools over the limit.
4. Sort by total score; ties go to the shorter commute, then the school name. Return the top `topN` (10) with `rank` 1..10.

**When travel times are unavailable** (the route service fails): COMMUTE shows "Not available", its weight is spread over the other factors (same rescaling), and each affected reason says so. A daily limit on Google calls is not in the skeleton; if the team adds one (owner E), hitting it counts as "unavailable" too. Google TRANSIT routes in Singapore have not been tried with a real key yet; if they do not work, the top 5 use OneMap routing and the rest a labelled straight-line estimate.

## 6. Settings

| Number | Value | Where it lives |
|:--:|:--:|:--:|
| SAFE margin `m` | 2 | constant `SchoolChoice.SAFE_MARGIN` (an entity cannot read settings) |
| Plan size | 6 | constant `ChoicePlan.MAX_CHOICES` (fixed by the posting exercise) |
| REACH warning limit (warn above this many REACH choices) | 3 | not yet — owner F adds a constant `ChoicePlan.MAX_REACH_CHOICES` with `getRiskWarnings` |
| `app.recommendation.weights.psle-fit` / `.commute` / `.cca` / `.programme` | 0.4 / 0.3 / 0.15 / 0.15 | setting, in `AppProperties` |
| `app.recommendation.commute-candidates` | 20 | setting, in `AppProperties` |
| `app.recommendation.top-n` | 10 | setting, in `AppProperties` |
| `app.recommendation.psle-fit.match` / `.safe` / `.reach-near` | 1.0 / 0.7 / 0.3 | not yet — owner B adds the settings with the scoring code |
| `app.recommendation.reach-near-margin` (REACH within `U + n` still counts) | 2 | not yet — owner B |

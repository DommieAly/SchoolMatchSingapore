# Black-box testing of `RecommendationController`

| Item | Value |
|:--:|:--:|
| Lab 4 section | 3.2.1 (equivalence class and boundary value testing) and its part of 3.2.3 (minimising the test cases) |
| Date | 9 Nov 2026 |
| Author | Lingyi Liu |
| Code version | branch `test/lab4-testing`, based on commit `856c52f` |
| Class under test | `RecommendationController` («control»), `src/main/java/sg/schoolmatch/control/RecommendationController.java` |
| Method used | `recommend(MatchCriteria criteria)`, the only public operation of the class |
| Use case | #06 Get School Recommendations |
| Requirements | FR-REC-01, NFR-USE-03, FR-FILTER-06 (the commute options) |
| JUnit class | `src/test/java/sg/schoolmatch/control/RecommendationControllerBlackBoxTest.java` |
| Test table | `src/test/resources/testcases/TC-BB-REC.csv` (the rows of TC-BB-REC-11 to TC-BB-REC-28 and TC-BB-REC-32) |
| Test case ids | TC-BB-REC-01 to TC-BB-REC-32 |
| Results | see `test-results.md` (built from the run; file `target/test-results/black-box-recommendation.csv`) |

## 1. Why this class

We chose `RecommendationController` because it holds the main logic of the recommendation use case. It checks the
criteria, picks the candidate schools by PSLE range, scores four factors, asks the route service for travel times,
drops schools that are too far, and ranks the rest. A wrong rule here gives a student a wrong list of schools without
any error on the page.

## 2. Technique

The tests use only what the class shows from outside: the public method `recommend`, the list it returns and the
exceptions it throws. They build the class with the constructor from our design,
`RecommendationController(LocationController, DirectionsController, SchoolDataController, AppProperties)`. They do not
read any constant of the class or compare the wording of its reasons, so they still apply when section 3.3.2 has an AI
write the class again.

The two services the class uses are mocks that answer only these calls, so a rewritten class has to use them too:

- `SchoolDataController.hasPsleData()` and `SchoolDataController.getSchools()` give the dataset;
- `DirectionsController.getCommuteTimes(start, schools, mode)` gives the travel times in one call, in the same order
  as the schools it was given.

`README.md` section 1 defines the terms.

## 3. Specification used for the expected outputs

We took the expected output of every case from the sources in Table 1, not from the code, and wrote them before the
first run. "Scoring §n" means section n of `docs/recommendation-scoring.md`.

**Table 1. Specification of `recommend`**

| Source | What it says |
|:--:|:--:|
| Use case #06, flow steps 4–5 | The class checks the criteria, gets the commute times, scores the schools on PSLE fit, commute, CCAs and programmes, and returns up to 10 schools, best first, each with its score, commute time and a reason for each factor. |
| Use case #06, EX-1; NFR-USE-03 | A missing or invalid value gives a message next to that field: `InvalidInputException` with a map field → message. |
| `src/main/resources/templates/recommendation-criteria.html` | The field keys of that map are the ones the criteria form reads from `fieldErrors`: `psleScore`, `postingGroup`, `travelMode`, `maxCommuteMin` and `startLocation`. The form has no weights field; the key `weights` follows the property name of `MatchCriteria`. |
| Use case #06, EX-2 | No suitable school: an empty list. |
| Use case #06, EX-3 | School information cannot be read: `ExternalServiceUnavailableException`. |
| Use case #06, EX-4; scoring §5 "When travel times are unavailable" | The route service is down or finds no route: the school is still shown, its commute is "Not available", and the commute weight is shared by the other factors. |
| `IndicativePsleScoreRange.MIN_SCORE`, `MAX_SCORE`; `InputMessages.PSLE` | PSLE score: a whole number from 4 to 32. Message "Enter a whole number from 4 to 32". |
| `IndicativePsleScoreRange.MIN_POSTING_GROUP`, `MAX_POSTING_GROUP`; `InputMessages.PG` | Posting group: 1, 2 or 3. Message "Choose posting group 1, 2 or 3". |
| `InputMessages.MODE`; FR-ROUTE-04 | Travel mode: `WALK`, `DRIVE` or `TRANSIT`. Message "Choose walk, drive or public transport". |
| FR-FILTER-06; `TransportationFilter.DURATION_OPTIONS_MIN`; `InputMessages.DURATION` | Longest commute: 15, 30, 45 or 60 minutes. Message "Choose 15, 30, 45 or 60 minutes". |
| Use case #06, step 2 and EX-1; `ReferenceLocation.isResolved` | A resolved starting point is needed: it has a coordinate, and the coordinate lies inside Singapore. The specification gives no message text. |
| `MatchCriteria.isValid` | Every weight has a value of 0 or more (`w != null && w >= 0`). The specification gives no message text. |
| DC-53 | Empty criteria weights mean "use `app.recommendation.weights`". |
| scoring §1; DC-22; DC-82 | Use the student's posting group. Use the affiliated range only when the student's primary school is one of the school's affiliated primary schools and the school has an affiliated range. Otherwise use the non-affiliated range. Use the latest admission year. Names of primary schools are compared ignoring case, spaces, dots, apostrophes and brackets, and a trailing "(Primary)" is ignored (DC-82). |
| DC-77; DC-82; scoring §1 | Only for PG3, and only when the school has no other PG3 range, the latest Integrated Programme (IP) range is used as the PG3 range. The PSLE fit reason marks it "IP". For PG1 and PG2 an IP range is never used. |
| scoring §2 and §5; DC-20; `SchoolChoice.SAFE_MARGIN` = 2; `app.recommendation.reach-near-margin` = 2 | With U = upper score of the range: SAFE if score ≤ U − 2 (0.7), MATCH if U − 2 < score ≤ U (1.0), REACH if U < score ≤ U + 2 (0.3), any other school is excluded. A school with no range for the posting group is not a candidate. |
| scoring §2; DC-22 | The lower score L of the range is not used: a score better than L is still SAFE. |
| scoring §5 factor table | CCA and PROGRAMME: the share of the preferred items the school offers. COMMUTE: 1 − min(t ÷ maxCommuteMin, 1), with t in minutes rounded up; a school whose travel time in seconds is more than maxCommuteMin × 60 is excluded. |
| scoring §5 factor table, column "Example reason" | Each factor has a reason. The texts given are examples, so the tests check only that a reason is not blank, plus the parts the specification fixes: "Not available" (EX-4), the DC-74 sentence and the mark "IP" (DC-77). |
| scoring §5 "Total score"; `app.recommendation.weights` | Total = Σ (weight × score) ÷ Σ weight over the factors that count. Weights 0.4 (PSLE fit), 0.3 (commute), 0.15 (CCA), 0.15 (programme). A factor without a preference gets weight 0. |
| scoring §5 "Steps"; `app.recommendation.commute-candidates` = 20, `top-n` = 10 | Score PSLE fit, CCA and programme first (this document calls that total the pre-score); keep the best 20 by pre-score, with ties broken by straight-line distance from the start; get their travel times in one call; drop schools over the limit; sort by total, ties by shorter commute, then by name; return the best 10 with rank 1..10. |
| DC-74; scoring §5 "No ranges in the dataset" | When the dataset has no PSLE ranges, PSLE fit gets weight 0, every school is a candidate, every result says "PSLE fit not used: no score ranges in the current dataset", and the PSLE score and posting group are optional but still checked when given. |

DC-20, DC-22, DC-53, DC-74, DC-77 and DC-82 are recorded in `docs/design-changes.md`, and the tests use them as the
specification. The tests also use the same settings as `src/main/resources/application.yml`, so the expected numbers
match the running site.

The criteria form (`RecommendationUI`) turns the typed score into a whole number before this class sees it, so text
and decimals have no equivalence class here.

## 4. Input conditions

The output depends on the criteria and on what the class gets from the two services it calls. Table 2 lists both.

**Table 2. Input conditions of `recommend`**

| Id | Condition | Where it comes from |
|:--:|:--:|:--:|
| C1 | `psleScore` | criteria |
| C2 | `postingGroup` | criteria |
| C3 | `travelMode` | criteria |
| C4 | `maxCommuteMin` | criteria |
| C5 | `startLocation`: missing, without a coordinate, with a coordinate outside Singapore, or resolved | criteria |
| C6 | `weights`: empty, or a value per factor | criteria |
| C7 | `preferredCCAs` against the CCAs the school offers | criteria and school data |
| C8 | `preferredProgrammes` against the programmes the school offers | criteria and school data |
| C9 | `primarySchool` against the school's affiliated primary schools | criteria and school data |
| E1 | whether the dataset has PSLE ranges (`SchoolDataController.hasPsleData()`, DC-74) | school data |
| E2 | whether the school data can be read | school data |
| E3 | the school's PSLE ranges for the student's posting group (lower score L, upper score U), relative to the score | school data |
| E4 | the travel time t in seconds, relative to M = maxCommuteMin × 60 | route service (`DirectionsController.getCommuteTimes`) |
| E5 | the number of candidates, relative to `commute-candidates` (20), and their straight-line distances when they tie | school data |
| E6 | the number of schools left after the commute check, relative to `top-n` (10) | school data and route service |
| E7 | the totals and commute times of schools that are next to each other in the order | all of the above |

## 5. Equivalence classes

Table 3 gives every class with its rule and the value that represents it. V marks a valid class and I an invalid
class. A valid class gives a list; an invalid class gives an exception.

**Table 3. Equivalence classes**

| Condition | Valid classes (representative) | Invalid classes (representative) |
|:--:|:--:|:--:|
| C1 `psleScore` | V1 whole number 4..32 (12) · V2 missing, when the dataset has no PSLE ranges | I1 below 4 (3) · I2 above 32 (33) · I3 missing, when the dataset has PSLE ranges |
| C2 `postingGroup` | V3 1, 2 or 3 (3) · V4 missing, when the dataset has no PSLE ranges | I4 below 1 (0) · I5 above 3 (4) · I6 missing, when the dataset has PSLE ranges |
| C3 `travelMode` | V5 `WALK`, `DRIVE` or `TRANSIT` (`TRANSIT`) | I7 missing |
| C4 `maxCommuteMin` | V6 one of 15, 30, 45, 60 (30) | I8 a whole number not in the set (20) · I9 missing |
| C5 `startLocation` | V7 resolved: a coordinate inside Singapore (Bishan) | I10 missing · I11a typed but not found: no coordinate ("Nowhere Road") · I11b a coordinate far outside Singapore (Kuala Lumpur) |
| C6 `weights` | V8 empty: the settings apply · V9 a value per factor, each 0 or more: these apply | I12 a negative weight (−0.01) · I13 a weight with no value (`null`) · I14 a weight that is not a number (`NaN`) |
| C7 CCAs | V10 no preference: weight 0 · V11 the school offers none (0.0) · V12 some (2 of 3 = 0.667) · V13 all (1.0) | – |
| C8 programmes | V14 no preference: weight 0 · V15 none offered (0.0) · V16 some (1 of 2 = 0.5) · V17 all (1.0) | – |
| C9 `primarySchool` | V18 not given: non-affiliated range · V19 names one of the school's affiliated primary schools, and the school has an affiliated range: that range ("CHIJ St. Nicholas Girls' School" against "CHIJ ST NICHOLAS GIRLS SCHOOL (PRIMARY)") · V20 given, not affiliated with this school: non-affiliated range · V21 affiliated, but the school has no affiliated range: non-affiliated range | – |
| E1 PSLE ranges in the dataset | V22 yes · V23 no (DC-74) | – |
| E2 school data | V24 readable | I15 the question "has PSLE ranges?" fails · I16 the school list fails |
| E3 range against score | V25 no range for the posting group: not a candidate · V26 only an IP range, student in PG1 or PG2: not a candidate · V27 score < L: still SAFE (13–16, score 12) · V28 SAFE, L ≤ score ≤ U − 2 · V29 MATCH, U − 2 < score ≤ U · V30 REACH, U < score ≤ U + 2 · V31 score > U + 2: excluded · V32 ranges for several years: the latest applies · V33 only an IP range, student in PG3: the IP range applies | – |
| E4 travel time t against M | V34 t ≤ M: kept, minutes rounded up · V35 t > M: excluded · V36 no route for this school: kept, "Not available" · V37 route service down: all kept, "Not available" | – |
| E5 candidates against 20 | V38 at most 20: all get a travel time · V39 more than 20, and the 21st has a lower pre-score: only the best 20 by PSLE fit, CCA and programme get one; the rest are dropped · V40 more than 20, and the 20th and 21st tie on pre-score: the school nearer in a straight line is kept | – |
| E6 schools left against 10 | V41 none: empty list · V42 1 to 10: all, ranked 1..n · V43 more than 10: the best 10 | – |
| E7 order of two schools | V44 different totals: higher first · V45 equal totals: shorter commute first · V46 equal totals and commute: by name | – |

That gives 46 valid classes and 17 invalid classes. Four choices need explaining:

- **C5.** `ReferenceLocation.isResolved` has two conditions: the start has a coordinate, and the coordinate lies inside
  Singapore. Each condition can fail on its own, so an unresolved start gives two invalid classes (I11a, I11b).
- **E2.** The class asks the data source two questions: "has PSLE ranges?" (DC-74) and "which schools are there?".
  Either can fail, so E2 has two invalid classes.
- **V27 and V28.** Both give SAFE. We keep them apart because the specification says the lower score is not used
  (scoring §2, DC-22). Code that accepts only scores "inside the range" would drop the V27 school.
- **V19.** The test value differs from the stored name only in the characters DC-82 ignores. Code that compares the
  names exactly, or only ignores case, picks the wrong range and the case fails.

## 6. Boundary values

We followed Lecture 18, slides 11–12: for each lower and upper boundary of a range, take one value just below, one on
the boundary and one just above. Slide 11 says a just-below or just-above value needs no extra case if it falls in a
class that is already tested. Table 4 lists all three values of every boundary and the case that uses each one. A dash
means the value needs no case of its own.

**Table 4. Boundary values (three per boundary)**

| Id | Boundary | Just below | On the boundary | Just above |
|:--:|:--:|:--:|:--:|:--:|
| B1 | lowest PSLE score 4 | 3, I1: case 11 | 4, V1: case 04 | 5, V1: case 03 |
| B2 | highest PSLE score 32 | 31, V1: case 02 | 32, V1: case 05 | 33, I2: cases 12 and 27 |
| B3 | lowest posting group 1 | 0, I4: cases 14 and 28 | 1, V3: case 02 | 2, V3: cases 04, 05, 06 |
| B4 | highest posting group 3 | 2, V3: cases 04, 05, 06 | 3, V3: case 01 | 4, I5: case 15 |
| B5 | weight 0 or more | −0.01, I12: case 24 | 0, V9: case 04 | 0.01, V9: – (same class as 0 and 1.0, case 04) |
| B6 | SAFE / MATCH at U − 2 | U − 3, SAFE: – (same class as U − 2) | U − 2, SAFE: case 01 (`fit-u14`) | U − 1, MATCH: case 01 (`fit-u13`) |
| B7 | MATCH / REACH at U | U − 1, MATCH: case 01 (`fit-u13`) | U, MATCH: case 01 (`fit-u12`) | U + 1, REACH: case 01 (`fit-u11`) |
| B8 | REACH / excluded at U + 2 | U + 1, REACH: case 01 (`fit-u11`) | U + 2, REACH: case 01 (`fit-u10`) | U + 3, excluded: case 01 (`fit-u09`) |
| B9 | lower score L, which the specification says is not used | L − 1, SAFE: case 01 (`fit-l13`) | L, SAFE: case 04 (`w-safe` 4–6) | L + 1, SAFE: – (same class as L) |
| B10 | commute limit M, in seconds | M − 1 = 3599 s, kept: – (same class as 600 s, case 05) | M = 3600 s, kept with COMMUTE 0.0: case 05 | M + 1 = 3601 s, excluded: case 05 |
| B11 | rounding up to whole minutes, at 600 s = 10 min | 599 s → 10 min: – (rounds up like 601 s) | 600 s → 10 min: case 05 | 601 s → 11 min: case 05 |
| B12 | `commute-candidates` 20 | 19 candidates: – (same class as 20, all get a time) | 20 candidates: case 09 | 21 candidates: cases 10 and 31 |
| B13 | `top-n` 10 | 9 schools left: – (same class as 10, all shown) | 10 schools left: case 10 | 11 schools left: case 09 |
| B14 | share of preferred items, from 0 to 1 | – (a share is never below 0) | 0 of n = 0.0: case 03 · n of n = 1.0: case 03 | – (a share is never above 1) |

At B9 the output must not change: L − 1, L and L + 1 all give SAFE. We test it because code that wrongly uses the
lower score would change its output exactly there.

Two inputs have no boundary values:

- **The longest commute** is one of four separate values, 15, 30, 45 and 60, not a range. Lecture 18, slide 16, gives
  such a set one valid and one invalid equivalence class and no boundary values. We test the invalid class I8 with 14,
  20 and 61 (cases 18 to 20). A check written as the range 15 ≤ x ≤ 60 would reject 14 and 61 but wrongly accept 20.
- **The starting point** is not an ordered value. "Inside Singapore" is the box in `Coordinate.isWithinSingapore`, and
  `CoordinateTest` tests its edges. I11b uses Kuala Lumpur, far from every edge.

## 7. Test set-up and notation

**Mocks.** `SchoolDataController` and `DirectionsController` are Mockito mocks. The data source returns small test
schools built with `TestSchools`, each with a known range. The route service knows made-up travel times in seconds
from Bishan, only for the travel mode of the case, so a wrong start or mode shows as "no route". All cases use
`start=Bishan` except TC-BB-REC-22, -23 and -32. Every school is at Bishan, except `a-far` in TC-BB-REC-31, which is
at Tampines (10.8 km away).

**Notation.** Each case turns the actual output into one line and compares it with the expected line. Table 5 shows
the parts of a line.

**Table 5. Notation of an output**

| Part | Example | Meaning |
|:--:|:--:|:--:|
| count | `6 results`, `0 results` | size of the returned list |
| result | `#1 fit-u13 total 0.786, 15 min` | rank, school code, total score (3 decimals), `commuteMin`; `commute n/a` when it is missing |
| PSLE fit | `FIT 1.0 MATCH`, `FIT 0.7 SAFE IP` | the factor's score, then the label this score stands for (scoring §5: MATCH 1.0, SAFE 0.7, REACH 0.3), then `IP` when the reason marks the range as an IP range (DC-77) |
| other factors | `COMMUTE 0.5; CCA 0.667; PROG 0.5` | the factor's score |
| not available | `COMMUTE n/a` | the commute reason says "Not available" |
| DC-74 | `FIT not used` | a reason of the result is "PSLE fit not used: no score ranges in the current dataset" |
| no reason | `CCA 0.5 (no reason)` | the factor's reason is blank; no expected line contains this |
| exception | `InvalidInputException {psleScore=Enter a whole number from 4 to 32}` | the field → message map; `*` in an expected line means any non-blank message, because the specification gives no text |
| exception | `ExternalServiceUnavailableException` | the class of the exception |

`CCA` and `PROG` are shown only when the criteria name a preference. Without one, the specification fixes only their
weight (0), not whether the factor is listed. For the same reason TC-BB-REC-04, where COMMUTE has weight 0, shows
only rank, code, total and minutes. TC-BB-REC-09, -10 and -31 show only rank and code, because their point is which
schools are in the list.

**Table 6. How the expected totals were computed** (total = Σ weight × score ÷ Σ weight, scoring §5)

| Case | Calculation |
|:--:|:--:|
| 01 | MATCH: (0.4 × 1.0 + 0.3 × (1 − 15/30)) ÷ (0.4 + 0.3) = 0.786; SAFE (`fit-l13`, `fit-u14`): (0.4 × 0.7 + 0.15) ÷ 0.7 = 0.614; REACH: (0.4 × 0.3 + 0.15) ÷ 0.7 = 0.386 |
| 02 | MATCH, 20 of 30 min: (0.4 × 1.0 + 0.3 × (1 − 20/30)) ÷ 0.7 = 0.714 |
| 03 | the weights sum to 1: `o-all` 0.4 × 1.0 + 0.3 × (1 − 15/45) + 0.15 × 1.0 + 0.15 × 1.0 = 0.9; `o-some` 0.4 + 0.2 + 0.15 × 2/3 + 0.15 × 0.5 = 0.775; `o-ip` 0.4 × 0.7 + 0.2 + 0 + 0 = 0.48 |
| 04 | criteria weights 1, 0, 1, 1; PROGRAMME has no preference: `w-near`, `w-far` (1 × 1.0 + 0 × c + 1 × 1.0) ÷ 2 = 1.0; `w-safe` (1 × 0.7 + 1 × 1.0) ÷ 2 = 0.85. With the settings instead, the order would be `w-near` 0.765, `w-safe` 0.741, `w-far` 0.718 |
| 05 | `t-600` (0.4 + 0.3 × (1 − 10/60)) ÷ 0.7 = 0.929; `t-601` (0.4 + 0.3 × (1 − 11/60)) ÷ 0.7 = 0.921; `t-3600` (0.4 + 0.3 × 0) ÷ 0.7 = 0.571; `t-none` 0.4 × 1.0 ÷ 0.4 = 1.0 |
| 06 | commute does not count: MATCH 0.4 × 1.0 ÷ 0.4 = 1.0; SAFE 0.4 × 0.7 ÷ 0.4 = 0.7 |
| 07 | PSLE fit does not count: `nd-choir` (0.15 × 1.0 + 0.3 × 0.5) ÷ 0.45 = 0.667; `nd-ranged` (0.15 × 0 + 0.3 × (1 − 6/30)) ÷ 0.45 = 0.533 |
| 09, 10 | `cap-safe` SAFE at 1 min: (0.4 × 0.7 + 0.3 × (1 − 1/30)) ÷ 0.7 = 0.814; `cap-mNN` MATCH at t min: (0.4 + 0.3 × (1 − t/30)) ÷ 0.7, from 0.7 (21 min) to 0.571 (30 min) |
| 31 | pre-score of every school: 0.4 × 1.0 ÷ 0.4 = 1.0, so all 21 tie. `near-NN` at 20 min: (0.4 + 0.3 × (1 − 20/30)) ÷ 0.7 = 0.714; `a-far` at 15 min, if it were kept: 0.786 |

## 8. Test cases

Unless a row says otherwise, the criteria are `psleScore=12; postingGroup=3; mode=TRANSIT; maxCommuteMin=30;
start=Bishan`, with no primary school, no preferences and no weights, and the dataset has PSLE ranges. Every range is
for admission year 2025 unless a row says otherwise.

The valid cases are 01 to 10 and 31. The invalid cases are 11 to 30 and 32.

**Table 7. Valid test cases (one case covers several valid classes)**

| Id | Requirement | Classes and boundaries | Input | Expected output | JUnit method |
|:--:|:--:|:--:|:--:|:--:|:--:|
| TC-BB-REC-01 | FR-REC-01; DC-20; DC-22 | V1 (12), V3 (3), V5, V6 (30), V7, V8, V10, V14, V18, V22, V24, V25, V27–V31, V34, V38, V42, V44, V46; B4, B6, B7, B8, B9 (L − 1) | default criteria. Schools, all 15 min: `fit-u14` PG3 8–14, `fit-u13` 8–13 named Anglican High School, `fit-u12` 8–12 named Zhonghua Secondary School, `fit-u11` 7–11, `fit-u10` 6–10, `fit-u09` 5–9 (and an affiliated 8–12 for Ai Tong School), `fit-l13` 13–16, `fit-pg1` only PG1 20–25 | 6 results: `fit-u13` MATCH 1.0, `fit-u12` MATCH 1.0 (equal total and commute, so by name: Anglican before Zhonghua, the opposite of code order), `fit-l13` SAFE 0.7 (score 12 is below its lower score 13), `fit-u14` SAFE 0.7 (equal to `fit-l13`, so by name), `fit-u10` REACH 0.3, `fit-u11` REACH 0.3; totals 0.786, 0.786, 0.614, 0.614, 0.386, 0.386; COMMUTE 0.5 each. `fit-u09` (U + 3) and `fit-pg1` (no PG3 range) are not listed | `tc01_psleFitAroundUpperScore` |
| TC-BB-REC-02 | FR-REC-01; DC-22; DC-77; DC-82 | V1 (31), V3 (1), V19, V20, V21, V26, V32; B2 (31), B3 (1) | `psleScore=31; postingGroup=1; primarySchool=CHIJ St. Nicholas Girls' School`. Schools, all 20 min: `aff-yes` PG1 24–28, affiliated 27–31 for CHIJ ST NICHOLAS GIRLS SCHOOL (PRIMARY); `aff-other` 27–31, affiliated 20–24 for RED SWASTIKA SCHOOL; `aff-norange` 27–31, affiliated with CHIJ ST NICHOLAS GIRLS SCHOOL (PRIMARY) but no affiliated range; `aff-years` 2024 24–28 and 2025 27–31; `aff-ip` only IP 27–31 | 4 results, all MATCH 1.0, total 0.714, COMMUTE 0.333, in name order: `aff-norange`, `aff-other`, `aff-years`, `aff-yes`. `aff-ip` is not listed, because an IP range is only a PG3 fallback. With a wrong range each listed school would be excluded, and `aff-ip` would be listed | `tc02_whichRangeApplies` |
| TC-BB-REC-03 | FR-REC-01; DC-77 | V1 (5), V6 (45), V11–V13, V15–V17, V28, V33, V44; B1 (5), B14 | `psleScore=5; maxCommuteMin=45; CCAs=[BASKETBALL, CHOIR, FENCING]; programmes=[Art, Music]`. Schools, all 15 min: `o-all` PG3 4–6, all 3 CCAs, Art and Music; `o-some` PG3 4–6, BASKETBALL, CHOIR and ROBOTICS, Art and Drama; `o-ip` only IP 4–8, ROBOTICS, Drama | 3 results: `o-all` MATCH, CCA 1.0, PROG 1.0, total 0.9; `o-some` MATCH, CCA 0.667, PROG 0.5, total 0.775; `o-ip` SAFE, reason marked "IP", CCA 0.0, PROG 0.0, total 0.48; COMMUTE 0.667 each | `tc03_ccaAndProgrammeShares` |
| TC-BB-REC-04 | FR-REC-01; DC-53 | V1 (4), V3 (2), V5 (`WALK`), V6 (15), V9, V28, V45; B1 (4), B3 and B4 (2), B5 (0), B9 (L) | `psleScore=4; postingGroup=2; mode=WALK; maxCommuteMin=15; CCAs=[CHOIR]; weights={PSLE_FIT=1.0, COMMUTE=0.0, CCA=1.0, PROGRAMME=1.0}`. Schools, all offer CHOIR: `w-near` PG2 4–5, 600 s; `w-far` PG2 4–5, 720 s; `w-safe` PG2 4–6, 300 s | 3 results: `w-near` 1.0, 10 min; `w-far` 1.0, 12 min (equal total, so the shorter commute first); `w-safe` 0.85, 5 min | `tc04_criteriaWeightsWithZero` |
| TC-BB-REC-05 | FR-REC-01 | V1 (32), V5 (`DRIVE`), V6 (60), V34, V35, V36; B2 (32), B10, B11 | `psleScore=32; postingGroup=2; mode=DRIVE; maxCommuteMin=60`. Schools, all PG2 28–32: `t-600` 600 s, `t-601` 601 s, `t-3600` 3600 s, `t-3601` 3601 s, `t-none` no route | 4 results: `t-none` total 1.0, commute n/a; `t-600` 10 min, COMMUTE 0.833, total 0.929; `t-601` 11 min, COMMUTE 0.817, total 0.921; `t-3600` 60 min, COMMUTE 0.0, total 0.571. `t-3601` is not listed | `tc05_commuteLimitInSeconds` |
| TC-BB-REC-06 | FR-REC-01; NFR-USE-03 | V37 | `psleScore=20; postingGroup=2; mode=DRIVE`. Schools: `rd-match` PG2 16–20, `rd-safe` PG2 18–22. The route service throws `ExternalServiceUnavailableException` | 2 results: `rd-match` MATCH, total 1.0; `rd-safe` SAFE, total 0.7; both commute n/a | `tc06_routeServiceDown` |
| TC-BB-REC-07 | FR-REC-01; DC-74 | V2, V4, V23 | `psleScore` and `postingGroup` missing; `CCAs=[CHOIR]`; the dataset has no PSLE ranges. Schools: `nd-choir` CHOIR, 900 s; `nd-ranged` PG3 8–12, ROBOTICS, 360 s; `nd-far` CHOIR, 2400 s | 2 results: `nd-choir` FIT not used, COMMUTE 0.5, CCA 1.0, total 0.667; `nd-ranged` FIT not used, COMMUTE 0.8, CCA 0.0, total 0.533. `nd-far` is over the limit and not listed | `tc07_noPsleData` |
| TC-BB-REC-08 | FR-REC-01 | V41 | default criteria. Schools: `e-pg1` only PG1 20–25; `e-u09` PG3 5–9, 600 s; `e-far` PG3 8–12, 1860 s | 0 results (EX-2) | `tc08_noSuitableSchool` |
| TC-BB-REC-09 | FR-REC-01 | V38 at 20, V43; B12 (20), B13 (11) | default criteria. 20 candidates: `cap-safe` PG3 8–14 at 1 min; `cap-m01`..`cap-m19` PG3 8–12, of which `cap-m01`..`cap-m10` at 21..30 min and `cap-m11`..`cap-m19` at 31 min | 10 results: `cap-safe`, `cap-m01` .. `cap-m09`, ranks 1..10. `cap-m10` is 11th and cut | `tc09_twentyCandidates` |
| TC-BB-REC-10 | FR-REC-01 | V39, V42 at 10; B12 (21), B13 (10) | as TC-BB-REC-09 with `cap-m20` added (31 min): 21 candidates | 10 results: `cap-m01` .. `cap-m10`, ranks 1..10. `cap-safe` is the 21st by PSLE fit, gets no travel time and is not listed | `tc10_twentyOneCandidates` |
| TC-BB-REC-31 | FR-REC-01 | V40, V43; B12 (21) | default criteria. 21 schools, all PG3 8–12 (MATCH, so the same pre-score): `a-far` at Tampines, 15 min, first in the data; `near-01`..`near-20` at Bishan, 20 min | 10 results: `near-01` .. `near-10`, ranks 1..10, total 0.714 each. `a-far` is the 21st by straight-line distance, gets no travel time and is not listed. If it had been kept, it would be first (0.786). It also comes first by name and in the data, so a tie-break by either of those would keep it | `tc31_tieAtTheTwentiethCandidate` |

The invalid cases start from the default criteria and one school `base` (PG3 8–12, 15 min). Without the invalid
value the output would be `1 result | #1 base total 0.786, 15 min`. Each case changes exactly one value.

**Table 8. Invalid test cases (one invalid class or value per case)**

| Id | Requirement | Class or boundary | Input (change) | Expected output | JUnit method |
|:--:|:--:|:--:|:--:|:--:|:--:|
| TC-BB-REC-11 | FR-REC-01; NFR-USE-03 | I1; B1 | `psleScore=3` | `InvalidInputException {psleScore=Enter a whole number from 4 to 32}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-12 | FR-REC-01; NFR-USE-03 | I2; B2 | `psleScore=33` | `InvalidInputException {psleScore=Enter a whole number from 4 to 32}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-13 | FR-REC-01; NFR-USE-03 | I3 | `psleScore` missing | `InvalidInputException {psleScore=Enter a whole number from 4 to 32}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-14 | FR-REC-01; NFR-USE-03 | I4; B3 | `postingGroup=0` | `InvalidInputException {postingGroup=Choose posting group 1, 2 or 3}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-15 | FR-REC-01; NFR-USE-03 | I5; B4 | `postingGroup=4` | `InvalidInputException {postingGroup=Choose posting group 1, 2 or 3}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-16 | FR-REC-01; NFR-USE-03 | I6 | `postingGroup` missing | `InvalidInputException {postingGroup=Choose posting group 1, 2 or 3}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-17 | FR-REC-01; NFR-USE-03 | I7 | `travelMode` missing | `InvalidInputException {travelMode=Choose walk, drive or public transport}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-18 | FR-REC-01; FR-FILTER-06; NFR-USE-03 | I8 (14) | `maxCommuteMin=14` | `InvalidInputException {maxCommuteMin=Choose 15, 30, 45 or 60 minutes}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-19 | FR-REC-01; FR-FILTER-06; NFR-USE-03 | I8 (20) | `maxCommuteMin=20` | `InvalidInputException {maxCommuteMin=Choose 15, 30, 45 or 60 minutes}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-20 | FR-REC-01; FR-FILTER-06; NFR-USE-03 | I8 (61) | `maxCommuteMin=61` | `InvalidInputException {maxCommuteMin=Choose 15, 30, 45 or 60 minutes}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-21 | FR-REC-01; NFR-USE-03 | I9 | `maxCommuteMin` missing | `InvalidInputException {maxCommuteMin=Choose 15, 30, 45 or 60 minutes}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-22 | FR-REC-01; NFR-USE-03 | I10 | `startLocation` missing | `InvalidInputException {startLocation=*}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-23 | FR-REC-01; NFR-USE-03 | I11b | `startLocation` = Kuala Lumpur (3.1390, 101.6869), far outside Singapore | `InvalidInputException {startLocation=*}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-24 | FR-REC-01; NFR-USE-03 | I12; B5 | `weights={PSLE_FIT=-0.01, COMMUTE=0.3, CCA=0.15, PROGRAMME=0.15}` | `InvalidInputException {weights=*}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-25 | FR-REC-01; NFR-USE-03 | I13 | `weights={PSLE_FIT=0.4, COMMUTE=0.3, CCA=null, PROGRAMME=0.15}` | `InvalidInputException {weights=*}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-26 | FR-REC-01; NFR-USE-03 | I14 | `weights={PSLE_FIT=0.4, COMMUTE=NaN, CCA=0.15, PROGRAMME=0.15}` | `InvalidInputException {weights=*}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-27 | FR-REC-01; NFR-USE-03; DC-74 | I2 when E1 = no; B2 | dataset without PSLE ranges; `psleScore=33` | `InvalidInputException {psleScore=Enter a whole number from 4 to 32}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-28 | FR-REC-01; NFR-USE-03; DC-74 | I4 when E1 = no; B3 | dataset without PSLE ranges; `postingGroup=0` | `InvalidInputException {postingGroup=Choose posting group 1, 2 or 3}` | `tc11to28and32_invalidInput` |
| TC-BB-REC-29 | FR-REC-01; NFR-USE-03 | I15 | `hasPsleData()` throws `IllegalStateException` | `ExternalServiceUnavailableException` (EX-3) | `tc29_psleDataQuestionFails` |
| TC-BB-REC-30 | FR-REC-01; NFR-USE-03 | I16 | `getSchools()` throws `IllegalStateException` | `ExternalServiceUnavailableException` (EX-3) | `tc30_schoolListFails` |
| TC-BB-REC-32 | FR-REC-01; NFR-USE-03 | I11a | `startLocation` typed as "Nowhere Road" but not found: no coordinate | `InvalidInputException {startLocation=*}` | `tc11to28and32_invalidInput` |

For TC-BB-REC-11 to -28 and -32 the test also checks that the map has only the one expected field, so a wrong extra
error fails the case. The exact expected lines of all 32 cases are in the JUnit methods and in `TC-BB-REC.csv`.

## 9. Minimising the test cases

We have 32 test cases: 11 valid and 21 invalid.

**We combine valid classes.** One call to `recommend` returns many schools, and each school is checked on its own. So
one case can hold several schools, each in a different class or on a different side of a boundary. For example,
TC-BB-REC-01 covers six positions of the score against U (B6, B7, B8), a score below the lower score (B9) and a school
with no range. We also picked the criteria of each case to sit on the edges of C1, C2 and C4: the scores 4, 5, 31 and
32, the posting groups 1 and 3, the options 15 and 60, and each travel mode. So 11 cases cover all 46 valid classes
and every boundary value in Table 4 that needs a case.

**Fewer valid cases would miss something.** Table 9 lists what only one case covers, so removing any case leaves a
class or boundary untested. Some classes cannot share a call:

- A case without PSLE data (07) or with the route service down (06) changes the output of every school in it.
- An empty list (08) cannot hold another school.
- The two sides of B12 and B13 need different numbers of schools, so they need two calls (09 and 10).
- With exactly 21 candidates, the 21st place is decided either by a lower pre-score (10) or by distance in a tie (31).
  One call cannot test both without moving off the boundary value 21.

**Table 9. What only one valid case covers**

| Case | Covered by this case only |
|:--:|:--:|
| 01 | V27, V30, V46; B6, B7, B8, B9 (L − 1) |
| 02 | V19, V20, V21, V26, V32; B2 (31), B3 (1) |
| 03 | V11–V13, V15–V17, V33; B1 (5), B14; the option 45 |
| 04 | V9, V45; B1 (4), B5 (0), B9 (L); the option 15; `WALK` |
| 05 | V36; B2 (32), B10, B11; the option 60 |
| 06 | V37 |
| 07 | V2, V4, V23 |
| 08 | V41 |
| 09 | B12 (20), B13 (11) |
| 10 | V39; B13 (10) |
| 31 | V40 |

**We do not combine invalid classes.** Each invalid case has exactly one invalid value, so one mistake cannot hide
another. With two invalid values, code that forgets one of the two rules would still throw an exception and the case
would pass. The 17 invalid classes need 17 cases. Four more cases test values inside a class that already has a case:
18 and 20 add 14 and 61 to I8 (section 6), and 27 and 28 show that the score and posting group are still checked when
the dataset has no PSLE ranges (DC-74).

**More cases would add nothing.** Any other value of a class follows the same rule as the one we test. Scores 6 to 30
behave like 12, U − 3 like U − 2, L − 2 like L − 1, and another place outside Singapore like Kuala Lumpur. Testing
every combination of the classes in Table 3 would need thousands of cases: C1 to C6 alone give 5 × 5 × 2 × 3 × 4 × 5 =
3,000. Those would only test the same rules again.

**Do the cases catch bugs?** To check, we put 15 bugs on purpose, one at a time, into a private copy of
`RecommendationController` and ran `RecommendationControllerBlackBoxTest` after each one. Each bug was caught by the
case designed for it. Table 10 gives the one-line change for each bug. It leaves out TC-BB-REC-26, which found a real
bug in our code (section 10).

**Table 10. Bugs we put in on purpose and the cases that caught them**

| Bug | Change in `RecommendationController` | Target | Failing cases |
|:--:|:--:|:--:|:--:|
| commute limit `>=` instead of `>` | in `addCommuteTimes`: `seconds > max * 60L` → `seconds >= max * 60L` | B10 | 05, 10 |
| minutes rounded down instead of up | `(int) ((seconds + 59) / 60)` → `(int) (seconds / 60)` | B11 | 05 |
| 21 commute candidates instead of 20 | in `recommend`: `commuteCandidates()` → `commuteCandidates() + 1` | B12 | 10, 31 |
| no tie-break by shorter commute | in `rank`: delete the line `.thenComparing(Recommendation::getCommuteMin, …)` | V45 | 04 |
| last tie-break by school code instead of by name | in `rank`: `.thenComparing(Recommendation::getSchool, SchoolDataCache.BY_NAME_THEN_CODE)` → `.thenComparing(r -> r.getSchool().getSchoolCode())` | V46 | 01 |
| score 32 rejected | in `validate`: `score > IndicativePsleScoreRange.MAX_SCORE` → `score >= …` | B2 | 05 |
| affiliated range never used | in `scoreSchool`: `boolean affiliated = school.hasAffiliatedPrimarySchool(…)` → `boolean affiliated = false` | V19 | 02 |
| REACH only up to U + 1 | in `psleFit`: `score > upper + …reachNearMargin()` → `score > upper + …reachNearMargin() - 1` | B8 | 01 |
| 11 results instead of 10 | in `rank`: `int topN = props.recommendation().topN()` → `… topN() + 1` | B13 | 09, 31 |
| score below the lower score excluded | in `scoreSchool`, before `psleFit`: add `if (score < range.get().getLowerScore()) return null;` | B9, V27 | 01 |
| no tie-break by straight-line distance before the cut | in `recommend`: delete the line `.thenComparingDouble(r -> … distanceTo(start) …)` | V40 | 31 |
| IP range used for every posting group | in `scoreSchool`: `school.getScoreRange(pg, affiliated)` → `school.getScoreRange(pg, affiliated).or(() -> the school's latest IP range)` | V26 | 02 |
| primary school names compared ignoring case only | in `scoreSchool`: `school.hasAffiliatedPrimarySchool(p)` → `school.getAffiliatedPrimarySchools().stream().anyMatch(n -> n.equalsIgnoreCase(p))` | V19 | 02 |
| start coordinate read without a null check | in `validate`: `!criteria.getStartLocation().isResolved()` → `!criteria.getStartLocation().getCoordinate().isWithinSingapore()` | I11a | 32 |
| start checked only for a coordinate | in `validate`: `!criteria.getStartLocation().isResolved()` → `criteria.getStartLocation().getCoordinate() == null` | I11b | 23 |

## 10. Bug found

**TC-BB-REC-26: a weight that is not a number was accepted.** Every weight must be 0 or more
(`MatchCriteria.isValid` requires `w != null && w >= 0`), and `NaN` is not. We expected `InvalidInputException
{weights=*}`, but the first run on 6 Nov 2026 gave `1 result | #1 base total NaN, 15 min: FIT 1.0 MATCH; COMMUTE 0.5`.
The check in `RecommendationController.validate` was `w == null || w < 0`, and `NaN < 0` is false, so `NaN` passed.
Every total then became `NaN` and the ranking meant nothing. Users could not hit this on the site, because the
criteria form has no weights field; the weights come from `app.recommendation.weights`. We changed the check to
`w == null || w.isNaN() || w < 0`, and this case now passes.

## 11. How to run

```
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B -q -Dtest=RecommendationControllerBlackBoxTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Each run writes `target/test-results/black-box-recommendation.csv` with the input, expected output and actual output
of all 32 cases. Results: see `test-results.md`.

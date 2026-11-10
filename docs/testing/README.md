# Lab 4 testing (section 3.2)

| Item | Value |
|:--:|:--:|
| Date | 9 Nov 2026 |
| Author | Lingyi Liu |
| Code version | branch `test/lab4-testing`, based on commit `856c52f` |
| Results | [`test-results.md`](test-results.md), from the run of 9 Nov 2026 |

## 1. What section 3.2 asks for and where it is answered

Terms used in all the testing documents:

- An **equivalence class** is a set of input values that the specification treats the same way, so one value from
  the set stands for all of them (Lecture 18).
- A **boundary value** is a value on the lower or upper edge of a range of values, or the value just below or just
  above that edge (Lecture 18, slides 11–12). A set of separate values has no boundary values (slide 16).
- A **control flow graph** shows one method as nodes (a block of statements or one decision) joined by edges (the
  possible next steps).
- Paths are **independent** when none of them can be built by adding and subtracting the others, with each path
  written as the number of times it uses each edge.
- A **decision node** has two outgoing edges, such as an `if` test or a loop test.
- A **basis set** is a set of V(G) independent paths from entry to exit; each of its paths is a **basis path**. We
  select them with the rule of Lecture 19–20, slide 21: each new path uses at least one edge that no path listed
  before it used.
- The **cyclomatic complexity** V(G) of a control flow graph is the number of paths in a basis set, and so the number
  of basis paths to test (Lecture 19–20, slides 23–25).
- A path is **feasible** when some input makes the method follow it.
- A **region** is an area enclosed by the edges of a drawn graph in which no two edges cross. The area outside the
  graph counts as one region.
- A **mock** is a stand-in object whose answers the test sets. A **spy** is a real object whose method calls the test
  can count.

**Table 1. Section 3.2 of the Lab 4 manual and where each part is answered**

| Section | What it asks | Where it is answered |
|:--:|:--:|:--:|
| 3.2.1 | Equivalence class and boundary value testing of one important control class | [`black-box-recommendation.md`](black-box-recommendation.md): `RecommendationController`, sections 3–8 |
| 3.2.2 (first method) | Basis path testing of a method with complex logic | [`basis-path-recommend.md`](basis-path-recommend.md): `RecommendationController.recommend`, sections 3–6 |
| 3.2.2 (second method) | Basis path testing of a second such method | [`basis-path-login.md`](basis-path-login.md): `AuthController.login`, sections 4–7 |
| 3.2.3 | Keep the number of test cases small by choosing the inputs with care | `black-box-recommendation.md` section 9; `basis-path-recommend.md` section 7; `basis-path-login.md` section 8 |
| 3.2.4 | Run the test cases and give Test Input, Expected Output and Actual Output | [`test-results.md`](test-results.md), one table per document, from the JUnit 5 run |

The black-box tests use only the public behaviour of `RecommendationController`: its constructor, `recommend` and the
exceptions it throws. So they still work when section 3.3.2 has an AI write the class again, as long as the new class
gets its data through the calls listed in section 2 of `black-box-recommendation.md` and keeps the public constant
`PSLE_FIT_NOT_USED`, which other tests refer to.

## 2. Test cases per document

**Table 2. Test cases and results of the run on 9 Nov 2026**

| Document | Technique | Under test | Test case ids | Cases | Passed / failed |
|:--:|:--:|:--:|:--:|:--:|:--:|
| `black-box-recommendation.md` | equivalence classes and boundary values | `RecommendationController` | TC-BB-REC-01 to TC-BB-REC-32 | 32 (11 valid, 21 invalid) | 32 / 0 |
| `basis-path-recommend.md` | basis paths, V(G) = 8 | `RecommendationController.recommend` | TC-BP-REC-01 to TC-BP-REC-08 | 8 | 8 / 0 |
| `basis-path-login.md` | basis paths, V(G) = 10 | `AuthController.login` | TC-BP-LOGIN-01 to TC-BP-LOGIN-10 | 10 | 10 / 0 |
| Total | | | | 50 | 50 / 0 |

The JUnit class of each document is in Table 4. All three JUnit classes are in `src/test/java/sg/schoolmatch/control/`.

## 3. Results of the run

The first run of the whole suite, on 6 Nov 2026, had one failure: TC-BB-REC-26, which found a bug in the code. We
fixed the bug and ran the whole suite again on 9 Nov 2026 with
`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B -o clean verify` on OpenJDK 21.0.7 (Microsoft build), starting
from an empty `target/`. It ran 1658 JUnit tests in 117 test classes: 0 failures, 0 errors, 0 skipped, and Maven
ended with "BUILD SUCCESS". All 50 of our Lab 4 test cases pass.

### TC-BB-REC-26: a bug the tests found

**Table 3. The bug found by TC-BB-REC-26**

| Part | Value |
|:--:|:--:|
| Input | default criteria, one MATCH school, and `weights={PSLE_FIT=0.4, COMMUTE=NaN, CCA=0.15, PROGRAMME=0.15}` |
| Expected output | `InvalidInputException {weights=*}`: the weight is rejected |
| Actual output on 6 Nov | `1 result \| #1 base total NaN, 15 min: FIT 1.0 MATCH; COMMUTE 0.5`: the weight is accepted |
| Specification | `MatchCriteria.isValid` requires every weight to satisfy `w != null && w >= 0`. `NaN` fails `w >= 0`. |
| Cause | `RecommendationController.validate` (line 151) rejected a weight only when `w == null \|\| w < 0`. `NaN < 0` is false, so `NaN` passed. Every total then became `NaN`, and the ranking had no meaning. |
| Effect | Small. The criteria form has no weights field; the weights come from `app.recommendation.weights`. |
| Fix | `validate` now rejects a weight when `w == null \|\| w.isNaN() \|\| w < 0`. |
| Actual output on 9 Nov | `InvalidInputException {weights=Weights must be 0 or more}`: the weight is rejected and the case passes |

The bug was in the code, not in the test. We kept the expected value from the specification and fixed the code.
Section 10 of `black-box-recommendation.md` has the details.

## 4. How to run

Run these from the project root. This command runs only the three Lab 4 classes:

```
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B -o clean test -Dtest='RecommendationControllerBlackBoxTest,RecommendationControllerBasisPathTest,AuthControllerBasisPathTest' -Dsurefire.failIfNoSpecifiedTests=false
```

This one runs the whole suite:

```
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B -o clean verify
```

Leave out `-o` if Maven has not downloaded the plugins yet.

Each run writes two kinds of files:

- `target/test-results/<document>.csv`: for each test case, the input, the expected output and the actual output.
  `src/test/java/sg/schoolmatch/support/TestResultLog.java` writes them before each assertion, so a failing case is
  recorded too.
- `target/surefire-reports/TEST-*.xml`: pass or fail of each JUnit test.

To rebuild the results table:

```
python3 -I docs/testing/make_results.py --command "<the Maven command you ran>"
```

The script stops without writing anything if a test case id is missing or repeated, or if a recorded output disagrees
with the JUnit result.

## 5. Traceability

The design changes (DC) below are recorded in `docs/design-changes.md`, and the tests use them as the specification.

**Table 4. Requirement → design class and method → test cases → JUnit class**

| Requirement | Design class and method | Test cases | JUnit class |
|:--:|:--:|:--:|:--:|
| FR-REC-01 | `RecommendationController.recommend(MatchCriteria)` | TC-BB-REC-01 to TC-BB-REC-32; TC-BP-REC-01 to TC-BP-REC-08 | `RecommendationControllerBlackBoxTest`; `RecommendationControllerBasisPathTest` |
| NFR-USE-03 | `RecommendationController.recommend`: field errors and "temporarily unavailable" errors | TC-BB-REC-06, TC-BB-REC-11 to TC-BB-REC-30, TC-BB-REC-32; TC-BP-REC-02 to TC-BP-REC-04 | `RecommendationControllerBlackBoxTest`; `RecommendationControllerBasisPathTest` |
| FR-FILTER-06 | `RecommendationController.recommend`: the commute options 15, 30, 45 and 60 minutes (`TransportationFilter.DURATION_OPTIONS_MIN`) | TC-BB-REC-18 to TC-BB-REC-20 | `RecommendationControllerBlackBoxTest` |
| FR-LOGIN-01 | `AuthController.login(String, String)` | TC-BP-LOGIN-01, TC-BP-LOGIN-06 | `AuthControllerBasisPathTest` |
| FR-LOGIN-02 | `AuthController.login` | TC-BP-LOGIN-02 to TC-BP-LOGIN-05, TC-BP-LOGIN-10 | `AuthControllerBasisPathTest` |
| FR-LOGIN-03 | `AuthController.login`; `Account.verifyPassword` | TC-BP-LOGIN-01, TC-BP-LOGIN-08 | `AuthControllerBasisPathTest` |
| FR-LOGIN-04 | `AuthController.login` | TC-BP-LOGIN-01, TC-BP-LOGIN-06 | `AuthControllerBasisPathTest` |
| FR-LOGIN-05 | `AuthController.login` | TC-BP-LOGIN-07 to TC-BP-LOGIN-09 | `AuthControllerBasisPathTest` |
| DC-20 | `RecommendationController.recommend`: SAFE, MATCH and REACH | TC-BB-REC-01 | `RecommendationControllerBlackBoxTest` |
| DC-22 | `RecommendationController.recommend`: which PSLE range applies | TC-BB-REC-01, TC-BB-REC-02 | `RecommendationControllerBlackBoxTest` |
| DC-53 | `RecommendationController.recommend`: the settings and the criteria weights | TC-BB-REC-04 | `RecommendationControllerBlackBoxTest` |
| DC-74 | `RecommendationController.recommend`: a dataset without PSLE ranges | TC-BB-REC-07, TC-BB-REC-27, TC-BB-REC-28; TC-BP-REC-07 | `RecommendationControllerBlackBoxTest`; `RecommendationControllerBasisPathTest` |
| DC-77 | `RecommendationController.recommend`: the IP range as PG3 fallback | TC-BB-REC-02, TC-BB-REC-03 | `RecommendationControllerBlackBoxTest` |
| DC-82 | `RecommendationController.recommend`: one name rule for affiliated primary schools | TC-BB-REC-02 | `RecommendationControllerBlackBoxTest` |
| DC-46 | `AuthController.login`: one generic error for every failed login | TC-BP-LOGIN-07 to TC-BP-LOGIN-09 | `AuthControllerBasisPathTest` |
| DC-70 | `AuthController.login`: username looked up by its lower-case key | TC-BP-LOGIN-01, TC-BP-LOGIN-07 to TC-BP-LOGIN-09 | `AuthControllerBasisPathTest` |

## 6. Other automated tests

The repository has 1608 other JUnit tests in 114 test classes. They are unit tests of the entity, control, boundary,
dataset and configuration classes (among them `RecommendationControllerTest` with `TC-REC.csv` and
`AuthControllerTest` with `TC-LOGIN.csv`), persistence tests on H2 and on an embedded PostgreSQL server, flow tests
that start the Spring Boot application and send requests to its pages, and an architecture test of the package and
naming rules. All of them passed in the same run. We wrote them during development. The 50 test cases above are the
ones we designed for section 3.2.

## 7. Files of this deliverable

All are on branch `test/lab4-testing`. The only code change under `src/main` is the one-line fix for TC-BB-REC-26 in
`RecommendationController.validate`. The class comment in the same file also drops "proposed id" after FR-REC-01.

**Table 5. Files of the Lab 4 testing deliverable**

| File | Content |
|:--:|:--:|
| `docs/testing/README.md` | this overview |
| `docs/testing/black-box-recommendation.md` | 3.2.1 and its part of 3.2.3 |
| `docs/testing/basis-path-recommend.md` | 3.2.2 for `recommend` and its part of 3.2.3 |
| `docs/testing/basis-path-login.md` | 3.2.2 for `login` and its part of 3.2.3 |
| `docs/testing/test-results.md` | 3.2.4, generated |
| `docs/testing/make_results.py` | builds `test-results.md` |
| `docs/testing/figures/cfg-recommend.*`, `docs/testing/figures/cfg-login.*` | control flow graphs (Mermaid source, SVG, PNG) |
| `docs/testing/figures/render_mermaid.py` | turns a `.mmd` file into SVG and PNG |
| `src/test/java/sg/schoolmatch/control/RecommendationControllerBlackBoxTest.java` | TC-BB-REC-01 to TC-BB-REC-32 |
| `src/test/java/sg/schoolmatch/control/RecommendationControllerBasisPathTest.java` | TC-BP-REC-01 to TC-BP-REC-08 |
| `src/test/java/sg/schoolmatch/control/AuthControllerBasisPathTest.java` | TC-BP-LOGIN-01 to TC-BP-LOGIN-10 |
| `src/test/resources/testcases/TC-BB-REC.csv` | the rows of TC-BB-REC-11 to TC-BB-REC-28 and TC-BB-REC-32 |
| `src/test/java/sg/schoolmatch/support/TestResultLog.java` | writes `target/test-results/*.csv` |

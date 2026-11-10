package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.common.Place;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.recommend.MatchCriteria;
import sg.schoolmatch.entity.recommend.MatchFactor;
import sg.schoolmatch.entity.recommend.Recommendation;
import sg.schoolmatch.entity.recommend.ScoreComponent;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.support.TestProperties;
import sg.schoolmatch.support.TestResultLog;
import sg.schoolmatch.support.TestSchools;

/**
 * Lab 4 section 3.2.1: black-box tests of the control class {@link RecommendationController} with equivalence
 * classes and boundary values (use case #06 Get School Recommendations, FR-REC-01; rules in
 * docs/recommendation-scoring.md sections 1, 2 and 5, DC-20, DC-22, DC-53, DC-74, DC-77, DC-82). The classes,
 * boundaries and the reason for each case are in docs/testing/black-box-recommendation.md (TC-BB-REC-01..32).
 * <p>
 * Only public behaviour is used, so the tests still apply when the class is written again from scratch (section
 * 3.3.2): the constructor of the design, {@code recommend(MatchCriteria)}, the returned {@link Recommendation}s and
 * the exceptions. {@link SchoolDataController} and {@link DirectionsController} are Mockito fakes: the data source
 * returns small schools with known ranges; the route service knows travel times in seconds from Bishan for one
 * travel mode only, so a wrong start point or mode would show up as "no route".
 * <p>
 * Every case records its input, the output the specification predicts and the actual output with
 * {@link TestResultLog} (file {@code black-box-recommendation}) before it asserts. Notation of an output:
 * {@code "#1 fit-u13 total 0.786, 15 min: FIT 1.0 MATCH; COMMUTE 0.5"} = rank, school code, total score and travel
 * time, then one entry per factor with its score (3 decimals). For PSLE_FIT the label is the one its score stands for
 * (scoring section 5: MATCH 1.0, SAFE 0.7, REACH 0.3), plus "IP" when the reason marks the range as an Integrated
 * Programme range (DC-77). The specification gives the wording of a reason only as an example, so the wording is not
 * compared; a blank reason shows as "(no reason)". "COMMUTE n/a" = the reason says "Not available"; "FIT not used" =
 * the DC-74 reason. CCA and PROG are shown only when the criteria name a preference: the specification fixes only
 * their weight (0) otherwise. Expected outputs were written from the specification before the first run.
 */
class RecommendationControllerBlackBoxTest {

    private static final String FILE = "black-box-recommendation";
    /** DC-74 and docs/recommendation-scoring.md section 5: the reason of every result without PSLE ranges. */
    private static final String PSLE_FIT_NOT_USED = "PSLE fit not used: no score ranges in the current dataset";
    /** app.recommendation.psle-fit.match / .safe / .reach-near in application.yml (scoring section 5, DC-53). */
    private static final double FIT_MATCH = 1.0;
    private static final double FIT_SAFE = 0.7;
    private static final double FIT_REACH = 0.3;

    private static final ReferenceLocation BISHAN =
            new ReferenceLocation(TestSchools.BISHAN, LocationSource.MANUAL_ENTRY, "Bishan");
    /** I11b: a real place about 300 km from Singapore, far from every edge of Singapore's box. */
    private static final ReferenceLocation KUALA_LUMPUR = new ReferenceLocation(
            new Coordinate(3.1390, 101.6869), LocationSource.MANUAL_ENTRY, "Kuala Lumpur (3.1390, 101.6869)");
    /** I11a: a typed place that the location service did not find, so it has no coordinate. */
    private static final ReferenceLocation NOT_FOUND = new ReferenceLocation(
            null, LocationSource.MANUAL_ENTRY, "Nowhere Road (not found, no coordinate)");

    private final SchoolDataController schoolData = mock(SchoolDataController.class);
    private final DirectionsController directions = mock(DirectionsController.class);
    private final LocationController location = mock(LocationController.class);
    /** app.recommendation.* exactly as in application.yml (DC-53, docs/recommendation-scoring.md section 6). */
    private final AppProperties props = TestProperties.bind(Map.ofEntries(
            Map.entry("app.recommendation.weights.psle-fit", 0.4),
            Map.entry("app.recommendation.weights.commute", 0.3),
            Map.entry("app.recommendation.weights.cca", 0.15),
            Map.entry("app.recommendation.weights.programme", 0.15),
            Map.entry("app.recommendation.commute-candidates", 20),
            Map.entry("app.recommendation.top-n", 10),
            Map.entry("app.recommendation.psle-fit.match", FIT_MATCH),
            Map.entry("app.recommendation.psle-fit.safe", FIT_SAFE),
            Map.entry("app.recommendation.psle-fit.reach-near", FIT_REACH),
            Map.entry("app.recommendation.reach-near-margin", 2)));
    private final RecommendationController controller =
            new RecommendationController(location, directions, schoolData, props);

    /** The fake route service: travel time in seconds from Bishan by school code; a missing code has no route. */
    private final Map<String, Integer> seconds = new HashMap<>();
    /** The only travel mode the fake route service has times for. */
    private TravelMode routedMode = TravelMode.TRANSIT;

    @BeforeEach
    void fakeEnvironment() {
        when(schoolData.hasPsleData()).thenReturn(true);   // E1: the dataset has PSLE ranges unless a case says not
        when(directions.getCommuteTimes(any(), anyList(), any())).thenAnswer(call -> {
            ReferenceLocation origin = call.getArgument(0);
            List<? extends Place> places = call.getArgument(1);
            TravelMode mode = call.getArgument(2);
            boolean fromBishan = origin != null && BISHAN.getCoordinate().equals(origin.getCoordinate());
            List<Route> routes = new ArrayList<>();
            for (Place place : places) {
                Integer s = seconds.get(((School) place).getSchoolCode());
                routes.add(s != null && fromBishan && mode == routedMode
                        ? new Route(mode, 1000, s, null, List.of()) : Route.unavailable(mode));
            }
            return routes;
        });
    }

    // ------------------------------------------------------------------ valid classes, combined

    /**
     * SAFE / MATCH / REACH and exclusion around U for one score (scoring sections 2 and 5; boundaries U−2|U−1,
     * U|U+1, U+2|U+3), a score one point better than the lower score L (still SAFE: the lower score is not used,
     * scoring section 2, DC-22), a school without a range for the posting group, no primary school (non-affiliated
     * range, DC-22), default weights with no CCA or programme preference (those factors get weight 0, the rest is
     * rescaled), ties broken by name. fit-u13 and fit-u12 tie on total and commute; their names are chosen so that
     * name order (Anglican before Zhonghua) is the opposite of code order, so a tie-break by code would fail.
     */
    @Test
    @Tag("FR-REC-01")
    @Tag("DC-20")
    @DisplayName("TC-BB-REC-01: PSLE fit at U-2, U-1, U, U+1, U+2, U+3 and at L-1; ties by name")
    void tc01_psleFitAroundUpperScore() {
        schools(TestSchools.school("fit-u14").range(2025, 3, 8, 14).build(),
                TestSchools.school("fit-u13").name("Anglican High School").range(2025, 3, 8, 13).build(),
                TestSchools.school("fit-u12").name("Zhonghua Secondary School").range(2025, 3, 8, 12).build(),
                TestSchools.school("fit-u11").range(2025, 3, 7, 11).build(),
                TestSchools.school("fit-u10").range(2025, 3, 6, 10).build(),
                TestSchools.school("fit-u09").range(2025, 3, 5, 9).affiliatedRange(2025, 3, 8, 12)
                        .affiliatedPrimarySchools("AI TONG SCHOOL").build(),
                TestSchools.school("fit-l13").range(2025, 3, 13, 16).build(),
                TestSchools.school("fit-pg1").range(2025, 1, 20, 25).build());
        minutes(15, "fit-u14", "fit-u13", "fit-u12", "fit-u11", "fit-u10", "fit-u09", "fit-l13", "fit-pg1");

        check("TC-BB-REC-01", criteria(12, 3, TravelMode.TRANSIT, 30),
                "schools (2025 PG3 ranges, 15 min each): fit-u14 8–14, fit-u13 8–13 (named Anglican High School), "
                        + "fit-u12 8–12 (named Zhonghua Secondary School), fit-u11 7–11, "
                        + "fit-u10 6–10, fit-u09 5–9 (affiliated 8–12 for Ai Tong School), fit-l13 13–16, "
                        + "fit-pg1 has only PG1 20–25",
                View.DETAIL,
                "6 results"
                        + " | #1 fit-u13 total 0.786, 15 min: FIT 1.0 MATCH; COMMUTE 0.5"
                        + " | #2 fit-u12 total 0.786, 15 min: FIT 1.0 MATCH; COMMUTE 0.5"
                        + " | #3 fit-l13 total 0.614, 15 min: FIT 0.7 SAFE; COMMUTE 0.5"
                        + " | #4 fit-u14 total 0.614, 15 min: FIT 0.7 SAFE; COMMUTE 0.5"
                        + " | #5 fit-u10 total 0.386, 15 min: FIT 0.3 REACH; COMMUTE 0.5"
                        + " | #6 fit-u11 total 0.386, 15 min: FIT 0.3 REACH; COMMUTE 0.5");
    }

    /**
     * Which range applies (scoring section 1, DC-22, DC-77, DC-82): the affiliated range for an affiliated primary
     * school whose name is written differently (case, dots, apostrophes, brackets and a trailing "(Primary)" are
     * ignored, DC-82), the non-affiliated one for a school the student is not affiliated with, the non-affiliated one
     * when an affiliated school has no affiliated range, the latest admission year, and no IP range for a PG1
     * student (the IP range is only a PG3 fallback). Score 31 and PG1 are boundary values; every listed school is
     * MATCH only with the right range and excluded with a wrong one, and aff-ip would be MATCH if its IP range were
     * used.
     */
    @Test
    @Tag("FR-REC-01")
    @Tag("DC-22")
    @Tag("DC-82")
    @DisplayName("TC-BB-REC-02: affiliated range by name rule, non-affiliated range, fallback, latest year, no IP for PG1")
    void tc02_whichRangeApplies() {
        schools(TestSchools.school("aff-yes").range(2025, 1, 24, 28).affiliatedRange(2025, 1, 27, 31)
                        .affiliatedPrimarySchools("CHIJ ST NICHOLAS GIRLS SCHOOL (PRIMARY)").build(),
                TestSchools.school("aff-other").range(2025, 1, 27, 31).affiliatedRange(2025, 1, 20, 24)
                        .affiliatedPrimarySchools("RED SWASTIKA SCHOOL").build(),
                TestSchools.school("aff-norange").range(2025, 1, 27, 31)
                        .affiliatedPrimarySchools("CHIJ ST NICHOLAS GIRLS SCHOOL (PRIMARY)").build(),
                TestSchools.school("aff-years").range(2024, 1, 24, 28).range(2025, 1, 27, 31).build(),
                TestSchools.school("aff-ip").ipRange(2025, 27, 31).build());
        minutes(20, "aff-yes", "aff-other", "aff-norange", "aff-years", "aff-ip");
        MatchCriteria criteria = criteria(31, 1, TravelMode.TRANSIT, 30);
        criteria.setPrimarySchool("CHIJ St. Nicholas Girls' School");

        check("TC-BB-REC-02", criteria,
                "schools (PG1, 20 min each): aff-yes 2025 24–28, affiliated 27–31 for "
                        + "CHIJ ST NICHOLAS GIRLS SCHOOL (PRIMARY); "
                        + "aff-other 2025 27–31, affiliated 20–24 for RED SWASTIKA SCHOOL; "
                        + "aff-norange 2025 27–31, affiliated with CHIJ ST NICHOLAS GIRLS SCHOOL (PRIMARY) but no "
                        + "affiliated range; aff-years 2024 24–28 then 2025 27–31; aff-ip only IP 27–31",
                View.DETAIL,
                "4 results"
                        + " | #1 aff-norange total 0.714, 20 min: FIT 1.0 MATCH; COMMUTE 0.333"
                        + " | #2 aff-other total 0.714, 20 min: FIT 1.0 MATCH; COMMUTE 0.333"
                        + " | #3 aff-years total 0.714, 20 min: FIT 1.0 MATCH; COMMUTE 0.333"
                        + " | #4 aff-yes total 0.714, 20 min: FIT 1.0 MATCH; COMMUTE 0.333");
    }

    /**
     * CCA and programme shares (scoring section 5): all, some and none of the preferred items (boundaries 0 of n and
     * n of n), with all four default weights counting (they sum to 1, no rescaling). The third school offers only an
     * Integrated Programme range, used as its PG3 range and marked "IP" in the reason (DC-77). Score 5 and the
     * longest commute 45 are valid values.
     */
    @Test
    @Tag("FR-REC-01")
    @Tag("DC-77")
    @DisplayName("TC-BB-REC-03: CCA and programme shares 1, 2/3, 1/2 and 0; IP-only school (score 5, 45 min)")
    void tc03_ccaAndProgrammeShares() {
        schools(TestSchools.school("o-all").range(2025, 3, 4, 6)
                        .ccas("BASKETBALL", "CHOIR", "FENCING").programmes("Art", "Music").build(),
                TestSchools.school("o-some").range(2025, 3, 4, 6)
                        .ccas("BASKETBALL", "CHOIR", "ROBOTICS").programmes("Art", "Drama").build(),
                TestSchools.school("o-ip").ipRange(2025, 4, 8).ccas("ROBOTICS").programmes("Drama").build());
        minutes(15, "o-all", "o-some", "o-ip");
        MatchCriteria criteria = criteria(5, 3, TravelMode.TRANSIT, 45);
        criteria.setPreferredCCAs(List.of("BASKETBALL", "CHOIR", "FENCING"));
        criteria.setPreferredProgrammes(List.of("Art", "Music"));

        check("TC-BB-REC-03", criteria,
                "schools (15 min each): o-all PG3 4–6, all 3 CCAs, Art and Music; o-some PG3 4–6, BASKETBALL, CHOIR "
                        + "and ROBOTICS, Art and Drama; o-ip only IP 4–8, ROBOTICS, Drama",
                View.DETAIL,
                "3 results"
                        + " | #1 o-all total 0.9, 15 min: FIT 1.0 MATCH; COMMUTE 0.667; CCA 1.0; PROG 1.0"
                        + " | #2 o-some total 0.775, 15 min: FIT 1.0 MATCH; COMMUTE 0.667; CCA 0.667; PROG 0.5"
                        + " | #3 o-ip total 0.48, 15 min: FIT 0.7 SAFE IP; COMMUTE 0.667; CCA 0.0; PROG 0.0");
    }

    /**
     * Weights given in the criteria are used instead of the settings (DC-53), and a weight of 0 (the boundary of
     * "0 or more", {@code MatchCriteria.isValid}) makes its factor count for nothing: with COMMUTE at 0 the two MATCH
     * schools tie on total and the shorter commute goes first (scoring section 5 step 4). With the default weights the
     * SAFE school would be second. Score 4 is the lower score L of every range here. Score 4, PG2, WALK and the
     * longest commute 15 are valid values.
     */
    @Test
    @Tag("FR-REC-01")
    @Tag("DC-53")
    @DisplayName("TC-BB-REC-04: criteria weights with COMMUTE 0; equal totals ordered by shorter commute")
    void tc04_criteriaWeightsWithZero() {
        schools(TestSchools.school("w-near").range(2025, 2, 4, 5).ccas("CHOIR").build(),
                TestSchools.school("w-far").range(2025, 2, 4, 5).ccas("CHOIR").build(),
                TestSchools.school("w-safe").range(2025, 2, 4, 6).ccas("CHOIR").build());
        routedMode = TravelMode.WALK;
        seconds.putAll(Map.of("w-near", 600, "w-far", 720, "w-safe", 300));
        MatchCriteria criteria = criteria(4, 2, TravelMode.WALK, 15);
        criteria.setPreferredCCAs(List.of("CHOIR"));
        criteria.setWeights(weights(1.0, 0.0, 1.0, 1.0));

        check("TC-BB-REC-04", criteria,
                "schools (walking, all offer CHOIR): w-near PG2 4–5, 600 s; w-far PG2 4–5, 720 s; w-safe PG2 4–6, 300 s",
                View.SUMMARY,
                "3 results | #1 w-near total 1.0, 10 min | #2 w-far total 1.0, 12 min | #3 w-safe total 0.85, 5 min");
    }

    /**
     * The commute limit and the minutes (scoring section 5, COMMUTE row): 3600 s is on the 60-minute limit and kept
     * with score 0, 3601 s is one second over and excluded; 600 s is 10 min, 601 s rounds up to 11 min. A school with
     * no route is kept with "Not available" and its commute weight spread over the other factors (EX-4). Score 32 and
     * the longest commute 60 are boundary values; DRIVE is a valid travel mode.
     */
    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-BB-REC-05: travel time 600 s, 601 s, 3600 s and 3601 s against 60 min; a school with no route")
    void tc05_commuteLimitInSeconds() {
        schools(TestSchools.school("t-600").range(2025, 2, 28, 32).build(),
                TestSchools.school("t-601").range(2025, 2, 28, 32).build(),
                TestSchools.school("t-3600").range(2025, 2, 28, 32).build(),
                TestSchools.school("t-3601").range(2025, 2, 28, 32).build(),
                TestSchools.school("t-none").range(2025, 2, 28, 32).build());
        routedMode = TravelMode.DRIVE;
        seconds.putAll(Map.of("t-600", 600, "t-601", 601, "t-3600", 3600, "t-3601", 3601));

        check("TC-BB-REC-05", criteria(32, 2, TravelMode.DRIVE, 60),
                "schools (all PG2 28–32, by car): t-600 600 s, t-601 601 s, t-3600 3600 s, t-3601 3601 s, t-none no route",
                View.DETAIL,
                "4 results"
                        + " | #1 t-none total 1.0, commute n/a: FIT 1.0 MATCH; COMMUTE n/a"
                        + " | #2 t-600 total 0.929, 10 min: FIT 1.0 MATCH; COMMUTE 0.833"
                        + " | #3 t-601 total 0.921, 11 min: FIT 1.0 MATCH; COMMUTE 0.817"
                        + " | #4 t-3600 total 0.571, 60 min: FIT 1.0 MATCH; COMMUTE 0.0");
    }

    /**
     * EX-4 and scoring section 5 "When travel times are unavailable": the route service fails, every school is still
     * recommended, its commute is "Not available" and the commute weight is spread over the other factors.
     */
    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-BB-REC-06: route service down: schools still ranked, commute 'Not available'")
    void tc06_routeServiceDown() {
        schools(TestSchools.school("rd-match").range(2025, 2, 16, 20).build(),
                TestSchools.school("rd-safe").range(2025, 2, 18, 22).build());
        when(directions.getCommuteTimes(any(), anyList(), any()))
                .thenThrow(new ExternalServiceUnavailableException("Google Routes", null));

        check("TC-BB-REC-06", criteria(20, 2, TravelMode.DRIVE, 30),
                "schools: rd-match PG2 16–20, rd-safe PG2 18–22; the route service throws "
                        + "ExternalServiceUnavailableException",
                View.DETAIL,
                "2 results"
                        + " | #1 rd-match total 1.0, commute n/a: FIT 1.0 MATCH; COMMUTE n/a"
                        + " | #2 rd-safe total 0.7, commute n/a: FIT 0.7 SAFE; COMMUTE n/a");
    }

    /**
     * DC-74: the dataset has no PSLE ranges. The score and posting group may be missing, every school is a candidate
     * (with or without a range), each result says "PSLE fit not used", and PSLE_FIT gets weight 0: the total is
     * (0.15 × CCA + 0.3 × COMMUTE) ÷ 0.45. A school over the limit is still excluded.
     */
    @Test
    @Tag("FR-REC-01")
    @Tag("DC-74")
    @DisplayName("TC-BB-REC-07: no PSLE ranges in the dataset: score and PG missing, PSLE fit not used")
    void tc07_noPsleData() {
        when(schoolData.hasPsleData()).thenReturn(false);
        schools(TestSchools.school("nd-choir").ccas("CHOIR").build(),
                TestSchools.school("nd-ranged").range(2025, 3, 8, 12).ccas("ROBOTICS").build(),
                TestSchools.school("nd-far").ccas("CHOIR").build());
        seconds.putAll(Map.of("nd-choir", 900, "nd-ranged", 360, "nd-far", 2400));
        MatchCriteria criteria = criteria(null, null, TravelMode.TRANSIT, 30);
        criteria.setPreferredCCAs(List.of("CHOIR"));

        check("TC-BB-REC-07", criteria,
                "dataset has no PSLE ranges; schools: nd-choir CHOIR 900 s, nd-ranged PG3 8–12 ROBOTICS 360 s, "
                        + "nd-far CHOIR 2400 s",
                View.DETAIL,
                "2 results"
                        + " | #1 nd-choir total 0.667, 15 min: FIT not used; COMMUTE 0.5; CCA 1.0"
                        + " | #2 nd-ranged total 0.533, 6 min: FIT not used; COMMUTE 0.8; CCA 0.0");
    }

    /** EX-2: no school is left (no range for the posting group, too far above U, over the commute limit). */
    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-BB-REC-08: no suitable school gives an empty list")
    void tc08_noSuitableSchool() {
        schools(TestSchools.school("e-pg1").range(2025, 1, 20, 25).build(),
                TestSchools.school("e-u09").range(2025, 3, 5, 9).build(),
                TestSchools.school("e-far").range(2025, 3, 8, 12).build());
        seconds.putAll(Map.of("e-pg1", 600, "e-u09", 600, "e-far", 1860));

        check("TC-BB-REC-08", criteria(12, 3, TravelMode.TRANSIT, 30),
                "schools: e-pg1 only PG1 20–25, e-u09 PG3 5–9 (600 s), e-far PG3 8–12 (1860 s)",
                View.SUMMARY,
                "0 results");
    }

    /**
     * Boundary 20 of app.recommendation.commute-candidates and 11 of top-n (scoring section 5 steps 2–4): 20
     * candidates (19 MATCH, 1 SAFE) all get a travel time, so the SAFE school (1 min) ranks first; 9 MATCH schools are
     * over the limit; 11 schools are left and the best 10 are returned, ranked 1..10.
     */
    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-BB-REC-09: 20 candidates all get a travel time; 11 schools left, top 10 returned")
    void tc09_twentyCandidates() {
        schools(capSchools(19));
        check("TC-BB-REC-09", criteria(12, 3, TravelMode.TRANSIT, 30),
                "20 candidates: cap-safe PG3 8–14 (SAFE) 1 min; cap-m01..cap-m19 PG3 8–12 (MATCH), "
                        + "cap-m01..cap-m10 21..30 min, cap-m11..cap-m19 31 min",
                View.RANKING,
                "10 results: #1 cap-safe, #2 cap-m01, #3 cap-m02, #4 cap-m03, #5 cap-m04, #6 cap-m05, #7 cap-m06, "
                        + "#8 cap-m07, #9 cap-m08, #10 cap-m09");
    }

    /**
     * Boundary 21 of commute-candidates and 10 of top-n: with 20 MATCH schools the SAFE school is 21st by PSLE fit,
     * gets no travel time and is not recommended, although it is the nearest; 10 MATCH schools are over the limit,
     * so exactly 10 are left and all 10 are returned.
     */
    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-BB-REC-10: the 21st candidate gets no travel time; 10 schools left, all 10 returned")
    void tc10_twentyOneCandidates() {
        schools(capSchools(20));
        check("TC-BB-REC-10", criteria(12, 3, TravelMode.TRANSIT, 30),
                "21 candidates: cap-safe PG3 8–14 (SAFE) 1 min; cap-m01..cap-m20 PG3 8–12 (MATCH), "
                        + "cap-m01..cap-m10 21..30 min, cap-m11..cap-m20 31 min",
                View.RANKING,
                "10 results: #1 cap-m01, #2 cap-m02, #3 cap-m03, #4 cap-m04, #5 cap-m05, #6 cap-m06, #7 cap-m07, "
                        + "#8 cap-m08, #9 cap-m09, #10 cap-m10");
    }

    /**
     * The tie-break before the cut to 20 candidates (scoring section 5 step 2: pre-rank by PSLE fit, CCA and
     * programme, ties by straight-line distance from the start). 21 MATCH schools have the same pre-score. a-far is
     * at Tampines, 10.8 km from Bishan; the other 20 are at Bishan. So a-far is the 21st candidate and gets no travel
     * time. Its fake travel time (15 min) is shorter than the others' (20 min), so it would rank first if it had been
     * kept. It also comes first by name and first in the data, so a tie-break by name or by data order keeps it.
     */
    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-BB-REC-31: 21 candidates tie on pre-score; the one farthest in a straight line is cut")
    void tc31_tieAtTheTwentiethCandidate() {
        List<School> list = new ArrayList<>();
        list.add(TestSchools.school("a-far").at(TestSchools.TAMPINES).range(2025, 3, 8, 12).build());
        seconds.put("a-far", 15 * 60);
        for (int i = 1; i <= 20; i++) {
            String code = String.format("near-%02d", i);
            list.add(TestSchools.school(code).range(2025, 3, 8, 12).build());
            seconds.put(code, 20 * 60);
        }
        schools(list);

        check("TC-BB-REC-31", criteria(12, 3, TravelMode.TRANSIT, 30),
                "21 candidates, all PG3 8–12 (MATCH): a-far at Tampines (10.8 km away) 15 min, first in the data; "
                        + "near-01..near-20 at Bishan 20 min",
                View.RANKING,
                "10 results: #1 near-01, #2 near-02, #3 near-03, #4 near-04, #5 near-05, #6 near-06, #7 near-07, "
                        + "#8 near-08, #9 near-09, #10 near-10");
    }

    // ------------------------------------------------------------------ invalid classes, one per case

    /**
     * TC-BB-REC-11..28 and 32 (TC-BB-REC.csv): valid criteria (score 12, PG3, TRANSIT, 30 min, Bishan, one MATCH
     * school at 15 min) with exactly one invalid value, so one fault cannot hide another. Expected:
     * InvalidInputException with only that field (EX-1, NFR-USE-03); the message is the shared text of
     * {@code InputMessages}, or any non-blank text ("*") where the specification gives none. The field keys are the
     * ones the criteria form reads ({@code recommendation-criteria.html}); {@code weights} is the property name of
     * {@code MatchCriteria}.
     */
    @ParameterizedTest(name = "{0}: {3} → {4}")
    @CsvFileSource(resources = "/testcases/TC-BB-REC.csv", numLinesToSkip = 1)
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-BB-REC-11..28 and 32: one invalid criteria value → InvalidInputException for that field only")
    void tc11to28and32_invalidInput(String tcId, String requirement, String technique, String input,
                                    String expected) {
        schools(TestSchools.school("base").range(2025, 3, 8, 12).build());
        minutes(15, "base");
        MatchCriteria criteria = criteria(12, 3, TravelMode.TRANSIT, 30);
        boolean psleData = apply(criteria, input);

        Outcome outcome = run(criteria);
        String actual = outcome.text(criteria, View.DETAIL);
        TestResultLog.record(FILE, tcId, describe(criteria) + " | " + (psleData ? "" : "dataset has no PSLE ranges; ")
                + "school: base PG3 8–12, 15 min", expected, actual);

        String field = expected.substring(expected.indexOf('{') + 1, expected.indexOf('='));
        String message = expected.substring(expected.indexOf('=') + 1, expected.lastIndexOf('}'));
        assertThat(outcome.error()).as("%s: actual %s", tcId, actual).isInstanceOf(InvalidInputException.class);
        Map<String, String> errors = ((InvalidInputException) outcome.error()).getFieldErrors();
        assertThat(errors).as(tcId).containsOnlyKeys(field);
        if (message.equals("*")) {
            assertThat(errors.get(field)).as(tcId).isNotBlank();
        } else {
            assertThat(errors.get(field)).as(tcId).isEqualTo(message);
        }
    }

    /** EX-3: the data source cannot say whether it has PSLE ranges (DC-74), so no school data can be read. */
    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-BB-REC-29: the PSLE-data question to the data source fails → ExternalServiceUnavailableException")
    void tc29_psleDataQuestionFails() {
        when(schoolData.hasPsleData()).thenThrow(new IllegalStateException("no dataset loaded"));
        schools(TestSchools.school("base").range(2025, 3, 8, 12).build());
        minutes(15, "base");

        check("TC-BB-REC-29", criteria(12, 3, TravelMode.TRANSIT, 30),
                "data source: hasPsleData() throws IllegalStateException", View.DETAIL,
                "ExternalServiceUnavailableException");
    }

    /** EX-3: the school list cannot be read. */
    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-BB-REC-30: the school list cannot be read → ExternalServiceUnavailableException")
    void tc30_schoolListFails() {
        when(schoolData.getSchools()).thenThrow(new IllegalStateException("snapshot unreadable"));

        check("TC-BB-REC-30", criteria(12, 3, TravelMode.TRANSIT, 30),
                "data source: getSchools() throws IllegalStateException", View.DETAIL,
                "ExternalServiceUnavailableException");
    }

    // ------------------------------------------------------------------ fixtures

    /** Valid criteria from Bishan with no preferences and no weights (the settings apply). */
    private static MatchCriteria criteria(Integer psleScore, Integer postingGroup, TravelMode mode,
                                          Integer maxCommuteMin) {
        MatchCriteria criteria = new MatchCriteria();
        criteria.setPsleScore(psleScore);
        criteria.setPostingGroup(postingGroup);
        criteria.setTravelMode(mode);
        criteria.setMaxCommuteMin(maxCommuteMin);
        criteria.setStartLocation(BISHAN);
        return criteria;
    }

    private static Map<MatchFactor, Double> weights(Double psleFit, Double commute, Double cca, Double programme) {
        Map<MatchFactor, Double> weights = new EnumMap<>(MatchFactor.class);
        weights.put(MatchFactor.PSLE_FIT, psleFit);
        weights.put(MatchFactor.COMMUTE, commute);
        weights.put(MatchFactor.CCA, cca);
        weights.put(MatchFactor.PROGRAMME, programme);
        return weights;
    }

    /**
     * Applies "psleScore=3", "postingGroup=", "mode=", "maxCommuteMin=20", "start=none|no coordinate|Kuala Lumpur",
     * "weight.CCA=null|-0.01|NaN" (the other weights stay at the settings' values) and "psleData=false", separated by
     * ";". Returns whether the dataset has PSLE ranges.
     */
    private boolean apply(MatchCriteria criteria, String input) {
        boolean psleData = true;
        for (String part : input.split(";")) {
            String[] pair = part.trim().split("=", 2);
            String value = pair.length > 1 ? pair[1].trim() : "";
            switch (pair[0]) {
                case "psleScore" -> criteria.setPsleScore(value.isEmpty() ? null : Integer.valueOf(value));
                case "postingGroup" -> criteria.setPostingGroup(value.isEmpty() ? null : Integer.valueOf(value));
                case "mode" -> criteria.setTravelMode(value.isEmpty() ? null : TravelMode.valueOf(value));
                case "maxCommuteMin" -> criteria.setMaxCommuteMin(value.isEmpty() ? null : Integer.valueOf(value));
                case "start" -> criteria.setStartLocation(switch (value) {
                    case "none" -> null;
                    case "no coordinate" -> NOT_FOUND;
                    case "Kuala Lumpur" -> KUALA_LUMPUR;
                    default -> throw new IllegalArgumentException("Unknown start " + value);
                });
                case "psleData" -> {
                    psleData = Boolean.parseBoolean(value);
                    when(schoolData.hasPsleData()).thenReturn(psleData);
                }
                default -> {
                    if (!pair[0].startsWith("weight.")) {
                        throw new IllegalArgumentException("Unknown input " + part);
                    }
                    Map<MatchFactor, Double> weights = weights(0.4, 0.3, 0.15, 0.15);
                    weights.put(MatchFactor.valueOf(pair[0].substring("weight.".length())),
                            value.equals("null") ? null : Double.valueOf(value));
                    criteria.setWeights(weights);
                }
            }
        }
        return psleData;
    }

    private void schools(School... list) {
        when(schoolData.getSchools()).thenReturn(List.of(list));
    }

    private void schools(List<School> list) {
        when(schoolData.getSchools()).thenReturn(list);
    }

    private void minutes(int min, String... codes) {
        for (String code : codes) {
            seconds.put(code, min * 60);
        }
    }

    /**
     * cap-safe (SAFE, 1 min) plus {@code matchCount} MATCH schools cap-m01..: m01..m10 at 21..30 min, the rest at
     * 31 min (over the 30-minute limit). Every MATCH school scores above cap-safe on PSLE fit, so cap-safe is the
     * last candidate before travel times are known, and the best one after.
     */
    private List<School> capSchools(int matchCount) {
        List<School> list = new ArrayList<>();
        list.add(TestSchools.school("cap-safe").range(2025, 3, 8, 14).build());
        seconds.put("cap-safe", 60);
        for (int i = 1; i <= matchCount; i++) {
            String code = String.format("cap-m%02d", i);
            list.add(TestSchools.school(code).range(2025, 3, 8, 12).build());
            seconds.put(code, (i <= 10 ? 20 + i : 31) * 60);
        }
        return list;
    }

    // ------------------------------------------------------------------ running and describing

    private void check(String tcId, MatchCriteria criteria, String environment, View view, String expected) {
        String actual = run(criteria).text(criteria, view);
        TestResultLog.record(FILE, tcId, describe(criteria) + " | " + environment, expected, actual);
        assertThat(actual).as(tcId).isEqualTo(expected);
    }

    private Outcome run(MatchCriteria criteria) {
        try {
            return new Outcome(controller.recommend(criteria), null);
        } catch (RuntimeException e) {
            return new Outcome(null, e);
        }
    }

    /** How much of each result the notation shows. */
    private enum View {
        /** Rank and school code. */
        RANKING,
        /** Rank, code, total and travel time. */
        SUMMARY,
        /** SUMMARY plus one entry per factor. */
        DETAIL
    }

    /** What {@code recommend} returned, or what it threw. */
    private record Outcome(List<Recommendation> recs, RuntimeException error) {

        private static final Pattern IP_MARK = Pattern.compile("\\bIP\\b");

        String text(MatchCriteria criteria, View view) {
            if (error instanceof InvalidInputException e) {
                return "InvalidInputException " + e.getFieldErrors();
            }
            if (error instanceof ExternalServiceUnavailableException) {
                return "ExternalServiceUnavailableException";
            }
            if (error != null) {
                return error.getClass().getSimpleName() + ": " + error.getMessage();
            }
            if (recs.isEmpty()) {
                return "0 results";
            }
            String count = recs.size() + (recs.size() == 1 ? " result" : " results");
            if (view == View.RANKING) {
                return count + ": " + recs.stream().map(r -> "#" + r.getRank() + " " + r.getSchool().getSchoolCode())
                        .collect(Collectors.joining(", "));
            }
            return count + recs.stream().map(r -> " | " + result(r, criteria, view)).collect(Collectors.joining());
        }

        private static String result(Recommendation r, MatchCriteria criteria, View view) {
            String head = "#" + r.getRank() + " " + r.getSchool().getSchoolCode() + " total " + num(r.getTotalScore())
                    + ", " + (r.getCommuteMin() == null ? "commute n/a" : r.getCommuteMin() + " min");
            if (view != View.DETAIL) {
                return head;
            }
            List<String> entries = new ArrayList<>();
            if (r.getReasons().contains(PSLE_FIT_NOT_USED)) {
                entries.add("FIT not used");
            } else {
                entries.add(factorEntry(r, MatchFactor.PSLE_FIT));
            }
            entries.add(factorEntry(r, MatchFactor.COMMUTE));
            if (!criteria.getPreferredCCAs().isEmpty()) {
                entries.add(factorEntry(r, MatchFactor.CCA));
            }
            if (!criteria.getPreferredProgrammes().isEmpty()) {
                entries.add(factorEntry(r, MatchFactor.PROGRAMME));
            }
            return head + ": " + String.join("; ", entries);
        }

        private static String factorEntry(Recommendation r, MatchFactor factor) {
            String name = switch (factor) {
                case PSLE_FIT -> "FIT";
                case COMMUTE -> "COMMUTE";
                case CCA -> "CCA";
                case PROGRAMME -> "PROG";
            };
            List<String> found = r.getComponents().stream().filter(c -> c.getFactor() == factor)
                    .map(c -> name + " " + entry(c)).toList();
            return found.isEmpty() ? name + " missing" : String.join("; ", found);
        }

        private static String entry(ScoreComponent c) {
            String reason = c.getReason() == null ? "" : c.getReason();
            String text;
            if (c.getFactor() == MatchFactor.COMMUTE && reason.toLowerCase(Locale.ROOT).contains("not available")) {
                text = "n/a";
            } else if (c.getFactor() == MatchFactor.PSLE_FIT) {
                text = num(c.getScore()) + " " + label(c.getScore()) + (IP_MARK.matcher(reason).find() ? " IP" : "");
            } else {
                text = num(c.getScore());
            }
            return reason.isBlank() ? text + " (no reason)" : text;
        }

        /** The label a PSLE fit score stands for (scoring section 5: MATCH 1.0, SAFE 0.7, REACH 0.3). */
        private static String label(double score) {
            if (Math.abs(score - FIT_MATCH) < 1e-9) {
                return "MATCH";
            }
            if (Math.abs(score - FIT_SAFE) < 1e-9) {
                return "SAFE";
            }
            if (Math.abs(score - FIT_REACH) < 1e-9) {
                return "REACH";
            }
            return "no-label";
        }

        /** Rounded to 3 decimals, at least one decimal: 1.0, 0.5, 0.667. */
        private static String num(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return String.valueOf(value);
            }
            String text = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
                    .toPlainString();
            return text.contains(".") ? text : text + ".0";
        }
    }

    /** "psleScore=12; postingGroup=3; mode=TRANSIT; maxCommuteMin=30; start=Bishan" plus what else is set. */
    private static String describe(MatchCriteria c) {
        StringBuilder text = new StringBuilder()
                .append("psleScore=").append(orMissing(c.getPsleScore()))
                .append("; postingGroup=").append(orMissing(c.getPostingGroup()))
                .append("; mode=").append(orMissing(c.getTravelMode()))
                .append("; maxCommuteMin=").append(orMissing(c.getMaxCommuteMin()))
                .append("; start=").append(c.getStartLocation() == null ? "missing" : c.getStartLocation().getInputText());
        if (c.getPrimarySchool() != null) {
            text.append("; primarySchool=").append(c.getPrimarySchool());
        }
        if (!c.getPreferredCCAs().isEmpty()) {
            text.append("; CCAs=").append(c.getPreferredCCAs());
        }
        if (!c.getPreferredProgrammes().isEmpty()) {
            text.append("; programmes=").append(c.getPreferredProgrammes());
        }
        if (!c.getWeights().isEmpty()) {
            text.append("; weights=").append(c.getWeights());
        }
        return text.toString();
    }

    private static String orMissing(Object value) {
        return value == null ? "missing" : value.toString();
    }
}

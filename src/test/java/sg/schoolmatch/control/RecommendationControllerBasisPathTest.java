package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.common.Place;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.recommend.MatchCriteria;
import sg.schoolmatch.entity.recommend.MatchFactor;
import sg.schoolmatch.entity.recommend.Recommendation;
import sg.schoolmatch.entity.recommend.ScoreComponent;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.support.TestProperties;
import sg.schoolmatch.support.TestResultLog;
import sg.schoolmatch.support.TestSchools;

/**
 * Lab 4, section 3.2.2: basis path testing of {@link RecommendationController#recommend} (use case #06 Get School
 * Recommendations, FR-REC-01; rules in docs/recommendation-scoring.md section 5, DC-20, DC-22, DC-74).
 * The control flow graph (18 nodes, 24 edges, V(G) = 8) and the basis paths P1..P8 are in
 * docs/testing/basis-path-recommend.md; test TC-BP-REC-0n runs path Pn. Each test changes as few inputs as possible
 * from the path it was derived from, so a failure points at one decision.
 * <p>
 * Environment: {@link SchoolDataController} and {@link DirectionsController} are Mockito mocks
 * ({@link LocationController} is never called, DC-53); travel times come from {@link #commuteSeconds};
 * {@code app.recommendation.*} has its defaults (weights 0.4 / 0.3 / 0.15 / 0.15, PSLE fit 1.0 / 0.7 / 0.3,
 * 20 commute candidates, top 10).
 * Baseline criteria: PSLE score 11, posting group 3, public transport, at most 30 minutes, from Bishan, no CCA or
 * programme preference, no weights of its own.
 * <p>
 * Every test records its input, the expected output (from the specification, plus the calls the path predicts) and
 * the actual output in {@code target/test-results/basis-path-recommend.csv} through {@link TestResultLog}, before it
 * asserts. The test then asserts only that the actual string equals the expected string, so every fact the test
 * checks is in the recorded row: a failing test can never show expected == actual in the results table.
 * The calls show which path ran: {@code getSchools} is node 6; {@code getCommuteTimes} (with the schools it was
 * asked for, in order, the start and the mode) is inside node 17; {@code School.getScoreRange} is inside
 * {@code scoreSchool} (node 11).
 */
class RecommendationControllerBasisPathTest {

    private static final String RESULTS = "basis-path-recommend";

    private static final ReferenceLocation BISHAN =
            new ReferenceLocation(TestSchools.BISHAN, LocationSource.MANUAL_ENTRY, "Bishan");

    /** The baseline criteria as written in the result file. */
    private static final String CRITERIA = "psleScore=11; postingGroup=3; mode=TRANSIT; maxCommuteMin=30; start=Bishan";

    /** 15 minutes, half of the 30-minute limit: COMMUTE = 1 - 15 / 30 = 0.5. */
    private static final int FIFTEEN_MIN_IN_SECONDS = 15 * 60;

    /** The DC-20 label as a word anywhere in the PSLE_FIT reason ("... → MATCH" or "... (MATCH)"). */
    private static final Pattern LABEL = Pattern.compile("\\b(SAFE|MATCH|REACH)\\b");

    private final SchoolDataController schoolData = mock(SchoolDataController.class);
    private final DirectionsController directions = mock(DirectionsController.class);
    private final LocationController location = mock(LocationController.class);
    private final AppProperties props = TestProperties.defaults();
    private final RecommendationController controller =
            new RecommendationController(location, directions, schoolData, props);

    /** Travel time in seconds by school code; a school that is not in the map gets "no route". */
    private final Map<String, Integer> commuteSeconds = new HashMap<>();

    /** One entry per getCommuteTimes call: "[alpha, beta] from Bishan by TRANSIT" (school codes in the asked order). */
    private final List<String> routeCalls = new ArrayList<>();

    /** The error a stub throws in P2 and P4; the actual output says whether recommend kept it as the cause. */
    private RuntimeException stubbedFailure;

    @BeforeEach
    void baselineEnvironment() {
        when(schoolData.hasPsleData()).thenReturn(true);   // node 1 "no": the dataset has PSLE ranges (DC-74)
        when(directions.getCommuteTimes(any(), anyList(), any())).thenAnswer(call -> {
            ReferenceLocation start = call.getArgument(0);
            List<? extends Place> places = call.getArgument(1);
            TravelMode mode = call.getArgument(2);
            routeCalls.add(places.stream().map(p -> ((School) p).getSchoolCode())
                    .collect(Collectors.joining(", ", "[", "]"))
                    + " from " + (start == BISHAN ? "Bishan" : "another start " + start) + " by " + mode);
            List<Route> routes = new ArrayList<>();
            for (Place place : places) {
                Integer seconds = commuteSeconds.get(((School) place).getSchoolCode());
                routes.add(seconds == null ? Route.unavailable(mode) : new Route(mode, 1000, seconds, null, List.of()));
            }
            return routes;
        });
    }

    // ------------------------------------------------------------------ P1 to P4: no school is scored

    /**
     * P1, the baseline: 1-3-4-6-8-9-15-16-18. The loop is not entered (lecture rule for a baseline), so node 15 is
     * true and node 16 returns the empty list. Spec: "Empty when no school fits (EX-2)" (Javadoc of recommend, use
     * case #06 EX-2). Path evidence: getSchools ran once (node 6), the route service was not asked (node 17 not run).
     */
    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-BP-REC-01 (P1, baseline): valid criteria, no school in the dataset → empty list, no route call")
    void p1_baseline_noSchool_emptyList() {
        when(schoolData.getSchools()).thenReturn(List.of());

        String actual = run(baselineCriteria());

        String expected = "0 results; getSchools 1x; getCommuteTimes 0x";
        TestResultLog.record(RESULTS, "TC-BP-REC-01", CRITERIA + "; hasPsleData=true; schools=[]", expected, actual);
        assertThat(actual).isEqualTo(expected);
    }

    /**
     * P2: 1-2-18 (node 1 flipped). hasPsleData fails, so validate and getSchools never run. Spec: "@throws
     * ExternalServiceUnavailableException when the school data cannot be read (EX-3)"; the page shows "temporarily
     * unavailable" (NFR-USE-03, Javadoc of ExternalServiceUnavailableException). Path evidence: getSchools 0 calls
     * (P4 has 1); the stub's own error is the cause.
     */
    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-BP-REC-02 (P2): hasPsleData fails → ExternalServiceUnavailableException, getSchools not called")
    void p2_psleDataCheckFails_serviceUnavailable() {
        stubbedFailure = new IllegalStateException("school data cache not loaded");
        when(schoolData.hasPsleData()).thenThrow(stubbedFailure);

        String actual = run(baselineCriteria());

        String expected = "ExternalServiceUnavailableException (School data): \"School data is temporarily "
                + "unavailable\", cause = stubbed IllegalStateException; getSchools 0x; getCommuteTimes 0x";
        TestResultLog.record(RESULTS, "TC-BP-REC-02", CRITERIA + "; hasPsleData throws IllegalStateException",
                expected, actual);
        assertThat(actual).isEqualTo(expected);
    }

    /**
     * P3: 1-3-4-5-18 (node 4 flipped). One invalid field: PSLE score 33 = {@link IndicativePsleScoreRange#MAX_SCORE}
     * + 1. Spec: use case #06 EX-1 (the form shows a message next to the field), NFR-USE-03, the message
     * {@code InputMessages.PSLE}. Path evidence: only that field is reported and getSchools is never called.
     */
    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-BP-REC-03 (P3): PSLE score 33 → InvalidInputException for psleScore only, getSchools not called")
    void p3_invalidCriteria_fieldError() {
        MatchCriteria criteria = baselineCriteria();
        criteria.setPsleScore(IndicativePsleScoreRange.MAX_SCORE + 1);

        String actual = run(criteria);

        String expected = "InvalidInputException {psleScore=Enter a whole number from 4 to 32}; getSchools 0x; "
                + "getCommuteTimes 0x";
        TestResultLog.record(RESULTS, "TC-BP-REC-03", CRITERIA.replace("psleScore=11", "psleScore=33")
                + "; hasPsleData=true", expected, actual);
        assertThat(actual).isEqualTo(expected);
    }

    /**
     * P4: 1-3-4-6-7-18 (node 6 flipped). The criteria are valid, then getSchools fails. Spec: EX-3, the
     * {@code @throws} clause of recommend, NFR-USE-03. Path evidence: getSchools was called once (P2: 0 calls), and the
     * stub's own error is the cause.
     */
    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-BP-REC-04 (P4): getSchools fails after valid criteria → ExternalServiceUnavailableException")
    void p4_schoolListFails_serviceUnavailable() {
        stubbedFailure = new IllegalStateException("snapshot unreadable");
        when(schoolData.getSchools()).thenThrow(stubbedFailure);

        String actual = run(baselineCriteria());

        String expected = "ExternalServiceUnavailableException (School data): \"School data is temporarily "
                + "unavailable\", cause = stubbed IllegalStateException; getSchools 1x; getCommuteTimes 0x";
        TestResultLog.record(RESULTS, "TC-BP-REC-04", CRITERIA + "; hasPsleData=true; getSchools throws "
                + "IllegalStateException", expected, actual);
        assertThat(actual).isEqualTo(expected);
    }

    // ------------------------------------------------------------------ P5 to P8: the loop runs

    /**
     * P5: 1-3-4-6-8-9-10-11-13-9-15-16-18 (node 9 flipped, node 15 kept true). One school with no PSLE range, like
     * the 8 specialised schools: scoreSchool returns null, so nothing is kept. Spec: docs/recommendation-scoring.md
     * sections 1 and 5 (candidates are schools with a range for the posting group, DC-22; a guest gets the
     * non-affiliated range; no range is never guessed), EX-2. Path evidence: {@code getScoreRange(3, false)} was
     * called once, so node 11 (scoreSchool) ran; no route call.
     */
    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-BP-REC-05 (P5): the only school has no PSLE range → scoreSchool drops it, empty list")
    void p5_loopOnce_schoolWithoutRange_dropped() {
        School alpha = spy(TestSchools.school("alpha").build());   // no PSLE range at all
        when(schoolData.getSchools()).thenReturn(List.of(alpha));
        commuteSeconds.put("alpha", FIFTEEN_MIN_IN_SECONDS);

        String actual = run(baselineCriteria()) + "; " + scoreRangeCalls("alpha", alpha);

        String expected = "0 results; getSchools 1x; getCommuteTimes 0x; alpha.getScoreRange(3, false) 1x";
        TestResultLog.record(RESULTS, "TC-BP-REC-05", CRITERIA + "; hasPsleData=true; schools=[alpha: no PSLE range]; "
                + "commute alpha 15 min", expected, actual);
        assertThat(actual).isEqualTo(expected);
    }

    /**
     * P6: 1-3-4-6-8-9-10-11-13-14-9-15-17-18 (node 13 flipped from P5; node 15 must flip with it, because a kept
     * school makes {@code scored} non-empty). Spec: DC-20 with U = 12 and m = {@code SchoolChoice.SAFE_MARGIN} = 2:
     * 10 &lt; 11 &le; 12 is MATCH (1.0); COMMUTE = 1 - 15 / 30 = 0.5; total = (0.4 x 1.0 + 0.3 x 0.5) / (0.4 + 0.3)
     * = 0.786 (no CCA or programme preference, so those weights drop out). Path evidence: the label MATCH with
     * score 1.0 can only come from scoreSchool (node 11); one route call for [alpha] (node 17).
     */
    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-BP-REC-06 (P6): one MATCH school within the limit → 1 result, total 0.786, one route call")
    void p6_loopOnce_schoolKept_ranked() {
        School alpha = TestSchools.school("alpha").range(2025, 3, 8, 12).build();
        when(schoolData.getSchools()).thenReturn(List.of(alpha));
        commuteSeconds.put("alpha", FIFTEEN_MIN_IN_SECONDS);

        String actual = run(baselineCriteria());

        String expected = "1 result: #1 alpha MATCH 1.0, 15 min, total 0.786; getSchools 1x; "
                + "getCommuteTimes 1x: [alpha] from Bishan by TRANSIT";
        TestResultLog.record(RESULTS, "TC-BP-REC-06", CRITERIA + "; hasPsleData=true; schools=[alpha: PG3 2025 range "
                + "8-12]; commute alpha 15 min", expected, actual);
        assertThat(actual).isEqualTo(expected);
    }

    /**
     * P7: 1-3-4-6-8-9-10-12-13-14-9-15-17-18 (node 10 flipped from P6). The dataset has no PSLE ranges, so no school
     * has one (alpha loses its range too). Spec: DC-74 and docs/recommendation-scoring.md section 5: every school is
     * a candidate, the PSLE_FIT reason is "PSLE fit not used: no score ranges in the current dataset", PSLE_FIT gets
     * weight 0 and the rest is rescaled: total = 0.3 x 0.5 / 0.3 = 0.5. Path evidence: that reason text (node 12,
     * scoreWithoutPsleFit) and no call to getScoreRange (node 11 not run).
     */
    @Test
    @Tag("FR-REC-01")
    @Tag("DC-74")
    @DisplayName("TC-BP-REC-07 (P7): no PSLE data in the dataset → scoreWithoutPsleFit keeps the school, total 0.500")
    void p7_loopOnce_noPsleData_scoredWithoutPsleFit() {
        when(schoolData.hasPsleData()).thenReturn(false);
        School alpha = spy(TestSchools.school("alpha").build());   // DC-74: without PSLE data no school has a range
        when(schoolData.getSchools()).thenReturn(List.of(alpha));
        commuteSeconds.put("alpha", FIFTEEN_MIN_IN_SECONDS);

        String actual = run(baselineCriteria()) + "; " + scoreRangeCalls("alpha", alpha);

        String expected = "1 result: #1 alpha PSLE fit not used, 15 min, total 0.500; getSchools 1x; "
                + "getCommuteTimes 1x: [alpha] from Bishan by TRANSIT; alpha.getScoreRange 0x";
        TestResultLog.record(RESULTS, "TC-BP-REC-07", CRITERIA + "; hasPsleData=false; schools=[alpha: no PSLE range]; "
                + "commute alpha 15 min", expected, actual);
        assertThat(actual).isEqualTo(expected);
    }

    /**
     * P8: 1-3-4-6-8-9-(10-11-13-14-9) twice-15-17-18. After P1..P7 every edge is used. Under the lecture's rules the
     * eighth basis path would be the infeasible flip of node 15 (1-3-4-6-8-9-15-17-18), with no test. P8 is a
     * feasible independent path instead: it takes edge 13→14 twice but 15→17 once (document, section 5). It runs the
     * loop twice, which departs from the lecture's "zero times or once" rule on purpose. Spec: beta U = 14:
     * 11 &le; 14 - 2 is SAFE (0.7), total = (0.4 x 0.7 + 0.3 x 0.5) / 0.7 = 0.614; alpha as in P6 (0.786); travel
     * times for the pre-ranked candidates in one call, best first, ranks 1..n (docs/recommendation-scoring.md
     * section 5, steps 2 to 4). The dataset lists beta first, so alpha first in the route call and in the result
     * shows that the controller sorted the list.
     */
    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-BP-REC-08 (P8): two schools both kept → MATCH 0.786 ranked before SAFE 0.614, one route call")
    void p8_loopTwice_bothKept_rankedBestFirst() {
        School alpha = TestSchools.school("alpha").range(2025, 3, 8, 12).build();
        School beta = TestSchools.school("beta").range(2025, 3, 8, 14).build();
        when(schoolData.getSchools()).thenReturn(List.of(beta, alpha));
        commuteSeconds.put("alpha", FIFTEEN_MIN_IN_SECONDS);
        commuteSeconds.put("beta", FIFTEEN_MIN_IN_SECONDS);

        String actual = run(baselineCriteria());

        String expected = "2 results: #1 alpha MATCH 1.0, 15 min, total 0.786 / #2 beta SAFE 0.7, 15 min, total 0.614; "
                + "getSchools 1x; getCommuteTimes 1x: [alpha, beta] from Bishan by TRANSIT";
        TestResultLog.record(RESULTS, "TC-BP-REC-08", CRITERIA + "; hasPsleData=true; schools=[beta: PG3 2025 range "
                + "8-14, alpha: PG3 2025 range 8-12]; commute 15 min each", expected, actual);
        assertThat(actual).isEqualTo(expected);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs recommend once and describes everything the test checks: the result or the exception (message and
     * cause), the number of getSchools calls, and each getCommuteTimes call with its schools, start and mode.
     */
    private String run(MatchCriteria criteria) {
        String result;
        try {
            result = describe(controller.recommend(criteria));
        } catch (InvalidInputException e) {
            result = "InvalidInputException " + e.getFieldErrors();
        } catch (ExternalServiceUnavailableException e) {
            result = "ExternalServiceUnavailableException (" + e.getService() + "): \"" + e.getMessage()
                    + "\", cause = " + cause(e.getCause());
        } catch (RuntimeException e) {   // not expected on any path: recorded so that the table shows it
            result = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long routeCallCount = calls(directions, "getCommuteTimes");
        return result + "; getSchools " + calls(schoolData, "getSchools") + "x; getCommuteTimes " + routeCallCount + "x"
                + (routeCalls.isEmpty() ? "" : ": " + String.join(" / ", routeCalls));
    }

    /** "stubbed IllegalStateException" when the cause is the stub's own error, otherwise what the cause is. */
    private String cause(Throwable cause) {
        if (cause == null) {
            return "none";
        }
        return (cause == stubbedFailure ? "stubbed " : "other ") + cause.getClass().getSimpleName();
    }

    /** "0 results" or "2 results: #1 alpha MATCH 1.0, 15 min, total 0.786 / #2 ...". */
    private static String describe(List<Recommendation> recs) {
        if (recs.isEmpty()) {
            return "0 results";
        }
        return recs.size() + (recs.size() == 1 ? " result: " : " results: ") + recs.stream()
                .map(r -> "#" + r.getRank() + " " + r.getSchool().getSchoolCode() + " " + psleFit(r) + ", "
                        + (r.getCommuteMin() == null ? "commute not available" : r.getCommuteMin() + " min")
                        + ", total " + String.format(Locale.ROOT, "%.3f", r.getTotalScore()))
                .collect(Collectors.joining(" / "));
    }

    /** "MATCH 1.0", "SAFE 0.7" (the DC-20 label in the reason and the factor score), or "PSLE fit not used". */
    private static String psleFit(Recommendation rec) {
        ScoreComponent fit = component(rec, MatchFactor.PSLE_FIT);
        if (RecommendationController.PSLE_FIT_NOT_USED.equals(fit.getReason())) {
            return "PSLE fit not used";
        }
        Matcher label = LABEL.matcher(fit.getReason());
        return (label.find() ? label.group(1) : "no label in \"" + fit.getReason() + "\"") + " "
                + String.format(Locale.ROOT, "%.1f", fit.getScore());
    }

    /** "alpha.getScoreRange(3, false) 1x" (each distinct argument list with its count), or "alpha.getScoreRange 0x". */
    private static String scoreRangeCalls(String code, School spy) {
        Map<String, Long> byArguments = mockingDetails(spy).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("getScoreRange"))
                .map(i -> Arrays.stream(i.getArguments()).map(String::valueOf).collect(Collectors.joining(", ")))
                .collect(Collectors.groupingBy(args -> args, LinkedHashMap::new, Collectors.counting()));
        if (byArguments.isEmpty()) {
            return code + ".getScoreRange 0x";
        }
        return byArguments.entrySet().stream()
                .map(e -> code + ".getScoreRange(" + e.getKey() + ") " + e.getValue() + "x")
                .collect(Collectors.joining("; "));
    }

    private static long calls(Object mockOrSpy, String method) {
        return mockingDetails(mockOrSpy).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(method)).count();
    }

    /** The baseline criteria: PSLE 11, PG3, public transport, at most 30 min, from Bishan. */
    private static MatchCriteria baselineCriteria() {
        MatchCriteria criteria = new MatchCriteria();
        criteria.setPsleScore(11);
        criteria.setPostingGroup(3);
        criteria.setTravelMode(TravelMode.TRANSIT);
        criteria.setMaxCommuteMin(30);
        criteria.setStartLocation(BISHAN);
        return criteria;
    }

    private static ScoreComponent component(Recommendation rec, MatchFactor factor) {
        return rec.getComponents().stream().filter(c -> c.getFactor() == factor).findFirst().orElseThrow();
    }
}

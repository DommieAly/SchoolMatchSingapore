package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
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
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.support.TestSchools;

/**
 * RecommendationController.recommend (use case Get School Recommendations, FR-REC-01; rules in
 * docs/recommendation-scoring.md, DC-20/DC-22). Controls are Mockito mocks; commute times come from a map.
 */
class RecommendationControllerTest {

    private static final ReferenceLocation BISHAN =
            new ReferenceLocation(TestSchools.BISHAN, LocationSource.MANUAL_ENTRY, "Bishan");

    private final SchoolDataController schoolData = mock(SchoolDataController.class);
    private final DirectionsController directions = mock(DirectionsController.class);
    private final LocationController location = mock(LocationController.class);
    private final AppProperties props =
            new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("app", AppProperties.class);
    private final RecommendationController controller =
            new RecommendationController(location, directions, schoolData, props);

    /** Commute minutes by school code; a code that is missing gets "no route". */
    private final Map<String, Integer> commuteMinutes = new HashMap<>();
    /** Exact commute seconds by school code; wins over {@link #commuteMinutes}. */
    private final Map<String, Integer> commuteSeconds = new HashMap<>();

    @BeforeEach
    void stubCommuteTimes() {
        when(directions.getCommuteTimes(any(), anyList(), any())).thenAnswer(call -> {
            List<? extends Place> places = call.getArgument(1);
            TravelMode mode = call.getArgument(2);
            List<Route> routes = new ArrayList<>();
            for (Place place : places) {
                String code = ((School) place).getSchoolCode();
                Integer minutes = commuteMinutes.get(code);
                Integer seconds = commuteSeconds.containsKey(code) ? commuteSeconds.get(code)
                        : minutes == null ? null : minutes * 60;
                routes.add(seconds == null ? Route.unavailable(mode)
                        : new Route(mode, 1000, seconds, null, List.of()));
            }
            return routes;
        });
    }

    // ------------------------------------------------------------------ TC-REC.csv

    @ParameterizedTest(name = "{0}: {3} → {4}")
    @CsvFileSource(resources = "/testcases/TC-REC.csv", numLinesToSkip = 1)
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-01..03: PSLE fit, input checks and the commute limit (school range PG3 2025 8–12)")
    void tcRec(String tcId, String requirement, String technique, String input, String expected) {
        School school = TestSchools.school("test-school").range(2025, 3, 8, 12).build();
        when(schoolData.getSchools()).thenReturn(List.of(school));
        commuteMinutes.put("test-school", 10);
        MatchCriteria criteria = criteria(12, 3);
        apply(criteria, input);

        if (expected.startsWith("error ")) {
            assertThatThrownBy(() -> controller.recommend(criteria))
                    .isInstanceOf(InvalidInputException.class)
                    .satisfies(e -> assertThat(((InvalidInputException) e).getFieldErrors())
                            .as(tcId).containsKey(expected.substring("error ".length())));
            return;
        }
        List<Recommendation> recs = controller.recommend(criteria);
        if (expected.equals("EXCLUDED")) {
            assertThat(recs).as(tcId).isEmpty();
            return;
        }
        String[] labelAndScore = expected.split(" ");
        ScoreComponent fit = component(recs.getFirst(), MatchFactor.PSLE_FIT);
        assertThat(fit.getReason()).as(tcId).endsWith("→ " + labelAndScore[0]);
        assertThat(fit.getScore()).as(tcId).isEqualTo(Double.parseDouble(labelAndScore[1]));
    }

    /** Applies "score=13", "pg=2", "maxMin=20", "mode=", "start=none|outside", "commute=31" to the criteria. */
    private void apply(MatchCriteria criteria, String input) {
        String[] pair = input.split("=", 2);
        String value = pair.length > 1 ? pair[1] : "";
        switch (pair[0]) {
            case "score" -> criteria.setPsleScore(value.isEmpty() ? null : Integer.valueOf(value));
            case "pg" -> criteria.setPostingGroup(Integer.valueOf(value));
            case "maxMin" -> criteria.setMaxCommuteMin(Integer.valueOf(value));
            case "mode" -> criteria.setTravelMode(value.isEmpty() ? null : TravelMode.valueOf(value));
            case "start" -> criteria.setStartLocation(value.equals("none") ? null
                    : new ReferenceLocation(TestSchools.OUTSIDE_SINGAPORE, LocationSource.MANUAL_ENTRY, "far away"));
            case "commute" -> commuteMinutes.put("test-school", Integer.valueOf(value));
            default -> throw new IllegalArgumentException("Unknown input " + input);
        }
    }

    // ------------------------------------------------------------------ scoring

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-04-01: the worked example of docs/recommendation-scoring.md scores 0.725")
    void workedExample() {
        School school = TestSchools.school("example").range(2025, 3, 8, 12)
                .ccas("BASKETBALL", "CHOIR").programmes("Art").build();
        when(schoolData.getSchools()).thenReturn(List.of(school));
        commuteMinutes.put("example", 15);
        MatchCriteria criteria = criteria(11, 3);
        criteria.setPreferredCCAs(List.of("BASKETBALL", "CHOIR", "FENCING"));
        criteria.setPreferredProgrammes(List.of("Art", "Music"));

        Recommendation rec = controller.recommend(criteria).getFirst();

        assertThat(rec.getTotalScore()).isCloseTo(0.725, within(1e-9));
        assertThat(rec.getCommuteMin()).isEqualTo(15);
        assertThat(component(rec, MatchFactor.PSLE_FIT).getReason())
                .isEqualTo("PSLE 11 is within the 2025 PG3 range 8–12 → MATCH");
        assertThat(component(rec, MatchFactor.COMMUTE).getScore()).isEqualTo(0.5);
        assertThat(component(rec, MatchFactor.COMMUTE).getReason()).contains("15 min").contains("public transport");
        assertThat(component(rec, MatchFactor.CCA).getScore()).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(component(rec, MatchFactor.CCA).getReason()).isEqualTo("Offers 2 of your 3 CCAs: BASKETBALL, CHOIR");
        assertThat(component(rec, MatchFactor.PROGRAMME).getReason()).isEqualTo("Offers 1 of your 2 programmes: Art");
        assertThat(rec.getReasons()).hasSize(4);
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-04-02: with no CCA or programme preference those factors drop out and the rest is rescaled")
    void noPreferences() {
        School school = TestSchools.school("plain").range(2025, 3, 8, 12).build();
        when(schoolData.getSchools()).thenReturn(List.of(school));
        commuteMinutes.put("plain", 15);

        Recommendation rec = controller.recommend(criteria(10, 3)).getFirst();   // SAFE 0.7, commute 0.5

        assertThat(rec.getComponents()).extracting(ScoreComponent::getFactor)
                .containsExactly(MatchFactor.PSLE_FIT, MatchFactor.COMMUTE);
        assertThat(rec.getTotalScore()).isCloseTo((0.4 * 0.7 + 0.3 * 0.5) / 0.7, within(1e-9));
    }

    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-DATA-03")
    @DisplayName("TC-REC-04-03: only schools with a range for the posting group are candidates (DC-22)")
    void onlySchoolsWithRange() {
        School pg3Only = TestSchools.school("pg3-only").range(2025, 3, 8, 12).build();
        School noRanges = TestSchools.school("no-ranges").build();
        School pg2 = TestSchools.school("pg2").range(2025, 2, 10, 14).build();
        when(schoolData.getSchools()).thenReturn(List.of(pg3Only, noRanges, pg2));
        commuteMinutes.putAll(Map.of("pg3-only", 10, "no-ranges", 10, "pg2", 10));

        assertThat(controller.recommend(criteria(12, 2))).extracting(r -> r.getSchool().getSchoolCode())
                .containsExactly("pg2");
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-04-04: the affiliated range is used only when the profile's primary school is affiliated")
    void affiliatedRange() {
        School school = TestSchools.school("aff").range(2025, 3, 8, 10).affiliatedRange(2025, 3, 8, 14)
                .affiliatedPrimarySchools("AI TONG SCHOOL").build();
        when(schoolData.getSchools()).thenReturn(List.of(school));
        commuteMinutes.put("aff", 10);
        MatchCriteria guest = criteria(13, 3);
        MatchCriteria affiliated = criteria(13, 3);
        affiliated.setPrimarySchool("Ai Tong School");

        assertThat(controller.recommend(guest)).as("13 > 10 + 2: excluded with the non-affiliated range").isEmpty();
        Recommendation rec = controller.recommend(affiliated).getFirst();
        assertThat(component(rec, MatchFactor.PSLE_FIT).getReason())
                .isEqualTo("PSLE 13 is within the 2025 PG3 affiliated range 8–14 → MATCH");
    }

    // ------------------------------------------------------------------ commute

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-05-04: the commute limit is checked on seconds and minutes round up, like the travel-time filter")
    void commuteLimitUsesSeconds() {
        List<School> schools = List.of(
                TestSchools.school("just-over").range(2025, 3, 8, 12).at(1.3511, 103.8484).build(),
                TestSchools.school("exactly-on").range(2025, 3, 8, 12).at(1.3512, 103.8484).build(),
                TestSchools.school("just-under").range(2025, 3, 8, 12).at(1.3513, 103.8484).build());
        commuteSeconds.put("just-over", 15 * 60 + 20);   // 15 min 20 s: over a 15-minute limit
        commuteSeconds.put("exactly-on", 15 * 60);
        commuteSeconds.put("just-under", 14 * 60 + 1);   // shown as 15 min (rounded up)
        when(schoolData.getSchools()).thenReturn(schools);
        MatchCriteria criteria = criteria(10, 3);
        criteria.setMaxCommuteMin(15);

        List<Recommendation> recs = controller.recommend(criteria);

        assertThat(recs).extracting(r -> r.getSchool().getSchoolCode())
                .containsExactlyInAnyOrder("exactly-on", "just-under");
        assertThat(recs).extracting(Recommendation::getCommuteMin).containsOnly(15);
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-05-01: only the top 20 by PSLE/CCA/programme score get travel times, in one call")
    void commuteOnlyForTop20() {
        List<School> schools = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            String code = String.format("school-%02d", i);
            // schools 1–10 MATCH (1.0), 11–25 SAFE (0.7): the top 20 are 1–10 plus the 10 nearest SAFE ones
            schools.add(TestSchools.school(code).range(2025, 3, 8, i <= 10 ? 12 : 16)
                    .at(1.3510 + i * 0.001, 103.8484).build());
            commuteMinutes.put(code, 20);
        }
        when(schoolData.getSchools()).thenReturn(schools);

        List<Recommendation> recs = controller.recommend(criteria(12, 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<? extends Place>> asked = ArgumentCaptor.forClass(List.class);
        verify(directions, times(1)).getCommuteTimes(eq(BISHAN), asked.capture(), eq(TravelMode.TRANSIT));
        assertThat(asked.getValue()).hasSize(props.recommendation().commuteCandidates())
                .extracting(p -> ((School) p).getSchoolCode())
                .contains("school-01", "school-10", "school-11", "school-20")
                .doesNotContain("school-21", "school-25");
        assertThat(recs).hasSize(props.recommendation().topN());
    }

    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-REC-05-02: when the route service fails, commute is 'Not available' and its weight is spread")
    void routeServiceDown() {
        School school = TestSchools.school("down").range(2025, 3, 8, 12).build();
        when(schoolData.getSchools()).thenReturn(List.of(school));
        when(directions.getCommuteTimes(any(), anyList(), any()))
                .thenThrow(new ExternalServiceUnavailableException("Google Routes", null));

        Recommendation rec = controller.recommend(criteria(12, 3)).getFirst();

        assertThat(rec.getCommuteMin()).isNull();
        assertThat(rec.getTotalScore()).isCloseTo(1.0, within(1e-9));   // only PSLE_FIT counts
        assertThat(component(rec, MatchFactor.COMMUTE).getReason())
                .contains("Not available").contains("route service");
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-05-03: a school with no route keeps 'Not available' commute; the others are scored normally")
    void noRouteForOneSchool() {
        School reachable = TestSchools.school("reachable").range(2025, 3, 8, 12).build();
        School island = TestSchools.school("island").range(2025, 3, 8, 12).build();
        when(schoolData.getSchools()).thenReturn(List.of(reachable, island));
        commuteMinutes.put("reachable", 15);

        Map<String, Recommendation> byCode = new HashMap<>();
        controller.recommend(criteria(12, 3)).forEach(r -> byCode.put(r.getSchool().getSchoolCode(), r));

        assertThat(byCode.get("reachable").getCommuteMin()).isEqualTo(15);
        assertThat(byCode.get("island").getCommuteMin()).isNull();
        assertThat(component(byCode.get("island"), MatchFactor.COMMUTE).getReason()).contains("No route");
    }

    // ------------------------------------------------------------------ ranking

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-06-01: results are the top 10 by total score, ranked 1..10; ties go to the shorter commute")
    void ranking() {
        List<School> schools = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String code = String.format("s-%02d", i);
            schools.add(TestSchools.school(code).range(2025, 3, 8, 12).build());
            commuteMinutes.put(code, 30 - i);   // s-12 has the shortest commute, so the highest score
        }
        when(schoolData.getSchools()).thenReturn(schools);

        List<Recommendation> recs = controller.recommend(criteria(12, 3));

        assertThat(recs).hasSize(10);
        assertThat(recs).extracting(Recommendation::getRank).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(recs).extracting(r -> r.getSchool().getSchoolCode()).startsWith("s-12", "s-11").doesNotContain("s-01", "s-02");
        assertThat(recs).extracting(Recommendation::getTotalScore).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-06-02: equal scores and commutes are ordered by school name")
    void tieByName() {
        School beta = TestSchools.school("beta").name("BETA SECONDARY SCHOOL").range(2025, 3, 8, 12).build();
        School alpha = TestSchools.school("alpha").name("ALPHA SECONDARY SCHOOL").range(2025, 3, 8, 12).build();
        when(schoolData.getSchools()).thenReturn(List.of(beta, alpha));
        commuteMinutes.putAll(Map.of("alpha", 20, "beta", 20));

        assertThat(controller.recommend(criteria(12, 3))).extracting(r -> r.getSchool().getSchoolCode())
                .containsExactly("alpha", "beta");
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-07-01: no candidate school (AF-2) gives an empty list, without asking for travel times")
    void noCandidates() {
        when(schoolData.getSchools()).thenReturn(List.of(TestSchools.school("none").build()));

        assertThat(controller.recommend(criteria(12, 3))).isEmpty();
        verify(directions, never()).getCommuteTimes(any(), anyList(), any());
    }

    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-REC-07-02: a school data error (EX-1) becomes ExternalServiceUnavailableException")
    void schoolDataError() {
        when(schoolData.getSchools()).thenThrow(new IllegalStateException("snapshot unreadable"));

        assertThatThrownBy(() -> controller.recommend(criteria(12, 3)))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-07-03: weights in the criteria are used instead of app.recommendation.weights")
    void criteriaWeights() {
        School school = TestSchools.school("w").range(2025, 3, 8, 12).build();
        when(schoolData.getSchools()).thenReturn(List.of(school));
        commuteMinutes.put("w", 15);
        MatchCriteria criteria = criteria(10, 3);   // SAFE 0.7, commute 0.5
        Map<MatchFactor, Double> weights = new EnumMap<>(MatchFactor.class);
        weights.put(MatchFactor.PSLE_FIT, 1.0);
        weights.put(MatchFactor.COMMUTE, 1.0);
        criteria.setWeights(weights);

        assertThat(controller.recommend(criteria).getFirst().getTotalScore()).isCloseTo(0.6, within(1e-9));
    }

    // ------------------------------------------------------------------ helpers

    /** Valid criteria: PSLE score, posting group, from Bishan by public transport, at most 30 min. */
    private static MatchCriteria criteria(int psleScore, int postingGroup) {
        MatchCriteria criteria = new MatchCriteria();
        criteria.setPsleScore(psleScore);
        criteria.setPostingGroup(postingGroup);
        criteria.setStartLocation(BISHAN);
        criteria.setTravelMode(TravelMode.TRANSIT);
        criteria.setMaxCommuteMin(30);
        return criteria;
    }

    private static ScoreComponent component(Recommendation rec, MatchFactor factor) {
        return rec.getComponents().stream().filter(c -> c.getFactor() == factor).findFirst().orElseThrow();
    }
}

package sg.schoolmatch.boundary.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.FilterController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.control.RecommendationController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.recommend.MatchCriteria;
import sg.schoolmatch.entity.recommend.MatchFactor;
import sg.schoolmatch.entity.recommend.Recommendation;
import sg.schoolmatch.entity.recommend.ScoreComponent;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.entity.search.AttributeCategory;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.support.LogCapture;
import sg.schoolmatch.support.TestSchools;

/**
 * Web test of RecommendationUI (DM-22 RecommendationCriteria, DM-23 loading, DM-24 Recommendation; use case
 * Get School Recommendations, FR-REC-01, DC-07). The controls are mocks.
 */
@WebMvcTest(RecommendationUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import({SessionCookie.class, ReferenceLocationStore.class})
@ActiveProfiles("test")
class RecommendationUITest {

    private static final String SESSION = "session-ann";
    private static final Coordinate HOME = new Coordinate(1.3510, 103.8484);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppProperties props;

    @MockitoBean
    private RecommendationController recommendationController;

    @MockitoBean
    private ProfileController profileController;

    @MockitoBean
    private FilterController filterController;

    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    private final School catholic = TestSchools.school("catholic-high-school").name("CATHOLIC HIGH SCHOOL")
            .range(2025, 3, 8, 12).build();

    @BeforeEach
    void logIn() {
        when(authController.verifySession(SESSION)).thenReturn(true);
        when(filterController.getFilterOptions(AttributeCategory.CCA)).thenReturn(Set.of("BASKETBALL", "CHOIR"));
        when(filterController.getFilterOptions(AttributeCategory.PROGRAMME)).thenReturn(Set.of("Art"));
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-RecommendationUI-01: a guest is sent to the login page (DC-07)")
    void guestGoesToLogin() throws Exception {
        mvc.perform(get("/recommendations"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?next=/recommendations"));
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-RecommendationUI-02: the form is prefilled from the profile; home is the starting point")
    void formPrefilledFromProfile() throws Exception {
        when(profileController.getProfile(SESSION)).thenReturn(completeProfile());

        MvcResult result = mvc.perform(get("/recommendations").cookie(member()))
                .andExpect(status().isOk())
                .andExpect(view().name("recommendation-criteria"))
                .andExpect(model().attributeDoesNotExist("missingProfileFields"))
                .andExpect(content().string(containsString("value=\"12\"")))
                .andExpect(content().string(containsString("9 Bishan Street 22")))
                .andExpect(content().string(not(containsString("Not built yet"))))
                .andReturn();
        MatchCriteria criteria = (MatchCriteria) result.getModelAndView().getModel().get("criteria");
        assertThat(criteria.getPostingGroup()).isEqualTo(3);
        assertThat(criteria.getTravelMode()).isEqualTo(TravelMode.TRANSIT);
        assertThat(criteria.getPreferredCCAs()).containsExactly("CHOIR");
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-RecommendationUI-03: an incomplete profile shows what is missing and a link to the profile (AF-1)")
    void incompleteProfile() throws Exception {
        when(profileController.getProfile(SESSION)).thenReturn(new UserProfile(account()));

        mvc.perform(get("/recommendations").cookie(member()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("missingProfileFields",
                        List.of("PSLE score", "posting group", "home address", "travel mode")))
                .andExpect(content().string(containsString("href=\"/profile\"")))
                .andExpect(content().string(containsString("Complete your profile")));
    }

    @Test
    @Tag("FR-REC-01")
    @Tag("FR-FILTER-04")
    @DisplayName("TC-RecommendationUI-04: a starting point chosen with the location picker replaces home")
    void sessionLocationWins() throws Exception {
        when(profileController.getProfile(SESSION)).thenReturn(completeProfile());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ReferenceLocationStore.CURRENT,
                new ReferenceLocation(TestSchools.TAMPINES, LocationSource.MANUAL_ENTRY, "Tampines MRT"));

        mvc.perform(get("/recommendations").cookie(member()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tampines MRT")));
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-RecommendationUI-05: submitting stores the results and redirects to /recommendations/results/{id}")
    void submitThenShowResults() throws Exception {
        when(profileController.getProfile(SESSION)).thenReturn(completeProfile());
        when(recommendationController.recommend(any())).thenReturn(List.of(recommendation(1, catholic, 25)));
        MockHttpSession session = new MockHttpSession();

        MvcResult submitted = mvc.perform(post("/recommendations").cookie(member()).session(session)
                        .param("psleScore", "12").param("postingGroup", "3").param("travelMode", "TRANSIT")
                        .param("maxCommuteMin", "45").param("preferredCCAs", "CHOIR", "BASKETBALL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/recommendations/results/*"))
                .andReturn();

        ArgumentCaptor<MatchCriteria> sent = ArgumentCaptor.forClass(MatchCriteria.class);
        verify(recommendationController).recommend(sent.capture());
        assertThat(sent.getValue().getPsleScore()).isEqualTo(12);
        assertThat(sent.getValue().getMaxCommuteMin()).isEqualTo(45);
        assertThat(sent.getValue().getPreferredCCAs()).containsExactlyInAnyOrder("CHOIR", "BASKETBALL");
        assertThat(sent.getValue().getPrimarySchool()).isEqualTo("Ai Tong School");
        assertThat(sent.getValue().getStartLocation().getCoordinate()).isEqualTo(HOME);
        assertThat(sent.getValue().getWeights()).isEqualTo(props.recommendation().weights());

        mvc.perform(get(submitted.getResponse().getRedirectedUrl()).cookie(member()).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("recommendation-results"))
                .andExpect(content().string(containsString("CATHOLIC HIGH SCHOOL")))
                .andExpect(content().string(containsString("href=\"/schools/catholic-high-school\"")))
                .andExpect(content().string(containsString("PSLE 12 is within the 2025 PG3 range 8–12 → MATCH")))
                .andExpect(content().string(containsString("25 min")))
                .andExpect(content().string(containsString("historical")))
                .andExpect(content().string(containsString("Refine criteria")))
                .andExpect(content().string(not(containsString("Not built yet"))));
    }

    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-RecommendationUI-06: invalid criteria show the field messages on the form, nothing is stored")
    void invalidCriteria() throws Exception {
        when(profileController.getProfile(SESSION)).thenReturn(completeProfile());
        when(recommendationController.recommend(any())).thenThrow(new InvalidInputException(
                Map.of("psleScore", "Enter a whole number from 4 to 32")));

        mvc.perform(post("/recommendations").cookie(member()).param("psleScore", "abc").param("postingGroup", "3"))
                .andExpect(status().isOk())
                .andExpect(view().name("recommendation-criteria"))
                .andExpect(content().string(containsString("Enter a whole number from 4 to 32")))
                .andExpect(content().string(containsString("is-invalid")));
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-RecommendationUI-07: no matching school shows the AF-2 message on the results page")
    void noMatches() throws Exception {
        when(profileController.getProfile(SESSION)).thenReturn(completeProfile());
        when(recommendationController.recommend(any())).thenReturn(List.of());
        MockHttpSession session = new MockHttpSession();

        MvcResult submitted = mvc.perform(post("/recommendations").cookie(member()).session(session)
                        .param("psleScore", "30").param("postingGroup", "3").param("travelMode", "WALK")
                        .param("maxCommuteMin", "15"))
                .andReturn();

        mvc.perform(get(submitted.getResponse().getRedirectedUrl()).cookie(member()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No school matches your criteria")));
    }

    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-RecommendationUI-08: a school data error (EX-1) keeps the form with a message")
    void schoolDataError() throws Exception {
        when(profileController.getProfile(SESSION)).thenReturn(completeProfile());
        when(recommendationController.recommend(any()))
                .thenThrow(new ExternalServiceUnavailableException("School data", null));

        mvc.perform(post("/recommendations").cookie(member()).param("psleScore", "12").param("postingGroup", "3"))
                .andExpect(status().isOk())
                .andExpect(view().name("recommendation-criteria"))
                .andExpect(content().string(containsString("temporarily unavailable")));
    }

    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-RecommendationUI-15: a school data error writes one WARN line naming the service")
    void schoolDataError_logsOneWarning() throws Exception {
        when(profileController.getProfile(SESSION)).thenReturn(completeProfile());
        when(recommendationController.recommend(any()))
                .thenThrow(new ExternalServiceUnavailableException("School data", new IllegalStateException("no snapshot")));

        try (LogCapture log = LogCapture.of(RecommendationUI.class)) {
            mvc.perform(post("/recommendations").cookie(member()).param("psleScore", "12").param("postingGroup", "3"))
                    .andExpect(content().string(containsString("temporarily unavailable")));

            assertThat(log.warnings()).containsExactly(
                    "Recommendations unavailable: School data: School data failed: IllegalStateException: no snapshot");
        }
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-RecommendationUI-09: an unknown or expired result id goes back to the form with a message")
    void unknownResultId() throws Exception {
        mvc.perform(get("/recommendations/results/no-such-id").cookie(member()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/recommendations"))
                .andExpect(flash().attribute("flashMessage", startsWith("Those results are no longer available")));
        verify(recommendationController, never()).recommend(any());
    }

    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-SEC-05")
    @DisplayName("TC-RecommendationUI-11: results are shown only to the login that asked, not to another account "
            + "in the same browser")
    void resultsBelongToTheirLogin() throws Exception {
        when(authController.verifySession("session-bob")).thenReturn(true);
        when(profileController.getProfile(SESSION)).thenReturn(completeProfile());
        when(recommendationController.recommend(any())).thenReturn(List.of(recommendation(1, catholic, 25)));
        MockHttpSession browser = new MockHttpSession();

        MvcResult submitted = mvc.perform(post("/recommendations").cookie(member()).session(browser)
                .param("psleScore", "12").param("postingGroup", "3").param("travelMode", "TRANSIT")
                .param("maxCommuteMin", "45")).andReturn();

        mvc.perform(get(submitted.getResponse().getRedirectedUrl()).cookie(new Cookie("SM_SESSION", "session-bob"))
                        .session(browser))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/recommendations"));
    }

    @Test
    @Tag("FR-REC-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-RecommendationUI-12: after an error the location picker returns to the form with the typed criteria")
    void errorKeepsDraftForLocationPicker() throws Exception {
        when(profileController.getProfile(SESSION)).thenReturn(new UserProfile(account()));
        when(recommendationController.recommend(any())).thenThrow(new InvalidInputException(
                Map.of("startLocation", "Set a starting point so commute times can be measured")));

        mvc.perform(post("/recommendations").cookie(member()).param("psleScore", "14").param("postingGroup", "3")
                        .param("travelMode", "WALK").param("maxCommuteMin", "30"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("returnTo",
                        "/recommendations?psleScore=14&postingGroup=3&travelMode=WALK&maxCommuteMin=30"));
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-RecommendationUI-13: GET /recommendations with draft criteria fills the form with them, not the profile")
    void draftCriteriaPrefillTheForm() throws Exception {
        when(profileController.getProfile(SESSION)).thenReturn(new UserProfile(account()));

        mvc.perform(get("/recommendations").cookie(member()).param("psleScore", "14").param("postingGroup", "3")
                        .param("travelMode", "WALK").param("maxCommuteMin", "30"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"14\"")))
                .andExpect(model().attribute("criteria", org.hamcrest.Matchers.hasProperty("psleScore",
                        org.hamcrest.Matchers.is(14))))
                .andExpect(model().attribute("criteria", org.hamcrest.Matchers.hasProperty("travelMode",
                        org.hamcrest.Matchers.is(TravelMode.WALK))));
    }

    @Test
    @Tag("FR-REC-01")
    @Tag("FR-FILTER-04")
    @DisplayName("TC-RecommendationUI-14: the profile's home address is labelled as such and has no Clear button")
    void profileHomeHasNoClear() throws Exception {
        when(profileController.getProfile(SESSION)).thenReturn(completeProfile());

        mvc.perform(get("/recommendations").cookie(member()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("(home address from your profile)")))
                .andExpect(content().string(not(containsString("action=\"/location/clear\""))));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ReferenceLocationStore.CURRENT,
                new ReferenceLocation(TestSchools.TAMPINES, LocationSource.MANUAL_ENTRY, "Tampines MRT"));
        mvc.perform(get("/recommendations").cookie(member()).session(session))
                .andExpect(content().string(not(containsString("(home address from your profile)"))))
                .andExpect(content().string(containsString("action=\"/location/clear\"")));
    }

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-RecommendationUI-10: with the seed snapshot the results say the ranges are TEST VALUES")
    void seedDataWarning() throws Exception {
        SchoolDataCache seed = new SchoolDataCache("test", Instant.EPOCH, null, List.of(catholic), List.of());
        seed.setDatasetKind("seed");
        when(schoolDataController.getActiveDataset()).thenReturn(seed);
        when(profileController.getProfile(SESSION)).thenReturn(completeProfile());
        when(recommendationController.recommend(any())).thenReturn(List.of(recommendation(1, catholic, null)));
        MockHttpSession session = new MockHttpSession();

        MvcResult submitted = mvc.perform(post("/recommendations").cookie(member()).session(session)
                .param("psleScore", "12").param("postingGroup", "3").param("travelMode", "TRANSIT")
                .param("maxCommuteMin", "45")).andReturn();

        mvc.perform(get(submitted.getResponse().getRedirectedUrl()).cookie(member()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TEST VALUES")))
                .andExpect(content().string(containsString("Not available")));
    }

    // ------------------------------------------------------------------ helpers

    private static Cookie member() {
        return new Cookie("SM_SESSION", SESSION);
    }

    private static Account account() {
        return new Account("ann", "ann@example.test", "hash", Instant.parse("2026-10-01T00:00:00Z"));
    }

    private static UserProfile completeProfile() {
        UserProfile profile = new UserProfile(account());
        profile.setPsleScore(12);
        profile.setPostingGroup(3);
        profile.setPrimarySchool("Ai Tong School");
        profile.setHomeAddress("9 Bishan Street 22");
        profile.setHomeLocation(HOME);
        profile.setTravelMode(TravelMode.TRANSIT);
        profile.setMaxCommuteMin(45);
        profile.setPreferredCCAs(List.of("CHOIR"));
        return profile;
    }

    private static Recommendation recommendation(int rank, School school, Integer commuteMin) {
        Recommendation rec = new Recommendation(school, 0.85, List.of(
                new ScoreComponent(MatchFactor.PSLE_FIT, 1.0, "PSLE 12 is within the 2025 PG3 range 8–12 → MATCH"),
                new ScoreComponent(MatchFactor.COMMUTE, 0.44, "About 25 min by public transport (your limit is 45 min)")));
        rec.setRank(rank);
        rec.setCommuteMin(commuteMin);
        return rec;
    }
}

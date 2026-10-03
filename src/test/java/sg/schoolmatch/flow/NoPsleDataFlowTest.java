package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import sg.schoolmatch.boundary.ui.support.FilterParams;
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AccountController;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.RecommendationController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.shortlist.ChoicePlan;
import sg.schoolmatch.support.TestMembers;
import sg.schoolmatch.support.TestSchools;

/**
 * DC-74: the whole app on a dataset WITHOUT any PSLE score range — the situation of the real snapshot until MOE
 * SchoolFinder ranges are curated. Fixture {@code fixtures/snapshot-mini-no-psle/}: snapshot-mini with every
 * {@code scoreRanges} emptied (kind "seed", so the seed's TEST VALUES warning must give way to "not available yet").
 * Real controls, real login, stub external services; its own in-memory database so it does not share tables with
 * the other application contexts.
 */
@SpringBootTest(properties = {
        "app.dataset.snapshot-location=classpath:fixtures/snapshot-mini-no-psle/",
        "spring.datasource.url=jdbc:h2:mem:nopsle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoPsleDataFlowTest {

    private static final String NOT_AVAILABLE_YET = "PSLE score ranges: not available yet";
    private static final String NOT_APPLIED_NOTE =
            "PSLE filter not applied: PSLE score ranges are not available in the current dataset.";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppProperties props;

    @Autowired
    private AccountController accountController;

    @Autowired
    private AuthController authController;

    @Autowired
    private SchoolDataController schoolDataController;

    private Cookie member;

    @BeforeEach
    void logIn() {
        member = TestMembers.cookie(props, TestMembers.registerAndLogIn(accountController, authController, "nopsle"));
    }

    @Test
    @Tag("FR-DATA-03")
    @Tag("DC-74")
    @DisplayName("TC-NoPsleFlow-01: the fixture loads with all 10 schools and hasPsleData is false")
    void datasetHasNoPsleData() throws Exception {
        assertThat(schoolDataController.getSchools()).hasSize(10);
        assertThat(schoolDataController.hasPsleData()).isFalse();
        mvc.perform(get("/schools")).andExpect(model().attribute("psleDataAvailable", false));
    }

    @Test
    @Tag("FR-FILTER-03")
    @Tag("FR-FILTER-08")
    @Tag("DC-74")
    @DisplayName("TC-FILTER-03-17: a PSLE score in the URL is not applied: every school stays, the chip says why")
    void searchWithPsle_notApplied() throws Exception {
        String page = mvc.perform(get("/schools").param("psle", "12").param("pg", "2"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 10))
                .andExpect(model().attribute("psleNotApplied", true))
                .andExpect(model().attribute("hasFilters", false))
                .andExpect(model().attribute("chips", hasSize(0)))
                .andExpect(model().attribute("notAppliedChips", hasSize(1)))
                .andExpect(model().attributeDoesNotExist("fieldErrors", "cardRanges"))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Not applied:").contains("PSLE 12 (PG2)").contains(NOT_APPLIED_NOTE)
                .doesNotContain("have no PSLE range for PG").doesNotContain("has no PSLE range for PG");
    }

    @Test
    @Tag("FR-FILTER-03")
    @Tag("FR-FILTER-07")
    @Tag("DC-74")
    @DisplayName("TC-FILTER-03-18: an invalid PSLE score is not reported, and the other filters still apply")
    void searchWithBadPsleAndType_otherFiltersApply() throws Exception {
        mvc.perform(get("/schools").param("psle", "abc").param("type", "GOVERNMENT SCHOOL"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("psleNotApplied", true))
                .andExpect(model().attribute("hasFilters", true))
                .andExpect(model().attribute("chips", hasSize(1)))
                .andExpect(model().attributeDoesNotExist("fieldErrors"))
                .andExpect(content().string(not(containsString(FilterParams.PSLE_MESSAGE))));
    }

    @Test
    @Tag("FR-FILTER-03")
    @Tag("DC-74")
    @DisplayName("TC-FILTER-03-19: the filter page shows PSLE score and posting group disabled, with the reason")
    void filterPage_psleInputsDisabled() throws Exception {
        String page = mvc.perform(get("/schools/filter").param("psle", "99").param("pg", "7"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("psleUnavailableMessage", SchoolDataController.NO_PSLE_DATA_MESSAGE))
                .andExpect(model().attributeDoesNotExist("fieldErrors", "pgHint"))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("PSLE score ranges are not available in the current dataset.");
        assertThat(tag(page, "input", "id=\"psle\"")).contains("disabled");
        assertThat(tag(page, "select", "id=\"pg\"")).contains("disabled");
        assertThat(tag(page, "select", "id=\"mode\"")).doesNotContain("disabled");
    }

    @Test
    @Tag("FR-MAP-01")
    @Tag("FR-FILTER-03")
    @Tag("DC-74")
    @DisplayName("TC-NoPsleFlow-02: the map with a PSLE score in the URL shows every school and says the filter is not applied")
    void mapWithPsle_notApplied() throws Exception {
        mvc.perform(get("/schools/map").param("psle", "12"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("resultCount", 10))
                .andExpect(model().attribute("psleNotApplied", true))
                .andExpect(model().attribute("psleActive", false))
                .andExpect(content().string(containsString(NOT_APPLIED_NOTE)));
    }

    @Test
    @Tag("FR-SEARCH-03")
    @Tag("NFR-DATA-03")
    @Tag("DC-74")
    @DisplayName("TC-SEARCH-03-03: each result card says once that PSLE score ranges are not available yet")
    void cards_sayNotAvailableYetOnce() throws Exception {
        String page = mvc.perform(get("/schools"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 10))
                .andReturn().getResponse().getContentAsString();

        assertThat(count(page, NOT_AVAILABLE_YET)).isEqualTo(10);
        assertThat(page).doesNotContain("PSLE AL").doesNotContain("TEST VALUES").doesNotContain("Seed PSLE ranges");
    }

    @Test
    @Tag("FR-SCHOOL-02")
    @Tag("NFR-DATA-03")
    @Tag("DC-74")
    @DisplayName("TC-SCHOOL-01-05: the details page says once that PSLE score ranges are not available yet")
    void details_notAvailableYet() throws Exception {
        String page = mvc.perform(get("/schools/catholic-high-school"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int start = page.indexOf("id=\"psle-ranges\"");
        String ranges = page.substring(start, page.indexOf("</section>", start));

        assertThat(count(ranges, NOT_AVAILABLE_YET)).isEqualTo(1);
        assertThat(ranges).doesNotContain("TEST VALUES").doesNotContain("Not available");
    }

    @Test
    @Tag("FR-COMPARE-01")
    @Tag("DC-74")
    @DisplayName("TC-COMPARE-01-10: compare leaves out the PSLE rows and says once that the ranges are not available yet")
    void compare_noPsleRows() throws Exception {
        shortlist("catholic-high-school", "hua-yi-secondary-school");

        String page = mvc.perform(get("/compare").param("codes", "catholic-high-school", "hua-yi-secondary-school")
                        .cookie(member))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).doesNotContain("PSLE AL range PG").doesNotContain("TEST VALUES")
                .contains("Number of CCAs");
        assertThat(count(page, NOT_AVAILABLE_YET)).isEqualTo(1);
    }

    @Test
    @Tag("FR-PLAN-01")
    @Tag("FR-PLAN-02")
    @Tag("DC-74")
    @DisplayName("TC-PLAN-02-01: the plan adds and moves choices, labels are Not available, and one note replaces the range warnings")
    void plan_worksWithoutRanges() throws Exception {
        shortlist("catholic-high-school", "hua-yi-secondary-school");
        mvc.perform(post("/plan/choices").param("code", "catholic-high-school").param("rank", "1").cookie(member))
                .andExpect(redirectedUrl("/plan"));
        mvc.perform(post("/plan/choices").param("code", "hua-yi-secondary-school").param("rank", "2").cookie(member))
                .andExpect(redirectedUrl("/plan"));
        mvc.perform(post("/plan/choices/hua-yi-secondary-school/move").param("dir", "up").cookie(member))
                .andExpect(redirectedUrl("/plan"));

        MvcResult result = mvc.perform(get("/plan").cookie(member))
                .andExpect(status().isOk())
                .andExpect(model().attribute("warnings", List.of(ChoicePlan.NO_PSLE_DATA_WARNING,
                        "You have 2 of 6 choices. Fill all 6 to lower the risk of being posted to a school you did "
                                + "not choose.")))
                .andReturn();
        String page = result.getResponse().getContentAsString();

        assertThat(page.indexOf("HUA YI SECONDARY SCHOOL")).isLessThan(page.indexOf("CATHOLIC HIGH SCHOOL"));
        assertThat(page).doesNotContain("data-chance=").doesNotContain(ChoicePlan.NO_SAFE_WARNING)
                .doesNotContain("no PSLE range data").doesNotContain("TEST VALUES").contains(NOT_AVAILABLE_YET);
        assertThat(count(page, "Not available")).isGreaterThanOrEqualTo(4);   // range and chance of both rows

        mvc.perform(post("/plan/choices/catholic-high-school/remove").cookie(member))
                .andExpect(redirectedUrl("/plan"));
    }

    @Test
    @Tag("FR-REC-01")
    @Tag("DC-74")
    @DisplayName("TC-REC-08-05: recommendations work without a PSLE score; every result says PSLE fit is not used")
    void recommendations_withoutPsleFit() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ReferenceLocationStore.CURRENT,
                new ReferenceLocation(TestSchools.BISHAN, LocationSource.MANUAL_ENTRY, "Bishan"));

        String results = mvc.perform(post("/recommendations").cookie(member).session(session)
                        .param("travelMode", "DRIVE").param("maxCommuteMin", "60"))
                .andExpect(redirectedUrlPattern("/recommendations/results/*"))
                .andReturn().getResponse().getRedirectedUrl();
        String page = mvc.perform(get(results).cookie(member).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int shown = count(page, "class=\"badge text-bg-primary\"");
        assertThat(shown).isGreaterThan(0);
        assertThat(count(page, RecommendationController.PSLE_FIT_NOT_USED)).isEqualTo(shown);
        assertThat(page).contains("not used").doesNotContain("TEST VALUES").doesNotContain("For PSLE");
    }

    @Test
    @Tag("FR-REC-01")
    @Tag("FR-PROFILE-01")
    @Tag("DC-74")
    @DisplayName("TC-PROFILE-01-32: profile and criteria keep the optional PSLE fields with a hint that they are not used yet")
    void profileAndCriteria_hint() throws Exception {
        mvc.perform(get("/profile").cookie(member))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"psleScore\"")))
                .andExpect(content().string(containsString("Optional: not")));
        mvc.perform(get("/recommendations").cookie(member))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"psleScore\"")))
                .andExpect(content().string(containsString("Optional: not used until PSLE score ranges are available.")))
                .andExpect(model().attribute("missingProfileFields", not(hasItem("PSLE score"))));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void shortlist(String... codes) throws Exception {
        for (String code : codes) {
            mvc.perform(post("/schools/" + code + "/shortlist").cookie(member))
                    .andExpect(redirectedUrl("/schools/" + code));
        }
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    /** The first {@code <name ...>} start tag that contains {@code marker}, or "" when there is none. */
    private static String tag(String html, String name, String marker) {
        Matcher m = Pattern.compile("<" + name + "\\b[^>]*>").matcher(html);
        while (m.find()) {
            if (m.group().contains(marker)) {
                return m.group();
            }
        }
        return "";
    }
}

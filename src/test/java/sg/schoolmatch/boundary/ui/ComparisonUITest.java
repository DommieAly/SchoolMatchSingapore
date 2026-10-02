package sg.schoolmatch.boundary.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.control.ShortlistController;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.support.TestSchools;

/**
 * Web test of ComparisonUI (DM-18 Compare Schools; use case Compare Schools, FR-COMPARE-01, DC-07, DC-28).
 * The control is a mock.
 */
@WebMvcTest(ComparisonUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import(SessionCookie.class)
@ActiveProfiles("test")
class ComparisonUITest {

    private static final String SESSION = "session-alice";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppProperties props;

    @MockitoBean
    private ShortlistController shortlistController;

    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    private final School catholic = TestSchools.school("catholic-high-school")
            .name("CATHOLIC HIGH SCHOOL").type("GOVERNMENT-AIDED SCH").planningArea("BISHAN")
            .ccas("Basketball", "Choir").programmes("Bicultural Studies Programme")
            .range(2024, 3, 7, 10).range(2025, 3, 6, 9).range(2025, 2, 9, 12)
            .build();
    private final School tampines = TestSchools.school("tampines-secondary-school")
            .name("TAMPINES SECONDARY SCHOOL").type("GOVERNMENT SCHOOL").planningArea("TAMPINES")
            .ccas("Basketball", "Choir")
            .range(2025, 3, 22, 25)
            .build();

    @BeforeEach
    void logIn() {
        when(authController.verifySession(SESSION)).thenReturn(true);
    }

    @Test
    @Tag("FR-COMPARE-01")
    @DisplayName("TC-ComparisonUI-01: the chosen schools appear side by side, each column linking to its details page")
    void displayComparison_showsColumns() throws Exception {
        when(shortlistController.compareSchools(SESSION, codes("catholic-high-school", "tampines-secondary-school")))
                .thenReturn(List.of(catholic, tampines));

        mvc.perform(get("/compare").param("codes", "catholic-high-school,tampines-secondary-school").cookie(member()))
                .andExpect(status().isOk())
                .andExpect(view().name("compare"))
                .andExpect(content().string(containsString("href=\"/schools/catholic-high-school\"")))
                .andExpect(content().string(containsString("href=\"/schools/tampines-secondary-school\"")))
                .andExpect(content().string(containsString("GOVERNMENT-AIDED SCH")))
                .andExpect(content().string(containsString("6–9 (2025)")))         // latest PG3 range, not 2024
                .andExpect(content().string(not(containsString("7–10 (2024)"))))
                .andExpect(content().string(containsString("Bicultural Studies Programme")))
                .andExpect(content().string(not(containsString("Not built yet"))));
    }

    @Test
    @Tag("FR-COMPARE-01")
    @DisplayName("TC-ComparisonUI-02: rows whose values differ are highlighted; equal rows are not (highlightDifferences)")
    void displayComparison_highlightsDifferences() throws Exception {
        when(shortlistController.compareSchools(SESSION, codes("catholic-high-school", "tampines-secondary-school")))
                .thenReturn(List.of(catholic, tampines));

        MvcResult result = mvc.perform(get("/compare")
                        .param("codes", "catholic-high-school")
                        .param("codes", "tampines-secondary-school")
                        .cookie(member()))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(row(html, "School type")).contains("data-differs=\"true\"");
        assertThat(row(html, "CCA list")).contains("data-differs=\"false\"");      // both: Basketball, Choir
        assertThat(row(html, "PSLE AL range PG1")).contains("data-differs=\"false\"")   // both missing
                .contains("Not available");
    }

    @Test
    @Tag("FR-COMPARE-01")
    @DisplayName("TC-ComparisonUI-03: fewer than 2 schools (or one not on the shortlist) goes back to the shortlist with the message (AF-1)")
    void displayComparison_invalidSelection_backToShortlist() throws Exception {
        when(shortlistController.compareSchools(anyString(), any()))
                .thenThrow(new InvalidInputException("codes", ShortlistController.COMPARE_COUNT_MESSAGE));

        mvc.perform(get("/compare").param("codes", "catholic-high-school").cookie(member()))
                .andExpect(redirectedUrl("/shortlist"))
                .andExpect(flash().attribute(PageMessages.FLASH_ERROR, ShortlistController.COMPARE_COUNT_MESSAGE));
        mvc.perform(get("/compare").cookie(member()))
                .andExpect(redirectedUrl("/shortlist"));
        verify(shortlistController).compareSchools(SESSION, codes());
    }

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-ComparisonUI-04: the PSLE rows say the ranges are historical, not guaranteed")
    void displayComparison_historicalNote() throws Exception {
        when(shortlistController.compareSchools(anyString(), any())).thenReturn(List.of(catholic, tampines));

        mvc.perform(get("/compare").param("codes", "catholic-high-school,tampines-secondary-school").cookie(member()))
                .andExpect(content().string(containsString("historical, not guaranteed")));
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-ComparisonUI-05: a guest is sent to the login page")
    void guest_isSentToLogin() throws Exception {
        mvc.perform(get("/compare?codes=a,b"))
                .andExpect(redirectedUrl("/login?next=/compare?codes%3Da,b"));
    }

    @Test
    @Tag("FR-COMPARE-01")
    @DisplayName("TC-ComparisonUI-06: highlightDifferences treats a missing value as different from a present one")
    void highlightDifferences_rules() {
        assertThat(ComparisonUI.differs(List.of("A", "A"))).isFalse();
        assertThat(ComparisonUI.differs(List.of("A", "B"))).isTrue();
        assertThat(ComparisonUI.differs(Arrays.asList("A", null))).isTrue();
        assertThat(ComparisonUI.differs(Arrays.asList(null, null))).isFalse();
    }

    private Cookie member() {
        return new Cookie(props.session().cookieName(), SESSION);
    }

    private static LinkedHashSet<String> codes(String... codes) {
        return new LinkedHashSet<>(List.of(codes));
    }

    /** The HTML of the table row whose header cell is {@code label}. */
    private static String row(String html, String label) {
        int at = html.indexOf(">" + label + "<");
        assertThat(at).as("row %s", label).isNotNegative();
        int start = html.lastIndexOf("<tr", at);
        return html.substring(start, html.indexOf("</tr>", at));
    }
}

package sg.schoolmatch.boundary.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.servlet.http.Cookie;
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
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.ChoicePlanController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.control.ShortlistController;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.shortlist.ChoicePlan;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.support.TestSchools;

/**
 * Web test of ChoicePlanUI (DM-17 ChoicePlanner; use case Plan School Choices, FR-PLAN-01, FR-PLAN-02, DC-20).
 * The controls are mocks; the plan is a real entity so the page shows real SAFE/MATCH/REACH labels.
 */
@WebMvcTest(ChoicePlanUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import(SessionCookie.class)
@ActiveProfiles("test")
class ChoicePlanUITest {

    private static final String SESSION = "session-alice";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppProperties props;

    @MockitoBean
    private ChoicePlanController choicePlanController;

    @MockitoBean
    private ShortlistController shortlistController;

    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    /** With score 12 in PG3: SAFE (U 25), MATCH (U 13), REACH (U 9), no range. */
    private final School tampines = TestSchools.school("tampines-secondary-school")
            .name("TAMPINES SECONDARY SCHOOL").range(2025, 3, 22, 25).build();
    private final School huaYi = TestSchools.school("hua-yi-secondary-school")
            .name("HUA YI SECONDARY SCHOOL").range(2025, 3, 10, 13).build();
    private final School catholic = TestSchools.school("catholic-high-school")
            .name("CATHOLIC HIGH SCHOOL").range(2025, 3, 6, 9).build();
    private final School westwood = TestSchools.school("westwood-secondary-school")
            .name("WESTWOOD SECONDARY SCHOOL").build();
    private final School jurongWest = TestSchools.school("jurong-west-secondary-school")
            .name("JURONG WEST SECONDARY SCHOOL").range(2025, 3, 12, 15).build();

    @BeforeEach
    void logIn() {
        when(authController.verifySession(SESSION)).thenReturn(true);
    }

    @Test
    @Tag("FR-PLAN-01")
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlanUI-01: the plan lists choices 1–6 with range, SAFE/MATCH/REACH badge, order buttons and warnings")
    void displayPlan_showsChoices() throws Exception {
        ChoicePlan plan = plan(12, 3, tampines, huaYi, catholic, westwood);
        when(choicePlanController.getPlan(SESSION)).thenReturn(plan);
        when(choicePlanController.assessPlan(plan)).thenReturn(List.of("You have 4 of 6 choices."));
        when(shortlistController.getShortlistedSchools(SESSION))
                .thenReturn(List.of(catholic, huaYi, jurongWest, tampines, westwood));

        String html = mvc.perform(get("/plan").cookie(member()))
                .andExpect(status().isOk())
                .andExpect(view().name("choice-plan"))
                .andExpect(model().attribute("addableSchools", List.of(jurongWest)))
                .andExpect(model().attribute("positions", List.of(1, 2, 3, 4, 5)))
                .andExpect(model().attribute("emptyRanks", List.of(5, 6)))
                .andExpect(content().string(not(containsString("Not built yet"))))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("href=\"/schools/tampines-secondary-school\"", "TAMPINES SECONDARY SCHOOL")
                .contains("PG3 22–25 (2025)")
                .contains("data-chance=\"SAFE\"", "data-chance=\"MATCH\"", "data-chance=\"REACH\"")
                .contains("action=\"/plan/choices/hua-yi-secondary-school/move\"", "name=\"dir\" value=\"up\"")
                .contains("action=\"/plan/choices/catholic-high-school/remove\"")
                .contains("You have 4 of 6 choices.")
                .contains("value=\"jurong-west-secondary-school\"")
                .doesNotContain("<option value=\"tampines-secondary-school\"")
                .contains("PSLE score <strong>12</strong>", "posting group <strong>3</strong>")
                .contains("Empty: add a school from your shortlist below");
        assertThat(between(html, "WESTWOOD SECONDARY SCHOOL", "</tr>")).contains("Not available");
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanUI-02: without a PSLE score the page asks for one and shows no labels")
    void displayPlan_noScore_prompt() throws Exception {
        ChoicePlan plan = plan(null, null, tampines);
        when(choicePlanController.getPlan(SESSION)).thenReturn(plan);
        when(choicePlanController.assessPlan(plan)).thenReturn(List.of());

        mvc.perform(get("/plan").cookie(member()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Add your PSLE score in your profile to see SAFE/MATCH/REACH")))
                .andExpect(content().string(containsString("href=\"/profile\"")))
                .andExpect(content().string(not(containsString("data-chance="))));
    }

    @Test
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlanUI-03: when the profile score changed, the page offers to use the new score")
    void displayPlan_outdated_offersNewScore() throws Exception {
        ChoicePlan plan = plan(12, 3, tampines);
        plan.setCurrentProfile(14, 3);
        when(choicePlanController.getPlan(SESSION)).thenReturn(plan);
        when(choicePlanController.assessPlan(plan))
                .thenReturn(List.of("This plan was made for a score of 12, but your profile now says 14."));

        mvc.perform(get("/plan").cookie(member()))
                .andExpect(content().string(containsString("This plan was made for a score of 12")))
                .andExpect(content().string(containsString("action=\"/plan/score\"")));
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanUI-04: a full plan has no add form; an empty one explains how to start")
    void displayPlan_fullAndEmpty() throws Exception {
        ChoicePlan full = plan(12, 3, tampines, huaYi, catholic, westwood, jurongWest,
                TestSchools.school("peirce-secondary-school").build());
        when(choicePlanController.getPlan(SESSION)).thenReturn(full);
        mvc.perform(get("/plan").cookie(member()))
                .andExpect(content().string(containsString("Your plan has 6 choices")))
                .andExpect(content().string(not(containsString("action=\"/plan/choices\""))));

        ChoicePlan empty = plan(12, 3);
        when(choicePlanController.getPlan(SESSION)).thenReturn(empty);
        mvc.perform(get("/plan").cookie(member()))
                .andExpect(content().string(containsString("Your shortlist is empty")))
                .andExpect(content().string(containsString("href=\"/schools\"")));
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanUI-05: Add a school calls addChoice with the code and position")
    void addChoice() throws Exception {
        mvc.perform(post("/plan/choices").param("code", "jurong-west-secondary-school").param("rank", "2")
                        .cookie(member()))
                .andExpect(redirectedUrl("/plan"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, ChoicePlanUI.ADDED_MESSAGE));
        verify(choicePlanController).addChoice(SESSION, "jurong-west-secondary-school", 2);
    }

    @Test
    @Tag("FR-PLAN-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-ChoicePlanUI-06: a rejected add shows the control's message; a non-number position never reaches the control")
    void addChoice_invalid() throws Exception {
        doThrow(new InvalidInputException("code", ChoicePlanController.PLAN_FULL_MESSAGE))
                .when(choicePlanController).addChoice(SESSION, "jurong-west-secondary-school", 1);
        mvc.perform(post("/plan/choices").param("code", "jurong-west-secondary-school").param("rank", "1")
                        .cookie(member()))
                .andExpect(redirectedUrl("/plan"))
                .andExpect(flash().attribute(PageMessages.FLASH_ERROR, ChoicePlanController.PLAN_FULL_MESSAGE));

        mvc.perform(post("/plan/choices").param("code", "jurong-west-secondary-school").param("rank", "abc")
                        .cookie(member()))
                .andExpect(redirectedUrl("/plan"))
                .andExpect(flash().attribute(PageMessages.FLASH_ERROR, ChoicePlanUI.POSITION_MESSAGE));
        verify(choicePlanController, times(1)).addChoice(anyString(), anyString(), anyInt());   // only the first post
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanUI-07: Up moves the choice one place up; Down on the last choice does nothing")
    void reorderChoice() throws Exception {
        when(choicePlanController.getPlan(SESSION)).thenReturn(plan(12, 3, tampines, huaYi, catholic));

        mvc.perform(post("/plan/choices/hua-yi-secondary-school/move").param("dir", "up").cookie(member()))
                .andExpect(redirectedUrl("/plan"));
        verify(choicePlanController).reorderChoices(SESSION, 2, 1);

        mvc.perform(post("/plan/choices/catholic-high-school/move").param("dir", "down").cookie(member()))
                .andExpect(redirectedUrl("/plan"));
        verify(choicePlanController, never()).reorderChoices(SESSION, 3, 4);
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanUI-08: moving a school that is not in the plan shows an error")
    void reorderChoice_unknownCode() throws Exception {
        when(choicePlanController.getPlan(SESSION)).thenReturn(plan(12, 3, tampines));

        mvc.perform(post("/plan/choices/raffles-institution/move").param("dir", "up").cookie(member()))
                .andExpect(redirectedUrl("/plan"))
                .andExpect(flash().attribute(PageMessages.FLASH_ERROR, ChoicePlanUI.NOT_IN_PLAN_MESSAGE));
        verify(choicePlanController, never()).reorderChoices(anyString(), anyInt(), anyInt());
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanUI-09: Remove calls removeChoice")
    void removeChoice() throws Exception {
        mvc.perform(post("/plan/choices/catholic-high-school/remove").cookie(member()))
                .andExpect(redirectedUrl("/plan"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, ChoicePlanUI.REMOVED_MESSAGE));
        verify(choicePlanController).removeChoice(SESSION, "catholic-high-school");
    }

    @Test
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlanUI-10: \"Use my current score\" calls useProfileScore")
    void useProfileScore() throws Exception {
        mvc.perform(post("/plan/score").cookie(member()))
                .andExpect(redirectedUrl("/plan"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, ChoicePlanUI.SCORE_UPDATED_MESSAGE));
        verify(choicePlanController).useProfileScore(SESSION);
    }

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-ChoicePlanUI-11: the page says ranges are historical and explains the SAFE margin")
    void displayPlan_historicalNote() throws Exception {
        ChoicePlan plan = plan(12, 3, tampines);
        when(choicePlanController.getPlan(SESSION)).thenReturn(plan);

        mvc.perform(get("/plan").cookie(member()))
                .andExpect(model().attribute("safeMargin", 2))
                .andExpect(content().string(containsString("historical, not guaranteed")));
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-ChoicePlanUI-12: a guest is sent to the login page and no plan action runs")
    void guest_isSentToLogin() throws Exception {
        mvc.perform(get("/plan")).andExpect(redirectedUrl("/login?next=/plan"));
        mvc.perform(post("/plan/choices").param("code", "x").param("rank", "1"))
                .andExpect(redirectedUrl("/login?next=/plan"));
        verify(choicePlanController, never()).getPlan(anyString());
        verify(choicePlanController, never()).addChoice(anyString(), anyString(), anyInt());
    }

    private Cookie member() {
        return new Cookie(props.session().cookieName(), SESSION);
    }

    /** A resolved plan with the member's profile equal to the plan's score (not outdated). */
    private static ChoicePlan plan(Integer score, Integer postingGroup, School... schools) {
        ChoicePlan plan = new ChoicePlan(score, postingGroup);
        for (int i = 0; i < schools.length; i++) {
            plan.addChoice(schools[i], i + 1);
        }
        plan.setCurrentProfile(score, postingGroup);
        return plan;
    }

    private static String between(String html, String from, String to) {
        int start = html.indexOf(from);
        assertThat(start).as("page contains %s", from).isNotNegative();
        return html.substring(start, html.indexOf(to, start));
    }
}

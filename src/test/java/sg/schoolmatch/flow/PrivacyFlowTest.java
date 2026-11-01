package sg.schoolmatch.flow;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AccountController;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.ChoicePlanController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.support.TestMembers;

/**
 * What the Terms and privacy page says, checked against the running app (NFR-PRIV-01, DC-87): the notice on the pages
 * that ask for details, that clearing the profile really clears the values, and that "What is stored" names every
 * column of the user tables, so a new column must be added to the page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrivacyFlowTest {

    private static final String NOTICE =
            "Course demo: use made-up details only. <a class=\"text-nowrap\" href=\"/about/privacy\">";

    private static final String LOCATION_NOTICE = "A typed address goes to OneMap. For travel times and directions, "
            + "the starting point goes to Google, rounded to about 100 m. <a href=\"/about/privacy\">Terms and privacy</a>";

    /** Stub OneMap answers this postal code with exactly one hit. */
    private static final String ONE_HIT_ADDRESS = "579767";

    /** Every column of the tables that hold a member's data → the words on the page that cover it. */
    private static final Map<String, String> STORED = Map.ofEntries(
            Map.entry("account.account_id", "random account id"),
            Map.entry("account.username", "username"),
            Map.entry("account.username_key", "lower-case copy of the username"),
            Map.entry("account.email", "email address"),
            Map.entry("account.password_hash", "hashed password"),
            Map.entry("account.created_at", "when the account was created"),
            Map.entry("account.status", "status (active or inactive)"),
            Map.entry("authenticated_session.session_id", "random login id"),
            Map.entry("authenticated_session.account_id", "random account id"),
            Map.entry("authenticated_session.issued_at", "when the login started"),
            Map.entry("authenticated_session.expires_at", "and expires"),
            Map.entry("authenticated_session.invalidated", "whether you logged out"),
            Map.entry("user_profile.account_id", "random account id"),
            Map.entry("user_profile.display_name", "Display name"),
            Map.entry("user_profile.psle_score", "PSLE score"),
            Map.entry("user_profile.posting_group", "posting group"),
            Map.entry("user_profile.primary_school", "primary school"),
            Map.entry("user_profile.home_address", "home address"),
            Map.entry("user_profile.home_latitude", "latitude"),
            Map.entry("user_profile.home_longitude", "longitude"),
            Map.entry("user_profile.max_commute_min", "commute limit"),
            Map.entry("user_profile.travel_mode", "usual travel mode"),
            Map.entry("user_profile_cca.account_id", "random account id"),
            Map.entry("user_profile_cca.cca", "CCA and programme choices"),
            Map.entry("user_profile_programme.account_id", "random account id"),
            Map.entry("user_profile_programme.programme", "CCA and programme choices"),
            Map.entry("shortlist.account_id", "random account id"),
            Map.entry("shortlist.updated_at", "when the list last changed"),
            Map.entry("shortlist.choice_plan_id", "Choice plan"),
            Map.entry("shortlist_school.account_id", "random account id"),
            Map.entry("shortlist_school.school_code", "The schools you saved"),
            Map.entry("choice_plan.id", "Choice plan"),
            Map.entry("choice_plan.based_on_score", "the PSLE score and posting group the plan was made for"),
            Map.entry("choice_plan.posting_group", "the PSLE score and posting group the plan was made for"),
            Map.entry("choice_plan.updated_at", "when it last changed"),
            Map.entry("choice_plan_choice.choice_plan_id", "Choice plan"),
            Map.entry("choice_plan_choice.choice_rank", "Your ranked schools"),
            Map.entry("choice_plan_choice.school_code", "Your ranked schools"));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppProperties props;

    @Autowired
    private AccountController accountController;

    @Autowired
    private AuthController authController;

    @Autowired
    private ProfileController profileController;

    @Autowired
    private ChoicePlanController choicePlanController;

    @Autowired
    private JdbcTemplate jdbc;

    private String sessionId;

    @BeforeEach
    void logIn() {
        sessionId = TestMembers.registerAndLogIn(accountController, authController, "privacy");
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-PrivacyFlow-01: the register page shows the made-up details notice with a link to the page")
    void noticeOnRegister() throws Exception {
        mvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(NOTICE)));
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-PrivacyFlow-02: the profile page shows the made-up details notice with a link to the page")
    void noticeOnProfile() throws Exception {
        mvc.perform(get("/profile").cookie(member()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(NOTICE)));
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-PrivacyFlow-06: the location picker says where a location goes, with a link to the page, on the filter, directions and recommendation pages")
    void noticeOnEveryLocationPicker() throws Exception {
        for (String page : List.of("/schools/filter", "/directions?to=school:catholic-high-school",
                "/recommendations")) {
            String html = mvc.perform(get(page).cookie(member())).andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString(UTF_8).replaceAll("\\s+", " ");
            assertThat(html).as(page).contains(LOCATION_NOTICE);
        }
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-PrivacyFlow-03: emptying score, school and address on the profile page removes them, with the coordinates")
    void clearingTheProfileRemovesTheValues() throws Exception {
        saveProfile("12", "3", "Ai Tong School", ONE_HIT_ADDRESS);
        UserProfile saved = profileController.getProfile(sessionId);
        assertThat(saved.getPsleScore()).isEqualTo(12);
        assertThat(saved.getPrimarySchool()).isEqualTo("Ai Tong School");
        assertThat(saved.getHomeAddress()).isEqualTo(ONE_HIT_ADDRESS);
        assertThat(saved.getHomeLocation()).isNotNull();

        saveProfile("", "", "", "");

        UserProfile cleared = profileController.getProfile(sessionId);
        assertThat(cleared.getPsleScore()).isNull();
        assertThat(cleared.getPostingGroup()).isNull();
        assertThat(cleared.getPrimarySchool()).isNull();
        assertThat(cleared.getHomeAddress()).isNull();
        assertThat(cleared.getHomeLocation()).isNull();
    }

    @Test
    @Tag("NFR-PRIV-01")
    @Tag("FR-PLAN-02")
    @DisplayName("TC-PrivacyFlow-04: a choice plan keeps its copy of the score after the profile is cleared, as the page says")
    void choicePlanKeepsItsCopy() throws Exception {
        saveProfile("12", "3", "", "");
        assertThat(choicePlanController.getPlan(sessionId).getBasedOnScore()).isEqualTo(12);

        saveProfile("", "", "", "");

        assertThat(profileController.getProfile(sessionId).getPsleScore()).isNull();
        assertThat(choicePlanController.getPlan(sessionId).getBasedOnScore()).isEqualTo(12);
        assertThat(privacyPage()).contains("A choice plan keeps the score and posting group it was made for");
    }

    @Test
    @Tag("NFR-PRIV-01")
    @Tag("FR-DATA-02")
    @DisplayName("TC-PrivacyFlow-05: \"What is stored\" names every column of the user tables; a new member table must be added")
    void pageNamesEveryStoredColumn() throws Exception {
        Set<String> tables = new TreeSet<>();
        STORED.keySet().forEach(column -> tables.add(column.substring(0, column.indexOf('.'))));
        Set<String> columns = new TreeSet<>();
        Set<String> tablesWithAMember = new TreeSet<>();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT table_name, column_name"
                + " FROM information_schema.columns WHERE LOWER(table_schema) = 'public'");
        for (Map<String, Object> row : rows) {
            String table = row.get("table_name").toString().toLowerCase(Locale.ROOT);
            String column = row.get("column_name").toString().toLowerCase(Locale.ROOT);
            if (tables.contains(table)) {
                columns.add(table + "." + column);
            }
            if (column.equals("account_id")) {
                tablesWithAMember.add(table);
            }
        }

        assertThat(columns).containsExactlyInAnyOrderElementsOf(STORED.keySet());
        assertThat(tables).containsAll(tablesWithAMember);
        assertThat(privacyPage()).contains(STORED.values());
    }

    private void saveProfile(String score, String postingGroup, String primarySchool, String address) throws Exception {
        mvc.perform(post("/profile").cookie(member()).param("psleScore", score).param("postingGroup", postingGroup)
                        .param("primarySchool", primarySchool).param("homeAddress", address))
                .andExpect(redirectedUrl("/profile"));
    }

    private Cookie member() {
        return TestMembers.cookie(props, sessionId);
    }

    /** The page as text, with every run of white space as one space, so a sentence may wrap in the template. */
    private String privacyPage() throws Exception {
        return mvc.perform(get("/about/privacy")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(UTF_8).replaceAll("\\s+", " ");
    }
}

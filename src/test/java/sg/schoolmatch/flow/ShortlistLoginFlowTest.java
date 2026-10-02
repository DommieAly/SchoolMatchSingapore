package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AccountController;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.control.ShortlistController;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.school.School;

/**
 * Shortlist and plan with the real login (owner A's AccountController, AuthController, ProfileController):
 * two registered members, each with their own session cookie (FR-SHORTLIST-03, NFR-SEC-05, FR-PLAN-01).
 * {@link ShortlistFlowTest} covers the same pages in more detail with login faked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShortlistLoginFlowTest {

    private static final String PASSWORD = "Secret123";

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
    private ShortlistController shortlistController;

    /** Makes the usernames unique per test: this context (and its database) is shared with other flow tests. */
    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    private String alice;
    private String bob;

    @BeforeEach
    void twoMembersLoggedIn() {
        alice = registerAndLogIn("alice");
        bob = registerAndLogIn("bob");
        UserProfile profile = new UserProfile(authController.getAccount(alice));
        profile.setPsleScore(12);
        profile.setPostingGroup(3);
        profileController.saveProfile(alice, profile);
    }

    @Test
    @Tag("FR-SHORTLIST-03")
    @Tag("NFR-SEC-05")
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ShortlistLoginFlow-01: each logged-in member sees and plans only their own shortlist")
    void membersAreSeparated() throws Exception {
        mvc.perform(post("/schools/hua-yi-secondary-school/shortlist").cookie(cookie(alice)))
                .andExpect(redirectedUrl("/schools/hua-yi-secondary-school"));
        mvc.perform(post("/schools/catholic-high-school/shortlist").cookie(cookie(alice)))
                .andExpect(redirectedUrl("/schools/catholic-high-school"));
        mvc.perform(post("/plan/choices").param("code", "hua-yi-secondary-school").param("rank", "1")
                        .cookie(cookie(alice)))
                .andExpect(redirectedUrl("/plan"));

        // Fixture: Hua Yi PG3 2025 upper score 13, so PSLE 12 is MATCH
        mvc.perform(get("/plan").cookie(cookie(alice)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-chance=\"MATCH\"")));
        mvc.perform(get("/shortlist").cookie(cookie(bob)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("HUA YI SECONDARY SCHOOL"))));
        mvc.perform(get("/compare?codes=hua-yi-secondary-school,catholic-high-school").cookie(cookie(bob)))
                .andExpect(redirectedUrl("/shortlist"))
                .andExpect(flash().attribute(PageMessages.FLASH_ERROR,
                        ShortlistController.COMPARE_NOT_SHORTLISTED_MESSAGE));

        assertThat(shortlistController.getShortlist(alice).getSchoolCodes())
                .containsExactly("catholic-high-school", "hua-yi-secondary-school");
        assertThat(shortlistController.getShortlist(bob).isEmpty()).isTrue();
    }

    @Test
    @Tag("FR-SHORTLIST-03")
    @DisplayName("TC-ShortlistLoginFlow-02: after logout the old cookie can neither open nor change the shortlist")
    void loggedOutCookieIsRejected() throws Exception {
        mvc.perform(post("/schools/catholic-high-school/shortlist").cookie(cookie(alice)));
        String oldSession = alice;
        authController.logout(oldSession);

        mvc.perform(get("/shortlist").cookie(cookie(oldSession)))
                .andExpect(redirectedUrl("/login?next=/shortlist"));
        mvc.perform(post("/shortlist/catholic-high-school/remove").cookie(cookie(oldSession)))
                .andExpect(redirectedUrl("/login?next=/shortlist"));

        String again = authController.login(username("alice"), PASSWORD).getSessionId();
        assertThat(shortlistController.getShortlistedSchools(again))
                .extracting(School::getSchoolCode).containsExactly("catholic-high-school");
    }

    private String username(String name) {
        return "f_" + name + "_" + suffix;
    }

    private String registerAndLogIn(String name) {
        accountController.register(username(name), username(name) + "@example.com", PASSWORD);
        return authController.login(username(name), PASSWORD).getSessionId();
    }

    private Cookie cookie(String sessionId) {
        return new Cookie(props.session().cookieName(), sessionId);
    }
}

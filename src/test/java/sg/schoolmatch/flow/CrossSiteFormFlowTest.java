package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AccountController;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.persistence.AccountRepository;
import sg.schoolmatch.support.TestMembers;

/**
 * Whole-application tests of the login security fixes: form posts from another website are refused (DC-72),
 * the case-insensitive username key (DC-70) and the HTTP session ending at logout (DC-71).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrossSiteFormFlowTest {

    private static final String HOST = "localhost:8080";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AccountController accountController;

    @Autowired
    private AuthController authController;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AppProperties props;

    private String username;

    @BeforeEach
    void register() {
        username = "xsite_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        accountController.register(username, username + "@example.com", TestMembers.PASSWORD);
    }

    @Test
    @Tag("NFR-SEC-05")
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-CSRF-01: POST /login with an Origin of another site gets 403 and no login cookie")
    void login_fromOtherSite_refused() throws Exception {
        mvc.perform(post("/login").header("Host", HOST).header("Origin", "https://evil.example")
                        .param("identifier", username).param("password", TestMembers.PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(cookie().doesNotExist(props.session().cookieName()));
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-CSRF-02: without Origin, a Referer of another site is refused too (POST /register)")
    void register_refererFromOtherSite_refused() throws Exception {
        String other = "xsite2_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        mvc.perform(post("/register").header("Host", HOST).header("Referer", "https://evil.example/x")
                        .param("username", other).param("email", other + "@example.com")
                        .param("password", TestMembers.PASSWORD).param("confirm", TestMembers.PASSWORD))
                .andExpect(status().isForbidden());

        assertThat(accountRepository.findByUsernameIgnoreCase(other)).isEmpty();
    }

    @Test
    @Tag("NFR-SEC-05")
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-CSRF-03: the same site's Origin, or no Origin/Referer at all, logs in normally")
    void login_sameSiteOrNoHeader_allowed() throws Exception {
        mvc.perform(post("/login").header("Host", HOST).header("Origin", "http://localhost:8080")
                        .param("identifier", username).param("password", TestMembers.PASSWORD))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"))
                .andExpect(cookie().exists(props.session().cookieName()));
        mvc.perform(post("/login").header("Host", HOST).header("Referer", "http://localhost:8080/login")
                        .param("identifier", username).param("password", TestMembers.PASSWORD))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
        mvc.perform(post("/login").param("identifier", username).param("password", TestMembers.PASSWORD))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-CSRF-04: a GET from another site is not affected (links into the site keep working)")
    void get_fromOtherSite_allowed() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/schools")
                        .header("Host", HOST).header("Referer", "https://search.example/?q=schools"))
                .andExpect(status().isOk());
    }

    @Test
    @Tag("FR-REG-04")
    @Tag("FR-LOGIN-01")
    @DisplayName("TC-REG-04-05: a username differing only in case is refused, and login by any case finds the one account")
    void usernameCaseVariant_refusedAndLoginStillWorks() throws Exception {
        String variant = username.toUpperCase(java.util.Locale.ROOT);
        mvc.perform(post("/register").param("username", variant).param("email", "v" + username + "@example.com")
                        .param("password", TestMembers.PASSWORD).param("confirm", TestMembers.PASSWORD))
                .andExpect(status().isOk());   // the form again, with the duplicate error

        assertThat(accountRepository.findAll().stream()
                .filter(a -> a.getUsername().equalsIgnoreCase(username)).count()).isEqualTo(1);
        mvc.perform(post("/login").param("identifier", variant).param("password", TestMembers.PASSWORD))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @Tag("FR-LOGOUT-02")
    @DisplayName("TC-LOGOUT-02-03: logout ends the HTTP session too, so the starting point is not left for the next user")
    void logout_endsHttpSession() throws Exception {
        String sessionId = authController.login(username, TestMembers.PASSWORD).getSessionId();
        Cookie login = TestMembers.cookie(props, sessionId);
        MockHttpSession httpSession = new MockHttpSession();
        httpSession.setAttribute(ReferenceLocationStore.CURRENT, new ReferenceLocation(
                new Coordinate(1.3500, 103.8500), LocationSource.MANUAL_ENTRY, "9 BISHAN STREET 22"));

        MvcResult result = mvc.perform(post("/logout").cookie(login).session(httpSession))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/"))
                .andReturn();

        assertThat(httpSession.isInvalid()).as("the old HTTP session is invalidated").isTrue();
        assertThat(result.getRequest().getSession(false)).as("a new session holds only the flash message")
                .isNotSameAs(httpSession);
        assertThat(authController.verifySession(sessionId)).isFalse();
    }
}

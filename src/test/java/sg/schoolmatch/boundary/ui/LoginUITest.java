package sg.schoolmatch.boundary.ui;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.control.ShortlistController;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.AuthenticatedSession;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotFoundException;

/**
 * Web test of the boundary class LoginUI (DM-02 LoginForm; use case Log In, FR-LOGIN-01..05, DC-08).
 * AuthController and ShortlistController are mocks. A failed login goes back to GET /login (Post/Redirect/Get),
 * keeping {@code next} and {@code add}.
 */
@WebMvcTest(LoginUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import(SessionCookie.class)
@ActiveProfiles("test")
class LoginUITest {

    private static final String COOKIE = "SM_SESSION";
    private static final String SESSION_ID = "new-session-id";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthController authController;

    @MockitoBean
    private ShortlistController shortlistController;

    @MockitoBean
    private SchoolDataController schoolDataController;

    private final Account alice = new Account("alice_01", "alice@example.com", "hash", Instant.EPOCH);

    @BeforeEach
    void setUp() {
        AuthenticatedSession session = new AuthenticatedSession(SESSION_ID, alice, Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1800));
        when(authController.login("alice_01", "Passw0rd")).thenReturn(session);
        when(authController.login("alice_01", "wrong"))
                .thenThrow(new InvalidInputException(AuthController.LOGIN_ERROR, AuthController.LOGIN_FAILED_MESSAGE));
    }

    @Test
    @Tag("FR-LOGIN-01")
    @Tag("NFR-SEC-04")
    @DisplayName("TC-LOGIN-01-06: the form has the identifier field and a masked password field, no placeholder")
    void displayLoginForm() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"identifier\"")))
                .andExpect(content().string(containsString("type=\"password\" name=\"password\"")))
                .andExpect(content().string(not(containsString("Not built yet"))));
    }

    @Test
    @Tag("FR-LOGIN-04")
    @Tag("NFR-SEC-03")
    @DisplayName("TC-LOGIN-04-01: success sets the HttpOnly session cookie and goes to the home page")
    void submitLogin_success_setsCookieAndGoesHome() throws Exception {
        mvc.perform(post("/login").param("identifier", "alice_01").param("password", "Passw0rd"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(cookie().value(COOKIE, SESSION_ID))
                .andExpect(cookie().httpOnly(COOKIE, true))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")));
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-LOGIN-04-02: success with a local next goes back to that page (T-77)")
    void submitLogin_success_goesToNext() throws Exception {
        mvc.perform(post("/login").param("identifier", "alice_01").param("password", "Passw0rd")
                        .param("next", "/schools?q=st.%20hilda%27s&page=2"))
                .andExpect(redirectedUrl("/schools?q=st.%20hilda%27s&page=2"));
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-LOGIN-04-03: success with a foreign next goes to the home page instead")
    void submitLogin_success_foreignNextIgnored() throws Exception {
        mvc.perform(post("/login").param("identifier", "alice_01").param("password", "Passw0rd")
                        .param("next", "//evil.com"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-LOGIN-04-04: a next with braces is redirected to as it is (not read as a URI template)")
    void submitLogin_success_nextWithBraces() throws Exception {
        mvc.perform(post("/login").param("identifier", "alice_01").param("password", "Passw0rd")
                        .param("next", "/schools?q={x}"))
                .andExpect(redirectedUrl("/schools?q={x}"));
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-LOGIN-04-05: login with add=<code> adds the school and returns to it with \"Added to shortlist\" (DC-08)")
    void submitLogin_add_addsSchool() throws Exception {
        when(shortlistController.addSchool(SESSION_ID, "catholic-high-school")).thenReturn(true);

        mvc.perform(post("/login").param("identifier", "alice_01").param("password", "Passw0rd")
                        .param("next", "/schools/catholic-high-school").param("add", "catholic-high-school"))
                .andExpect(redirectedUrl("/schools/catholic-high-school"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, "Added to shortlist"));
        verify(shortlistController).addSchool(SESSION_ID, "catholic-high-school");
    }

    @Test
    @Tag("FR-SHORTLIST-02")
    @DisplayName("TC-LOGIN-04-06: login with add=<code> for a school already shortlisted says so")
    void submitLogin_add_alreadyShortlisted() throws Exception {
        when(shortlistController.addSchool(SESSION_ID, "catholic-high-school")).thenReturn(false);

        mvc.perform(post("/login").param("identifier", "alice_01").param("password", "Passw0rd")
                        .param("add", "catholic-high-school"))
                .andExpect(redirectedUrl("/schools/catholic-high-school"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, "Already in your shortlist"));
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-LOGIN-04-07: login with add=<code> for a school no longer in the dataset still logs in, with a message")
    void submitLogin_add_unknownSchool() throws Exception {
        when(shortlistController.addSchool(SESSION_ID, "gone-school")).thenThrow(new NotFoundException("No school gone-school"));

        mvc.perform(post("/login").param("identifier", "alice_01").param("password", "Passw0rd")
                        .param("add", "gone-school"))
                .andExpect(redirectedUrl("/schools"))
                .andExpect(cookie().value(COOKIE, SESSION_ID))
                .andExpect(flash().attributeExists(PageMessages.FLASH_ERROR));
    }

    @Test
    @Tag("FR-LOGIN-05")
    @DisplayName("TC-LOGIN-05-08: wrong credentials go back to the form with the generic message; next, add and the identifier are kept")
    void submitLogin_failure_backToForm() throws Exception {
        mvc.perform(post("/login").param("identifier", "alice_01").param("password", "wrong")
                        .param("next", "/schools/catholic-high-school").param("add", "catholic-high-school"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?next=/schools/catholic-high-school&add=catholic-high-school"))
                .andExpect(cookie().doesNotExist(COOKIE))
                .andExpect(flash().attribute(PageMessages.FIELD_ERRORS,
                        Map.of(AuthController.LOGIN_ERROR, "Incorrect username/email or password")))
                .andExpect(flash().attribute("identifier", "alice_01"));
        verifyNoInteractions(shortlistController);
    }

    @Test
    @Tag("FR-LOGIN-02")
    @DisplayName("TC-LOGIN-02-05: the form shows the flashed errors and highlights the missing field")
    void displayLoginForm_showsFlashedErrors() throws Exception {
        mvc.perform(get("/login")
                        .flashAttr(PageMessages.FIELD_ERRORS, Map.of("password", "Enter your password"))
                        .flashAttr("identifier", "alice_01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Enter your password")))
                .andExpect(content().string(containsString("value=\"alice_01\"")))
                .andExpect(content().string(containsString("is-invalid")));
    }

    @Test
    @Tag("FR-LOGIN-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-LOGIN-01-07: the database being down shows \"Login is temporarily unavailable\" (EX-1)")
    void submitLogin_serviceUnavailable() throws Exception {
        when(authController.login(anyString(), anyString())).thenThrow(new DataAccessResourceFailureException("down"));

        mvc.perform(post("/login").param("identifier", "alice_01").param("password", "Passw0rd"))
                .andExpect(redirectedUrl("/login"))
                .andExpect(cookie().doesNotExist(COOKIE))
                .andExpect(flash().attribute(PageMessages.FLASH_ERROR,
                        "Login is temporarily unavailable. Please try again later."));
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-LOGIN-04-08: logging in while another session cookie is present ends that old session")
    void submitLogin_endsOldSession() throws Exception {
        mvc.perform(post("/login").param("identifier", "alice_01").param("password", "Passw0rd")
                        .cookie(new Cookie(COOKIE, "old-session")))
                .andExpect(cookie().value(COOKIE, SESSION_ID));
        verify(authController).logout("old-session");
    }

    @Test
    @Tag("FR-LOGOUT-03")
    @DisplayName("TC-LOGIN-04-09: next=/logout is dropped: after an expired session the user is already logged out (Log Out EX-1)")
    void displayLoginForm_nextLogoutDropped() throws Exception {
        mvc.perform(get("/login").param("next", "/logout"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("next", (Object) null))
                .andExpect(content().string(containsString("already logged out")));
        mvc.perform(post("/login").param("identifier", "alice_01").param("password", "Passw0rd")
                        .param("next", "/logout"))
                .andExpect(redirectedUrl("/"));
        verify(authController, never()).logout(any());
    }
}

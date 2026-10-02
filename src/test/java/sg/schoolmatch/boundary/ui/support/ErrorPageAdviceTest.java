package sg.schoolmatch.boundary.ui.support;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.error.NotAuthenticatedException;

/**
 * {@link ErrorPageAdvice} when a session ends in the middle of a request ({@code NotAuthenticatedException}
 * from a control): the login cookie is cleared and the user goes to the login page with a safe {@code next}.
 * Standalone MockMvc with a small handler that always throws; no Spring context.
 */
class ErrorPageAdviceTest {

    private static final String COOKIE = "SM_SESSION";

    private MockMvc mvc;

    /**
     * Every route throws NotAuthenticatedException, as a control does when the session has ended.
     * A non-static inner class on purpose: component scanning skips it, so it never joins other tests' contexts.
     */
    @Controller
    class SessionEndedHandler {

        @GetMapping("/plan")
        public String plan() {
            throw new NotAuthenticatedException();
        }

        @PostMapping("/plan/choices")
        public String addChoice() {
            throw new NotAuthenticatedException();
        }

        @PostMapping("/schools/{code}/shortlist")
        public String addToShortlist() {
            throw new NotAuthenticatedException();
        }
    }

    @BeforeEach
    void setUp() {
        AppProperties props = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("app", AppProperties.class);
        ErrorPageAdvice advice = new ErrorPageAdvice(mock(LayoutModelAdvice.class), new SessionCookie(props));
        mvc = MockMvcBuilders.standaloneSetup(new SessionEndedHandler()).setControllerAdvice(advice).build();
    }

    @Test
    @Tag("FR-LOGOUT-03")
    @Tag("NFR-SEC-05")
    @DisplayName("TC-ErrorPageAdvice-01: GET → login with next = the same path and query; the cookie is cleared")
    void get_nextIsSamePage() throws Exception {
        mvc.perform(get("/plan?view=all"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?next=/plan?view%3Dall"))
                .andExpect(cookie().maxAge(COOKIE, 0));
    }

    @Test
    @Tag("FR-LOGOUT-03")
    @DisplayName("TC-ErrorPageAdvice-02: POST → next = the Referer's path and query when the Referer is this site")
    void post_nextIsLocalReferer() throws Exception {
        mvc.perform(post("/plan/choices").header(HttpHeaders.REFERER, "http://localhost/plan?view=all"))
                .andExpect(redirectedUrl("/login?next=/plan?view%3Dall"))
                .andExpect(cookie().maxAge(COOKIE, 0));
        mvc.perform(post("/plan/choices").header(HttpHeaders.REFERER, "/shortlist"))
                .andExpect(redirectedUrl("/login?next=/shortlist"));
    }

    @ParameterizedTest(name = "TC-ErrorPageAdvice-03 [{index}]: Referer {0}")
    @ValueSource(strings = {"https://evil.com/plan", "http://localhost//evil.com", "javascript:alert(1)", "::not a uri"})
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-ErrorPageAdvice-03: POST with a foreign or broken Referer → next=/")
    void post_foreignRefererGivesRoot(String referer) throws Exception {
        mvc.perform(post("/plan/choices").header(HttpHeaders.REFERER, referer))
                .andExpect(redirectedUrl("/login?next=/"));
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-ErrorPageAdvice-04: POST without a Referer → next=/")
    void post_noRefererGivesRoot() throws Exception {
        mvc.perform(post("/plan/choices"))
                .andExpect(redirectedUrl("/login?next=/"));
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-ErrorPageAdvice-05: add-to-shortlist keeps next=/schools/{code}&add={code} so the add finishes after login")
    void addToShortlist_keepsAdd() throws Exception {
        mvc.perform(post("/schools/catholic-high-school/shortlist")
                        .header(HttpHeaders.REFERER, "http://localhost/schools?q=high"))
                .andExpect(redirectedUrl("/login?next=/schools/catholic-high-school&add=catholic-high-school"));
    }
}

package sg.schoolmatch.boundary.ui.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;

/**
 * Open-redirect guard for {@code next} (login) and {@code returnTo} (location forms): a link must never send
 * the user to another site (docs/routes.md "Rules"). Plain JUnit, no Spring. The same rule seen through real
 * requests is in {@code flow/RedirectTargetFlowTest}.
 * <p>
 * Owner A: keep these green when LoginUI.submitLogin starts redirecting to {@code next}.
 */
class AuthInterceptorTest {

    @ParameterizedTest(name = "TC-AuthInterceptor-01 [{index}]: rejects \"{0}\"")
    @NullSource
    @ValueSource(strings = {
            "",
            "evil.com",
            "//evil.com",
            "/\\evil.com",
            "https://evil.com",
            "javascript:alert(1)",
            "/\t/evil.com",
            "/shortlist\r\nSet-Cookie: x=1"})
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthInterceptor-01: safeLocalPath rejects anything that could leave the site")
    void safeLocalPath_rejectsOtherSites(String path) {
        assertThat(AuthInterceptor.safeLocalPath(path)).isNull();
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthInterceptor-02: safeLocalPath rejects a path longer than 2000 characters")
    void safeLocalPath_rejectsTooLong() {
        assertThat(AuthInterceptor.safeLocalPath("/" + "a".repeat(2000))).isNull();
        assertThat(AuthInterceptor.safeLocalPath("/" + "a".repeat(1999))).isNotNull();
    }

    @ParameterizedTest(name = "TC-AuthInterceptor-03 [{index}]: keeps \"{0}\"")
    @ValueSource(strings = {"/", "/shortlist", "/shortlist?x=1", "/schools?q=st.%20hilda%27s&page=2"})
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthInterceptor-03: safeLocalPath keeps a normal local path unchanged")
    void safeLocalPath_keepsLocalPaths(String path) {
        assertThat(AuthInterceptor.safeLocalPath(path)).isEqualTo(path);
    }

    @Test
    @Tag("FR-SHORTLIST-03")
    @DisplayName("TC-AuthInterceptor-04: loginUrl keeps a GET page with its query, and sends a POST back to its section")
    void loginUrl_nextForGetAndPost() {
        assertThat(AuthInterceptor.loginUrl(request("GET", "/shortlist", "x=1")))
                .isEqualTo("/login?next=/shortlist?x%3D1");
        assertThat(AuthInterceptor.loginUrl(request("POST", "/plan/choices", null)))
                .isEqualTo("/login?next=/plan");
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-AuthInterceptor-05: loginUrl for a guest's add-to-shortlist returns to the school with add=<code>")
    void loginUrl_addToShortlist() {
        assertThat(AuthInterceptor.loginUrl(request("POST", "/schools/catholic-high-school/shortlist", null)))
                .isEqualTo("/login?next=/schools/catholic-high-school&add=catholic-high-school");
        assertThat(AuthInterceptor.loginUrl(request("POST", "/schools/Bad%20Code/shortlist", null)))
                .isEqualTo("/login?next=/schools");
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthInterceptor-06: loginUrl drops next when the request path itself is not a safe local path")
    void loginUrl_unsafePathHasNoNext() {
        assertThat(AuthInterceptor.loginUrl(request("GET", "//evil.com/profile", null))).isEqualTo("/login");
    }

    // ---- preHandle (the interceptor as WebConfig runs it), with a mocked AuthController ----------------------

    private final AuthController authController = mock(AuthController.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(authController, new SessionCookie(
            new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("app", AppProperties.class)));

    @Test
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-AuthInterceptor-07: a guest's POST add-to-shortlist is redirected to /login?next=/schools/{code}&add={code}")
    void preHandle_guestAddToShortlist() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(
                request("POST", "/schools/catholic-high-school/shortlist", null), response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getRedirectedUrl())
                .isEqualTo("/login?next=/schools/catholic-high-school&add=catholic-high-school");
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-AuthInterceptor-08: an expired session on add-to-shortlist clears the cookie and keeps add={code}")
    void preHandle_expiredSessionAddToShortlist() throws Exception {
        when(authController.verifySession("old-session")).thenReturn(false);
        MockHttpServletRequest request = request("POST", "/schools/catholic-high-school/shortlist", null);
        request.setCookies(new Cookie("SM_SESSION", "old-session"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getRedirectedUrl())
                .isEqualTo("/login?next=/schools/catholic-high-school&add=catholic-high-school");
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(c -> c.startsWith("SM_SESSION=;") && c.contains("Max-Age=0"));
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-AuthInterceptor-09: a logged-in POST add-to-shortlist passes with the session id as a request attribute")
    void preHandle_loggedInAddToShortlist() throws Exception {
        when(authController.verifySession("good-session")).thenReturn(true);
        MockHttpServletRequest request = request("POST", "/schools/catholic-high-school/shortlist", null);
        request.setCookies(new Cookie("SM_SESSION", "good-session"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE)).isEqualTo("good-session");
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-AuthInterceptor-10: GET /schools/{code}/shortlist is not guarded (only the POST needs a login)")
    void preHandle_getAddToShortlistNotGuarded() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("GET", "/schools/catholic-high-school/shortlist", null), response,
                new Object())).isTrue();
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthInterceptor-11: loginUrl(next, add) drops an unsafe next and an add that is not a school code")
    void loginUrl_nextAndAdd() {
        assertThat(AuthInterceptor.loginUrl("/plan?view=all", null)).isEqualTo("/login?next=/plan?view%3Dall");
        assertThat(AuthInterceptor.loginUrl("//evil.com", "Bad Code")).isEqualTo("/login");
        assertThat(AuthInterceptor.loginUrl(null, "catholic-high-school")).isEqualTo("/login?add=catholic-high-school");
    }

    private static MockHttpServletRequest request(String method, String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setQueryString(query);
        return request;
    }
}

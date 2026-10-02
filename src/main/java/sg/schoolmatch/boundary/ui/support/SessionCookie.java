package sg.schoolmatch.boundary.ui.support;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;
import sg.schoolmatch.config.AppProperties;

/**
 * Reads, creates and clears the login cookie ({@code app.session.cookie-name}, default {@code SM_SESSION}).
 * The cookie only carries the AuthenticatedSession id; the server decides whether it is valid.
 * Flags: HttpOnly, SameSite=Lax, Path=/, and Secure when {@code app.session.cookie-secure} is true (NFR-SEC-03).
 */
@Component
public class SessionCookie {

    private final AppProperties props;

    public SessionCookie(AppProperties props) {
        this.props = props;
    }

    public String name() {
        return props.session().cookieName();
    }

    /** The session id from the request, if the cookie is present and not blank. */
    public Optional<String> read(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, name());
        return Optional.ofNullable(cookie).map(Cookie::getValue).filter(v -> !v.isBlank());
    }

    /** Sets the cookie after a successful login (FR-LOGIN-04). No Max-Age: the idle timeout is checked on the server. */
    public void create(HttpServletResponse response, String sessionId) {
        write(response, base(sessionId).build());
    }

    /** Removes the cookie (FR-LOGOUT-02, or when the session is no longer valid). */
    public void clear(HttpServletResponse response) {
        write(response, base("").maxAge(0).build());
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(name(), value)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .secure(props.session().cookieSecure());
    }

    private static void write(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}

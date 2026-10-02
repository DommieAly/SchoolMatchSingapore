package sg.schoolmatch.boundary.ui.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UriUtils;
import sg.schoolmatch.control.AuthController;

/**
 * Guards the login-only pages in {@code WebConfig.LOGIN_REQUIRED_PATHS} (FR-SHORTLIST-03, NFR-SEC-05, DC-07).
 * <ul>
 *   <li>No session cookie: redirect to {@code /login?next=<path>}.</li>
 *   <li>Cookie present: ask {@link AuthController#verifySession}. If it says no (expired, logged out or
 *       unknown), treat the user as a guest: clear the cookie and redirect to the login page.</li>
 *   <li>{@code /schools/{code}/shortlist} is only guarded for POST. A guest goes to
 *       {@code /login?next=/schools/{code}&add={code}} so the add can finish after login (DC-08).</li>
 * </ul>
 * For a valid session the id is stored in request attribute {@link #SESSION_ID_ATTRIBUTE}; handlers read it with
 * {@code @RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId}.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String SESSION_ID_ATTRIBUTE = "schoolmatch.sessionId";

    private static final Pattern ADD_TO_SHORTLIST = Pattern.compile("^/schools/([^/]+)/shortlist$");
    private static final Pattern SCHOOL_CODE = Pattern.compile("^[a-z0-9-]{1,100}$");
    private static final int MAX_LOCAL_PATH_LENGTH = 2000;

    private final AuthController authController;
    private final SessionCookie sessionCookie;

    public AuthInterceptor(AuthController authController, SessionCookie sessionCookie) {
        this.authController = authController;
        this.sessionCookie = sessionCookie;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (isAddToShortlist(request) && !HttpMethod.POST.matches(request.getMethod())) {
            return true;   // only POST /schools/{code}/shortlist needs a login
        }
        Optional<String> sessionId = sessionCookie.read(request);
        if (sessionId.isPresent() && authController.verifySession(sessionId.get())) {
            request.setAttribute(SESSION_ID_ATTRIBUTE, sessionId.get());
            return true;
        }
        if (sessionId.isPresent()) {
            sessionCookie.clear(response);   // expired, logged out or unknown: act as a guest
        }
        response.sendRedirect(request.getContextPath() + loginUrl(request));
        return false;
    }

    /**
     * The login page URL for this request, e.g. {@code /login?next=/shortlist}.
     * GET keeps its path and query; add-to-shortlist returns to the school (DC-08); any other POST returns
     * to the first path segment (POST /plan/choices → /plan), which is always a GET page.
     */
    public static String loginUrl(HttpServletRequest request) {
        String path = pathWithinApp(request);
        Matcher addMatcher = ADD_TO_SHORTLIST.matcher(path);
        String add = null;
        String next;
        if (addMatcher.matches()) {
            add = safeSchoolCode(addMatcher.group(1));
            next = add == null ? "/schools" : "/schools/" + add;
        } else if (HttpMethod.GET.matches(request.getMethod())) {
            next = request.getQueryString() == null ? path : path + "?" + request.getQueryString();
        } else {
            next = "/" + firstSegment(path);
        }
        return loginUrl(next, add);
    }

    /**
     * {@code /login} with {@code next} (only when it is a safe local path, encoded) and {@code add}
     * (only when it looks like a school code), e.g. {@code /login?next=/schools/x&add=x}.
     */
    public static String loginUrl(String next, String add) {
        StringBuilder url = new StringBuilder("/login");
        char separator = '?';
        if (safeLocalPath(next) != null) {
            url.append(separator).append("next=").append(UriUtils.encodeQueryParam(next, StandardCharsets.UTF_8));
            separator = '&';
        }
        if (safeSchoolCode(add) != null) {
            url.append(separator).append("add=").append(add);
        }
        return url.toString();
    }

    /** True for {@code /schools/{code}/shortlist} (the add-to-shortlist action, DC-08/DC-38). */
    static boolean isAddToShortlist(HttpServletRequest request) {
        return ADD_TO_SHORTLIST.matcher(pathWithinApp(request)).matches();
    }

    /**
     * Returns {@code path} when it is safe to redirect to: it starts with a single '/' (not '//'),
     * and has no backslash or control characters. Otherwise null. Used for {@code next} and {@code returnTo},
     * so a link can never send the user to another site.
     */
    public static String safeLocalPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.length() > MAX_LOCAL_PATH_LENGTH) {
            return null;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' || Character.isISOControl(c)) {
                return null;
            }
        }
        return path;
    }

    /** Returns {@code code} when it looks like a school code (lower-case letters, digits, '-'); otherwise null. */
    public static String safeSchoolCode(String code) {
        return code != null && SCHOOL_CODE.matcher(code).matches() ? code : null;
    }

    /** The request path without the context path and without ";jsessionid=…" style path parameters. */
    static String pathWithinApp(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.indexOf(';') < 0 ? path : path.replaceAll(";[^/]*", "");
    }

    private static String firstSegment(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(0, slash);
    }
}

package sg.schoolmatch.boundary.ui.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URISyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotAuthenticatedException;
import sg.schoolmatch.error.NotFoundException;

/**
 * Turns the app's exceptions into friendly pages ({@code templates/error.html}, NFR-USE-03):
 * <ul>
 *   <li>NotFoundException → 404 (FR-SCHOOL-01: unknown school code)</li>
 *   <li>ExternalServiceUnavailableException → 503 "temporarily unavailable"</li>
 *   <li>UnsupportedOperationException("TODO …") → 501 "Not built yet" with the TODO text</li>
 *   <li>InvalidInputException not handled by the page itself → 400 with the field messages</li>
 *   <li>NotAuthenticatedException → clear the cookie and go to the login page (session ended between checks):
 *       GET → {@code /login?next=<path+query>}; POST → {@code next} = the Referer's path when it is from this
 *       site, else {@code /}; add-to-shortlist keeps {@code next=/schools/{code}&add={code}} (DC-08)</li>
 * </ul>
 * Other errors (and URLs with no handler) use Spring Boot's error page, which renders the same template.
 */
@ControllerAdvice
public class ErrorPageAdvice {

    private static final Logger log = LoggerFactory.getLogger(ErrorPageAdvice.class);

    private final LayoutModelAdvice layout;
    private final SessionCookie sessionCookie;

    public ErrorPageAdvice(LayoutModelAdvice layout, SessionCookie sessionCookie) {
        this.layout = layout;
        this.sessionCookie = sessionCookie;
    }

    @ExceptionHandler(NotFoundException.class)
    public ModelAndView notFound(NotFoundException e, HttpServletRequest request) {
        return errorPage(request, HttpStatus.NOT_FOUND, "Page not found", e.getMessage());
    }

    @ExceptionHandler(ExternalServiceUnavailableException.class)
    public ModelAndView serviceUnavailable(ExternalServiceUnavailableException e, HttpServletRequest request) {
        log.warn("{} unavailable for {}", e.getService(), request.getRequestURI(), e);
        return errorPage(request, HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable",
                e.getService() + " is temporarily unavailable. Please try again in a few minutes.");
    }

    /** Only the skeleton's {@code TODO …} exceptions become "Not built yet"; any other one is a real bug (500). */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ModelAndView notBuiltYet(UnsupportedOperationException e, HttpServletRequest request) {
        if (e.getMessage() == null || !e.getMessage().startsWith("TODO")) {
            throw e;
        }
        log.info("Not built yet: {} {} ({})", request.getMethod(), request.getRequestURI(), e.getMessage());
        return errorPage(request, HttpStatus.NOT_IMPLEMENTED, "Not built yet", e.getMessage());
    }

    @ExceptionHandler(InvalidInputException.class)
    public ModelAndView invalidInput(InvalidInputException e, HttpServletRequest request) {
        ModelAndView page = errorPage(request, HttpStatus.BAD_REQUEST, "Please check your input", null);
        page.addObject(PageMessages.FIELD_ERRORS, e.getFieldErrors());
        return page;
    }

    @ExceptionHandler(NotAuthenticatedException.class)
    public ModelAndView notAuthenticated(HttpServletRequest request, HttpServletResponse response) {
        sessionCookie.clear(response);
        RedirectView toLogin = new RedirectView(loginUrlAfterSessionEnded(request), true);
        toLogin.setExpandUriTemplateVariables(false);
        toLogin.setExposeModelAttributes(false);
        return new ModelAndView(toLogin);
    }

    /**
     * Where to send a user whose session ended during a request. A GET (and add-to-shortlist) goes through
     * {@link AuthInterceptor#loginUrl(HttpServletRequest)}. Any other POST returns to the page the form was on:
     * the Referer's path and query when the Referer is a page of this site, otherwise {@code /}.
     */
    static String loginUrlAfterSessionEnded(HttpServletRequest request) {
        if (HttpMethod.GET.matches(request.getMethod()) || AuthInterceptor.isAddToShortlist(request)) {
            return AuthInterceptor.loginUrl(request);
        }
        String next = localRefererPath(request);
        return AuthInterceptor.loginUrl(next == null ? "/" : next, null);
    }

    /** The Referer's path (+ query) when it points at this site (same host, or a relative URL); else null. */
    private static String localRefererPath(HttpServletRequest request) {
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(referer.strip());
            if (uri.getHost() != null && !uri.getHost().equalsIgnoreCase(request.getServerName())) {
                return null;   // another site
            }
            if (uri.getHost() == null && (uri.getScheme() != null || uri.getRawAuthority() != null)) {
                return null;   // e.g. "javascript:…" or "//"
            }
            String path = uri.getRawPath();
            if (path == null || !path.startsWith(request.getContextPath())) {
                return null;
            }
            path = path.substring(request.getContextPath().length());
            String next = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
            return AuthInterceptor.safeLocalPath(next);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private ModelAndView errorPage(HttpServletRequest request, HttpStatus status, String title, String message) {
        ModelAndView page = new ModelAndView("error", status);
        page.addAllObjects(layout.layoutAttributes(request));
        page.addObject("status", status.value());
        page.addObject("error", status.getReasonPhrase());
        page.addObject("title", title);
        page.addObject("message", message);
        return page;
    }
}

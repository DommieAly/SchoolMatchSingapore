package sg.schoolmatch.boundary.ui.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * DC-72: refuses form posts sent from another website (cross-site request forgery, NFR-SEC-05).
 * <p>
 * For every request that can change something (any method other than GET, HEAD, OPTIONS, TRACE) the browser's
 * {@code Origin} header, or the {@code Referer} header when there is no Origin, must name this site's host and
 * port. A request from another site gets 403 and nothing runs. A request with neither header (curl, a very old
 * browser) is let through: {@code SameSite=Lax} on {@code SM_SESSION} still keeps it from using a login.
 * <p>
 * This mainly closes login CSRF: SameSite=Lax does not stop another site from posting {@code /login} with the
 * attacker's username and password, after which the visitor would type their own profile into that account.
 * Registered for every path in {@code config/WebConfig}.
 */
@Component
public class OriginCheckInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OriginCheckInterceptor.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            return true;
        }
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String source = origin != null ? origin : request.getHeader(HttpHeaders.REFERER);
        if (source == null || source.isBlank() || isSameSite(source, request)) {
            return true;
        }
        log.warn("Refused {} {} from another site ({} {})", request.getMethod(), request.getRequestURI(),
                origin != null ? "Origin" : "Referer", source);
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
        return false;
    }

    /**
     * True when {@code url} (an Origin or Referer value) has this request's host and port. Default ports are
     * ignored on both sides ({@code http://site} equals Host {@code site:80}). Origin {@code null} (a sandboxed or
     * privacy-hidden page) and unreadable values count as another site.
     */
    static boolean isSameSite(String url, HttpServletRequest request) {
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        int defaultPort = "https".equals(scheme) ? 443 : 80;
        int port = uri.getPort() == -1 ? defaultPort : uri.getPort();

        String hostHeader = request.getHeader(HttpHeaders.HOST);
        if (hostHeader == null || hostHeader.isBlank()) {
            return host.equalsIgnoreCase(request.getServerName()) && port == request.getServerPort();
        }
        URI requestAuthority;
        try {
            requestAuthority = new URI("x://" + hostHeader.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        int requestPort = requestAuthority.getPort() == -1 ? defaultPort : requestAuthority.getPort();
        return host.equalsIgnoreCase(requestAuthority.getHost()) && port == requestPort;
    }
}

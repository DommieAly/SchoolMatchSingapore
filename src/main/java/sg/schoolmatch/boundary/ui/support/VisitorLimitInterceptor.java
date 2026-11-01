package sg.schoolmatch.boundary.ui.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.error.TooManyRequestsException;

/**
 * DC-90: limits per visitor, so one stranger cannot send requests without end (NFR-LIMIT-01). They do not protect the
 * day's Google allowance (docs/deployment.md, Google allowance). Only the requests that can call Google or OneMap, and
 * the sign-in forms, are counted ({@link #ruleFor}); every other request is never counted.
 * <p>
 * Requests are counted per visitor address ({@code request.getRemoteAddr()}; in the prod profile Tomcat sets it from
 * the proxy's forwarded header) and per {@link Rule}, in fixed one-minute and one-hour windows of the clock. Over a
 * limit, {@link TooManyRequestsException} becomes the 429 page with {@code Retry-After} (ErrorPageAdvice). A refused
 * request is not counted, so trying again does not use up the hour. A class on one Wi-Fi address shares these numbers:
 * about 10 people can press Get recommendations in one minute and 40 in one clock hour, and a walk-through by 30 people
 * reaches the address-search and Google-page limits for the hour (docs/deployment.md, Updating the site).
 * <p>
 * The counters live in memory and stay small: {@link #dropEndedHour} drops them when the hour has changed, and at most
 * {@link #MAX_TRACKED} are kept. Runs only when {@code app.visitor-limit.enabled} is true (the prod profile).
 * Registered first in {@code config/WebConfig}.
 */
@Component
public class VisitorLimitInterceptor implements HandlerInterceptor {

    /** The most counters kept; when there are more, the one not used for longest is forgotten. */
    static final int MAX_TRACKED = 10_000;

    private static final Logger log = LoggerFactory.getLogger(VisitorLimitInterceptor.class);
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = 3600;
    private static final String FACILITY_DESTINATION = "facility:";

    /** A group of routes that share one limit per address. */
    enum Rule {
        ADDRESS_SEARCH(10, 60),
        SIGN_IN(20, 200),
        HEAVY_GOOGLE(10, 40),
        RECOMMENDATIONS(10, 40),
        GOOGLE_PAGE(30, 120);

        final int perMinute;
        final int perHour;

        Rule(int perMinute, int perHour) {
            this.perMinute = perMinute;
            this.perHour = perHour;
        }
    }

    private record Visitor(String address, Rule rule) {
    }

    /** Requests let through in the current minute and hour. */
    private static final class Counter {
        long minute;
        int inMinute;
        int inHour;
        boolean refusalLogged;
    }

    private final boolean enabled;
    private final Clock clock;
    private final Map<Visitor, Counter> counters = new LinkedHashMap<>(16, 0.75f, true) {   // oldest use first
        @Override
        protected boolean removeEldestEntry(Map.Entry<Visitor, Counter> eldest) {
            return size() > MAX_TRACKED;
        }
    };
    private long currentHour;   // guarded by this

    public VisitorLimitInterceptor(AppProperties props, Clock clock) {
        this.enabled = props.visitorLimit().enabled();
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Rule rule = enabled ? ruleFor(request) : null;
        if (rule != null) {
            long retryAfter = count(new Visitor(request.getRemoteAddr(), rule));
            if (retryAfter > 0) {
                throw new TooManyRequestsException(retryAfter);
            }
        }
        return true;
    }

    /**
     * The rule that counts this request; null when it is never counted. It goes by the route Spring matched, not the
     * URL as typed, so {@code /schools/map;x=1} counts as {@code /schools/map}, and a HEAD (which runs the GET handler)
     * counts as a GET. A new route that calls Google or OneMap must be added here (docs/routes.md).
     */
    static Rule ruleFor(HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String route)) {
            return null;
        }
        boolean post = HttpMethod.POST.matches(request.getMethod());
        return switch (route) {
            case "/location" -> Rule.ADDRESS_SEARCH;
            // Saving a profile can look up a home address (ProfileController). Every save counts, because the binder
            // also takes the address from repeated values and from !homeAddress.
            case "/profile" -> post ? Rule.ADDRESS_SEARCH : null;
            case "/login", "/register" -> post ? Rule.SIGN_IN : null;
            // Its own count: sort, map and filter links on a travel list use HEAVY_GOOGLE and must not use this one up.
            case "/recommendations" -> post ? Rule.RECOMMENDATIONS : null;
            // The pager's links carry page=N. Their route answers are already cached (GoogleRoutesApi), so paging
            // through a list must not use up the heavy limit.
            case "/schools" -> !FilterParams.from(request).travel() ? null
                    : request.getParameter(FilterParams.PAGE) == null ? Rule.HEAVY_GOOGLE : Rule.GOOGLE_PAGE;
            case "/schools/map" -> FilterParams.from(request).travel() ? Rule.HEAVY_GOOGLE : Rule.GOOGLE_PAGE;
            case "/schools/{code}/facilities", "/schools/{code}/facilities/map", "/facilities/{placeId}" ->
                    Rule.GOOGLE_PAGE;
            // A mode asks for the route; a facility destination asks for its details even without a mode.
            case "/directions" -> request.getParameter("mode") != null || isFacility(request.getParameter("to"))
                    ? Rule.GOOGLE_PAGE : null;
            default -> null;
        };
    }

    private static boolean isFacility(String to) {
        return to != null && to.strip().startsWith(FACILITY_DESTINATION);
    }

    /** Counts one request. Returns 0 when it may go on, else the seconds until the limit it reached starts again. */
    private synchronized long count(Visitor visitor) {
        dropEndedHour();
        long now = clock.instant().getEpochSecond();
        long minute = now / SECONDS_PER_MINUTE;
        Counter counter = counters.computeIfAbsent(visitor, v -> new Counter());
        if (counter.minute != minute) {
            counter.minute = minute;
            counter.inMinute = 0;
            counter.refusalLogged = false;
        }
        Rule rule = visitor.rule();
        if (counter.inHour >= rule.perHour) {
            return refused(counter, rule, SECONDS_PER_HOUR - now % SECONDS_PER_HOUR);
        }
        if (counter.inMinute >= rule.perMinute) {
            return refused(counter, rule, SECONDS_PER_MINUTE - now % SECONDS_PER_MINUTE);
        }
        counter.inMinute++;
        counter.inHour++;
        return 0;
    }

    /** Forgets the addresses of an hour that has ended, even when no counted request comes (about-privacy.html). */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public synchronized void dropEndedHour() {
        long hour = clock.instant().getEpochSecond() / SECONDS_PER_HOUR;
        if (hour != currentHour) {
            counters.clear();   // every window is on the clock, so they have all ended
            currentHour = hour;
        }
    }

    /** One WARN line per counter per minute, so a burst cannot fill the log. The address is not logged. */
    private static long refused(Counter counter, Rule rule, long retryAfter) {
        if (!counter.refusalLogged) {
            counter.refusalLogged = true;
            log.warn("Visitor limit reached: {} ({} a minute, {} an hour)", rule, rule.perMinute, rule.perHour);
        }
        return retryAfter;
    }

    /** How many counters are kept now, for tests. */
    synchronized int trackedCount() {
        return counters.size();
    }
}

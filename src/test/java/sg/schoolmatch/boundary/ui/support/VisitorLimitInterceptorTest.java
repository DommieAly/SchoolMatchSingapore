package sg.schoolmatch.boundary.ui.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import sg.schoolmatch.boundary.ui.support.VisitorLimitInterceptor.Rule;
import sg.schoolmatch.error.TooManyRequestsException;
import sg.schoolmatch.support.FixedClock;
import sg.schoolmatch.support.TestProperties;

/**
 * Unit tests of the limits per visitor (DC-90): the counting, with a clock the test moves. The test clock starts at
 * 10:00:00, so at the start of a minute and of an hour. {@code VisitorLimitFlowTest} runs the same rules on the real
 * routes of the whole app.
 */
class VisitorLimitInterceptorTest {

    private static final String ADDRESS = "203.0.113.1";

    private final FixedClock clock = FixedClock.atDefault();
    private final VisitorLimitInterceptor interceptor = interceptor(true);

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-01: the numbers of each rule: 10/60 address search, 20/200 sign-in, 10/40 heavy Google, 10/40 Get recommendations, 30/120 Google pages")
    void numbers() {
        assertThat(Rule.ADDRESS_SEARCH.perMinute).isEqualTo(10);
        assertThat(Rule.ADDRESS_SEARCH.perHour).isEqualTo(60);
        assertThat(Rule.SIGN_IN.perMinute).isEqualTo(20);
        assertThat(Rule.SIGN_IN.perHour).isEqualTo(200);
        assertThat(Rule.HEAVY_GOOGLE.perMinute).isEqualTo(10);
        assertThat(Rule.HEAVY_GOOGLE.perHour).isEqualTo(40);
        assertThat(Rule.RECOMMENDATIONS.perMinute).isEqualTo(10);
        assertThat(Rule.RECOMMENDATIONS.perHour).isEqualTo(40);
        assertThat(Rule.GOOGLE_PAGE.perMinute).isEqualTo(30);
        assertThat(Rule.GOOGLE_PAGE.perHour).isEqualTo(120);
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-02: requests up to the minute limit pass; the next one is refused with the seconds left in the minute")
    void overMinuteLimit() {
        passes(10, addressSearch(ADDRESS));

        clock.advance(Duration.ofSeconds(15));
        assertRefused(addressSearch(ADDRESS), 45);
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-03: a new minute passes again, until the hour limit; a new hour passes again")
    void newWindows() {
        passes(10, addressSearch(ADDRESS));
        assertRefused(addressSearch(ADDRESS), 60);

        for (int minute = 1; minute < 6; minute++) {
            clock.advance(Duration.ofMinutes(1));
            passes(10, addressSearch(ADDRESS));
        }
        clock.advance(Duration.ofMinutes(1));   // 60 requests in 6 minutes: the hour limit, 54 minutes before the hour ends
        assertRefused(addressSearch(ADDRESS), 54 * 60);

        clock.advance(Duration.ofMinutes(54));
        passes(10, addressSearch(ADDRESS));
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-04: a refused request is not counted, so trying again does not use up the hour")
    void refusedNotCounted() {
        passes(10, addressSearch(ADDRESS));
        for (int i = 0; i < 100; i++) {
            assertRefused(addressSearch(ADDRESS), 60);
        }
        clock.advance(Duration.ofMinutes(1));
        passes(10, addressSearch(ADDRESS));
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-05: two addresses are counted apart, and so are two rules of one address")
    void countedApart() {
        passes(10, addressSearch(ADDRESS));
        assertRefused(addressSearch(ADDRESS), 60);

        passes(10, addressSearch("203.0.113.2"));
        passes(20, request("POST", "/login", ADDRESS));
        assertRefused(request("POST", "/login", ADDRESS), 60);
    }

    @ParameterizedTest(name = "{0} {1} {2}={3} → {4}")
    @CsvSource(nullValues = "-", value = {
            "POST, /location, -, -, ADDRESS_SEARCH",
            "POST, /profile, homeAddress, 579767, ADDRESS_SEARCH",
            "POST, /profile, homeAddress, ' ', ADDRESS_SEARCH",
            "POST, /profile, !homeAddress, 579767, ADDRESS_SEARCH",
            "POST, /profile, -, -, ADDRESS_SEARCH",
            "GET, /profile, -, -, -",
            "POST, /login, -, -, SIGN_IN",
            "POST, /register, -, -, SIGN_IN",
            "GET, /login, -, -, -",
            "GET, /register, -, -, -",
            "GET, /schools, travel, 1, HEAVY_GOOGLE",
            "GET, /schools, travel, true, HEAVY_GOOGLE",
            "HEAD, /schools, travel, 1, HEAVY_GOOGLE",
            "GET, /schools, q, high, -",
            "GET, /schools, page, 2, -",
            "GET, /schools/map, travel, 1, HEAVY_GOOGLE",
            "GET, /schools/map, -, -, GOOGLE_PAGE",
            "HEAD, /schools/map, -, -, GOOGLE_PAGE",
            "POST, /recommendations, -, -, RECOMMENDATIONS",
            "GET, /recommendations, -, -, -",
            "GET, /schools/{code}/facilities, -, -, GOOGLE_PAGE",
            "GET, /schools/{code}/facilities/map, -, -, GOOGLE_PAGE",
            "GET, /facilities/{placeId}, -, -, GOOGLE_PAGE",
            "GET, /directions, mode, WALK, GOOGLE_PAGE",
            "GET, /directions, to, facility:abc, GOOGLE_PAGE",
            "GET, /directions, to, school:abc, -",
            "GET, /directions, -, -, -",
            "GET, /, -, -, -",
            "GET, /schools/{code}, -, -, -",
            "GET, /schools/filter, travel, 1, -",
            "GET, /api/districts, -, -, -",
            "POST, /location/device, -, -, -",
            "POST, /location/choose, -, -, -",
            "POST, /location/clear, -, -, -",
            "GET, /**, -, -, -",
    })
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-06: each route has its rule, and every other route has none")
    void rules(String method, String route, String param, String value, Rule expected) {
        MockHttpServletRequest request = request(method, route, ADDRESS);
        if (param != null) {
            request.setParameter(param, value);
        }

        assertThat(VisitorLimitInterceptor.ruleFor(request)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "page={0}")
    @CsvSource({"1", "2"})
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-14: a page of a travel-filtered list counts as a Google page, not as heavy use, because its route answers are cached")
    void pagingIsNotHeavyUse(String page) {
        MockHttpServletRequest request = request("GET", "/schools", ADDRESS);
        request.setParameter("travel", "1");
        request.setParameter("page", page);

        assertThat(VisitorLimitInterceptor.ruleFor(request)).isEqualTo(Rule.GOOGLE_PAGE);
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-07: a request no route was matched for (a preflight OPTIONS, say) is not counted")
    void noRoute() {
        assertThat(VisitorLimitInterceptor.ruleFor(new MockHttpServletRequest("OPTIONS", "/location"))).isNull();
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-08: an uncounted path is never limited and takes no memory")
    void uncountedNeverLimited() {
        passes(1000, request("GET", "/schools/{code}", ADDRESS));

        assertThat(interceptor.trackedCount()).isZero();
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-09: with the setting off nothing is limited or counted")
    void settingOff() {
        VisitorLimitInterceptor off = interceptor(false);
        for (int i = 0; i < 1000; i++) {
            assertThat(off.preHandle(addressSearch(ADDRESS), new MockHttpServletResponse(), new Object())).isTrue();
        }

        assertThat(off.trackedCount()).isZero();
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-10: the counters are dropped when the hour ends")
    void endedWindowsDropped() {
        passes(1, addressSearch(ADDRESS));
        passes(1, addressSearch("203.0.113.2"));
        assertThat(interceptor.trackedCount()).isEqualTo(2);

        clock.advance(Duration.ofHours(1));
        passes(1, addressSearch("203.0.113.3"));

        assertThat(interceptor.trackedCount()).isEqualTo(1);
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-11: at most 10,000 counters are kept; the one not used for longest is forgotten")
    void memoryBound() {
        assertThat(VisitorLimitInterceptor.MAX_TRACKED).isEqualTo(10_000);
        passes(10, addressSearch(ADDRESS));

        for (int i = 0; i < VisitorLimitInterceptor.MAX_TRACKED; i++) {
            passes(1, addressSearch("visitor-" + i));
        }

        assertThat(interceptor.trackedCount()).isEqualTo(VisitorLimitInterceptor.MAX_TRACKED);
        passes(1, addressSearch(ADDRESS));   // forgotten, so it starts again from zero
        assertThat(interceptor.trackedCount()).isEqualTo(VisitorLimitInterceptor.MAX_TRACKED);
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimit-12: the message says how long to wait: a minute at the minute limit, about so many minutes at the hour limit")
    void messageSaysHowLong() {
        assertThat(new TooManyRequestsException(45))
                .hasMessage("Too many requests from your network. Please wait a minute and try again.");
        assertThat(new TooManyRequestsException(60))
                .hasMessage("Too many requests from your network. Please wait a minute and try again.");
        assertThat(new TooManyRequestsException(61))
                .hasMessage("Too many requests from your network. Please try again in about 2 minutes.");
        assertThat(new TooManyRequestsException(3360))
                .hasMessage("Too many requests from your network. Please try again in about 56 minutes.");
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-VisitorLimit-13: the addresses of an ended hour are forgotten even when no counted request comes; within the hour they stay")
    void endedHourDroppedWithoutARequest() {
        passes(1, addressSearch(ADDRESS));

        clock.advance(Duration.ofMinutes(59));
        interceptor.dropEndedHour();
        assertThat(interceptor.trackedCount()).isEqualTo(1);

        clock.advance(Duration.ofHours(2));
        interceptor.dropEndedHour();
        assertThat(interceptor.trackedCount()).isZero();
    }

    private VisitorLimitInterceptor interceptor(boolean enabled) {
        return new VisitorLimitInterceptor(TestProperties.bind(Map.of("app.visitor-limit.enabled", enabled)), clock);
    }

    private void passes(int times, MockHttpServletRequest request) {
        for (int i = 0; i < times; i++) {
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        }
    }

    private void assertRefused(MockHttpServletRequest request, long retryAfterSeconds) {
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(TooManyRequestsException.class)
                .satisfies(e -> assertThat(((TooManyRequestsException) e).getRetryAfterSeconds())
                        .isEqualTo(retryAfterSeconds));
    }

    private static MockHttpServletRequest addressSearch(String address) {
        return request("POST", "/location", address);
    }

    /** A request as the interceptor sees it: Spring has already matched it to {@code route}. */
    private static MockHttpServletRequest request(String method, String route, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, route);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
        request.setRemoteAddr(address);
        return request;
    }
}

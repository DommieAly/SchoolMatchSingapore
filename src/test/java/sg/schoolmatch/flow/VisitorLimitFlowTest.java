package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import sg.schoolmatch.support.FixedClock;

/**
 * The limits per visitor on the real routes (DC-90): the whole app with the setting on, the offline stubs and a clock
 * the test moves. Each test sends from its own address, so the tests do not share a count.
 */
@SpringBootTest(properties = "app.visitor-limit.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VisitorLimitFlowTest {

    private static final String CODE = "catholic-high-school";
    private static final String MESSAGE = "Too many requests from your network. Please wait a minute and try again.";
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger();

    /** Replaces the Clock bean, so a test never crosses the end of a minute by chance. */
    @TestConfiguration
    static class MovableClock {

        @Bean
        @Primary
        FixedClock fixedClock() {
            return FixedClock.atDefault();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FixedClock clock;

    @Autowired
    private ScheduledTaskHolder scheduled;

    @Test
    @Tag("NFR-LIMIT-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-VisitorLimitFlow-01: over the limit, POST /location gets the site's error page with 429, Retry-After and the message; a new minute works again")
    void overLimit_errorPage_thenNewMinute() throws Exception {
        RequestPostProcessor visitor = newVisitor();
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/location").with(visitor).param("address", "579767").param("returnTo", "/schools"))
                    .andExpect(redirectedUrl("/schools"));
        }

        mvc.perform(post("/location").with(visitor).param("address", "579767").param("returnTo", "/schools"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(view().name("error"))
                .andExpect(content().string(containsString("Too many requests")))
                .andExpect(content().string(containsString(MESSAGE)))
                .andExpect(content().string(containsString("Search schools")));   // the page's own link
        mvc.perform(get("/").with(visitor)).andExpect(status().isOk());   // the rest of the site still works

        clock.advance(Duration.ofMinutes(1));
        mvc.perform(post("/location").with(visitor).param("address", "579767").param("returnTo", "/schools"))
                .andExpect(redirectedUrl("/schools"));
    }

    @ParameterizedTest(name = "{0} {1}: {2} a minute")
    @CsvSource({
            "POST, /location, 10",
            "POST, /profile, 10",
            "POST, /login, 20",
            "POST, /register, 20",
            "GET, /schools?travel=1, 10",
            "HEAD, /schools?travel=1, 10",
            "GET, /schools?travel=1&page=2, 30",
            "GET, /schools/map?travel=1, 10",
            "POST, /recommendations, 10",
            "GET, /schools/map, 30",
            "HEAD, /schools/map, 30",
            "GET, /schools/map;x=1, 30",
            "GET, /schools/" + CODE + "/facilities, 30",
            "GET, /schools/" + CODE + "/facilities/map, 30",
            "GET, /facilities/some-place, 30",
            "GET, /directions?to=school:" + CODE + "&mode=WALK, 30",
            "GET, /directions?to=facility:some-place, 30",
    })
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimitFlow-02: every route that can call Google or OneMap, and the sign-in forms, is limited")
    void everyLimitedRoute(String method, String url, int limit) throws Exception {
        RequestPostProcessor visitor = newVisitor();
        for (int i = 0; i < limit; i++) {
            int answer = mvc.perform(request(HttpMethod.valueOf(method), url).with(visitor))
                    .andReturn().getResponse().getStatus();
            assertThat(answer).as("request %d", i + 1).isNotEqualTo(429);
        }

        mvc.perform(request(HttpMethod.valueOf(method), url).with(visitor))
                .andExpect(status().isTooManyRequests());
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET, /",
            "GET, /about/data",
            "GET, /login",
            "GET, /register",
            "GET, /schools",
            "GET, /schools?q=high",
            "GET, /schools/filter?travel=1",
            "GET, /schools/" + CODE,
            "GET, /directions?to=school:" + CODE,
            "GET, /api/districts",
            "GET, /css/app.css",
            "GET, /actuator/health",
            "POST, /location/clear",
            "POST, /location/choose",
    })
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimitFlow-03: every other request is never limited, however many are sent")
    void uncountedRoutes(String method, String url) throws Exception {
        RequestPostProcessor visitor = newVisitor();
        for (int i = 0; i < 40; i++) {
            int answer = mvc.perform(request(HttpMethod.valueOf(method), url).with(visitor))
                    .andReturn().getResponse().getStatus();
            assertThat(answer).as("request %d", i + 1).isNotEqualTo(429);
        }
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimitFlow-06: every way of sending a home address to POST /profile is counted: repeated values, !homeAddress, an empty value in the URL")
    void profileAddressForms() throws Exception {
        assertLimitedAfterTen(() -> post("/profile").param("homeAddress", "579767"));
        assertLimitedAfterTen(() -> post("/profile").param("homeAddress", "", "579767"));
        assertLimitedAfterTen(() -> post("/profile").param("!homeAddress", "579767"));
        assertLimitedAfterTen(() -> post("/profile?homeAddress=").param("homeAddress", "579767"));
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-VisitorLimitFlow-07: over the hour limit the page and Retry-After say how long is left in the hour, not \"a minute\"")
    void overHourLimit_saysHowLong() throws Exception {
        RequestPostProcessor visitor = newVisitor();
        for (int minute = 0; minute < 4; minute++) {
            for (int i = 0; i < 30; i++) {
                mvc.perform(get("/schools/map").with(visitor)).andExpect(status().isOk());
            }
            if (minute < 3) {
                clock.advance(Duration.ofMinutes(1));
            }
        }

        long secondsLeft = 3600 - clock.instant().getEpochSecond() % 3600;
        mvc.perform(get("/schools/map").with(visitor))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", String.valueOf(secondsLeft)))
                .andExpect(content().string(containsString("Please try again in about " + secondsLeft / 60 + " minutes.")))
                .andExpect(content().string(not(containsString(MESSAGE))));
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-VisitorLimitFlow-08: the running app checks every minute for an ended hour, so an address stays in memory about one hour (about-privacy.html)")
    void endedHourCheckIsScheduled() {
        Task check = scheduled.getScheduledTasks().stream().map(ScheduledTask::getTask)
                .filter(task -> task.toString().contains("VisitorLimitInterceptor.dropEndedHour"))
                .findFirst().orElseThrow();

        assertThat(check).isInstanceOfSatisfying(FixedDelayTask.class,
                task -> assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(1)));
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimitFlow-09: browsing a travel list up to its limit does not stop Get recommendations, which has its own count")
    void travelListLeavesRecommendationsAlone() throws Exception {
        RequestPostProcessor visitor = newVisitor();
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/schools?q=&travel=1&mode=WALK&maxMin=15&sort=COMMUTE_ASC").with(visitor))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/schools?q=&travel=1&mode=WALK&maxMin=15&sort=COMMUTE_ASC").with(visitor))
                .andExpect(status().isTooManyRequests());

        int answer = mvc.perform(post("/recommendations").with(visitor)).andReturn().getResponse().getStatus();
        assertThat(answer).isNotEqualTo(429);
    }

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimitFlow-04: another address is not affected")
    void otherAddress() throws Exception {
        RequestPostProcessor visitor = newVisitor();
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/schools?travel=1").with(visitor)).andExpect(status().isOk());
        }
        mvc.perform(get("/schools?travel=1").with(visitor)).andExpect(status().isTooManyRequests());

        mvc.perform(get("/schools?travel=1").with(newVisitor())).andExpect(status().isOk());
    }

    /** Ten of these requests pass from one new address; the eleventh gets 429. */
    private void assertLimitedAfterTen(Supplier<MockHttpServletRequestBuilder> request) throws Exception {
        RequestPostProcessor visitor = newVisitor();
        for (int i = 0; i < 10; i++) {
            int answer = mvc.perform(request.get().with(visitor)).andReturn().getResponse().getStatus();
            assertThat(answer).as("request %d", i + 1).isNotEqualTo(429);
        }

        mvc.perform(request.get().with(visitor)).andExpect(status().isTooManyRequests());
    }

    /** Sets the address the request comes from, as Tomcat does from the proxy's forwarded header. */
    private static RequestPostProcessor newVisitor() {
        String address = "198.51.100." + NEXT_ADDRESS.incrementAndGet();
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}

package sg.schoolmatch.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import sg.schoolmatch.boundary.external.ExternalCallBudget;
import sg.schoolmatch.support.ExternalFailures;
import sg.schoolmatch.support.LogCapture;

/**
 * {@link ExternalFailureLog}: the one WARN line written when a Google or OneMap failure becomes a page message
 * (NFR-MAIN-02, NFR-USE-03). It tells the app's own daily limit apart from Google refusing or failing, and never
 * contains a key, a full URL or a request header.
 */
class ExternalFailureLogTest {

    /** Made-up key in Google's format (AIza + 35 characters); it must never reach a log line. */
    static final String FAKE_KEY = "AIzaSyFAKE0123456789abcdefghijklmnopq";

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-FailureLog-01: the app's own limit → 'app daily limit reached' with SKU, used/limit, day and zone")
    void appDailyLimit() {
        ExternalServiceUnavailableException e = new ExternalServiceUnavailableException("Google route-matrix-elements",
                new ExternalCallBudget.DailyLimitReachedException("route-matrix-elements", 200, 147, 266,
                        LocalDate.of(2026, 10, 12), ZoneId.of("America/Los_Angeles")));

        assertThat(ExternalFailureLog.describe(e)).isEqualTo("Google route-matrix-elements: app daily limit reached for "
                + "route-matrix-elements (200 of 266 used on 2026-10-12, America/Los_Angeles day; 147 more asked); "
                + "nothing was sent to Google");
    }

    @Test
    @Tag("NFR-MAIN-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FailureLog-02: a Google client's cause → 'Google refused the request' with HTTP status, Google's status and message")
    void googleRefused() {
        String line = ExternalFailureLog.describe(ExternalFailures.googleRefused("Google Places", 403,
                "PERMISSION_DENIED", "Requests to this API places.googleapis.com are blocked."));

        assertThat(line).isEqualTo("Google Places: Google refused the request: HTTP 403, PERMISSION_DENIED: "
                + "Requests to this API places.googleapis.com are blocked.");
        assertThat(line).doesNotContain("app daily limit");
    }

    @Test
    @Tag("NFR-USE-03")
    @DisplayName("TC-FailureLog-03: other causes → '<service> failed: <class>: <first part of the message>' plus the root cause")
    void otherCauses() {
        String oneMap503 = ExternalFailureLog.describe(new ExternalServiceUnavailableException("OneMap",
                HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", new HttpHeaders(),
                        "{\"error\":\"down for 579767\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)));
        String timeout = ExternalFailureLog.describe(new ExternalServiceUnavailableException("OneMap",
                new ResourceAccessException("I/O error on GET request for \"https://www.onemap.gov.sg/api/common/"
                        + "elastic/search?searchVal=579767\": Read timed out", new SocketTimeoutException("Read timed out"))));
        String noCause = ExternalFailureLog.describe(new ExternalServiceUnavailableException("School data", null));
        String plain = ExternalFailureLog.describe(new ExternalServiceUnavailableException("School data",
                new IllegalStateException("no snapshot")));

        assertThat(oneMap503).isEqualTo("OneMap: OneMap failed: HttpServerErrorException$ServiceUnavailable: "
                + "503 Service Unavailable");
        assertThat(timeout).startsWith("OneMap: OneMap failed: ResourceAccessException: I/O error on GET request for")
                .endsWith("; SocketTimeoutException: Read timed out")
                .doesNotContain("searchVal").doesNotContain("579767");
        assertThat(noCause).isEqualTo("School data: School data failed (no further detail)");
        assertThat(plain).isEqualTo("School data: School data failed: IllegalStateException: no snapshot");
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-FailureLog-04: a key, a key= parameter, a full URL or a line break never reaches the line")
    void neverKeyOrUrl() {
        class Safe extends RuntimeException implements ExternalFailureLog.Described {
            Safe(String message) {
                super(message);
            }
        }
        String described = ExternalFailureLog.describe(new ExternalServiceUnavailableException("Google Routes",
                new Safe("Google refused the request: HTTP 400: API key " + FAKE_KEY + " not valid, see "
                        + "https://console.cloud.google.com/apis/credentials?key=" + FAKE_KEY + "&project=x\nnext line")));
        String generic = ExternalFailureLog.describe(new ExternalServiceUnavailableException("OneMap",
                new ResourceAccessException("I/O error", new java.io.IOException(
                        "GET https://www.onemap.gov.sg/api/common/elastic/search?searchVal=579767&token=" + FAKE_KEY))));

        assertThat(described).contains("https://console.cloud.google.com").contains("next line")
                .doesNotContain(FAKE_KEY).doesNotContain("AIza").doesNotContain("key=").doesNotContain("/apis/")
                .doesNotContain("\n");
        assertThat(generic).contains("https://www.onemap.gov.sg")
                .doesNotContain(FAKE_KEY).doesNotContain("searchVal").doesNotContain("token=");
        assertThat(ExternalFailureLog.clean("x".repeat(500), 300)).hasSize(301).endsWith("…");
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-FailureLog-05: warn() writes exactly one WARN line '<feature> unavailable: …' without a stack trace")
    void warnWritesOneLine() {
        Logger log = LoggerFactory.getLogger(ExternalFailureLogTest.class);
        try (LogCapture capture = LogCapture.of(ExternalFailureLogTest.class)) {
            ExternalFailureLog.warn(log, "Nearby facilities", ExternalFailures.timeout("Google Places"));

            assertThat(capture.warnings()).containsExactly("Nearby facilities unavailable: Google Places: Google failed: "
                    + "no answer (ResourceAccessException; SocketTimeoutException: Read timed out)");
            assertThat(capture.hasStackTrace()).isFalse();
        }
    }
}

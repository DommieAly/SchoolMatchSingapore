package sg.schoolmatch.boundary.external.google;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * {@link GoogleApiFailure}: what a live Google client says went wrong, for the log line (NFR-MAIN-02, NFR-USE-03).
 */
class GoogleApiFailureTest {

    private static final String FAKE_KEY = "AIzaSyFAKE0123456789abcdefghijklmnopq";

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleApiFailure-01: HTTP 403 → 'Google refused the request' + HTTP status, Google's status, ErrorInfo reason, message")
    void refused() {
        String body = """
                {"error": {"code": 403,
                  "message": "Requests to this API places.googleapis.com method google.maps.places.v1.Places.SearchNearby are blocked.",
                  "status": "PERMISSION_DENIED",
                  "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "API_KEY_SERVICE_BLOCKED",
                               "metadata": {"consumer": "projects/123456789", "service": "places.googleapis.com"}}]}}
                """;
        GoogleApiFailure failure = GoogleApiFailure.of(http(HttpStatus.FORBIDDEN, body));

        assertThat(failure.getMessage()).isEqualTo("Google refused the request: HTTP 403, PERMISSION_DENIED, "
                + "API_KEY_SERVICE_BLOCKED: Requests to this API places.googleapis.com method "
                + "google.maps.places.v1.Places.SearchNearby are blocked.");
        assertThat(failure.getCause()).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleApiFailure-05: an error answer whose details is an object (not Google's array) is still described, never a NullPointerException")
    void detailsObjectIsNotAnArray() {
        String body = """
                {"error": {"status": "X", "details": {"reason": "R"}, "message": "m"}}
                """;
        GoogleApiFailure failure = GoogleApiFailure.of(http(HttpStatus.FORBIDDEN, body));

        assertThat(failure.getMessage()).isEqualTo("Google refused the request: HTTP 403, X: m");
        assertThat(failure.getCause()).isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleApiFailure-02:Google's daily quota (429, array answer of computeRouteMatrix) is a refusal with RESOURCE_EXHAUSTED")
    void quotaArray() {
        String body = "[{\"error\": {\"code\": 429, \"message\": \"Quota exceeded for quota metric 'Compute Route Matrix "
                + "elements' and limit 'per day'.\", \"status\": \"RESOURCE_EXHAUSTED\"}}]";

        assertThat(GoogleApiFailure.of(http(HttpStatus.TOO_MANY_REQUESTS, body)).getMessage())
                .isEqualTo("Google refused the request: HTTP 429, RESOURCE_EXHAUSTED: Quota exceeded for quota metric "
                        + "'Compute Route Matrix elements' and limit 'per day'.");
    }

    @Test
    @Tag("NFR-USE-03")
    @DisplayName("TC-GoogleApiFailure-03: HTTP 5xx, a timeout and an unreadable answer → 'Google failed' with the status or exception class")
    void failed() {
        RestClientException server = HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                new HttpHeaders(), "<html>oops</html>".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        RestClientException timeout = new ResourceAccessException("I/O error on POST request for \"https://routes."
                + "googleapis.com/distanceMatrix/v2:computeRouteMatrix\": Read timed out",
                new SocketTimeoutException("Read timed out"));
        RestClientException unreadable = new RestClientException("Error while extracting response",
                new IllegalStateException("Unexpected character"));

        assertThat(GoogleApiFailure.of(server).getMessage()).isEqualTo("Google failed: HTTP 500");
        assertThat(GoogleApiFailure.of(timeout).getMessage())
                .isEqualTo("Google failed: no answer (ResourceAccessException; SocketTimeoutException: Read timed out)");
        assertThat(GoogleApiFailure.of(unreadable).getMessage())
                .isEqualTo("Google failed: unreadable answer (RestClientException; IllegalStateException: Unexpected character)");
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleApiFailure-04: a key or URL inside Google's message is removed; request headers are never read")
    void neverKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Goog-Api-Key", FAKE_KEY);
        String body = "{\"error\": {\"code\": 400, \"status\": \"INVALID_ARGUMENT\", \"message\": \"API key " + FAKE_KEY
                + " not valid, see https://console.cloud.google.com/apis/credentials?key=" + FAKE_KEY + "\"}}";
        RestClientException e = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", headers,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(GoogleApiFailure.of(e).getMessage()).startsWith("Google refused the request: HTTP 400, INVALID_ARGUMENT")
                .contains("https://console.cloud.google.com")
                .doesNotContain(FAKE_KEY).doesNotContain("AIza").doesNotContain("key=");
    }

    private static HttpClientErrorException http(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}

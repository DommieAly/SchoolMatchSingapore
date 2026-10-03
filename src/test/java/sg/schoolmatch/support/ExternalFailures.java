package sg.schoolmatch.support;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import sg.schoolmatch.boundary.external.ExternalCallBudget;
import sg.schoolmatch.boundary.external.google.GoogleApiFailure;
import sg.schoolmatch.error.ExternalServiceUnavailableException;

/**
 * Test helper: the {@link ExternalServiceUnavailableException}s the live clients really throw, for tests of the
 * WARN line a page writes (ExternalFailureLog).
 */
public final class ExternalFailures {

    private ExternalFailures() {
    }

    /** What {@code ExternalCallBudget.charge} throws when today's limit of {@code sku} is used up. */
    public static ExternalServiceUnavailableException dailyLimit(String sku, int used, int requested, int limit) {
        return new ExternalServiceUnavailableException("Google " + sku, new ExternalCallBudget.DailyLimitReachedException(
                sku, used, requested, limit, LocalDate.of(2026, 10, 12), ZoneId.of("America/Los_Angeles")));
    }

    /** What a live Google client throws for an HTTP 4xx answer in Google's error format. */
    public static ExternalServiceUnavailableException googleRefused(String service, int httpStatus, String googleStatus,
                                                                    String message) {
        String body = "{\"error\": {\"code\": " + httpStatus + ", \"message\": \"" + message + "\", \"status\": \""
                + googleStatus + "\"}}";
        return new ExternalServiceUnavailableException(service, GoogleApiFailure.of(HttpClientErrorException.create(
                HttpStatusCode.valueOf(httpStatus), "", new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8)));
    }

    /**
     * What a live client throws when the service does not answer in time: a Google client wraps the cause in a
     * {@link GoogleApiFailure}; OneMapClient passes Spring's exception on as it is.
     */
    public static ExternalServiceUnavailableException timeout(String service) {
        ResourceAccessException e = new ResourceAccessException("I/O error: Read timed out",
                new SocketTimeoutException("Read timed out"));
        return new ExternalServiceUnavailableException(service,
                service.startsWith("Google") ? GoogleApiFailure.of(e) : e);
    }
}

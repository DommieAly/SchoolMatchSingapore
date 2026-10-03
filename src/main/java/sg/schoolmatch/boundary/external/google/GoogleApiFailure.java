package sg.schoolmatch.boundary.external.google;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import sg.schoolmatch.error.ExternalFailureLog;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cause of the {@code ExternalServiceUnavailableException} a live Google client throws. Its message says what went
 * wrong, safe for a log line ({@link ExternalFailureLog.Described}); the original exception is its cause.
 * <ul>
 *   <li>HTTP 4xx: {@code Google refused the request: HTTP 403, PERMISSION_DENIED, API_KEY_INVALID: <message>},
 *       with Google's error status, ErrorInfo reason and message when the answer has them
 *       ({@code {"error": {...}}}, or {@code [{"error": {...}}]} from computeRouteMatrix).</li>
 *   <li>HTTP 5xx: {@code Google failed: HTTP 500} (plus Google's status and message when present).</li>
 *   <li>Timeout or network error: {@code Google failed: no answer (ResourceAccessException; SocketTimeoutException:
 *       Read timed out)}; an answer that cannot be read: {@code Google failed: unreadable answer (…)}.</li>
 * </ul>
 * Never the key (it is only in a request header, which is not read here) and never a full URL.
 */
public final class GoogleApiFailure extends RuntimeException implements ExternalFailureLog.Described {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_MESSAGE = 300;

    private GoogleApiFailure(String message, RestClientException cause) {
        super(message, cause);
    }

    /** The safe description of {@code e}, with {@code e} as the cause. */
    public static GoogleApiFailure of(RestClientException e) {
        try {
            return new GoogleApiFailure(describe(e), e);
        } catch (RuntimeException ex) {
            // The log text must never replace the failure itself (callers turn it into "temporarily unavailable").
            return new GoogleApiFailure("Google failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String describe(RestClientException e) {
        if (e instanceof RestClientResponseException http) {
            boolean refused = http.getStatusCode().is4xxClientError();
            return (refused ? "Google refused the request: " : "Google failed: ") + "HTTP "
                    + http.getStatusCode().value() + googleError(http);
        }
        StringBuilder text = new StringBuilder("Google failed: ")
                .append(e instanceof ResourceAccessException ? "no answer (" : "unreadable answer (")
                .append(e.getClass().getSimpleName());
        Throwable root = e;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 10; depth++) {
            root = root.getCause();
        }
        if (root != e) {
            text.append("; ").append(root.getClass().getSimpleName());
            if (root.getMessage() != null && !root.getMessage().isBlank()) {
                text.append(": ").append(ExternalFailureLog.clean(root.getMessage(), 160));
            }
        }
        return text.append(')').toString();
    }

    /** ", STATUS, REASON: message" from Google's error answer; "" when the answer has none of them. */
    private static String googleError(RestClientResponseException http) {
        JsonNode error;
        try {
            JsonNode body = JSON.readTree(http.getResponseBodyAsString());
            if (body != null && body.isArray() && body.size() > 0) {
                body = body.get(0);
            }
            error = body == null ? null : body.get("error");
        } catch (JacksonException | IllegalArgumentException ex) {
            return "";
        }
        if (error == null || !error.isObject()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        String status = error.path("status").asString("");
        if (!status.isBlank()) {
            text.append(", ").append(ExternalFailureLog.clean(status, 60));
        }
        JsonNode details = error.path("details");
        for (int i = 0; details.isArray() && i < details.size(); i++) {
            String reason = details.get(i).path("reason").asString("");
            if (!reason.isBlank()) {
                text.append(", ").append(ExternalFailureLog.clean(reason, 60));
                break;
            }
        }
        String message = error.path("message").asString("");
        if (!message.isBlank()) {
            text.append(": ").append(ExternalFailureLog.clean(message, MAX_MESSAGE));
        }
        return text.toString();
    }
}

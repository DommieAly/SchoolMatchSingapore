package sg.schoolmatch.error;

/** One visitor address sent too many requests (VisitorLimitInterceptor, DC-90). Shown as a 429 page. */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super(retryAfterSeconds <= 60
                ? "Too many requests from your network. Please wait a minute and try again."
                : "Too many requests from your network. Please try again in about "
                        + (retryAfterSeconds + 59) / 60 + " minutes.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Seconds until the limit that was reached starts again, for the {@code Retry-After} header. */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

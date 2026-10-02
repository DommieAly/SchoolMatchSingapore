package sg.schoolmatch.error;

/**
 * An external service (Google, OneMap, data.gov.sg) failed or its budget is spent.
 * The page shows "temporarily unavailable" (NFR-USE-03).
 */
public class ExternalServiceUnavailableException extends RuntimeException {

    private final String service;

    public ExternalServiceUnavailableException(String service, Throwable cause) {
        super(service + " is temporarily unavailable", cause);
        this.service = service;
    }

    /** Short service name, e.g. "Google Routes", "OneMap". */
    public String getService() {
        return service;
    }
}

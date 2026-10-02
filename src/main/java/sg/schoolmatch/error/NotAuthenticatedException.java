package sg.schoolmatch.error;

/** The session is missing, expired or logged out; the user must log in (FR-LOGIN, NFR-SEC-05). */
public class NotAuthenticatedException extends RuntimeException {

    public NotAuthenticatedException() {
        super("Not logged in");
    }

    public NotAuthenticatedException(String message) {
        super(message);
    }
}

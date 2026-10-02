package sg.schoolmatch.error;

/** A requested school, facility or other record does not exist. Shown as a 404 page. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}

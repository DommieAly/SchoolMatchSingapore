package sg.schoolmatch.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User input failed validation. Carries one message per form field (field name → message) so the page
 * can highlight the fields (NFR-USE-03). Thrown by controls, caught by the XxxUI handlers.
 */
public class InvalidInputException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public InvalidInputException(Map<String, String> fieldErrors) {
        super("Invalid input: " + fieldErrors);
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    /** One field error, e.g. {@code new InvalidInputException("q", "Enter 1–100 characters")}. */
    public InvalidInputException(String field, String message) {
        this(Map.of(field, message));
    }

    /** Field name → message, in insertion order. */
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}

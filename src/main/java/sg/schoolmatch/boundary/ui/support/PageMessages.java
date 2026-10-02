package sg.schoolmatch.boundary.ui.support;

/**
 * Model attribute names read by {@code templates/fragments/messages.html}.
 * <ul>
 *   <li>{@link #FLASH_MESSAGE} — info banner, usually set as a flash attribute before a redirect</li>
 *   <li>{@link #FLASH_ERROR} — red banner</li>
 *   <li>{@link #FIELD_ERRORS} — {@code Map<String, String>} field → message, e.g. from
 *       {@code InvalidInputException.getFieldErrors()} (NFR-USE-03)</li>
 * </ul>
 */
public final class PageMessages {

    public static final String FLASH_MESSAGE = "flashMessage";
    public static final String FLASH_ERROR = "flashError";
    public static final String FIELD_ERRORS = "fieldErrors";

    private PageMessages() {
    }
}

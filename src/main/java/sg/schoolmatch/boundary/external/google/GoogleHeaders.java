package sg.schoolmatch.boundary.external.google;

/** HTTP header names of the Google Maps Platform web services (Routes API, Places API (New)). */
final class GoogleHeaders {

    /** The server key. Header only: never in a URL, a log line or an exception message. */
    static final String API_KEY = "X-Goog-Api-Key";

    /** Comma-separated response fields; Google bills by the fields asked for, and requires the header. */
    static final String FIELD_MASK = "X-Goog-FieldMask";

    private GoogleHeaders() {
    }
}

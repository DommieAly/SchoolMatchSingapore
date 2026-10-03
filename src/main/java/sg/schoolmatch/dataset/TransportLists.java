package sg.schoolmatch.dataset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import sg.schoolmatch.dataset.CuratedCsvReader.TransportOverride;

/**
 * DC-84 (snapshot format 2, database design 6.1): turns MOE's bus and MRT texts ({@code bus_desc},
 * {@code mrt_desc}) into lists, one bus service or one MRT station per element, in the published order.
 * <ul>
 *   <li>{@link #split}: split on commas, trim each piece, drop empty and repeated pieces.</li>
 *   <li>{@link #problem}: the rule every element must pass (the importer does not check it; the validator's
 *       {@code transport-list} rule does, and names the school and the element).</li>
 *   <li>A text with an element that fails needs a row in {@code data/curated/transport-overrides.csv}. The row is
 *       used only while MOE's text is still exactly its {@code published_text}; otherwise the importer logs
 *       {@link ImportLog#TRANSPORT_OVERRIDE_STALE} and uses the plain split, which the validator then refuses.</li>
 * </ul>
 * {@link #join} rebuilds the display text ({@code School.busInfo}, {@code School.nearestMrt}).
 */
public class TransportLists {

    /** One bus service: {@code 904}, {@code 70M}, {@code 74e}, {@code CT18}. */
    public static final Pattern BUS_SERVICE = Pattern.compile("^[A-Z]{0,2}[0-9]{1,3}[A-Za-z]?$");

    /**
     * Text that shows an MRT element is not one station name (compared ignoring case). The comma is not in the
     * design's list; it is added because the database stores one station per row (CHECK "no comma in a name").
     */
    static final List<String> MRT_FORBIDDEN = List.of(":", " - ", " and ", "&", "campus", ",");

    /** {@link ImportLog} counter: overrides used in one import. */
    public static final String OVERRIDES_APPLIED = "transport-overrides-applied";

    private static final String SEPARATOR = ", ";

    /** Bus services or MRT stations; {@link #csvValue()} is the {@code kind} column of the overrides file. */
    public enum Kind {
        BUS("bus", "bus service"),
        MRT("mrt", "MRT station");

        private final String csvValue;
        private final String label;

        Kind(String csvValue, String label) {
            this.csvValue = csvValue;
            this.label = label;
        }

        public String csvValue() {
            return csvValue;
        }

        /** "bus service" / "MRT station", for messages. */
        public String label() {
            return label;
        }

        /** {@code bus} or {@code mrt}, ignoring case. */
        public static Kind fromCsv(String value) {
            for (Kind kind : values()) {
                if (kind.csvValue.equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("kind '" + value + "' must be bus or mrt");
        }

        @Override
        public String toString() {
            return csvValue;
        }
    }

    private final Map<String, TransportOverride> overrides = new LinkedHashMap<>();   // "code|kind" → row

    /** With the rows of {@code transport-overrides.csv}; for one school and kind, the first row is used. */
    public TransportLists(Collection<TransportOverride> overrides) {
        overrides.forEach(o -> this.overrides.putIfAbsent(key(o.schoolCode(), o.kind()), o));
    }

    /**
     * The elements of one school's published text: the override's elements while its {@code published_text} equals
     * {@code text}, else {@link #split}. A stale override is logged; an applied one is counted.
     *
     * @param text MOE's text as the importer cleans it (trimmed, single spaces); null when there is none
     */
    public List<String> elements(Kind kind, String schoolCode, String text, ImportLog log) {
        TransportOverride override = overrides.get(key(schoolCode, kind));
        if (override != null) {
            if (override.publishedText().equals(text)) {
                log.increment(OVERRIDES_APPLIED);
                log.info(CuratedCsvReader.TRANSPORT_OVERRIDES + " used for " + schoolCode + " " + kind);
                return override.elements();
            }
            log.warn(ImportLog.TRANSPORT_OVERRIDE_STALE, schoolCode + " " + kind + ": MOE's text is now \""
                    + text + "\", not the published_text of its row in " + CuratedCsvReader.TRANSPORT_OVERRIDES
                    + " (\"" + override.publishedText() + "\"); the row was not used. Update or remove it.");
        }
        return split(text);
    }

    /** Split on commas, trim each piece, drop empty and repeated pieces (published order kept). */
    public static List<String> split(String text) {
        List<String> elements = new ArrayList<>();
        if (text == null) {
            return elements;
        }
        for (String piece : text.split(",")) {
            String element = piece.trim();
            if (!element.isEmpty() && !elements.contains(element)) {
                elements.add(element);
            }
        }
        return elements;
    }

    /** The elements joined with {@code ", "} (the Lab 2 display text); null when there are none. */
    public static String join(List<String> elements) {
        return elements == null || elements.isEmpty() ? null : String.join(SEPARATOR, elements);
    }

    /**
     * Why {@code element} is not one bus service or one MRT station, or null when it is.
     * Used by the validator's {@code transport-list} rule.
     * <p>An element with leading or trailing white space is refused: the loader writes each element trimmed, so
     * "TAMPINES MRT" and "TAMPINES MRT " would become the same key in {@code school_mrt_station} and the load would
     * fail. Refusing it here keeps "a snapshot that validates always loads", and the JSON in git is exactly what
     * reaches the database (design 6.1). The importer trims every piece, so only a hand edit can cause it.
     */
    public static String problem(Kind kind, String element) {
        if (element == null || element.isBlank()) {
            return "is blank";
        }
        if (!element.equals(element.trim()) || !element.equals(element.strip())) {
            return "has leading or trailing white space";
        }
        if (kind == Kind.BUS) {
            return BUS_SERVICE.matcher(element).matches() ? null : "must match " + BUS_SERVICE.pattern();
        }
        String lower = element.toLowerCase(Locale.ROOT);
        for (String forbidden : MRT_FORBIDDEN) {
            if (lower.contains(forbidden)) {
                return "must be one station name, without '" + forbidden + "'";
            }
        }
        return null;
    }

    private static String key(String schoolCode, Kind kind) {
        return schoolCode + "|" + kind.csvValue();
    }
}

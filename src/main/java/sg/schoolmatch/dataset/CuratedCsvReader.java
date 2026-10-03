package sg.schoolmatch.dataset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the hand-maintained CSV files in {@code data/curated/} for the importer (DC-12, DC-18, DC-21;
 * curation rules in data/README.md). Each file has a header row; values may be quoted ("A, B"), and an empty
 * cell means "unknown". A row that cannot be used is skipped and reported in {@link CuratedData#problems()},
 * so one typo does not stop an import.
 * <p>
 * Folder: {@code app.dataset.curated-dir} (default {@code data/curated}, relative to the repository root).
 */
@Component
public class CuratedCsvReader {

    public static final String SCHOOL_CODES = "school-codes.csv";
    public static final String NAME_ALIASES = "name-aliases.csv";
    public static final String PSLE_RANGES = "psle-ranges.csv";
    public static final String GEOCODE_OVERRIDES = "geocode-overrides.csv";
    public static final String AFFILIATIONS = "affiliations.csv";
    public static final String SUBJECT_EXCLUSIONS = "subject-exclusions.csv";
    /** DC-84: the elements of a bus or MRT text that the plain comma split gets wrong ({@link TransportLists}). */
    public static final String TRANSPORT_OVERRIDES = "transport-overrides.csv";

    /** {@code psle-ranges.csv} track values (DC-18). */
    public static final String TRACK_NON_AFFILIATED = "NON_AFFILIATED";
    public static final String TRACK_AFFILIATED = "AFFILIATED";
    public static final String TRACK_IP = "IP";
    /** DC-82: an Integrated Programme value in SchoolFinder's "Affiliated" column (only Nanyang Girls' High has one). */
    public static final String TRACK_IP_AFFILIATED = "IP_AFFILIATED";
    private static final Set<String> TRACKS = Set.of(TRACK_NON_AFFILIATED, TRACK_AFFILIATED, TRACK_IP,
            TRACK_IP_AFFILIATED);

    private final Path folder;

    public CuratedCsvReader(@Value("${app.dataset.curated-dir:data/curated}") String folder) {
        this.folder = Path.of(folder);
    }

    public Path getFolder() {
        return folder;
    }

    /**
     * Reads all seven files. A missing file reads as empty (and is reported).
     *
     * @throws IllegalStateException when the folder itself does not exist (wrong working directory)
     */
    public CuratedData read() {
        if (!Files.isDirectory(folder)) {
            throw new IllegalStateException("Curated data folder not found: " + folder.toAbsolutePath()
                    + " (run the import from the repository root, or set app.dataset.curated-dir)");
        }
        List<String> problems = new ArrayList<>();
        List<SchoolCodeRow> codes = rows(SCHOOL_CODES, List.of("school_name", "school_code", "source_url"), problems,
                r -> new SchoolCodeRow(required(r, "school_name"), required(r, "school_code"), r.get("source_url")));
        List<NameAlias> aliases = rows(NAME_ALIASES, List.of("dataset", "raw_name", "canonical_name"), problems,
                r -> new NameAlias(required(r, "dataset"), required(r, "raw_name"), required(r, "canonical_name")));
        List<PsleRangeRow> ranges = rows(PSLE_RANGES, List.of("school_code", "admission_year", "posting_group",
                "track", "lower", "upper", "raw_text", "source_url", "entered_by", "checked_by"), problems,
                CuratedCsvReader::toPsleRange);
        List<GeocodeOverride> overrides = rows(GEOCODE_OVERRIDES,
                List.of("postal_code", "school_code", "latitude", "longitude", "reason"), problems,
                CuratedCsvReader::toGeocodeOverride);
        List<Affiliation> affiliations = rows(AFFILIATIONS, List.of("school_code", "primary_school", "source_url"),
                problems, r -> new Affiliation(required(r, "school_code"), required(r, "primary_school"),
                        r.get("source_url")));
        List<SubjectExclusion> exclusions = rows(SUBJECT_EXCLUSIONS, List.of("school_name", "subject_desc", "reason"),
                problems, r -> new SubjectExclusion(required(r, "school_name"), required(r, "subject_desc"),
                        r.get("reason")));
        List<TransportOverride> transport = rows(TRANSPORT_OVERRIDES,
                List.of("school_code", "kind", "published_text", "elements", "reason"), problems,
                CuratedCsvReader::toTransportOverride);
        return new CuratedData(codes, aliases, ranges, overrides, affiliations, exclusions, transport, problems);
    }

    // ------------------------------------------------------------------ rows → records

    private static PsleRangeRow toPsleRange(Map<String, String> r) {
        String track = required(r, "track").toUpperCase(Locale.ROOT);
        if (!TRACKS.contains(track)) {
            throw new IllegalArgumentException("track '" + track + "' must be one of " + TRACKS);
        }
        return new PsleRangeRow(required(r, "school_code"), wholeNumber(r, "admission_year"),
                wholeNumber(r, "posting_group"), track, wholeNumber(r, "lower"), wholeNumber(r, "upper"),
                r.get("raw_text"), r.get("source_url"), r.get("entered_by"), r.get("checked_by"));
    }

    private static GeocodeOverride toGeocodeOverride(Map<String, String> r) {
        String postal = r.get("postal_code");
        String code = r.get("school_code");
        if (postal == null && code == null) {
            throw new IllegalArgumentException("needs a postal_code or a school_code");
        }
        return new GeocodeOverride(postal, code, number(r, "latitude"), number(r, "longitude"), r.get("reason"));
    }

    /** {@code elements} are separated by ";" and trimmed; empty pieces are dropped, and at least one must remain. */
    private static TransportOverride toTransportOverride(Map<String, String> r) {
        TransportLists.Kind kind = TransportLists.Kind.fromCsv(required(r, "kind"));
        List<String> elements = new ArrayList<>();
        for (String piece : required(r, "elements").split(";")) {
            if (!piece.isBlank()) {
                elements.add(piece.trim());
            }
        }
        if (elements.isEmpty()) {
            throw new IllegalArgumentException("elements has no element (separate them with ';')");
        }
        return new TransportOverride(required(r, "school_code"), kind, required(r, "published_text"), elements,
                r.get("reason"));
    }

    private static String required(Map<String, String> row, String column) {
        String value = row.get(column);
        if (value == null) {
            throw new IllegalArgumentException(column + " is empty");
        }
        return value;
    }

    private static int wholeNumber(Map<String, String> row, String column) {
        String value = required(row, column);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " '" + value + "' is not a whole number");
        }
    }

    private static double number(Map<String, String> row, String column) {
        String value = required(row, column);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " '" + value + "' is not a number");
        }
    }

    // ------------------------------------------------------------------ CSV

    /** Maps one CSV row (column → value, empty cells null) to a record, or throws IllegalArgumentException. */
    private interface RowMapper<T> {
        T map(Map<String, String> row);
    }

    private <T> List<T> rows(String file, List<String> columns, List<String> problems, RowMapper<T> mapper) {
        Path path = folder.resolve(file);
        if (!Files.isRegularFile(path)) {
            problems.add(file + ": not found in " + folder + " (read as empty)");
            return List.of();
        }
        List<CsvLine> lines;
        try {
            lines = parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
        if (lines.isEmpty()) {
            problems.add(file + ": no header row (read as empty)");
            return List.of();
        }
        List<String> header = lines.getFirst().values().stream()
                .map(h -> h.replace("﻿", "").trim().toLowerCase(Locale.ROOT)).toList();
        if (!header.containsAll(columns)) {
            problems.add(file + ": header must have the columns " + String.join(",", columns) + " but is "
                    + String.join(",", header) + " (rows not used)");
            return List.of();
        }
        List<T> result = new ArrayList<>();
        for (CsvLine line : lines.subList(1, lines.size())) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                String value = i < line.values().size() ? line.values().get(i).trim() : "";
                row.put(header.get(i), value.isEmpty() ? null : value);
            }
            if (row.values().stream().allMatch(v -> v == null)) {
                continue;   // a row of empty cells, e.g. ",,,"
            }
            try {
                result.add(mapper.map(row));
            } catch (IllegalArgumentException e) {
                problems.add(file + " line " + line.number() + ": " + e.getMessage() + " (row skipped)");
            }
        }
        return List.copyOf(result);
    }

    /** One CSV record and the line it starts on (1 = header). */
    record CsvLine(int number, List<String> values) {
    }

    /**
     * Splits CSV text into records (RFC 4180: commas, "quoted, values", "" inside quotes, CRLF or LF).
     * Blank lines are skipped.
     */
    static List<CsvLine> parse(String text) {
        List<CsvLine> lines = new ArrayList<>();
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        boolean lineHasContent = false;
        int lineNumber = 1;
        int recordStart = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    if (c == '\n') {
                        lineNumber++;
                    }
                    value.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                lineHasContent = true;
            } else if (c == ',') {
                values.add(value.toString());
                value.setLength(0);
                lineHasContent = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (lineHasContent || value.length() > 0) {
                    values.add(value.toString());
                    lines.add(new CsvLine(recordStart, List.copyOf(values)));
                }
                values.clear();
                value.setLength(0);
                lineHasContent = false;
                lineNumber++;
                recordStart = lineNumber;
            } else {
                value.append(c);
                if (!Character.isWhitespace(c)) {
                    lineHasContent = true;
                }
            }
        }
        if (lineHasContent || value.length() > 0) {
            values.add(value.toString());
            lines.add(new CsvLine(recordStart, List.copyOf(values)));
        }
        return lines;
    }

    // ------------------------------------------------------------------ records

    /** Everything read from {@code data/curated/}, plus the rows that could not be used. */
    public record CuratedData(
            List<SchoolCodeRow> schoolCodes,
            List<NameAlias> aliases,
            List<PsleRangeRow> psleRanges,
            List<GeocodeOverride> geocodeOverrides,
            List<Affiliation> affiliations,
            List<SubjectExclusion> subjectExclusions,
            List<TransportOverride> transportOverrides,
            List<String> problems) {

        public CuratedData {
            schoolCodes = List.copyOf(schoolCodes);
            aliases = List.copyOf(aliases);
            psleRanges = List.copyOf(psleRanges);
            geocodeOverrides = List.copyOf(geocodeOverrides);
            affiliations = List.copyOf(affiliations);
            subjectExclusions = List.copyOf(subjectExclusions);
            transportOverrides = List.copyOf(transportOverrides);
            problems = List.copyOf(problems);
        }
    }

    /**
     * {@code school-codes.csv}: data.gov.sg name → the app's frozen school code (our own id, a slug of the name made
     * by {@link NameNormaliser#slug}; not the MOE SchoolFinder slug, which {@code sourceUrl} holds).
     */
    public record SchoolCodeRow(String schoolName, String schoolCode, String sourceUrl) {
    }

    /** {@code name-aliases.csv}: a dataset's raw name → the canonical (schools dataset) name. */
    public record NameAlias(String dataset, String rawName, String canonicalName) {
    }

    /** {@code psle-ranges.csv}: one range as typed from MOE SchoolFinder (track upper-cased). */
    public record PsleRangeRow(String schoolCode, int admissionYear, int postingGroup, String track,
                               int lower, int upper, String rawText, String sourceUrl,
                               String enteredBy, String checkedBy) {

        /** Used only when a second team member checked it (data/README.md curation rules). */
        public boolean isChecked() {
            return checkedBy != null && !checkedBy.isBlank()
                    && (enteredBy == null || !checkedBy.trim().equalsIgnoreCase(enteredBy.trim()));
        }
    }

    /** {@code geocode-overrides.csv}: the coordinate to use for a postal code or school code. */
    public record GeocodeOverride(String postalCode, String schoolCode, double latitude, double longitude,
                                  String reason) {
    }

    /** {@code affiliations.csv}: one affiliated primary school of a secondary school (DC-21). */
    public record Affiliation(String schoolCode, String primarySchool, String sourceUrl) {
    }

    /**
     * {@code subject-exclusions.csv}: a row of the Subjects Offered dataset that is not a real subject (e.g. a
     * placeholder such as "Test Subject"), so the importer leaves it out (DC-76). {@code schoolName} and
     * {@code subjectDesc} are as published; {@code reason} says why and when it was checked.
     */
    public record SubjectExclusion(String schoolName, String subjectDesc, String reason) {
    }

    /**
     * {@code transport-overrides.csv} (DC-84): the elements to use for one school's bus or MRT text while MOE's text
     * is exactly {@code publishedText} (as the importer cleans it: trimmed, single spaces). {@code elements} are in
     * published order; {@code reason} says what is wrong with the text.
     */
    public record TransportOverride(String schoolCode, TransportLists.Kind kind, String publishedText,
                                    List<String> elements, String reason) {

        public TransportOverride {
            elements = List.copyOf(elements);
        }
    }
}

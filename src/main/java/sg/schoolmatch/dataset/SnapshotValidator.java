package sg.schoolmatch.dataset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.School;

/**
 * Checks a {@link LoadedSnapshot} before the app serves it (FR-DATA-01, FR-DATA-06, NFR-DATA-01, NFR-DATA-02).
 * Errors make the snapshot FAILED (the app refuses to start); warnings are allowed.
 * Each message starts with a rule id below. Most error rules have a broken fixture in
 * {@code src/test/resources/fixtures/snapshot-broken/<rule>/}; {@code bad-postal-code}, {@code bad-district} and
 * {@code duplicate-element} (DC-85) are covered by unit tests in {@code SnapshotValidatorTest} only, and the format
 * check ({@code manifest} rule, fixture {@code format-1/}) has its own test.
 */
@Component
public class SnapshotValidator {

    // Error rules
    public static final String MANIFEST = "manifest";
    public static final String SCHOOL_COUNT = "school-count";
    public static final String INVALID_CODE = "invalid-code";
    public static final String DUPLICATE_CODE = "duplicate-code";
    public static final String MISSING_NAME = "missing-name";
    public static final String BAD_COORDINATE = "bad-coordinate";
    public static final String UNKNOWN_PLANNING_AREA = "unknown-planning-area";
    public static final String BAD_PSLE_RANGE = "bad-psle-range";
    public static final String DUPLICATE_PSLE_RANGE = "duplicate-psle-range";
    /** DC-84: an element of busServices / mrtStations that is not one bus service or one MRT station. */
    public static final String TRANSPORT_LIST = "transport-list";
    /** DC-85: a range's moeText that MoeRangeText cannot store as parts (other form, or other numbers). */
    public static final String MOE_TEXT_FORMAT = "moe-text-format";
    /** DC-85: a text longer than its database column ({@link #MAX_LENGTHS}). */
    public static final String TOO_LONG = "too-long";
    /** DC-85: a postal code that is not 6 digits. */
    public static final String BAD_POSTAL_CODE = "bad-postal-code";
    /** DC-85: two schools of one snapshot with the same name (ignoring case and outer spaces). */
    public static final String DUPLICATE_NAME = "duplicate-name";
    /** DC-85: a planning area without a code or name, or a code or name used twice. */
    public static final String BAD_DISTRICT = "bad-district";
    /** DC-85: a CCA, programme or affiliated primary school listed twice for one school. */
    public static final String DUPLICATE_ELEMENT = "duplicate-element";

    // Warning rules
    public static final String NO_CCAS = "no-ccas";
    public static final String NO_PSLE_RANGES = "no-psle-ranges";

    /** A full (importer) snapshot must hold this many schools; the seed is exempt. */
    public static final int FULL_MIN_SCHOOLS = 140;
    public static final int FULL_MAX_SCHOOLS = 160;
    public static final int MIN_SCORE = 4;
    public static final int MAX_SCORE = 32;

    /** AL scores (PSLE 4–32) exist from the 2022 admission year (ck_indicative_psle_score_range_year). */
    public static final int MIN_ADMISSION_YEAR = 2022;
    public static final int MAX_ADMISSION_YEAR = 2100;

    /**
     * DC-85: the length of every database column that holds snapshot text, as {@code table.column → characters}
     * (src/main/resources/db/migration/V2__school_dataset.sql). The validator refuses a longer value, so a snapshot
     * that validates always loads; SchemaLimitsTest checks these numbers against the migrated database.
     */
    public static final Map<String, Integer> MAX_LENGTHS = Map.ofEntries(
            Map.entry("dataset_version.dataset_version", 40),
            Map.entry("dataset_version.notes", 2000),
            Map.entry("dataset_source.source_id", 80),
            Map.entry("dataset_source.source_name", 300),
            Map.entry("dataset_warning.message", 1000),
            Map.entry("district.planning_area_code", 10),
            Map.entry("district.planning_area_name", 60),
            Map.entry("district.boundary_geojson", 100_000),
            Map.entry("school.school_code", 100),
            Map.entry("school.school_name", 200),
            Map.entry("school.address", 200),
            Map.entry("school.postal_code", 6),       // bounded by the bad-postal-code pattern
            Map.entry("school.telephone", 40),
            Map.entry("school.website", 200),
            Map.entry("school.email", 254),
            Map.entry("school.school_type", 60),
            Map.entry("school.session_type", 40),
            Map.entry("school.school_nature", 40),
            Map.entry("school.planning_area_code", 10),
            Map.entry("school_cca.cca_name", 100),
            Map.entry("school_programme.programme_name", 100),
            Map.entry("school_affiliated_primary.primary_school_name", 120),
            Map.entry("school_bus_service.service_no", 10),
            Map.entry("school_mrt_station.station_name", 80));

    private static final Pattern SCHOOL_CODE = Pattern.compile("^[a-z0-9-]+$");
    /** ck_dataset_version_name: the manifest version is the version key in the database. */
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern POSTAL_CODE = Pattern.compile("^[0-9]{6}$");
    private static final Set<Integer> POSTING_GROUPS = Set.of(1, 2, 3);

    public ValidationReport validate(LoadedSnapshot s) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        SnapshotManifest manifest = s.manifest();
        if (manifest.version() == null || manifest.version().isBlank()) {
            errors.add(MANIFEST + ": version is missing");
        }
        // DC-84: format 1 had busInfo / nearestMrt texts and no arrays; reading it would show no buses or MRT.
        int format = manifest.formatVersionOrDefault();
        if (format != SnapshotManifest.FORMAT_VERSION) {
            errors.add(MANIFEST + ": formatVersion " + format + " is not supported; this app reads format "
                    + SnapshotManifest.FORMAT_VERSION + " (busServices and mrtStations arrays). "
                    + (format < SnapshotManifest.FORMAT_VERSION
                            ? "Point ACTIVE at a format-2 snapshot, or re-import (data/README.md)."
                            : "This snapshot was written by a newer importer."));
        }
        if (!SnapshotManifest.KIND_SEED.equalsIgnoreCase(manifest.kind()) && !manifest.isFull()) {
            errors.add(MANIFEST + ": kind must be 'seed' or 'full', got '" + manifest.kind() + "'");
        }
        checkManifest(manifest, errors);
        checkDistricts(s.districts(), errors);
        int count = s.records().size();
        if (manifest.isFull() && (count < FULL_MIN_SCHOOLS || count > FULL_MAX_SCHOOLS)) {
            errors.add(SCHOOL_COUNT + ": " + count + " schools, expected " + FULL_MIN_SCHOOLS + "–" + FULL_MAX_SCHOOLS);
        }

        Set<String> districtCodes = s.districts().stream().map(District::getPlanningAreaCode)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, Integer> codeCounts = new LinkedHashMap<>();
        Map<String, List<String>> codesByName = new LinkedHashMap<>();

        for (int i = 0; i < s.records().size(); i++) {
            SchoolRecord r = s.records().get(i);
            String label = r.schoolCode() == null || r.schoolCode().isBlank() ? "school #" + (i + 1) : r.schoolCode();

            if (r.schoolCode() == null || !SCHOOL_CODE.matcher(r.schoolCode()).matches()) {
                errors.add(INVALID_CODE + ": " + label + ": schoolCode must match " + SCHOOL_CODE.pattern());
            } else {
                codeCounts.merge(r.schoolCode(), 1, Integer::sum);
            }
            if (r.name() == null || r.name().isBlank()) {
                errors.add(MISSING_NAME + ": " + label + ": name is missing");
            } else {
                codesByName.computeIfAbsent(r.name().trim().toLowerCase(Locale.ROOT), n -> new ArrayList<>())
                        .add(label);
            }
            if (r.postalCode() != null && !r.postalCode().isBlank()
                    && !POSTAL_CODE.matcher(r.postalCode().trim()).matches()) {
                errors.add(BAD_POSTAL_CODE + ": " + label + ": postalCode '" + r.postalCode() + "' is not 6 digits");
            }
            checkSchoolLengths(r, label, errors);
            checkNoRepeats("CCA", r.ccas(), label, errors);
            checkNoRepeats("programme", r.programmes(), label, errors);
            checkNoRepeats("affiliated primary school", r.affiliatedPrimarySchools(), label, errors);
            checkCoordinate(r, label, errors);
            if (r.planningAreaCode() == null || !districtCodes.contains(r.planningAreaCode())) {
                errors.add(UNKNOWN_PLANNING_AREA + ": " + label + ": planningAreaCode '" + r.planningAreaCode()
                        + "' is not in districts.geojson");
            }
            checkRanges(r, label, errors);
            checkTransport(TransportLists.Kind.MRT, r.mrtStations(), label, errors);
            checkTransport(TransportLists.Kind.BUS, r.busServices(), label, errors);

            if (r.ccas().isEmpty()) {
                warnings.add(NO_CCAS + ": " + label + " has no CCAs");
            }
            if (r.scoreRanges().isEmpty()) {
                warnings.add(NO_PSLE_RANGES + ": " + label + " has no PSLE score ranges");
            }
        }
        codeCounts.forEach((code, n) -> {
            if (n > 1) {
                errors.add(DUPLICATE_CODE + ": '" + code + "' appears " + n + " times");
            }
        });
        codesByName.forEach((name, codes) -> {
            if (codes.size() > 1) {
                errors.add(DUPLICATE_NAME + ": " + String.join(", ", codes) + " have the same name '" + name + "'");
            }
        });
        return new ValidationReport(errors, warnings);
    }

    /**
     * DC-85: what the database needs of the manifest: a version usable as a key, both dates, and sources with an id
     * and a name (each id once), and texts that fit their columns.
     */
    private static void checkManifest(SnapshotManifest m, List<String> errors) {
        if (m.version() != null && !m.version().isBlank() && !VERSION.matcher(m.version()).matches()) {
            errors.add(MANIFEST + ": version '" + m.version() + "' may hold only letters, digits, '.', '_' and '-'");
        }
        if (m.effectiveDate() == null) {
            errors.add(MANIFEST + ": effectiveDate is missing");
        }
        if (m.importedAt() == null) {
            errors.add(MANIFEST + ": importedAt is missing");
        }
        Set<String> sourceIds = new HashSet<>();
        for (int i = 0; i < m.sources().size(); i++) {
            SnapshotManifest.Source source = m.sources().get(i);
            String label = "source #" + (i + 1);
            if (source == null || source.datasetId() == null || source.datasetId().isBlank()
                    || source.name() == null || source.name().isBlank()) {
                errors.add(MANIFEST + ": " + label + " needs a datasetId and a name");
                continue;
            }
            if (!sourceIds.add(source.datasetId().trim())) {
                errors.add(MANIFEST + ": " + label + ": datasetId '" + source.datasetId() + "' is listed twice");
            }
            checkLength(label, "datasetId", source.datasetId(), "dataset_source.source_id", errors);
            checkLength(label, "name", source.name(), "dataset_source.source_name", errors);
        }
        checkLength("manifest", "version", m.version(), "dataset_version.dataset_version", errors);
        checkLength("manifest", "notes", m.notes(), "dataset_version.notes", errors);
        for (int i = 0; i < m.warnings().size(); i++) {
            checkLength("manifest", "warning #" + (i + 1), m.warnings().get(i), "dataset_warning.message", errors);
        }
    }

    /** DC-85: each planning area has a code and a name, each used once, and they fit their columns. */
    private static void checkDistricts(List<District> districts, List<String> errors) {
        Set<String> codes = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < districts.size(); i++) {
            District d = districts.get(i);
            String code = d.getPlanningAreaCode();
            String label = code == null ? "planning area #" + (i + 1) : "planning area " + code;
            if (code == null || d.getPlanningAreaName() == null) {
                errors.add(BAD_DISTRICT + ": " + label + " needs a planningAreaCode and a planningAreaName");
                continue;
            }
            if (!codes.add(code)) {
                errors.add(BAD_DISTRICT + ": planningAreaCode '" + code + "' is used twice");
            }
            if (!names.add(d.getPlanningAreaName())) {
                errors.add(BAD_DISTRICT + ": planningAreaName '" + d.getPlanningAreaName() + "' is used twice");
            }
            checkLength(label, "planningAreaCode", code, "district.planning_area_code", errors);
            checkLength(label, "planningAreaName", d.getPlanningAreaName(), "district.planning_area_name", errors);
            checkLength(label, "boundary", d.getBoundaryGeoJson(), "district.boundary_geojson", errors);
        }
    }

    /** DC-85: every text of a school fits its database column. */
    private static void checkSchoolLengths(SchoolRecord r, String label, List<String> errors) {
        checkLength(label, "schoolCode", r.schoolCode(), "school.school_code", errors);
        checkLength(label, "name", r.name(), "school.school_name", errors);
        checkLength(label, "address", r.address(), "school.address", errors);
        checkLength(label, "telephone", r.telephone(), "school.telephone", errors);
        checkLength(label, "website", r.website(), "school.website", errors);
        checkLength(label, "email", r.email(), "school.email", errors);
        checkLength(label, "schoolType", r.schoolType(), "school.school_type", errors);
        checkLength(label, "sessionType", r.sessionType(), "school.session_type", errors);
        checkLength(label, "schoolNature", r.schoolNature(), "school.school_nature", errors);
        checkLength(label, "planningAreaCode", r.planningAreaCode(), "school.planning_area_code", errors);
        r.ccas().forEach(v -> checkLength(label, "CCA", v, "school_cca.cca_name", errors));
        r.programmes().forEach(v -> checkLength(label, "programme", v, "school_programme.programme_name", errors));
        r.affiliatedPrimarySchools().forEach(v -> checkLength(label, "affiliated primary school", v,
                "school_affiliated_primary.primary_school_name", errors));
        r.busServices().forEach(v -> checkLength(label, "bus service", v, "school_bus_service.service_no", errors));
        r.mrtStations().forEach(v -> checkLength(label, "MRT station", v, "school_mrt_station.station_name", errors));
    }

    private static void checkLength(String label, String field, String value, String column, List<String> errors) {
        int max = MAX_LENGTHS.get(column);
        if (value != null && value.trim().length() > max) {
            errors.add(TOO_LONG + ": " + label + ": " + field + " has " + value.trim().length()
                    + " characters; the database column " + column + " holds " + max);
        }
    }

    /** DC-85: the database keys these lists by school and value, so a value may appear once (after trimming). */
    private static void checkNoRepeats(String what, List<String> values, String label, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !seen.add(value.trim())) {
                errors.add(DUPLICATE_ELEMENT + ": " + label + ": " + what + " '" + value.trim() + "' is listed twice");
            }
        }
    }

    private static void checkCoordinate(SchoolRecord r, String label, List<String> errors) {
        if (r.latitude() == null || r.longitude() == null) {
            errors.add(BAD_COORDINATE + ": " + label + ": coordinate is missing");
            return;
        }
        boolean inSingapore;
        try {
            inSingapore = new Coordinate(r.latitude(), r.longitude()).isWithinSingapore();
        } catch (IllegalArgumentException e) {
            inSingapore = false;
        }
        if (!inSingapore) {
            errors.add(BAD_COORDINATE + ": " + label + ": (" + r.latitude() + ", " + r.longitude()
                    + ") is outside Singapore");
        }
    }

    /**
     * DC-84: each element is one bus service or one MRT station ({@link TransportLists#problem}), and none is
     * repeated (the database keys each list by school and element). {@code problem} refuses outer white space, so
     * an element that reaches the repeat check is exactly the text the loader writes.
     */
    private static void checkTransport(TransportLists.Kind kind, List<String> elements, String label,
                                       List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (String element : elements) {
            String problem = TransportLists.problem(kind, element);
            if (problem == null && !seen.add(element)) {
                problem = "is listed twice";
            }
            if (problem != null) {
                errors.add(TRANSPORT_LIST + ": " + label + ": " + kind.label() + " '" + element + "' " + problem
                        + " (add a row to data/curated/" + CuratedCsvReader.TRANSPORT_OVERRIDES + " and re-import)");
            }
        }
    }

    /**
     * DC-21/22: ranges are never merged, so each (year, PG, affiliated) appears at most once per school.
     * DC-77: an Integrated Programme range is counted apart from the others (it may share year and PG3 with a
     * non-IP range), and it must be PG3, since it is used only as the PG3 fallback. DC-82: it may be affiliated
     * (one affiliated and one non-affiliated IP range per year).
     */
    private static void checkRanges(SchoolRecord r, String label, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (ScoreRangeRecord range : r.scoreRanges()) {
            if (!range.isComplete()) {
                errors.add(BAD_PSLE_RANGE + ": " + label + ": range has a missing value " + range);
                continue;
            }
            if (range.lowerScore() > range.upperScore()
                    || range.lowerScore() < MIN_SCORE || range.upperScore() > MAX_SCORE
                    || !POSTING_GROUPS.contains(range.postingGroup())) {
                errors.add(BAD_PSLE_RANGE + ": " + label + ": need " + MIN_SCORE + " ≤ lower ≤ upper ≤ " + MAX_SCORE
                        + " and PG 1–3, got " + range);
            }
            if (range.admissionYear() < MIN_ADMISSION_YEAR || range.admissionYear() > MAX_ADMISSION_YEAR) {
                errors.add(BAD_PSLE_RANGE + ": " + label + ": admission year must be " + MIN_ADMISSION_YEAR + "–"
                        + MAX_ADMISSION_YEAR + " (AL scores), got " + range);
            }
            String textProblem = MoeRangeText.problem(range.moeText(), range.lowerScore(), range.upperScore());
            if (textProblem != null) {
                errors.add(MOE_TEXT_FORMAT + ": " + label + ": " + range.admissionYear() + " PG" + range.postingGroup()
                        + ": " + textProblem);
            }
            if (range.integratedProgramme() && range.postingGroup() != School.IP_POSTING_GROUP) {
                errors.add(BAD_PSLE_RANGE + ": " + label + ": an Integrated Programme range must be PG"
                        + School.IP_POSTING_GROUP + ", got " + range);
            }
            String key = range.admissionYear() + "/PG" + range.postingGroup() + "/" + range.affiliated()
                    + (range.integratedProgramme() ? "/IP" : "");
            if (!seen.add(key)) {
                errors.add(DUPLICATE_PSLE_RANGE + ": " + label + ": more than one range for " + key);
            }
        }
    }
}

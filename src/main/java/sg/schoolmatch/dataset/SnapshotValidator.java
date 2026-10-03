package sg.schoolmatch.dataset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Each message starts with a rule id below; each error rule has a broken fixture in
 * {@code src/test/resources/fixtures/snapshot-broken/<rule>/}.
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

    // Warning rules
    public static final String NO_CCAS = "no-ccas";
    public static final String NO_PSLE_RANGES = "no-psle-ranges";

    /** A full (importer) snapshot must hold this many schools; the seed is exempt. */
    public static final int FULL_MIN_SCHOOLS = 140;
    public static final int FULL_MAX_SCHOOLS = 160;
    public static final int MIN_SCORE = 4;
    public static final int MAX_SCORE = 32;

    private static final Pattern SCHOOL_CODE = Pattern.compile("^[a-z0-9-]+$");
    private static final Set<Integer> POSTING_GROUPS = Set.of(1, 2, 3);

    public ValidationReport validate(LoadedSnapshot s) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        SnapshotManifest manifest = s.manifest();
        if (manifest.version() == null || manifest.version().isBlank()) {
            errors.add(MANIFEST + ": version is missing");
        }
        if (!SnapshotManifest.KIND_SEED.equalsIgnoreCase(manifest.kind()) && !manifest.isFull()) {
            errors.add(MANIFEST + ": kind must be 'seed' or 'full', got '" + manifest.kind() + "'");
        }
        int count = s.records().size();
        if (manifest.isFull() && (count < FULL_MIN_SCHOOLS || count > FULL_MAX_SCHOOLS)) {
            errors.add(SCHOOL_COUNT + ": " + count + " schools, expected " + FULL_MIN_SCHOOLS + "–" + FULL_MAX_SCHOOLS);
        }

        Set<String> districtCodes = s.districts().stream().map(District::getPlanningAreaCode)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, Integer> codeCounts = new LinkedHashMap<>();

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
            }
            checkCoordinate(r, label, errors);
            if (r.planningAreaCode() == null || !districtCodes.contains(r.planningAreaCode())) {
                errors.add(UNKNOWN_PLANNING_AREA + ": " + label + ": planningAreaCode '" + r.planningAreaCode()
                        + "' is not in districts.geojson");
            }
            checkRanges(r, label, errors);

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
        return new ValidationReport(errors, warnings);
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

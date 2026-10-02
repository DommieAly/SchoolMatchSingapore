package sg.schoolmatch.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static sg.schoolmatch.dataset.DatasetTestSupport.MINI;
import static sg.schoolmatch.dataset.DatasetTestSupport.broken;
import static sg.schoolmatch.dataset.DatasetTestSupport.reader;
import static sg.schoolmatch.dataset.DatasetTestSupport.withCodeAndCoordinate;
import static sg.schoolmatch.dataset.DatasetTestSupport.withRanges;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import sg.schoolmatch.entity.school.ValidationStatus;

/** SnapshotValidator: snapshot-mini passes; every broken fixture fails with its own rule. */
class SnapshotValidatorTest {

    private final SnapshotReader reader = reader(Map.of());
    private final SnapshotValidator validator = new SnapshotValidator();
    private final LoadedSnapshot mini = reader.read(MINI);

    @Test
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotValidator-01: snapshot-mini passes with warnings only")
    void miniPasses() {
        ValidationReport report = validator.validate(mini);

        assertThat(report.getErrors()).isEmpty();
        assertThat(report.isUsable()).isTrue();
        assertThat(report.getStatus()).isEqualTo(ValidationStatus.PASSED_WITH_WARNINGS);
        assertThat(report.getWarnings())
                .contains(SnapshotValidator.NO_PSLE_RANGES + ": westwood-secondary-school has no PSLE score ranges");
    }

    static final List<String> BROKEN_RULES = DatasetTestSupport.BROKEN_RULES;

    @ParameterizedTest(name = "TC-SnapshotValidator-02 [{index}]: snapshot-broken/{0} fails with rule {0}")
    @FieldSource("BROKEN_RULES")
    @Tag("FR-DATA-01")
    @Tag("FR-DATA-06")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotValidator-02: each broken fixture fails with exactly its one defect")
    void brokenFixtureFails(String rule) {
        ValidationReport report = validator.validate(reader.read(broken(rule)));

        assertThat(report.getStatus()).isEqualTo(ValidationStatus.FAILED);
        assertThat(report.isUsable()).isFalse();
        assertThat(report.hasError(rule)).as(report.toString()).isTrue();
        assertThat(report.getErrors()).as("exactly one defect per fixture").hasSize(1);
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotValidator-03: a full snapshot must hold 140–160 schools")
    void fullSnapshotNeedsAboutAllSchools() {
        SnapshotManifest m = mini.manifest();
        SnapshotManifest full = new SnapshotManifest(SnapshotManifest.KIND_FULL, m.version(), m.effectiveDate(),
                m.importedAt(), m.sources(), m.validationStatus(), m.warnings(), m.counts(), m.notes());

        ValidationReport report = validator.validate(
                new LoadedSnapshot(mini.location(), full, mini.records(), mini.schools(), mini.districts()));

        assertThat(report.hasError(SnapshotValidator.SCHOOL_COUNT)).isTrue();
    }

    @Test
    @Tag("FR-DATA-01")
    @DisplayName("TC-SnapshotValidator-04: school codes must be lower-case slugs")
    void rejectsBadCode() {
        ValidationReport report = validateWithFirst(withCodeAndCoordinate(first(), "Catholic High", 1.35, 103.84));

        assertThat(report.hasError(SnapshotValidator.INVALID_CODE)).isTrue();
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-SnapshotValidator-05: a school without a coordinate fails")
    void rejectsMissingCoordinate() {
        ValidationReport report = validateWithFirst(withCodeAndCoordinate(first(), first().schoolCode(), null, null));

        assertThat(report.hasError(SnapshotValidator.BAD_COORDINATE)).isTrue();
    }

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-SnapshotValidator-06: PSLE ranges need 4 ≤ lower ≤ upper ≤ 32, PG 1–3 and no duplicates")
    void rejectsBadRanges() {
        ScoreRangeRecord ok = new ScoreRangeRecord(2025, 3, false, 8, 12);

        assertThat(validateWithFirst(withRanges(first(), List.of(ok))).isUsable()).isTrue();
        assertThat(validateWithFirst(withRanges(first(), List.of(ok, ok)))
                .hasError(SnapshotValidator.DUPLICATE_PSLE_RANGE)).isTrue();
        assertThat(validateWithFirst(withRanges(first(), List.of(new ScoreRangeRecord(2025, 4, false, 8, 12))))
                .hasError(SnapshotValidator.BAD_PSLE_RANGE)).isTrue();
        assertThat(validateWithFirst(withRanges(first(), List.of(new ScoreRangeRecord(2025, 3, false, 3, 12))))
                .hasError(SnapshotValidator.BAD_PSLE_RANGE)).isTrue();
        assertThat(validateWithFirst(withRanges(first(), List.of(new ScoreRangeRecord(2025, 3, false, 8, 33))))
                .hasError(SnapshotValidator.BAD_PSLE_RANGE)).isTrue();
        assertThat(validateWithFirst(withRanges(first(), List.of(new ScoreRangeRecord(2025, 3, null, 8, 12))))
                .hasError(SnapshotValidator.BAD_PSLE_RANGE)).isTrue();
    }

    private SchoolRecord first() {
        return mini.records().get(0);
    }

    /** Validates snapshot-mini with its first record replaced. */
    private ValidationReport validateWithFirst(SchoolRecord replacement) {
        List<SchoolRecord> records = new ArrayList<>(mini.records());
        records.set(0, replacement);
        return validator.validate(
                new LoadedSnapshot(mini.location(), mini.manifest(), records, mini.schools(), mini.districts()));
    }
}

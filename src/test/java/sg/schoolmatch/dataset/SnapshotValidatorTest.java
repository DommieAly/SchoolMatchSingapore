package sg.schoolmatch.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static sg.schoolmatch.dataset.DatasetTestSupport.MINI;
import static sg.schoolmatch.dataset.DatasetTestSupport.broken;
import static sg.schoolmatch.dataset.DatasetTestSupport.reader;
import static sg.schoolmatch.dataset.DatasetTestSupport.withCodeAndCoordinate;
import static sg.schoolmatch.dataset.DatasetTestSupport.withFormat;
import static sg.schoolmatch.dataset.DatasetTestSupport.withManifest;
import static sg.schoolmatch.dataset.DatasetTestSupport.withRanges;
import static sg.schoolmatch.dataset.DatasetTestSupport.withTexts;
import static sg.schoolmatch.dataset.DatasetTestSupport.withTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import sg.schoolmatch.entity.school.District;
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
        SnapshotManifest full = new SnapshotManifest(m.formatVersion(), SnapshotManifest.KIND_FULL, m.version(),
                m.effectiveDate(),
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

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-SnapshotValidator-07: an IP range (PG3, non-affiliated) may sit next to a PG3 range of the same year; two IP ranges of one year are duplicates")
    void integratedProgrammeRanges() {
        ScoreRangeRecord pg3 = new ScoreRangeRecord(2025, 3, false, 6, 8);
        ScoreRangeRecord ip = new ScoreRangeRecord(2025, 3, false, 4, 7, true);

        assertThat(validateWithFirst(withRanges(first(), List.of(pg3, ip))).getErrors()).isEmpty();
        assertThat(validateWithFirst(withRanges(first(), List.of(ip))).getErrors()).isEmpty();
        assertThat(validateWithFirst(withRanges(first(), List.of(ip, new ScoreRangeRecord(2025, 3, false, 4, 6, true))))
                .hasError(SnapshotValidator.DUPLICATE_PSLE_RANGE)).isTrue();
        // An IP range is used only as the PG3 fallback, so another posting group is a typo.
        assertThat(validateWithFirst(withRanges(first(), List.of(new ScoreRangeRecord(2025, 2, false, 4, 7, true))))
                .hasError(SnapshotValidator.BAD_PSLE_RANGE)).isTrue();
    }

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-SnapshotValidator-08: an affiliated IP range may sit next to the non-affiliated one (DC-82); two affiliated IP ranges of one year are duplicates")
    void affiliatedIntegratedProgrammeRanges() {
        ScoreRangeRecord ip = new ScoreRangeRecord(2025, 3, false, 4, 6, true);
        ScoreRangeRecord ipAffiliated = new ScoreRangeRecord(2025, 3, true, 4, 8, true);

        assertThat(validateWithFirst(withRanges(first(), List.of(ip, ipAffiliated))).getErrors()).isEmpty();
        assertThat(validateWithFirst(withRanges(first(), List.of(ipAffiliated,
                new ScoreRangeRecord(2025, 3, true, 4, 7, true)))).hasError(SnapshotValidator.DUPLICATE_PSLE_RANGE))
                .isTrue();
        assertThat(validateWithFirst(withRanges(first(), List.of(new ScoreRangeRecord(2025, 1, true, 4, 8, true))))
                .hasError(SnapshotValidator.BAD_PSLE_RANGE)).isTrue();
    }

    @Test
    @Tag("FR-DATA-01")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotValidator-09: a format-1 snapshot (no formatVersion, text instead of arrays) fails with exactly one manifest error")
    void refusesFormat1() {
        LoadedSnapshot format1 = reader.read(DatasetTestSupport.FORMAT_1);

        ValidationReport report = validator.validate(format1);

        assertThat(format1.manifest().formatVersion()).isNull();
        assertThat(format1.manifest().formatVersionOrDefault()).isEqualTo(1);
        assertThat(report.getStatus()).isEqualTo(ValidationStatus.FAILED);
        assertThat(report.getErrors()).as(report.toString()).singleElement().asString()
                .startsWith(SnapshotValidator.MANIFEST + ": formatVersion 1 ").contains("re-import");
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotValidator-10: only formatVersion 2 is accepted (1 and a newer 3 are refused)")
    void acceptsOnlyFormat2() {
        assertThat(validateWithManifest(withFormat(mini.manifest(), 2)).getErrors()).isEmpty();
        assertThat(validateWithManifest(withFormat(mini.manifest(), 1)).hasError(SnapshotValidator.MANIFEST)).isTrue();
        assertThat(validateWithManifest(withFormat(mini.manifest(), null)).hasError(SnapshotValidator.MANIFEST))
                .isTrue();
        assertThat(validateWithManifest(withFormat(mini.manifest(), 3)).getErrors()).singleElement().asString()
                .startsWith(SnapshotValidator.MANIFEST + ": formatVersion 3 ");
    }

    @Test
    @Tag("FR-DATA-01")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotValidator-11: transport-list names the school and the element that is not one bus service or one MRT station")
    void transportListRule() {
        SchoolRecord r = first();

        assertThat(validateWithFirst(withTransport(r, List.of("BISHAN MRT", "BRADDELL MRT"), List.of("13", "162M")))
                .getErrors()).isEmpty();
        assertThat(validateWithFirst(withTransport(r, List.of(), List.of())).getErrors())
                .as("no stations or buses is allowed (page shows 'Not available')").isEmpty();
        assertThat(validateWithFirst(withTransport(r, List.of("BISHAN MRT"), List.of("13", "243G/W"))).getErrors())
                .singleElement().asString()
                .startsWith(SnapshotValidator.TRANSPORT_LIST + ": " + r.schoolCode() + ": ")
                .contains("bus service '243G/W'").contains(CuratedCsvReader.TRANSPORT_OVERRIDES);
        assertThat(validateWithFirst(withTransport(r, List.of("York Hill Campus", "OUTRAM PARK MRT (EW16)"),
                List.of("13"))).getErrors()).singleElement().asString()
                .startsWith(SnapshotValidator.TRANSPORT_LIST + ": " + r.schoolCode() + ": ")
                .contains("MRT station 'York Hill Campus'");
        assertThat(validateWithFirst(withTransport(r, List.of("BISHAN MRT"), List.of("13", "52", "13"))).getErrors())
                .singleElement().asString().startsWith(SnapshotValidator.TRANSPORT_LIST + ": ").contains("'13'");
        assertThat(validateWithFirst(withTransport(r, List.of("BISHAN MRT", " "), List.of("13"))).getErrors())
                .singleElement().asString().startsWith(SnapshotValidator.TRANSPORT_LIST + ": ");
    }

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-SnapshotValidator-12: moe-text-format accepts MOE's five forms and refuses other text or other numbers")
    void moeTextFormatRule() {
        SchoolRecord r = first();
        ScoreRangeRecord plain = new ScoreRangeRecord(2025, 3, false, 6, 8, false, "6(D) - 8(M)");
        ScoreRangeRecord star = new ScoreRangeRecord(2025, 1, false, 26, 30, false, "26 - 30*");

        assertThat(validateWithFirst(withRanges(r, List.of(plain, star))).getErrors()).isEmpty();
        assertThat(validateWithFirst(withRanges(r, List.of(new ScoreRangeRecord(2025, 3, false, 6, 8, false,
                "6 to 8")))).getErrors()).singleElement().asString()
                .startsWith(SnapshotValidator.MOE_TEXT_FORMAT + ": " + r.schoolCode() + ": 2025 PG3: ");
        assertThat(validateWithFirst(withRanges(r, List.of(new ScoreRangeRecord(2025, 3, false, 6, 9, false,
                "6 - 8")))).hasError(SnapshotValidator.MOE_TEXT_FORMAT)).isTrue();
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotValidator-13: too-long refuses a text longer than its database column and accepts the limit")
    void tooLongRule() {
        SchoolRecord r = first();
        int phone = SnapshotValidator.MAX_LENGTHS.get("school.telephone");
        String longCca = "C".repeat(SnapshotValidator.MAX_LENGTHS.get("school_cca.cca_name") + 1);

        assertThat(validateWithFirst(withTexts(r, null, null, "6".repeat(phone), null)).getErrors()).isEmpty();
        assertThat(validateWithFirst(withTexts(r, null, null, "6".repeat(phone + 1), null)).getErrors())
                .singleElement().asString().startsWith(SnapshotValidator.TOO_LONG + ": " + r.schoolCode() + ": ")
                .contains("school.telephone");
        assertThat(validateWithFirst(withTexts(r, null, null, null, List.of(longCca))).getErrors())
                .singleElement().asString().startsWith(SnapshotValidator.TOO_LONG + ": ").contains("school_cca");
        assertThat(validateWithFirst(withTransport(r, List.of("M".repeat(81)), List.of("13"))).getErrors())
                .singleElement().asString().startsWith(SnapshotValidator.TOO_LONG + ": ").contains("station_name");
        SnapshotManifest m = mini.manifest();
        assertThat(validateWithManifest(withManifest(m, m.version(), m.effectiveDate(), m.importedAt(), m.sources(),
                "n".repeat(2001))).getErrors()).singleElement().asString()
                .startsWith(SnapshotValidator.TOO_LONG + ": manifest: notes");
    }

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-SnapshotValidator-14: a range's admission year must be 2022–2100 (AL scores)")
    void admissionYearRule() {
        SchoolRecord r = first();

        assertThat(validateWithFirst(withRanges(r, List.of(new ScoreRangeRecord(2022, 3, false, 6, 8),
                new ScoreRangeRecord(2100, 3, false, 6, 8)))).getErrors()).isEmpty();
        assertThat(validateWithFirst(withRanges(r, List.of(new ScoreRangeRecord(2021, 3, false, 6, 8))))
                .getErrors()).singleElement().asString().startsWith(SnapshotValidator.BAD_PSLE_RANGE + ": ")
                .contains("admission year");
        assertThat(validateWithFirst(withRanges(r, List.of(new ScoreRangeRecord(2101, 3, false, 6, 8))))
                .hasError(SnapshotValidator.BAD_PSLE_RANGE)).isTrue();
    }

    @Test
    @Tag("FR-DATA-01")
    @DisplayName("TC-SnapshotValidator-15: a postal code must be 6 digits (or missing)")
    void postalCodeRule() {
        SchoolRecord r = first();

        assertThat(validateWithFirst(withTexts(r, null, "579767", null, null)).getErrors()).isEmpty();
        assertThat(validateWithFirst(withTexts(r, null, "57976", null, null)).getErrors()).singleElement()
                .asString().startsWith(SnapshotValidator.BAD_POSTAL_CODE + ": " + r.schoolCode() + ": ");
        assertThat(validateWithFirst(withTexts(r, null, "S579767", null, null))
                .hasError(SnapshotValidator.BAD_POSTAL_CODE)).isTrue();
    }

    @Test
    @Tag("FR-DATA-01")
    @DisplayName("TC-SnapshotValidator-16: duplicate-name names both school codes; case and outer spaces are ignored")
    void duplicateNameRule() {
        SchoolRecord second = mini.records().get(1);

        assertThat(validateWithFirst(withTexts(first(), " " + second.name().toLowerCase() + " ", null, null, null))
                .getErrors()).singleElement().asString()
                .startsWith(SnapshotValidator.DUPLICATE_NAME + ": " + first().schoolCode() + ", " + second.schoolCode());
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotValidator-17: the manifest needs both dates, a version usable as a key, and sources with an id and a name, each id once")
    void manifestRules() {
        SnapshotManifest m = mini.manifest();
        SnapshotManifest.Source first = m.sources().get(0);

        assertThat(validateWithManifest(withManifest(m, m.version(), null, m.importedAt(), m.sources(), m.notes()))
                .getErrors()).singleElement().asString().startsWith(SnapshotValidator.MANIFEST + ": effectiveDate");
        assertThat(validateWithManifest(withManifest(m, m.version(), m.effectiveDate(), null, m.sources(), m.notes()))
                .getErrors()).singleElement().asString().startsWith(SnapshotValidator.MANIFEST + ": importedAt");
        assertThat(validateWithManifest(withManifest(m, "2026 10 04", m.effectiveDate(), m.importedAt(), m.sources(),
                m.notes())).getErrors()).singleElement().asString().startsWith(SnapshotValidator.MANIFEST + ": version");
        assertThat(validateWithManifest(withManifest(m, m.version(), m.effectiveDate(), m.importedAt(),
                List.of(first, first), m.notes())).getErrors()).singleElement().asString()
                .startsWith(SnapshotValidator.MANIFEST + ": source #2").contains("twice");
        assertThat(validateWithManifest(withManifest(m, m.version(), m.effectiveDate(), m.importedAt(),
                List.of(new SnapshotManifest.Source("x", null, null)), m.notes())).getErrors()).singleElement()
                .asString().startsWith(SnapshotValidator.MANIFEST + ": source #1");
    }

    @Test
    @Tag("FR-DATA-01")
    @DisplayName("TC-SnapshotValidator-18: bad-district refuses a planning area without a name, or a code or name used twice")
    void badDistrictRule() {
        District d = mini.districts().get(0);

        assertThat(validateWithExtraDistrict(new District("ZZ", d.getPlanningAreaName(), null)))
                .singleElement().asString().startsWith(SnapshotValidator.BAD_DISTRICT + ": planningAreaName");
        assertThat(validateWithExtraDistrict(new District(d.getPlanningAreaCode(), "ELSEWHERE", null)))
                .singleElement().asString().startsWith(SnapshotValidator.BAD_DISTRICT + ": planningAreaCode");
        assertThat(validateWithExtraDistrict(new District("ZZ", null, null)))
                .singleElement().asString().startsWith(SnapshotValidator.BAD_DISTRICT + ": planning area ZZ");
    }

    @Test
    @Tag("FR-DATA-01")
    @DisplayName("TC-SnapshotValidator-19: duplicate-element refuses a CCA, programme or affiliation listed twice for one school")
    void duplicateElementRule() {
        SchoolRecord r = first();

        assertThat(validateWithFirst(withTexts(r, null, null, null, List.of("BADMINTON", "CHOIR", "BADMINTON ")))
                .getErrors()).singleElement().asString()
                .startsWith(SnapshotValidator.DUPLICATE_ELEMENT + ": " + r.schoolCode() + ": CCA 'BADMINTON'");
    }

    @Test
    @Tag("FR-DATA-01")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotValidator-20: transport-list refuses an element with outer white space, so a snapshot that validates always loads (snapshot-broken/transport-list-padded)")
    void transportListRefusesPaddedElement() {
        ValidationReport report = validator.validate(reader.read(DatasetTestSupport.TRANSPORT_LIST_PADDED));

        assertThat(report.getStatus()).isEqualTo(ValidationStatus.FAILED);
        assertThat(report.getErrors()).as(report.toString()).singleElement().asString()
                .startsWith(SnapshotValidator.TRANSPORT_LIST + ": catholic-high-school: ")
                .contains("MRT station 'BISHAN MRT '").contains("white space");

        SchoolRecord r = first();
        assertThat(validateWithFirst(withTransport(r, List.of("TAMPINES MRT", "SIMEI MRT", "TAMPINES MRT "),
                List.of("13"))).getErrors()).singleElement().asString()
                .startsWith(SnapshotValidator.TRANSPORT_LIST + ": " + r.schoolCode() + ": ")
                .contains("MRT station 'TAMPINES MRT '");
        assertThat(validateWithFirst(withTransport(r, List.of("TAMPINES MRT"), List.of("13", " 13"))).getErrors())
                .singleElement().asString().contains("bus service ' 13'");
    }

    private List<String> validateWithExtraDistrict(District extra) {
        List<District> districts = new ArrayList<>(mini.districts());
        districts.add(extra);
        return validator.validate(new LoadedSnapshot(mini.location(), mini.manifest(), mini.records(), mini.schools(),
                districts)).getErrors();
    }

    private ValidationReport validateWithManifest(SnapshotManifest manifest) {
        return validator.validate(new LoadedSnapshot(mini.location(), manifest, mini.records(), mini.schools(),
                mini.districts()));
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

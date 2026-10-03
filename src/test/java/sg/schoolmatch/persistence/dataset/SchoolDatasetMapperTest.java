package sg.schoolmatch.persistence.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.dataset.SnapshotManifest;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.entity.school.ValidationStatus;

/**
 * SchoolDatasetMapper on hand-made rows (docs/database-design.md, sections 2.3, 6.3 and 7.2). The database returns
 * rows in no fixed order, so every test feeds them shuffled; the mapper sorts in Java.
 */
class SchoolDatasetMapperTest {

    private static final Instant IMPORTED = Instant.parse("2026-10-04T01:02:03.123456Z");
    private static final String V = "2026-10-04.1";

    private static final DatasetVersionRow VERSION = new DatasetVersionRow(V, "full", LocalDate.of(2026, 10, 4),
            IMPORTED, "PASSED_WITH_WARNINGS", "PASSED", "notes", "a".repeat(64), 1, IMPORTED);

    private static SnapshotRows rows(List<DistrictRow> districts, List<SchoolRow> schools, List<ScoreRangeRow> ranges,
                                     List<SchoolCcaRow> ccas, List<SchoolBusServiceRow> buses,
                                     List<SchoolMrtStationRow> stations) {
        return new SnapshotRows(VERSION,
                List.of(new DatasetSourceRow(V, 2, "onemap", "OneMap", IMPORTED),
                        new DatasetSourceRow(V, 1, "d_1", "data.gov.sg Schools", null)),
                List.of(new DatasetWarningRow(V, 2, "second"), new DatasetWarningRow(V, 1, "first")),
                districts, schools, ranges, ccas,
                List.of(new SchoolProgrammeRow("b-school", "Music"), new SchoolProgrammeRow("b-school", "Art")),
                List.of(new SchoolAffiliatedPrimaryRow("b-school", "Zeta Primary"),
                        new SchoolAffiliatedPrimaryRow("b-school", "Alpha Primary")),
                buses, stations);
    }

    private static SchoolRow school(String code, String name, String area) {
        return new SchoolRow(code, name, "1 Road", "123456", 1.35, 103.8, "61234567", "https://x.example", null,
                "GOVERNMENT SCHOOL", "SINGLE SESSION", "CO-ED SCHOOL", area, null);
    }

    private static SnapshotRows sample() {
        return rows(
                List.of(new DistrictRow("TM", "TAMPINES", "{\"type\":\"Polygon\",\"coordinates\":[]}", null),
                        new DistrictRow("BS", "BISHAN", null, null)),
                List.of(school("b-school", "B SCHOOL", "TM"), school("a-school", "A SCHOOL", "BS"),
                        school("a-school-2", "A SCHOOL", "BS")),
                List.of(new ScoreRangeRow("b-school", 2024, 3, false, false, 10, 14, null, null, false),
                        new ScoreRangeRow("b-school", 2025, 3, false, true, 4, 8, "D", "M", false),
                        new ScoreRangeRow("b-school", 2025, 1, false, false, 26, 30, null, null, true),
                        new ScoreRangeRow("b-school", 2025, 3, true, false, 6, 9, null, null, false),
                        new ScoreRangeRow("b-school", 2025, 3, false, false, 8, 12, null, null, false),
                        new ScoreRangeRow("a-school", 2025, 2, false, false, 20, 22, null, null, null)),
                List.of(new SchoolCcaRow("b-school", "Choir"), new SchoolCcaRow("b-school", "Badminton")),
                List.of(new SchoolBusServiceRow("b-school", "88", 2), new SchoolBusServiceRow("b-school", "156", 3),
                        new SchoolBusServiceRow("b-school", "13", 1)),
                List.of(new SchoolMrtStationRow("b-school", "TAMPINES MRT", 2),
                        new SchoolMrtStationRow("b-school", "BISHAN MRT", 1)));
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-SchoolDatasetMapper-01: rows in any order give schools by name then code, lists in natural order, bus and MRT by position, ranges newest first")
    void sortsInJava() {
        List<School> schools = SchoolDatasetMapper.schools(sample());

        assertThat(schools).extracting(School::getSchoolCode).containsExactly("a-school", "a-school-2", "b-school");
        School b = schools.get(2);
        assertThat(b.getCcas()).containsExactly("Badminton", "Choir");
        assertThat(b.getProgrammes()).containsExactly("Art", "Music");
        assertThat(b.getAffiliatedPrimarySchools()).containsExactly("Alpha Primary", "Zeta Primary");
        assertThat(b.getBusInfo()).isEqualTo("13, 88, 156");
        assertThat(b.getNearestMrt()).isEqualTo("BISHAN MRT, TAMPINES MRT");
        assertThat(b.getScoreRanges()).extracting(IndicativePsleScoreRange::describe).containsExactly(
                "PG3 8–12 (2025, non-affiliated)", "PG3 6–9 (2025, affiliated)", "PG3 IP 4–8 (2025)",
                "PG1 26–30 (2025, non-affiliated)", "PG3 10–14 (2024, non-affiliated)");
        assertThat(SchoolDatasetMapper.districts(sample())).extracting(District::getPlanningAreaCode)
                .containsExactly("BS", "TM");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-SchoolDatasetMapper-02: computed values: planning-area name through the code, MOE text from its parts, the IP note from the IP ranges; an unknown text and no lists stay null")
    void computedValues() {
        List<School> schools = SchoolDatasetMapper.schools(sample());
        School a = schools.get(0);
        School b = schools.get(2);

        assertThat(b.getPlanningArea()).isEqualTo("TAMPINES");
        assertThat(a.getPlanningArea()).isEqualTo("BISHAN");
        assertThat(b.getScoreRanges()).extracting(IndicativePsleScoreRange::getMoeText)
                .containsExactly("8 - 12", "6 - 9", "4(D) - 8(M)", "26 - 30*", "10 - 14");
        assertThat(a.getScoreRanges().getFirst().getMoeText()).isNull();
        assertThat(b.getIpRangeNote()).isEqualTo("IP 2025 PG3: 4(D) - 8(M)");
        assertThat(a.getIpRangeNote()).isNull();
        assertThat(a.getBusInfo()).isNull();
        assertThat(a.getNearestMrt()).isNull();
        assertThat(a.getEmail()).isNull();
        assertThat(a.getCoordinate().getLatitude()).isEqualTo(1.35);
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchoolDatasetMapper-03: the manifest: format 2, sources and warnings in manifest order, counts of the active rows; the cache carries the version metadata and the load status")
    void manifestAndCache() {
        SnapshotManifest m = SchoolDatasetMapper.manifest(sample());

        assertThat(m.formatVersion()).isEqualTo(SnapshotManifest.FORMAT_VERSION);
        assertThat(m.kind()).isEqualTo("full");
        assertThat(m.version()).isEqualTo(V);
        assertThat(m.importedAt()).isEqualTo(IMPORTED);
        assertThat(m.validationStatus()).isEqualTo(ValidationStatus.PASSED_WITH_WARNINGS);
        assertThat(m.sources()).extracting(SnapshotManifest.Source::datasetId).containsExactly("d_1", "onemap");
        assertThat(m.warnings()).containsExactly("first", "second");
        assertThat(m.counts()).isEqualTo(Map.of("schools", 3, "districts", 2, "scoreRanges", 6));
        assertThat(m.notes()).isEqualTo("notes");

        SchoolDataCache cache = SchoolDatasetMapper.cache(sample(), "src", IMPORTED, IMPORTED.plusSeconds(3600));
        assertThat(cache.getDatasetVersion()).isEqualTo(V);
        assertThat(cache.getDatasetKind()).isEqualTo("full");
        assertThat(cache.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(cache.getValidationStatus()).isEqualTo(ValidationStatus.PASSED);
        assertThat(cache.getExpiresAt()).isEqualTo(IMPORTED.plusSeconds(3600));
        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.hasPsleData()).isTrue();
        assertThat(cache.hasAffiliationData()).isTrue();
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchoolDatasetMapper-04: a school whose planning area is not among the active areas is refused (the loader never leaves one)")
    void unknownAreaRefused() {
        SnapshotRows rows = rows(List.of(new DistrictRow("BS", "BISHAN", null, null)),
                List.of(school("a-school", "A SCHOOL", "XX")), List.of(), List.of(), List.of(), List.of());

        assertThatThrownBy(() -> SchoolDatasetMapper.schools(rows)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a-school").hasMessageContaining("XX");
    }
}

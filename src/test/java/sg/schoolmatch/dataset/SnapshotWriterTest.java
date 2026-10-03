package sg.schoolmatch.dataset;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static sg.schoolmatch.dataset.DatasetTestSupport.MINI;
import static sg.schoolmatch.dataset.DatasetTestSupport.reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.ValidationStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** SnapshotWriter: the importer's output folder (DC-12) reads back with SnapshotReader and passes SnapshotValidator. */
class SnapshotWriterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    @TempDir
    Path out;

    private final SnapshotWriter writer = new SnapshotWriter();
    private final LoadedSnapshot mini = reader(Map.of()).read(MINI);

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotWriter-01: the next version is <date>.<n>, one more than the highest folder of that day")
    void nextVersion() throws IOException {
        assertThat(writer.nextVersion(out.resolve("missing"), DAY)).isEqualTo("2026-10-02.1");

        Files.createDirectories(out.resolve("2026-10-02.1"));
        Files.createDirectories(out.resolve("2026-10-02.3"));
        Files.createDirectories(out.resolve("2026-10-01.7"));
        Files.createDirectories(out.resolve("0000-seed"));

        assertThat(writer.nextVersion(out, DAY)).isEqualTo("2026-10-02.4");
    }

    @Test
    @Tag("NFR-DATA-01")
    @Tag("FR-DATA-03")
    @DisplayName("TC-SnapshotWriter-02: the folder holds the 5 files; schools sorted by code; missing values are JSON null")
    void writesFiles() throws IOException {
        List<SchoolRecord> reversed = new ArrayList<>(mini.records());
        Collections.reverse(reversed);

        Path folder = write("2026-10-02.1", reversed);

        assertThat(folder).isEqualTo(out.resolve("2026-10-02.1"));
        assertThat(folder.resolve(SnapshotReader.MANIFEST_FILE)).isRegularFile();
        assertThat(folder.resolve(SnapshotReader.SCHOOLS_FILE)).isRegularFile();
        assertThat(folder.resolve(SnapshotReader.DISTRICTS_FILE)).isRegularFile();
        assertThat(folder.resolve(SnapshotWriter.VALIDATION_REPORT_FILE)).content(UTF_8).contains("# Validation report");
        assertThat(folder.resolve(SnapshotWriter.IMPORT_LOG_FILE)).content(UTF_8).isEqualTo("log line\n");
        assertThat(folder.resolve(SnapshotReader.MANIFEST_FILE)).content(UTF_8)
                .as("isFull() is derived, not a field").doesNotContain("\"full\"");

        JsonNode schools = JsonMapper.builder().build().readTree(folder.resolve(SnapshotReader.SCHOOLS_FILE).toFile());
        List<String> codes = schools.values().stream().map(s -> s.get("schoolCode").asString()).toList();
        assertThat(codes).isSorted().hasSize(10);
        JsonNode jurongWest = schools.values().stream()
                .filter(s -> s.get("schoolCode").asString().equals("jurong-west-secondary-school")).findFirst().orElseThrow();
        assertThat(jurongWest.has("email")).isTrue();
        assertThat(jurongWest.get("email").isNull()).isTrue();
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotWriter-03: the written folder reads back with SnapshotReader and passes SnapshotValidator")
    void roundTrip() {
        Path folder = write("2026-10-02.1", mini.records());

        LoadedSnapshot back = reader(Map.of()).read(folder.toUri().toString());
        ValidationReport report = new SnapshotValidator().validate(back);

        assertThat(back.manifest()).isEqualTo(mini.manifest());
        assertThat(back.records()).isEqualTo(mini.records());
        assertThat(back.districts()).extracting(District::getPlanningAreaCode).containsExactly("BS", "JW", "TM");
        assertThat(report.isUsable()).as(report.toString()).isTrue();
        assertThat(report.getStatus()).isEqualTo(ValidationStatus.PASSED_WITH_WARNINGS);
    }

    @Test
    @Tag("NFR-DATA-01")
    @Tag("FR-DATA-03")
    @DisplayName("TC-SnapshotWriter-08: the integratedProgramme flag survives write and read; a range without it reads as non-IP")
    void integratedProgrammeFlag() throws IOException {
        assertThat(mini.records()).flatExtracting(SchoolRecord::scoreRanges).isNotEmpty()
                .as("snapshot-mini has no integratedProgramme field").allMatch(r -> Boolean.FALSE.equals(r.integratedProgramme()));
        List<SchoolRecord> records = new ArrayList<>(mini.records());
        records.set(0, DatasetTestSupport.withRanges(records.get(0), List.of(
                new ScoreRangeRecord(2025, 3, false, 6, 8), new ScoreRangeRecord(2025, 3, false, 4, 7, true))));

        Path folder = write("2026-10-02.1", records);
        LoadedSnapshot back = reader(Map.of()).read(folder.toUri().toString());

        assertThat(Files.readString(folder.resolve(SnapshotReader.SCHOOLS_FILE)))
                .contains("\"integratedProgramme\" : true");
        String code = records.get(0).schoolCode();
        assertThat(back.records()).filteredOn(r -> r.schoolCode().equals(code)).singleElement()
                .satisfies(r -> assertThat(r.scoreRanges()).extracting(ScoreRangeRecord::integratedProgramme)
                        .containsExactly(false, true));
        assertThat(back.schools()).filteredOn(school -> school.getSchoolCode().equals(code)).singleElement()
                .satisfies(school -> assertThat(school.getScoreRanges())
                        .extracting(IndicativePsleScoreRange::isIntegratedProgramme).containsExactly(false, true));
        assertThat(new SnapshotValidator().validate(back).getErrors()).isEmpty();
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotWriter-04: an existing version folder is never overwritten")
    void refusesExistingFolder() throws IOException {
        Files.createDirectories(out.resolve("2026-10-02.1"));
        Files.writeString(out.resolve("2026-10-02.1").resolve("keep.txt"), "x");

        assertThatThrownBy(() -> write("2026-10-02.1", mini.records()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2026-10-02.1");
        assertThat(out.resolve("2026-10-02.1").resolve(SnapshotReader.SCHOOLS_FILE)).doesNotExist();
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotWriter-05: activate() points ACTIVE at the new version")
    void activate() throws IOException {
        Files.writeString(out.resolve(SnapshotReader.ACTIVE_FILE), "0000-seed\n");

        writer.activate(out, "2026-10-02.1");

        assertThat(SnapshotReader.readActiveVersion(out)).isEqualTo("2026-10-02.1");
        assertThat(out.resolve(SnapshotReader.ACTIVE_FILE)).content(UTF_8).isEqualTo("2026-10-02.1\n");
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-SnapshotWriter-06: districts are written with our property names and simplified boundaries")
    void districtsSimplified() {
        District detailed = new District("SQ", "SQUARE", squareWithManyPoints(2000));

        String json = writer.districtsGeoJson(List.of(detailed), SnapshotWriter.MAX_DISTRICTS_BYTES);
        List<District> back = DistrictLocator.parseFeatureCollection(json);

        assertThat(back).singleElement().satisfies(d -> {
            assertThat(d.getPlanningAreaCode()).isEqualTo("SQ");
            assertThat(d.getPlanningAreaName()).isEqualTo("SQUARE");
            assertThat(d.getBoundaryGeoJson().length()).isLessThan(detailed.getBoundaryGeoJson().length() / 10);
            assertThat(d.contains(new Coordinate(1.35, 103.85))).isTrue();
            assertThat(d.contains(new Coordinate(1.45, 103.85))).isFalse();
        });
        assertThat(json).contains("\"planningAreaCode\":\"SQ\"").contains("\"planningAreaName\":\"SQUARE\"");
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-SnapshotWriter-07: a stronger simplification is used when the file would be too big")
    void districtsSizeLimit() {
        List<District> wiggly = List.of(new District("W", "WIGGLY", wigglyRing(3000)));
        int finest = writer.districtsGeoJson(wiggly, Integer.MAX_VALUE).length();

        String limited = writer.districtsGeoJson(wiggly, finest - 1);

        assertThat(limited.length()).isLessThan(finest);
    }

    private Path write(String version, List<SchoolRecord> records) {
        return writer.write(out, version, mini.manifest(), records, writer.districtsGeoJson(mini.districts(),
                SnapshotWriter.MAX_DISTRICTS_BYTES), "# Validation report\n", "log line\n");
    }

    /** A square (1.30–1.40, 103.80–103.90) whose bottom edge has {@code n} extra points on a straight line. */
    private static String squareWithManyPoints(int n) {
        StringBuilder ring = new StringBuilder("[[103.80,1.30]");
        for (int i = 1; i < n; i++) {
            ring.append(",[").append(103.80 + 0.10 * i / n).append(",1.30]");
        }
        ring.append(",[103.90,1.30],[103.90,1.40],[103.80,1.40],[103.80,1.30]]");
        return "{\"type\":\"Polygon\",\"coordinates\":[" + ring + "]}";
    }

    /** A ring around (1.35, 103.85) whose radius wobbles by about 20 m, so each tolerance keeps fewer points. */
    private static String wigglyRing(int n) {
        StringBuilder ring = new StringBuilder("[");
        for (int i = 0; i <= n; i++) {
            int j = i % n;   // the last point repeats the first, so the ring is closed
            double angle = 2 * Math.PI * j / n;
            double r = 0.03 + 0.0002 * Math.sin(j * 7.0) + 0.0004 * Math.sin(j * 0.37);
            if (i > 0) {
                ring.append(',');
            }
            ring.append('[').append(103.85 + r * Math.cos(angle)).append(',').append(1.35 + r * Math.sin(angle)).append(']');
        }
        return "{\"type\":\"Polygon\",\"coordinates\":[" + ring.append(']') + "]}";
    }
}

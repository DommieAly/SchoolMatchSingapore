package sg.schoolmatch.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static sg.schoolmatch.dataset.DatasetTestSupport.MINI;
import static sg.schoolmatch.dataset.DatasetTestSupport.reader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;

/** SnapshotReader on the 10-school fixture snapshot-mini (same content as data/snapshots/0000-seed). */
class SnapshotReaderTest {

    private final SnapshotReader reader = reader(Map.of());
    private final LoadedSnapshot mini = reader(Map.of("app.dataset.snapshot-location", MINI)).read();

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-SnapshotReader-01: snapshot-mini has 10 schools, 3 districts and a seed manifest")
    void readsSnapshotMini() {
        assertThat(mini.schools()).hasSize(10);
        assertThat(mini.records()).hasSize(10);
        assertThat(mini.districts()).extracting(District::getPlanningAreaName)
                .containsExactlyInAnyOrder("BISHAN", "JURONG WEST", "TAMPINES");
        assertThat(mini.manifest().kind()).isEqualTo("seed");
        assertThat(mini.manifest().isFull()).isFalse();
        assertThat(mini.version()).isEqualTo("0000-seed");
        assertThat(mini.manifest().effectiveDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(mini.manifest().importedAt()).isNotNull();
        assertThat(mini.manifest().sources()).isNotEmpty();
        assertThat(mini.location()).isEqualTo(MINI);
    }

    @Test
    @Tag("FR-DATA-03")
    @Tag("FR-DATA-06")
    @DisplayName("TC-SnapshotReader-02: a school record maps to a School with coordinate, district and ranges")
    void mapsSchoolFields() {
        School chs = school("catholic-high-school");

        assertThat(chs.getName()).isEqualTo("CATHOLIC HIGH SCHOOL");
        assertThat(chs.getAddress()).isNotBlank();
        assertThat(chs.hasValidCoordinate()).isTrue();
        assertThat(chs.getPlanningArea()).isEqualTo("BISHAN");
        assertThat(chs.getCcas()).isNotEmpty();
        assertThat(chs.getProgrammes()).isNotEmpty();
        assertThat(chs.getScoreRanges()).hasSize(4);
        assertThat(chs.getScoreRange(3, false)).map(IndicativePsleScoreRange::getAdmissionYear).contains(2025);
    }

    @Test
    @Tag("FR-SCHOOL-03")
    @DisplayName("TC-SnapshotReader-03: missing values stay null or empty (page shows 'Not available')")
    void keepsMissingValuesNull() {
        assertThat(school("jurong-west-secondary-school").getEmail()).isNull();
        assertThat(school("westwood-secondary-school").getScoreRanges()).isEmpty();
        assertThat(school("westwood-secondary-school").getIpRangeNote()).isNull();
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-SnapshotReader-04: each district keeps its GeoJSON geometry as a JSON string")
    void keepsDistrictGeometry() {
        assertThat(mini.districts()).allSatisfy(d -> {
            assertThat(d.getPlanningAreaCode()).isNotBlank();
            assertThat(d.getBoundaryGeoJson()).startsWith("{").contains("\"coordinates\"");
        });
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotReader-05: without snapshot-location, the folder named in <dir>/ACTIVE is read")
    void readsFolderNamedInActive(@TempDir Path dir) throws IOException {
        Path folder = Files.createDirectories(dir.resolve("2026-10-07.1"));
        for (String file : List.of(SnapshotReader.MANIFEST_FILE, SnapshotReader.SCHOOLS_FILE,
                SnapshotReader.DISTRICTS_FILE)) {
            try (InputStream in = new ClassPathResource("fixtures/snapshot-mini/" + file).getInputStream()) {
                Files.copy(in, folder.resolve(file));
            }
        }
        Files.writeString(dir.resolve(SnapshotReader.ACTIVE_FILE), "\n2026-10-07.1\n");

        SnapshotReader reader = reader(Map.of("app.dataset.dir", dir.toString()));

        assertThat(reader.resolveLocation()).startsWith("file:").endsWith("/2026-10-07.1/");
        assertThat(reader.read().schools()).hasSize(10);
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotReader-06: a missing ACTIVE file or one naming a path outside the dir is refused")
    void refusesBadActiveFile(@TempDir Path dir) throws IOException {
        SnapshotReader reader = reader(Map.of("app.dataset.dir", dir.toString()));
        assertThatThrownBy(reader::resolveLocation).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE");

        Files.writeString(dir.resolve(SnapshotReader.ACTIVE_FILE), "../outside\n");
        assertThatThrownBy(reader::resolveLocation).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid folder");
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotReader-07: a folder without snapshot files is refused with a clear message")
    void refusesMissingFiles(@TempDir Path dir) {
        SnapshotReader reader = reader(Map.of());
        assertThatThrownBy(() -> reader.read(dir.toUri().toString())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SnapshotReader.MANIFEST_FILE);
    }

    @Test
    @Tag("FR-SCHOOL-03")
    @Tag("FR-DATA-03")
    @DisplayName("TC-SnapshotReader-08: format 2: busServices / mrtStations arrays are joined with \", \" into busInfo / nearestMrt; empty arrays give null")
    void joinsTransportLists() {
        SchoolRecord huaYiRecord = mini.records().stream()
                .filter(r -> "hua-yi-secondary-school".equals(r.schoolCode())).findFirst().orElseThrow();

        assertThat(mini.manifest().formatVersion()).isEqualTo(SnapshotManifest.FORMAT_VERSION);
        assertThat(huaYiRecord.mrtStations()).containsExactly("LAKESIDE MRT", "CHINESE GARDEN MRT");
        assertThat(school("hua-yi-secondary-school").getNearestMrt()).isEqualTo("LAKESIDE MRT, CHINESE GARDEN MRT");
        assertThat(school("catholic-high-school").getNearestMrt()).isEqualTo("BISHAN MRT");
        assertThat(school("catholic-high-school").getBusInfo()).isEqualTo("13, 52, 54, 88, 156, 162, 162M, 410");

        SchoolRecord none = DatasetTestSupport.withTransport(huaYiRecord, List.of(), List.of(" "));
        assertThat(SnapshotReader.toSchool(none).getNearestMrt()).isNull();
        assertThat(SnapshotReader.toSchool(none).getBusInfo()).isNull();
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotReader-09: a manifest without formatVersion reads as format 1 (refused by the validator)")
    void missingFormatVersionIsOne() {
        LoadedSnapshot format1 = reader(Map.of()).read(DatasetTestSupport.FORMAT_1);

        assertThat(format1.manifest().formatVersion()).isNull();
        assertThat(format1.manifest().formatVersionOrDefault()).isEqualTo(1);
        assertThat(format1.records()).allMatch(r -> r.busServices().isEmpty() && r.mrtStations().isEmpty());
    }

    /** SHA-256 of snapshot-mini's manifest.json, 0, schools.json, 0, districts.geojson, computed with Python hashlib. */
    static final String MINI_SHA256 = "6c77bf1d6b6e9b8e16ba4df1446895042710844afeb72007166949985a8ead0e";

    @Test
    @Tag("FR-DATA-02")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SnapshotReader-10: contentSha256 hashes manifest.json, byte 0, schools.json, byte 0, districts.geojson")
    void contentSha256() {
        assertThat(reader.contentSha256(MINI)).isEqualTo(MINI_SHA256);
        assertThat(reader.contentSha256(mini)).isEqualTo(MINI_SHA256);
    }

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-SnapshotReader-11: one changed byte in any of the three files changes the hash; other files do not count")
    void contentSha256SeesEveryFile(@TempDir Path dir) throws IOException {
        for (String file : List.of(SnapshotReader.MANIFEST_FILE, SnapshotReader.SCHOOLS_FILE,
                SnapshotReader.DISTRICTS_FILE)) {
            Path copy = dir.resolve(file.replace('.', '-'));
            copyMini(copy);
            assertThat(reader.contentSha256(copy.toUri().toString())).isEqualTo(MINI_SHA256);
            Files.writeString(copy.resolve(file), " ", java.nio.file.StandardOpenOption.APPEND);
            Files.writeString(copy.resolve("import-log.txt"), "not hashed");
            assertThat(reader.contentSha256(copy.toUri().toString())).as(file).isNotEqualTo(MINI_SHA256);
        }
    }

    private static void copyMini(Path folder) throws IOException {
        Files.createDirectories(folder);
        for (String file : List.of(SnapshotReader.MANIFEST_FILE, SnapshotReader.SCHOOLS_FILE,
                SnapshotReader.DISTRICTS_FILE)) {
            try (InputStream in = new ClassPathResource("fixtures/snapshot-mini/" + file).getInputStream()) {
                Files.copy(in, folder.resolve(file));
            }
        }
    }

    private School school(String code) {
        return mini.schools().stream().filter(s -> code.equals(s.getSchoolCode())).findFirst().orElseThrow();
    }
}

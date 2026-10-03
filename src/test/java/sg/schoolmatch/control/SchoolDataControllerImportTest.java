package sg.schoolmatch.control;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import sg.schoolmatch.boundary.external.DataGovSgInterface;
import sg.schoolmatch.boundary.external.DataGovSgRecord;
import sg.schoolmatch.boundary.external.OneMapHit;
import sg.schoolmatch.boundary.external.OneMapInterface;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.dataset.CuratedCsvReader;
import sg.schoolmatch.dataset.ImportLog;
import sg.schoolmatch.dataset.LoadedSnapshot;
import sg.schoolmatch.dataset.SchoolRecord;
import sg.schoolmatch.dataset.ScoreRangeRecord;
import sg.schoolmatch.dataset.SnapshotManifest;
import sg.schoolmatch.dataset.SnapshotReader;
import sg.schoolmatch.dataset.SnapshotValidator;
import sg.schoolmatch.dataset.SnapshotWriter;
import sg.schoolmatch.dataset.ValidationReport;
import sg.schoolmatch.entity.school.ValidationStatus;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.persistence.dataset.SchoolDatasetStore;
import sg.schoolmatch.support.FixedClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SchoolDataController.importDataset() end to end with fixtures (DC-12): fake data.gov.sg and OneMap, curated CSVs
 * and the output folder in a temp dir. No network. The clock is 12 Oct 2026, so the version is 2026-10-12.1.
 */
class SchoolDataControllerImportTest {

    private static final String VERSION = "2026-10-12.1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private Path out;
    private Path curated;
    private final FixedClock clock = FixedClock.atDefault();
    private final SchoolDatasetStore store = mock(SchoolDatasetStore.class);
    private final Map<String, List<OneMapHit>> oneMapAnswers = new HashMap<>();
    private final OneMapInterface oneMap = text -> oneMapAnswers.getOrDefault(text, List.of());

    @BeforeEach
    void setUp() throws IOException {
        out = Files.createDirectories(temp.resolve("snapshots"));
        Files.writeString(out.resolve(SnapshotReader.ACTIVE_FILE), "0000-seed\n");
        curated = Files.createDirectories(temp.resolve("curated"));
        writeCurated(CuratedCsvReader.SCHOOL_CODES, "school_name,school_code,source_url\n");
        writeCurated(CuratedCsvReader.NAME_ALIASES, "dataset,raw_name,canonical_name\n");
        writeCurated(CuratedCsvReader.PSLE_RANGES,
                "school_code,admission_year,posting_group,track,lower,upper,raw_text,source_url,entered_by,checked_by\n");
        writeCurated(CuratedCsvReader.GEOCODE_OVERRIDES, "postal_code,school_code,latitude,longitude,reason\n");
        writeCurated(CuratedCsvReader.AFFILIATIONS, "school_code,primary_school,source_url\n");
        writeCurated(CuratedCsvReader.SUBJECT_EXCLUSIONS, "school_name,subject_desc,reason\n");
        writeCurated(CuratedCsvReader.TRANSPORT_OVERRIDES, "school_code,kind,published_text,elements,reason\n");
    }

    @Test
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-Import-01: 145 schools → a full snapshot folder that reads back and passes validation")
    void importsFullSnapshot() {
        ValidationReport report = controller(Map.of(), syntheticDataGovSg(145)).importDataset();

        assertThat(report.getErrors()).isEmpty();
        assertThat(report.getStatus()).isEqualTo(ValidationStatus.PASSED_WITH_WARNINGS);
        Path folder = out.resolve(VERSION);
        assertThat(folder.resolve(SnapshotWriter.VALIDATION_REPORT_FILE)).content(UTF_8)
                .contains("PASSED_WITH_WARNINGS").contains("school-code-slug").contains("geocode-outcome-matched: 145");
        assertThat(folder.resolve(SnapshotWriter.IMPORT_LOG_FILE)).content(UTF_8).contains("145");

        LoadedSnapshot back = reader().read(folder.toUri().toString());
        assertThat(new SnapshotValidator().validate(back).isUsable()).isTrue();
        SnapshotManifest manifest = back.manifest();
        assertThat(manifest.kind()).isEqualTo(SnapshotManifest.KIND_FULL);
        assertThat(manifest.version()).isEqualTo(VERSION);
        assertThat(manifest.effectiveDate()).hasToString("2026-10-12");
        assertThat(manifest.validationStatus()).isEqualTo(ValidationStatus.PASSED_WITH_WARNINGS);
        assertThat(manifest.counts()).containsEntry("schools", 145).containsEntry("districts", 3)
                .containsEntry("scoreRanges", 0);
        assertThat(manifest.sources()).extracting(SnapshotManifest.Source::datasetId).contains(
                "d_688b934f82c1059ed0a6993d2a829089", "d_9aba12b5527843afb0b2e8e4ed6ac6bd",
                "d_f1d144e423570c9d84dbc5102c2e664d", "d_4765db0e87b9c86336792efe8a1f7a66", "onemap-elastic-search");
        assertThat(manifest.sources()).allMatch(s -> s.downloadedAt() != null);
        assertThat(manifest.notes()).contains("No PSLE ranges");
        assertThat(back.records()).allMatch(r -> "BS".equals(r.planningAreaCode()) && r.latitude() != null);
        assertThat(back.records().getFirst().schoolCode()).isEqualTo("test-secondary-school-001");
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-Import-02: by default ACTIVE is not changed, even when the import passes")
    void doesNotActivateByDefault() throws IOException {
        controller(Map.of(), syntheticDataGovSg(145)).importDataset();

        assertThat(SnapshotReader.readActiveVersion(out)).isEqualTo("0000-seed");
        assertThat(out.resolve(VERSION).resolve(SnapshotWriter.IMPORT_LOG_FILE)).content(UTF_8)
                .contains("ACTIVE not changed");
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-Import-03: with activate-on-import=true a passing import becomes ACTIVE and the reader loads it")
    void activatesWhenAllowed() {
        controller(Map.of("app.dataset.activate-on-import", "true"), syntheticDataGovSg(145)).importDataset();

        assertThat(SnapshotReader.readActiveVersion(out)).isEqualTo(VERSION);
        AppProperties props = props(Map.of("app.dataset.dir", out.toString()));
        LoadedSnapshot active = new SnapshotReader(props, new DefaultResourceLoader()).read();
        assertThat(active.version()).isEqualTo(VERSION);
        assertThat(active.schools()).hasSize(145);
    }

    @Test
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-Import-04: the 4 fixture schools fail validation (a full snapshot needs 140–160); ACTIVE stays")
    void fixtureSchoolsFailCount() {
        oneMapAnswers.put("579767", List.of(hit("CATHOLIC HIGH SCHOOL", 1.354525, 103.844901)));
        oneMapAnswers.put("528986", List.of(hit("SAINT HILDA'S SECONDARY SCHOOL", 1.3495, 103.9563)));

        ValidationReport report = controller(Map.of("app.dataset.activate-on-import", "true"), fixtureDataGovSg())
                .importDataset();

        assertThat(report.getStatus()).isEqualTo(ValidationStatus.FAILED);
        assertThat(report.hasError(SnapshotValidator.SCHOOL_COUNT)).isTrue();
        assertThat(SnapshotReader.readActiveVersion(out)).isEqualTo("0000-seed");
        assertThat(out.resolve(VERSION).resolve(SnapshotReader.SCHOOLS_FILE)).exists();
        assertThat(report.getWarnings()).anyMatch(w -> w.startsWith(ImportLog.GEOCODE_FAILED + ": hua-yi-secondary-school"));
        assertThat(report.getWarnings()).anyMatch(w -> w.startsWith(ImportLog.CCA_JOIN_MISS + ": CHIJ ST. THERESA'S CONVENT"));
    }

    @Test
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-03")
    @DisplayName("TC-Import-05: curated codes, checked PSLE ranges with MOE text, IP ranges (also affiliated) and notes, affiliations and overrides are applied")
    void appliesCuratedData() throws IOException {
        writeCurated(CuratedCsvReader.SCHOOL_CODES, "school_name,school_code,source_url\n"
                + "Catholic High School,catholic-high,https://example.test/chs\n");
        writeCurated(CuratedCsvReader.PSLE_RANGES,
                "school_code,admission_year,posting_group,track,lower,upper,raw_text,source_url,entered_by,checked_by\n"
                        + "catholic-high,2025,3,NON_AFFILIATED,8,12,8 - 12,https://example.test,Ann,Ben\n"
                        + "catholic-high,2025,3,AFFILIATED,8,14,8 - 14,https://example.test,Ann,Ben\n"
                        + "catholic-high,2025,2,NON_AFFILIATED,10,15,10 - 15,https://example.test,Ann,\n"
                        + "catholic-high,2025,3,IP,4,6,4 - 6,https://example.test,Ann,Ben\n"
                        + "catholic-high,2025,3,IP_AFFILIATED,4,8,4(D) - 8(M),https://example.test,Ann,Ben\n"
                        + "no-such-school,2025,3,NON_AFFILIATED,8,12,8 - 12,https://example.test,Ann,Ben\n");
        writeCurated(CuratedCsvReader.AFFILIATIONS, "school_code,primary_school,source_url\n"
                + "catholic-high,Catholic High School (Primary),https://example.test\n");
        writeCurated(CuratedCsvReader.GEOCODE_OVERRIDES, "postal_code,school_code,latitude,longitude,reason\n"
                + ",catholic-high,1.3546,103.8450,test override\n");

        controller(Map.of(), fixtureDataGovSg()).importDataset();

        SchoolRecord catholic = writtenSchools().get("catholic-high");
        assertThat(catholic).isNotNull();
        assertThat(catholic.latitude()).isEqualTo(1.3546);
        assertThat(catholic.planningAreaName()).isEqualTo("BISHAN");
        // DC-77: the IP row is kept as a range (PG3, non-affiliated, integratedProgramme), after the others.
        // DC-82: an IP_AFFILIATED row is an affiliated IP range; every range keeps raw_text as moeText.
        assertThat(catholic.scoreRanges()).containsExactly(new ScoreRangeRecord(2025, 3, false, 8, 12, false, "8 - 12"),
                new ScoreRangeRecord(2025, 3, true, 8, 14, false, "8 - 14"),
                new ScoreRangeRecord(2025, 3, false, 4, 6, true, "4 - 6"),
                new ScoreRangeRecord(2025, 3, true, 4, 8, true, "4(D) - 8(M)"));
        assertThat(catholic.ipRangeNote()).isEqualTo("IP 2025 PG3: 4 - 6; IP 2025 PG3 affiliated: 4(D) - 8(M)");
        assertThat(catholic.affiliatedPrimarySchools()).containsExactly("CATHOLIC HIGH SCHOOL (PRIMARY)");
        assertThat(catholic.ccas()).containsExactly("ART AND CRAFTS", "ARTISTIC GYMNASTICS", "BASKETBALL");
        String log = Files.readString(out.resolve(VERSION).resolve(SnapshotWriter.IMPORT_LOG_FILE));
        assertThat(log).contains("not checked by a second person").contains("no-such-school");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-Import-06: published values are cleaned: 'na' → null, 5-digit postal code padded, spaces collapsed")
    void cleansValues() {
        oneMapAnswers.put("099138", List.of(hit("CHIJ SAINT THERESA'S CONVENT", 1.2763, 103.8239)));

        controller(Map.of(), fixtureDataGovSg()).importDataset();

        SchoolRecord chij = writtenSchools().get("chij-st-theresas-convent");
        assertThat(chij.postalCode()).isEqualTo("099138");
        assertThat(chij.latitude()).isEqualTo(1.2763);
        assertThat(chij.planningAreaCode()).as("outside the 3 fixture planning areas").isNull();
        assertThat(chij.name()).isEqualTo("CHIJ ST. THERESA'S CONVENT");
        assertThat(chij.address()).doesNotEndWith(" ");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-Import-07: a school whose dgp_code names another planning area is reported")
    void districtMismatch() {
        List<DataGovSgRecord> schools = new ArrayList<>(fixtureRecords("schools.json"));
        schools.replaceAll(r -> r.get("school_name").equals("CATHOLIC HIGH SCHOOL") ? with(r, "dgp_code", "TAMPINES") : r);
        oneMapAnswers.put("579767", List.of(hit("CATHOLIC HIGH SCHOOL", 1.354525, 103.844901)));

        ValidationReport report = controller(Map.of(), dataGovSg(onlySecondary(schools))).importDataset();

        assertThat(report.getWarnings()).contains(ImportLog.DISTRICT_MISMATCH
                + ": catholic-high-school is in BISHAN by its coordinate, but its dgp_code says TAMPINES");
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Import-08: when data.gov.sg is unavailable the import stops and writes nothing")
    void dataGovSgDown() {
        DataGovSgInterface down = new DataGovSgInterface() {
            public List<DataGovSgRecord> fetchSchools() {
                throw new ExternalServiceUnavailableException("data.gov.sg", null);
            }

            public List<DataGovSgRecord> fetchSchoolCcas() {
                return List.of();
            }

            public List<DataGovSgRecord> fetchSchoolSubjects() {
                return List.of();
            }

            public String fetchDistrictsGeoJson() {
                return "{}";
            }
        };

        assertThatThrownBy(() -> controller(Map.of(), down).importDataset())
                .isInstanceOf(ExternalServiceUnavailableException.class);
        assertThat(out.resolve(VERSION)).doesNotExist();
    }

    // TC-Import-09 ("getActiveManifest() is the manifest of the snapshot the app serves") is retired: the app now
    // serves the manifest from the database, so that check moved to TC-SchoolDataServing-01. Its id is not reused.

    @Test
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-Import-12: with load-on-startup false (the import profile) the importer reads no served snapshot and never uses the school database")
    void importUsesNoDatabase() {
        SchoolDataController controller = controller(Map.of(), syntheticDataGovSg(1));

        controller.importDataset();

        verifyNoInteractions(store);
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-Import-10: the importer writes snapshot format 2: formatVersion 2 and busServices / mrtStations arrays split from MOE's text")
    void writesFormat2() throws IOException {
        controller(Map.of(), fixtureDataGovSg()).importDataset();

        SnapshotManifest manifest = reader().read(out.resolve(VERSION).toUri().toString()).manifest();
        assertThat(manifest.formatVersion()).isEqualTo(SnapshotManifest.FORMAT_VERSION);
        SchoolRecord catholic = writtenSchools().get("catholic-high-school");
        assertThat(catholic.busServices()).containsExactly("13", "52", "54", "88", "156", "162", "162M", "410");
        assertThat(catholic.mrtStations()).containsExactly("BISHAN MRT");
        assertThat(writtenSchools().get("chij-st-theresas-convent").mrtStations())
                .containsExactly("HARBOURFRONT MRT", "TIONG BAHRU MRT");
        String json = Files.readString(out.resolve(VERSION).resolve(SnapshotReader.SCHOOLS_FILE));
        assertThat(json).contains("\"busServices\"").contains("\"mrtStations\"")
                .doesNotContain("\"busInfo\"").doesNotContain("\"nearestMrt\"");
        assertThat(Files.readString(out.resolve(VERSION).resolve(SnapshotReader.MANIFEST_FILE)))
                .contains("\"formatVersion\" : 2");
    }

    @Test
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-Import-11: transport-overrides.csv applies while MOE's text is unchanged; a stale row is reported; an irregular text without a row fails transport-list")
    void transportOverrides() throws IOException {
        writeCurated(CuratedCsvReader.TRANSPORT_OVERRIDES, "school_code,kind,published_text,elements,reason\n"
                + "catholic-high-school,bus,SBS Transit No 13 & 52,13;52,operator name\n"
                + "hua-yi-secondary-school,mrt,LAKESIDE MRT & CHINESE GARDEN MRT,LAKESIDE MRT;CHINESE GARDEN MRT,old\n"
                + "no-such-school,bus,1 & 2,1;2,typo\n");
        List<DataGovSgRecord> schools = new ArrayList<>(fixtureRecords("schools.json"));
        schools.replaceAll(r -> switch (r.get("school_name")) {
            case "CATHOLIC HIGH SCHOOL" -> with(r, "bus_desc", "SBS Transit No 13 & 52");
            case "ST. HILDA'S SECONDARY SCHOOL" -> with(r, "mrt_desc", "TAMPINES MRT & TAMPINES WEST MRT");
            default -> r;
        });

        ValidationReport report = controller(Map.of(), dataGovSg(onlySecondary(schools))).importDataset();

        assertThat(writtenSchools().get("catholic-high-school").busServices()).containsExactly("13", "52");
        assertThat(writtenSchools().get("hua-yi-secondary-school").mrtStations())
                .containsExactly("LAKESIDE MRT", "CHINESE GARDEN MRT");
        assertThat(report.getWarnings()).anyMatch(w -> w.startsWith(ImportLog.TRANSPORT_OVERRIDE_STALE
                + ": hua-yi-secondary-school mrt"));
        assertThat(report.getWarnings()).anyMatch(w -> w.startsWith(ImportLog.CURATED + ": "
                + CuratedCsvReader.TRANSPORT_OVERRIDES + ": school_code no-such-school"));
        assertThat(report.getErrors()).anyMatch(e -> e.startsWith(SnapshotValidator.TRANSPORT_LIST
                + ": st-hildas-secondary-school: MRT station 'TAMPINES MRT & TAMPINES WEST MRT'"));
        assertThat(report.getErrors()).filteredOn(e -> e.startsWith(SnapshotValidator.TRANSPORT_LIST + ":"))
                .as("the override and the stale row's plain split pass").hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    private SchoolDataController controller(Map<String, String> settings, DataGovSgInterface dataGovSg) {
        Map<String, String> all = new HashMap<>(settings);
        all.put("app.dataset.snapshot-location", "classpath:fixtures/snapshot-mini/");
        all.put("app.dataset.import-output-dir", out.toString());
        all.put("app.dataset.load-on-startup", "false");   // as in the import profile: no snapshot read, no database
        AppProperties props = props(all);
        return new SchoolDataController(dataGovSg, oneMap, new SnapshotReader(props, new DefaultResourceLoader()),
                new SnapshotValidator(), new CuratedCsvReader(curated.toString()), new SnapshotWriter(), props, clock, store);
    }

    /** {@code count} made-up schools in Bishan, each with a OneMap hit named like it and one CCA and subject. */
    private DataGovSgInterface syntheticDataGovSg(int count) {
        List<DataGovSgRecord> schools = new ArrayList<>();
        List<DataGovSgRecord> ccas = new ArrayList<>();
        List<DataGovSgRecord> subjects = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String name = String.format("TEST SECONDARY SCHOOL %03d", i);
            String postal = String.format("57%04d", i);
            schools.add(row("school_name", name, "postal_code", postal, "address", i + " BISHAN STREET 22  ",
                    "telephone_no", "na", "email_address", "test" + i + "@example.test", "dgp_code", "BISHAN",
                    "type_code", "GOVERNMENT SCHOOL", "mainlevel_code", "SECONDARY (S1-S5)"));
            ccas.add(row("School_name", name, "school_section", "SECONDARY (S1-S5)", "cca_grouping_desc", "CHOIR"));
            subjects.add(row("School_Name", name, "Subject_Desc", "Art"));
            oneMapAnswers.put(postal, List.of(hit(name, 1.3540 + (i % 10) * 0.0001, 103.8440 + (i / 10) * 0.0001)));
        }
        return new FakeDataGovSg(schools, ccas, subjects, fixtureText("planning-areas.geojson"));
    }

    private DataGovSgInterface fixtureDataGovSg() {
        return dataGovSg(onlySecondary(fixtureRecords("schools.json")));
    }

    private DataGovSgInterface dataGovSg(List<DataGovSgRecord> schools) {
        return new FakeDataGovSg(schools, fixtureRecords("cca.json"), fixtureRecords("subjects.json"),
                fixtureText("planning-areas.geojson"));
    }

    private record FakeDataGovSg(List<DataGovSgRecord> schools, List<DataGovSgRecord> ccas,
                                 List<DataGovSgRecord> subjects, String districts) implements DataGovSgInterface {
        public List<DataGovSgRecord> fetchSchools() {
            return schools;
        }

        public List<DataGovSgRecord> fetchSchoolCcas() {
            return ccas;
        }

        public List<DataGovSgRecord> fetchSchoolSubjects() {
            return subjects;
        }

        public String fetchDistrictsGeoJson() {
            return districts;
        }
    }

    private static List<DataGovSgRecord> onlySecondary(List<DataGovSgRecord> schools) {
        return schools.stream().filter(r -> r.get("mainlevel_code").startsWith("SECONDARY")
                || r.get("mainlevel_code").startsWith("MIXED LEVEL")).toList();
    }

    private Map<String, SchoolRecord> writtenSchools() {
        Map<String, SchoolRecord> byCode = new LinkedHashMap<>();
        reader().read(out.resolve(VERSION).toUri().toString()).records().forEach(r -> byCode.put(r.schoolCode(), r));
        return byCode;
    }

    private SnapshotReader reader() {
        return new SnapshotReader(props(Map.of()), new DefaultResourceLoader());
    }

    private static AppProperties props(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("app", AppProperties.class);
    }

    private void writeCurated(String file, String content) throws IOException {
        Files.writeString(curated.resolve(file), content, UTF_8);
    }

    private static OneMapHit hit(String building, double latitude, double longitude) {
        return new OneMapHit(building, building, "ADDRESS OF " + building, null, latitude, longitude);
    }

    private static DataGovSgRecord row(String... namesAndValues) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            fields.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return new DataGovSgRecord(fields);
    }

    private static DataGovSgRecord with(DataGovSgRecord r, String field, String value) {
        Map<String, String> fields = new LinkedHashMap<>(r.fields());
        fields.put(field, value);
        return new DataGovSgRecord(fields);
    }

    private static List<DataGovSgRecord> fixtureRecords(String file) {
        JsonNode root = JSON.readTree(fixtureText(file));
        List<DataGovSgRecord> rows = new ArrayList<>();
        for (JsonNode record : root.path("result").path("records").values()) {
            Map<String, String> fields = new LinkedHashMap<>();
            record.properties().forEach(e -> fields.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
            rows.add(new DataGovSgRecord(fields));
        }
        return rows;
    }

    private static String fixtureText(String file) {
        try (InputStream in = SchoolDataControllerImportTest.class
                .getResourceAsStream("/fixtures/external/datagovsg/" + file)) {
            return new String(in.readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

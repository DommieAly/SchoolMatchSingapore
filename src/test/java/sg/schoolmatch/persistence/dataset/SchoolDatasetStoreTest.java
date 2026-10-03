package sg.schoolmatch.persistence.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.dataset.LoadedSnapshot;
import sg.schoolmatch.dataset.SchoolRecord;
import sg.schoolmatch.dataset.SnapshotManifest;
import sg.schoolmatch.dataset.SnapshotReader;
import sg.schoolmatch.dataset.SnapshotValidator;
import sg.schoolmatch.dataset.ValidationReport;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.support.FixedClock;

/**
 * SchoolDatasetStore.ensureLoaded: the loader's write path (docs/database-design.md, sections 6.2, 6.4, 6.5 and
 * 10). Runs on H2 ({@link H2SchoolDatasetStoreTest}) and embedded PostgreSQL ({@link PostgresSchoolDatasetStoreTest}).
 * <p>
 * The database is migrated by Flyway before Spring starts. The store commits for real (no test transaction), so
 * every test starts by emptying the school tables.
 */
@JdbcTest
@ActiveProfiles("test")
@Import(SchoolDatasetStoreTest.Stores.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // the store's own transactions must commit
abstract class SchoolDatasetStoreTest {

    static final String MINI = "classpath:fixtures/snapshot-mini/";

    /** The store with the default {@code allow-rollback: true}, and one with false (as in prod). */
    @TestConfiguration
    static class Stores {

        @Bean
        FixedClock clock() {
            return FixedClock.atDefault();
        }

        @Bean
        SchoolDatasetStore store(NamedParameterJdbcTemplate jdbc, FixedClock clock) {
            return new SchoolDatasetStore(jdbc, clock, true);
        }

        @Bean
        SchoolDatasetStore strictStore(NamedParameterJdbcTemplate jdbc, FixedClock clock) {
            return new SchoolDatasetStore(jdbc, clock, false);
        }
    }

    @Autowired
    @Qualifier("store")
    private SchoolDatasetStore store;

    @Autowired
    @Qualifier("strictStore")
    private SchoolDatasetStore strictStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FixedClock clock;

    private final SnapshotReader reader = new SnapshotReader(
            new Binder().bindOrCreate("app", AppProperties.class), new DefaultResourceLoader());   // defaults
    private final SnapshotValidator validator = new SnapshotValidator();
    private final LoadedSnapshot mini = reader.read(MINI);

    /** Migrates the empty database at {@code url} with every Flyway migration (V1, V2) before Spring starts. */
    static void migrate(String url, String user, String password) {
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void emptySchoolTables() {
        clock.setInstant(FixedClock.DEFAULT_INSTANT);   // one clock bean for every test of the class
        jdbc.update("UPDATE active_dataset SET dataset_version = NULL");
        for (String table : List.of("indicative_psle_score_range", "school_cca", "school_programme",
                "school_affiliated_primary", "school_bus_service", "school_mrt_station", "shortlist_school",
                "shortlist", "account", "school", "district", "dataset_version")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    // ---------------------------------------------------------------------------------------------- tests

    @Test
    @Tag("FR-DATA-02")
    @Tag("FR-DATA-03")
    @DisplayName("TC-SchoolDatasetStore-01: the first load writes the version, its sources and warnings, the areas, schools and all child rows, and activates it")
    void firstLoad() {
        assertThat(store.ensureLoaded(mini, validator.validate(mini), sha(mini))).isEqualTo(SchoolDatasetStore.Outcome.LOADED);

        assertThat(active()).isEqualTo("0000-seed");
        assertThat(count("dataset_version")).isOne();
        assertThat(count("dataset_source")).isEqualTo(mini.manifest().sources().size());
        assertThat(count("dataset_warning")).isEqualTo(mini.manifest().warnings().size());
        assertThat(count("district")).isEqualTo(3);
        assertThat(count("school")).isEqualTo(10);
        assertThat(count("indicative_psle_score_range")).isEqualTo(29);
        assertThat(count("school_bus_service"))
                .isEqualTo(mini.records().stream().mapToInt(r -> r.busServices().size()).sum());
        assertThat(count("school_mrt_station"))
                .isEqualTo(mini.records().stream().mapToInt(r -> r.mrtStations().size()).sum());
        assertThat(count("school_cca")).isEqualTo(mini.schools().stream().mapToInt(s -> s.getCcas().size()).sum());
        assertThat(count("school_programme"))
                .isEqualTo(mini.schools().stream().mapToInt(s -> s.getProgrammes().size()).sum());

        Map<String, Object> version = jdbc.queryForMap("SELECT dataset_kind, effective_date, manifest_status,"
                + " load_status, content_sha256, loader_format FROM dataset_version");
        assertThat(version).containsEntry("dataset_kind", "seed").containsEntry("manifest_status", "PASSED_WITH_WARNINGS")
                .containsEntry("load_status", "PASSED_WITH_WARNINGS").containsEntry("content_sha256", sha(mini))
                .containsEntry("loader_format", SchoolDatasetStore.FORMAT);
        assertThat(loadedAt()).isEqualTo(FixedClock.DEFAULT_INSTANT);
        assertThat(jdbc.queryForObject("SELECT source_id FROM dataset_source WHERE source_no = 1", String.class))
                .isEqualTo(mini.manifest().sources().getFirst().datasetId());

        Map<String, Object> school = jdbc.queryForMap("SELECT school_name, postal_code, planning_area_code, latitude,"
                + " withdrawn_in_version FROM school WHERE school_code = 'catholic-high-school'");
        assertThat(school).containsEntry("school_name", "CATHOLIC HIGH SCHOOL").containsEntry("postal_code", "579767")
                .containsEntry("planning_area_code", "BS").containsEntry("latitude", 1.354525)
                .containsEntry("withdrawn_in_version", null);
        assertThat(jdbc.queryForList("SELECT service_no FROM school_bus_service WHERE school_code ="
                + " 'catholic-high-school' ORDER BY list_position", String.class))
                .containsExactly("13", "52", "54", "88", "156", "162", "162M", "410");
        // the seed has no MOE text: places_left NULL, so the text stays unknown (design section 5.2)
        assertThat(count("indicative_psle_score_range WHERE places_left IS NULL")).isEqualTo(29);
    }

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-SchoolDatasetStore-02: loading the same snapshot again changes nothing, and loaded_at stays the same")
    void secondLoadChangesNothing() {
        store.ensureLoaded(mini, validator.validate(mini), sha(mini));
        clock.advance(Duration.ofHours(2));

        assertThat(store.ensureLoaded(mini, validator.validate(mini), sha(mini)))
                .isEqualTo(SchoolDatasetStore.Outcome.UNCHANGED);
        assertThat(strictStore.ensureLoaded(mini, validator.validate(mini), sha(mini)))
                .isEqualTo(SchoolDatasetStore.Outcome.UNCHANGED);
        assertThat(loadedAt()).isEqualTo(FixedClock.DEFAULT_INSTANT);
        assertThat(count("school")).isEqualTo(10);
        assertThat(count("dataset_source")).isEqualTo(mini.manifest().sources().size());
    }

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-SchoolDatasetStore-03: a changed hash, or a new loader_format, loads the snapshot again")
    void changedHashOrFormatReloads() {
        store.ensureLoaded(mini, validator.validate(mini), sha(mini));
        clock.advance(Duration.ofHours(1));

        assertThat(store.ensureLoaded(mini, validator.validate(mini), hash("rebuilt in place")))
                .isEqualTo(SchoolDatasetStore.Outcome.LOADED);
        assertThat(jdbc.queryForObject("SELECT content_sha256 FROM dataset_version", String.class))
                .isEqualTo(hash("rebuilt in place"));
        assertThat(loadedAt()).isEqualTo(FixedClock.DEFAULT_INSTANT.plus(Duration.ofHours(1)));

        jdbc.update("UPDATE dataset_version SET loader_format = ?", SchoolDatasetStore.FORMAT + 1);
        clock.advance(Duration.ofHours(1));
        assertThat(store.ensureLoaded(mini, validator.validate(mini), hash("rebuilt in place")))
                .isEqualTo(SchoolDatasetStore.Outcome.LOADED);
        assertThat(jdbc.queryForObject("SELECT loader_format FROM dataset_version", Integer.class))
                .isEqualTo(SchoolDatasetStore.FORMAT);
        assertThat(count("dataset_version")).isOne();
        assertThat(count("school")).isEqualTo(10);
        assertThat(count("dataset_source")).isEqualTo(mini.manifest().sources().size());
    }

    @Test
    @Tag("FR-DATA-02")
    @Tag("FR-DATA-05")
    @DisplayName("TC-SchoolDatasetStore-04: a school missing from a newer snapshot is withdrawn, not deleted; a shortlist row on it survives")
    void missingSchoolIsWithdrawn() {
        store.ensureLoaded(mini, validator.validate(mini), sha(mini));
        saveToShortlist("westwood-secondary-school");

        LoadedSnapshot v2 = variant("2026-10-05.1", 1, r -> !r.schoolCode().equals("westwood-secondary-school"),
                d -> true, UnaryOperator.identity());
        assertThat(store.ensureLoaded(v2, validator.validate(v2), hash("v2"))).isEqualTo(SchoolDatasetStore.Outcome.LOADED);

        assertThat(active()).isEqualTo("2026-10-05.1");
        assertThat(count("school")).isEqualTo(10);
        assertThat(count("school WHERE withdrawn_in_version IS NULL")).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT withdrawn_in_version FROM school WHERE school_code ="
                + " 'westwood-secondary-school'", String.class)).isEqualTo("2026-10-05.1");
        assertThat(count("school_cca WHERE school_code = 'westwood-secondary-school'")).isZero();
        assertThat(count("school_bus_service WHERE school_code = 'westwood-secondary-school'")).isZero();
        assertThat(count("shortlist_school WHERE school_code = 'westwood-secondary-school'")).isOne();
        assertThat(count("dataset_version")).isEqualTo(2);   // history is kept
    }

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-SchoolDatasetStore-05: loading back a snapshot that has the school reactivates it (rollback, design section 6.5)")
    void loadingBackReactivates() {
        LoadedSnapshot v2 = variant("2026-10-05.1", 1, r -> !r.schoolCode().equals("westwood-secondary-school"),
                d -> true, UnaryOperator.identity());
        store.ensureLoaded(mini, validator.validate(mini), sha(mini));
        store.ensureLoaded(v2, validator.validate(v2), hash("v2"));

        assertThat(store.ensureLoaded(mini, validator.validate(mini), sha(mini)))
                .isEqualTo(SchoolDatasetStore.Outcome.LOADED);

        assertThat(active()).isEqualTo("0000-seed");
        assertThat(count("school WHERE withdrawn_in_version IS NULL")).isEqualTo(10);
        assertThat(count("school_cca WHERE school_code = 'westwood-secondary-school'")).isPositive();
        assertThat(count("dataset_version")).isEqualTo(2);
    }

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-SchoolDatasetStore-06: a planning area missing from a newer snapshot is withdrawn with its schools")
    void missingDistrictIsWithdrawn() {
        store.ensureLoaded(mini, validator.validate(mini), sha(mini));
        LoadedSnapshot v3 = variant("2026-10-06.1", 2, r -> !"TM".equals(r.planningAreaCode()),
                d -> !"TM".equals(d.getPlanningAreaCode()), UnaryOperator.identity());
        long tampines = mini.records().stream().filter(r -> "TM".equals(r.planningAreaCode())).count();

        assertThat(store.ensureLoaded(v3, validator.validate(v3), hash("v3"))).isEqualTo(SchoolDatasetStore.Outcome.LOADED);

        assertThat(count("district")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT withdrawn_in_version FROM district WHERE planning_area_code = 'TM'",
                String.class)).isEqualTo("2026-10-06.1");
        assertThat(count("district WHERE withdrawn_in_version IS NULL")).isEqualTo(2);
        assertThat((long) count("school WHERE withdrawn_in_version = '2026-10-06.1'")).isEqualTo(tampines).isPositive();
    }

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-SchoolDatasetStore-07: an older snapshot is refused when allow-rollback is false and loaded when it is true")
    void olderSnapshotNeedsAllowRollback() {
        LoadedSnapshot newer = variant("2026-10-05.1", 1, r -> true, d -> true, UnaryOperator.identity());
        store.ensureLoaded(newer, validator.validate(newer), hash("newer"));

        assertThat(strictStore.ensureLoaded(mini, validator.validate(mini), sha(mini)))
                .isEqualTo(SchoolDatasetStore.Outcome.KEPT_NEWER);
        assertThat(active()).isEqualTo("2026-10-05.1");
        assertThat(count("dataset_version")).isOne();

        assertThat(store.ensureLoaded(mini, validator.validate(mini), sha(mini)))
                .isEqualTo(SchoolDatasetStore.Outcome.LOADED);
        assertThat(active()).isEqualTo("0000-seed");
    }

    @Test
    @Tag("FR-DATA-02")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchoolDatasetStore-08: a failure in the middle of a load rolls everything back; the old version stays active")
    void failureRollsBack() {
        store.ensureLoaded(mini, validator.validate(mini), sha(mini));
        int buses = count("school_bus_service");
        String first = mini.records().getFirst().schoolCode();
        // a bus element the database refuses (ck_school_bus_service_no), written after versions, areas and schools
        LoadedSnapshot broken = variant("2026-10-05.1", 1, r -> !r.schoolCode().equals("westwood-secondary-school"),
                d -> true, r -> r.schoolCode().equals(first) ? withBuses(r, List.of("13", "243G/W")) : r);

        assertThatThrownBy(() -> store.ensureLoaded(broken, new ValidationReport(List.of(), List.of()), hash("broken")))
                .isInstanceOf(DataAccessException.class);

        assertThat(active()).isEqualTo("0000-seed");
        assertThat(count("dataset_version")).isOne();
        assertThat(count("dataset_source WHERE dataset_version = '2026-10-05.1'")).isZero();
        assertThat(count("school WHERE withdrawn_in_version IS NULL")).isEqualTo(10);
        assertThat(count("school_bus_service")).isEqualTo(buses);
    }

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-SchoolDatasetStore-09: two threads load at once; exactly one does the work, the other finds it done")
    void twoThreadsLoadOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<SchoolDatasetStore.Outcome> load = () -> {
            start.await();
            return store.ensureLoaded(mini, validator.validate(mini), sha(mini));
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SchoolDatasetStore.Outcome> a = pool.submit(load);
            Future<SchoolDatasetStore.Outcome> b = pool.submit(load);
            start.countDown();
            List<SchoolDatasetStore.Outcome> outcomes = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder(SchoolDatasetStore.Outcome.LOADED,
                    SchoolDatasetStore.Outcome.UNCHANGED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("dataset_version")).isOne();
        assertThat(count("school")).isEqualTo(10);
        assertThat(count("indicative_psle_score_range")).isEqualTo(29);
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchoolDatasetStore-10: a FAILED snapshot, or one whose manifest counts disagree with its rows, is never written")
    void refusesFailedOrMiscounted() {
        ValidationReport failed = new ValidationReport(List.of("bad-coordinate: x"), List.of());
        assertThatThrownBy(() -> store.ensureLoaded(mini, failed, sha(mini))).isInstanceOf(IllegalArgumentException.class);

        SnapshotManifest m = mini.manifest();
        Map<String, Integer> counts = new LinkedHashMap<>(m.counts());
        counts.put("schools", 11);
        LoadedSnapshot miscounted = new LoadedSnapshot(mini.location(), new SnapshotManifest(m.formatVersion(),
                m.kind(), m.version(), m.effectiveDate(), m.importedAt(), m.sources(), m.validationStatus(),
                m.warnings(), counts, m.notes()), mini.records(), mini.schools(), mini.districts());
        assertThatThrownBy(() -> store.ensureLoaded(miscounted, validator.validate(miscounted), sha(mini)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("11 schools");

        assertThat(active()).isNull();
        assertThat(count("dataset_version")).isZero();
        assertThat(count("school")).isZero();
    }

    @Test
    @Tag("FR-DATA-02")
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchoolDatasetStore-11: the real ACTIVE snapshot loads: 147 schools, 55 areas, 444 ranges, 1,492 bus and 243 MRT rows")
    void realActiveSnapshotLoads() {
        LoadedSnapshot active = reader.read();
        ValidationReport report = validator.validate(active);

        assertThat(store.ensureLoaded(active, report, reader.contentSha256(active)))
                .isEqualTo(SchoolDatasetStore.Outcome.LOADED);

        assertThat(count("school")).isEqualTo(active.records().size()).isEqualTo(147);
        assertThat(count("district")).isEqualTo(55);
        assertThat(count("indicative_psle_score_range")).isEqualTo(444);
        assertThat(count("school_bus_service")).isEqualTo(1492);
        assertThat(count("school_mrt_station")).isEqualTo(243);
        assertThat(count("school_cca")).isEqualTo(active.schools().stream().mapToInt(s -> s.getCcas().size()).sum());
        assertThat(count("school_programme"))
                .isEqualTo(active.schools().stream().mapToInt(s -> s.getProgrammes().size()).sum());
        assertThat(count("school_affiliated_primary"))
                .isEqualTo(active.schools().stream().mapToInt(s -> s.getAffiliatedPrimarySchools().size()).sum());
        assertThat(count("indicative_psle_score_range WHERE places_left IS NULL")).isZero();
        assertThat(jdbc.queryForObject("SELECT load_status FROM dataset_version", String.class))
                .isEqualTo(report.getStatus().name());
    }

    @Test
    @Tag("FR-DATA-02")
    @Tag("FR-DATA-05")
    @DisplayName("TC-SchoolDatasetStore-12: readActive() leaves out a withdrawn school and area, so the cache has no such school, while the shortlist row on it survives")
    void readActiveLeavesOutWithdrawn() {
        store.ensureLoaded(mini, validator.validate(mini), sha(mini));
        saveToShortlist("westwood-secondary-school");
        LoadedSnapshot v3 = variant("2026-10-06.1", 2,
                r -> !"TM".equals(r.planningAreaCode()) && !r.schoolCode().equals("westwood-secondary-school"),
                d -> !"TM".equals(d.getPlanningAreaCode()), UnaryOperator.identity());
        store.ensureLoaded(v3, validator.validate(v3), hash("v3"));

        SnapshotRows rows = store.readActive().orElseThrow();

        assertThat(rows.version().datasetVersion()).isEqualTo("2026-10-06.1");
        assertThat(rows.schools()).extracting(SchoolRow::schoolCode)
                .doesNotContain("westwood-secondary-school").hasSize(v3.records().size());
        assertThat(rows.districts()).extracting(DistrictRow::planningAreaCode).containsExactlyInAnyOrder("BS", "JW");
        assertThat(rows.schools()).extracting(SchoolRow::withdrawnInVersion).containsOnlyNulls();
        SchoolDataCache cache = SchoolDatasetMapper.cache(rows, "test", clock.instant(), clock.instant());
        assertThat(cache.findByCode("westwood-secondary-school")).isEmpty();
        assertThat(cache.size()).isEqualTo(v3.records().size());
        assertThat(cache.getDistricts()).extracting(District::getPlanningAreaCode).containsExactly("BS", "JW");
        assertThat(count("school")).isEqualTo(10);
        assertThat(count("shortlist_school WHERE school_code = 'westwood-secondary-school'")).isOne();
    }

    @Test
    @Tag("FR-DATA-02")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchoolDatasetStore-13: activeState() names the active version and its hash; before the first load it and readActive() are empty")
    void activeStateAndEmptyDatabase() {
        assertThat(store.activeState()).isEmpty();
        assertThat(store.readActive()).isEmpty();

        store.ensureLoaded(mini, validator.validate(mini), sha(mini));

        assertThat(store.activeState()).contains(new SchoolDatasetStore.ActiveState("0000-seed", sha(mini)));
        LoadedSnapshot v2 = variant("2026-10-05.1", 1, r -> true, d -> true, UnaryOperator.identity());
        store.ensureLoaded(v2, validator.validate(v2), hash("v2"));
        assertThat(store.activeState()).contains(new SchoolDatasetStore.ActiveState("2026-10-05.1", hash("v2")));
    }

    @Test
    @Tag("FR-DATA-05")
    @Tag("FR-SHORTLIST-05")
    @DisplayName("TC-SchoolDatasetStore-14: schoolNames() gives the last-known name of active and withdrawn schools and nothing for an unknown code")
    void schoolNamesIncludeWithdrawnSchools() {
        assertThat(store.schoolNames(List.of("westwood-secondary-school"))).isEmpty();   // nothing loaded yet
        store.ensureLoaded(mini, validator.validate(mini), sha(mini));
        String westwood = jdbc.queryForObject(
                "SELECT school_name FROM school WHERE school_code = 'westwood-secondary-school'", String.class);
        LoadedSnapshot v2 = variant("2026-10-05.1", 1,
                r -> !r.schoolCode().equals("westwood-secondary-school"), d -> true, UnaryOperator.identity());
        store.ensureLoaded(v2, validator.validate(v2), hash("v2"));

        assertThat(store.schoolNames(List.of("westwood-secondary-school", "catholic-high-school", "no-such-school")))
                .containsOnlyKeys("westwood-secondary-school", "catholic-high-school")
                .containsEntry("westwood-secondary-school", westwood);
        assertThat(store.schoolNames(List.of())).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private int count(String tableAndWhere) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + tableAndWhere, Integer.class);
    }

    private String active() {
        return jdbc.queryForObject("SELECT dataset_version FROM active_dataset WHERE singleton_id = 1", String.class);
    }

    private Instant loadedAt() {
        return jdbc.queryForObject("SELECT loaded_at FROM dataset_version", OffsetDateTime.class).toInstant();
    }

    private String sha(LoadedSnapshot snapshot) {
        return reader.contentSha256(snapshot);
    }

    /** A made-up content hash (64 lower-case hex digits). */
    private static String hash(String seed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * snapshot-mini as a later version: imported {@code daysLater} days after it, with only the schools and areas
     * kept, each school changed by {@code change}; the manifest counts follow.
     */
    private LoadedSnapshot variant(String version, int daysLater, Predicate<SchoolRecord> keepSchool,
                                   Predicate<District> keepDistrict, UnaryOperator<SchoolRecord> change) {
        List<SchoolRecord> records = new ArrayList<>(mini.records().stream().filter(keepSchool).map(change).toList());
        List<District> districts = mini.districts().stream().filter(keepDistrict).toList();
        SnapshotManifest m = mini.manifest();
        Map<String, Integer> counts = Map.of("schools", records.size(), "districts", districts.size(),
                "scoreRanges", records.stream().mapToInt(r -> r.scoreRanges().size()).sum());
        SnapshotManifest manifest = new SnapshotManifest(m.formatVersion(), m.kind(), version,
                m.effectiveDate().plusDays(daysLater), m.importedAt().plus(Duration.ofDays(daysLater)), m.sources(),
                m.validationStatus(), m.warnings(), counts, m.notes());
        return new LoadedSnapshot(mini.location(), manifest, records,
                records.stream().map(SnapshotReader::toSchool).toList(), districts);
    }

    private static SchoolRecord withBuses(SchoolRecord r, List<String> buses) {
        return new SchoolRecord(r.schoolCode(), r.name(), r.address(), r.postalCode(), r.latitude(), r.longitude(),
                r.telephone(), r.website(), r.email(), r.schoolType(), r.planningAreaCode(), r.planningAreaName(),
                r.mrtStations(), buses, r.sessionType(), r.schoolNature(), r.programmes(), r.ccas(),
                r.affiliatedPrimarySchools(), r.ipRangeNote(), r.scoreRanges());
    }

    private void saveToShortlist(String schoolCode) {
        jdbc.update("INSERT INTO account (account_id, username, username_key, email, password_hash, created_at,"
                + " status) VALUES ('a1', 'Alice', 'alice', 'alice@example.com', 'h', CURRENT_TIMESTAMP, 'ACTIVE')");
        jdbc.update("INSERT INTO shortlist (account_id) VALUES ('a1')");
        jdbc.update("INSERT INTO shortlist_school (account_id, school_code) VALUES ('a1', ?)", schoolCode);
    }
}

package sg.schoolmatch.persistence.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.dataset.LoadedSnapshot;
import sg.schoolmatch.dataset.SnapshotReader;
import sg.schoolmatch.dataset.SnapshotValidator;
import sg.schoolmatch.dataset.ValidationReport;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.entity.search.AttributeCategory;
import sg.schoolmatch.support.FixedClock;

/**
 * The read path (docs/database-design.md, sections 6.3, 7.2 and 10): a snapshot loaded by
 * {@link SchoolDatasetStore#ensureLoaded} and read back with {@link SchoolDatasetStore#readActive()} and
 * {@link SchoolDatasetMapper} gives exactly what {@link SnapshotReader} builds from the JSON files: every attribute of
 * every {@code School}, {@code IndicativePsleScoreRange} and {@code District}, the {@code SnapshotManifest} of the
 * about page, and the {@code SchoolDataCache} the pages use.
 * <p>
 * Snapshots: the two valid test fixtures, the seed {@code 0000-seed} and the real {@code ACTIVE}. The broken fixtures
 * FAIL validation and are never loaded. Runs on H2 ({@link H2SchoolDatasetRoundTripTest}) and embedded PostgreSQL
 * ({@link PostgresSchoolDatasetRoundTripTest}).
 * <p>
 * Orders: the database has no order, so the mapper sorts in Java (section 6.3). The JSON side is compared in the same
 * order where the JSON has none of its own: PSLE ranges (a set keyed by year, posting group, affiliation and IP; the
 * details page sorts them with this order anyway) and planning areas (compared by code).
 */
@JdbcTest
@ActiveProfiles("test")
@Import(SchoolDatasetRoundTripTest.Store.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // the store's own transactions must commit
abstract class SchoolDatasetRoundTripTest {

    static final String MINI = "classpath:fixtures/snapshot-mini/";
    static final String MINI_NO_PSLE = "classpath:fixtures/snapshot-mini-no-psle/";
    static final String SEED = Path.of("data", "snapshots", "0000-seed").toAbsolutePath().toUri().toString();

    @TestConfiguration
    static class Store {

        @Bean
        FixedClock clock() {
            return FixedClock.atDefault();
        }

        @Bean
        SchoolDatasetStore store(NamedParameterJdbcTemplate jdbc, FixedClock clock) {
            return new SchoolDatasetStore(jdbc, clock, true);
        }
    }

    @Autowired
    private SchoolDatasetStore store;

    @Autowired
    private JdbcTemplate jdbc;

    private static final SnapshotReader READER = new SnapshotReader(
            new Binder().bindOrCreate("app", AppProperties.class), new DefaultResourceLoader());   // defaults: ACTIVE
    private final SnapshotValidator validator = new SnapshotValidator();

    /** Migrates the empty database at {@code url} with every Flyway migration before Spring starts. */
    static void migrate(String url, String user, String password) {
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate();
    }

    /** The four snapshots: both valid fixtures, the seed and the real ACTIVE folder. */
    static Stream<String> snapshots() {
        return Stream.of(MINI, MINI_NO_PSLE, SEED, READER.resolveLocation());
    }

    @BeforeEach
    void emptySchoolTables() {
        jdbc.update("UPDATE active_dataset SET dataset_version = NULL");
        for (String table : List.of("indicative_psle_score_range", "school_cca", "school_programme",
                "school_affiliated_primary", "school_bus_service", "school_mrt_station", "school", "district",
                "dataset_version")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    // ---------------------------------------------------------------------------------------------- tests

    @ParameterizedTest(name = "TC-SchoolDatasetRoundTrip-01 [{index}] {0}")
    @MethodSource("snapshots")
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchoolDatasetRoundTrip-01: every School, IndicativePsleScoreRange and District attribute, the manifest and the cache built from the database equal those built from the JSON")
    void roundTrip(String location) {
        LoadedSnapshot snapshot = READER.read(location);
        ValidationReport report = validator.validate(snapshot);
        assertThat(report.isUsable()).as("%s validates", location).isTrue();

        store.ensureLoaded(snapshot, report, READER.contentSha256(snapshot));

        assertSameDataset(snapshot, report, store.readActive().orElseThrow());
    }

    @Test
    @Tag("FR-DATA-02")
    @Tag("FR-DATA-03")
    @DisplayName("TC-SchoolDatasetRoundTrip-02: the four snapshots loaded one after another into one database: after each load the database gives exactly that snapshot (withdrawn schools and areas left out), and loading ACTIVE again brings every school back")
    void loadedOneAfterAnother() {
        List<String> order = new ArrayList<>(snapshots().toList());
        order.add(order.removeFirst());   // MINI_NO_PSLE, SEED, ACTIVE, MINI
        order.addFirst(READER.resolveLocation());   // ACTIVE first, so the fixtures withdraw most of its schools
        for (String location : order) {
            LoadedSnapshot snapshot = READER.read(location);
            ValidationReport report = validator.validate(snapshot);
            store.ensureLoaded(snapshot, report, READER.contentSha256(snapshot));

            assertSameDataset(snapshot, report, store.readActive().orElseThrow());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM school WHERE withdrawn_in_version IS NOT NULL",
                Integer.class)).as("ACTIVE ran last but one, so only the schools missing from snapshot-mini are"
                + " withdrawn").isPositive();
    }

    // ---------------------------------------------------------------------------------------------- comparison

    private static final Instant NOW = FixedClock.DEFAULT_INSTANT;

    /** Everything the app reads from {@code rows} equals what it reads from {@code json}. */
    static void assertSameDataset(LoadedSnapshot json, ValidationReport report, SnapshotRows rows) {
        SoftAssertions softly = new SoftAssertions();
        String label = json.location();

        // Schools: every attribute, in the order of SchoolDataCache.BY_NAME_THEN_CODE.
        List<School> fromDb = SchoolDatasetMapper.schools(rows);
        List<School> fromJson = json.schools().stream().sorted(SchoolDataCache.BY_NAME_THEN_CODE).toList();
        softly.assertThat(codes(fromDb)).as("%s: school codes and order", label).isEqualTo(codes(fromJson));
        Map<String, School> dbByCode = new LinkedHashMap<>();
        fromDb.forEach(s -> dbByCode.put(s.getSchoolCode(), s));
        for (School expected : fromJson) {
            School actual = dbByCode.get(expected.getSchoolCode());
            if (actual != null) {
                softly.assertThat(describe(actual, Function.identity())).as("%s: %s", label, expected.getSchoolCode())
                        .isEqualTo(describe(expected, SchoolDatasetRoundTripTest::sortedRanges));
            }
        }

        // Planning areas: code, name and the boundary text.
        softly.assertThat(describeDistricts(SchoolDatasetMapper.districts(rows))).as("%s: districts", label)
                .isEqualTo(describeDistricts(json.districts()));

        // The about page's manifest, as a whole record.
        softly.assertThat(SchoolDatasetMapper.manifest(rows)).as("%s: manifest", label).isEqualTo(json.manifest());

        // The cache the pages use, against the one SchoolDataController built from the JSON before step 6.
        SchoolDataCache db = SchoolDatasetMapper.cache(rows, "source", NOW, NOW.plus(Duration.ofHours(1)));
        SchoolDataCache expected = new SchoolDataCache("source", NOW, NOW.plus(Duration.ofHours(1)), json.schools(),
                json.districts());
        expected.setDatasetVersion(json.manifest().version());
        expected.setDatasetKind(json.manifest().kind());
        expected.setEffectiveDate(json.manifest().effectiveDate());
        expected.setImportedAt(json.manifest().importedAt());
        expected.setValidationStatus(report.getStatus());
        softly.assertThat(describe(db)).as("%s: cache", label).isEqualTo(describe(expected));
        softly.assertAll();
    }

    private static List<String> codes(List<School> schools) {
        return schools.stream().map(School::getSchoolCode).toList();
    }

    private static List<IndicativePsleScoreRange> sortedRanges(List<IndicativePsleScoreRange> ranges) {
        return ranges.stream().sorted(SchoolDatasetMapper.RANGE_ORDER).toList();
    }

    /** Every attribute of section 7.2, and what School computes from them. */
    private static Map<String, Object> describe(School s,
                                                Function<List<IndicativePsleScoreRange>, List<IndicativePsleScoreRange>> order) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("schoolCode", s.getSchoolCode());
        d.put("name", s.getName());
        d.put("address", s.getAddress());
        d.put("latitude", s.getCoordinate() == null ? null : s.getCoordinate().getLatitude());
        d.put("longitude", s.getCoordinate() == null ? null : s.getCoordinate().getLongitude());
        d.put("hasValidCoordinate", s.hasValidCoordinate());
        d.put("telephone", s.getTelephone());
        d.put("website", s.getWebsite());
        d.put("email", s.getEmail());
        d.put("schoolType", s.getSchoolType());
        d.put("planningArea", s.getPlanningArea());
        d.put("nearestMrt", s.getNearestMrt());
        d.put("busInfo", s.getBusInfo());
        d.put("sessionType", s.getSessionType());
        d.put("schoolNature", s.getSchoolNature());
        d.put("programmes", List.copyOf(s.getProgrammes()));
        d.put("ccas", List.copyOf(s.getCcas()));
        d.put("affiliatedPrimarySchools", List.copyOf(s.getAffiliatedPrimarySchools()));
        d.put("ipRangeNote", s.getIpRangeNote());
        d.put("scoreRanges", order.apply(s.getScoreRanges()).stream().map(SchoolDatasetRoundTripTest::describe).toList());
        for (int pg = 1; pg <= 3; pg++) {
            for (boolean affiliated : new boolean[] {false, true}) {
                d.put("scoreRange(" + pg + "," + affiliated + ")",
                        s.getScoreRange(pg, affiliated).map(SchoolDatasetRoundTripTest::describe).orElse(null));
            }
        }
        return d;
    }

    private static List<Object> describe(IndicativePsleScoreRange r) {
        List<Object> d = new ArrayList<>();
        d.add(r.getAdmissionYear());
        d.add(r.getPostingGroup());
        d.add(r.isAffiliated());
        d.add(r.isIntegratedProgramme());
        d.add(r.getLowerScore());
        d.add(r.getUpperScore());
        d.add(r.getMoeText());
        d.add(r.describe());
        d.add(r.hasHigherChineseGrades());
        d.add(r.hadPlacesLeft());
        return d;
    }

    private static List<List<String>> describeDistricts(List<District> districts) {
        return districts.stream().sorted(Comparator.comparing(District::getPlanningAreaCode))
                .map(d -> {
                    List<String> v = new ArrayList<>();
                    v.add(d.getPlanningAreaCode());
                    v.add(d.getPlanningAreaName());
                    v.add(d.getBoundaryGeoJson());
                    return v;
                })
                .toList();
    }

    private static Map<String, Object> describe(SchoolDataCache c) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("datasetVersion", c.getDatasetVersion());
        d.put("datasetKind", c.getDatasetKind());
        d.put("isSeedData", c.isSeedData());
        d.put("effectiveDate", c.getEffectiveDate());
        d.put("importedAt", c.getImportedAt());
        d.put("validationStatus", c.getValidationStatus());
        d.put("size", c.size());
        d.put("schools", codes(c.getSchools()));
        d.put("findByName(school)", codes(c.findByName("school")));
        d.put("hasPsleData", c.hasPsleData());
        d.put("hasAffiliationData", c.hasAffiliationData());
        for (AttributeCategory category : AttributeCategory.values()) {
            d.put(category.name(), List.copyOf(c.getAttributeValues(category)));
        }
        d.put("districts", describeDistricts(c.getDistricts()));
        return d;
    }
}

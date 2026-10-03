package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import sg.schoolmatch.boundary.external.DataGovSgInterface;
import sg.schoolmatch.boundary.external.OneMapInterface;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.dataset.CuratedCsvReader;
import sg.schoolmatch.dataset.LoadedSnapshot;
import sg.schoolmatch.dataset.SchoolRecord;
import sg.schoolmatch.dataset.SnapshotManifest;
import sg.schoolmatch.dataset.SnapshotReader;
import sg.schoolmatch.dataset.SnapshotValidator;
import sg.schoolmatch.dataset.SnapshotWriter;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.error.NotFoundException;
import sg.schoolmatch.persistence.dataset.SchoolDatasetStore;
import sg.schoolmatch.support.FixedClock;

/**
 * SchoolDataController serves the school data from the database (docs/database-design.md, sections 6.2 and 6.3;
 * DC-83): it loads the snapshot through {@link SchoolDatasetStore} at start-up, builds {@code SchoolDataCache} and
 * the manifest from the database, and checks {@code active_dataset} every {@code app.dataset.recheck-after}.
 * <p>
 * H2 with the test profile; each test runs in a test transaction that is rolled back, so the store's transactions
 * join it and every test starts with empty school tables. "Another app instance" is the same store called directly.
 */
@JdbcTest
@ActiveProfiles("test")
@Import(SchoolDataControllerServingTest.Stores.class)
class SchoolDataControllerServingTest {

    static final String MINI = "classpath:fixtures/snapshot-mini/";

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

    /** How often a test's controller read snapshot files. */
    private final AtomicInteger fileReads = new AtomicInteger();

    private final SnapshotValidator validator = new SnapshotValidator();

    @BeforeEach
    void resetClock() {
        clock.setInstant(FixedClock.DEFAULT_INSTANT);
    }

    // ---------------------------------------------------------------------------------------------- tests

    @Test
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchoolDataServing-01: at start-up the snapshot is loaded into the database, and the schools, areas, cache and manifest are served from the database")
    void loadsAtStartUpAndServesFromDatabase() {
        SchoolDataController controller = controller(Map.of(), store);

        assertThat(jdbc.queryForObject("SELECT dataset_version FROM active_dataset", String.class)).isEqualTo("0000-seed");
        assertThat(controller.getSchools()).hasSize(10);
        assertThat(controller.getDistricts()).hasSize(3);
        assertThat(controller.getActiveDataset().getDatasetVersion()).isEqualTo("0000-seed");
        assertThat(controller.getActiveDataset().isSeedData()).isTrue();
        assertThat(controller.hasPsleData()).isTrue();
        assertThat(controller.getActiveManifest().version()).isEqualTo("0000-seed");   // was TC-Import-09
        assertThat(controller.getActiveManifest().sources()).hasSize(5);
        assertThat(fileReads).hasValue(1);

        // The cache is built from the database, not from the files: a value only the database has is served.
        jdbc.update("UPDATE school SET address = 'FROM THE DATABASE' WHERE school_code = 'catholic-high-school'");
        SchoolDataController restarted = controller(Map.of(), store);   // same version and hash: nothing loaded
        assertThat(restarted.getSchool("catholic-high-school").getAddress()).isEqualTo("FROM THE DATABASE");
    }

    @Test
    @Tag("NFR-DATA-01")
    @Tag("FR-DATA-05")
    @DisplayName("TC-SchoolDataServing-02: after recheck-after, a version another instance activated is served from the database without reading files; a school it withdrew is no longer found")
    void hourlyCheckSwitchesToAnotherInstancesVersion() {
        SchoolDataController controller = controller(Map.of(), store);
        LoadedSnapshot v2 = withoutWestwood(controller, "2026-10-05.1");
        store.ensureLoaded(v2, validator.validate(v2), "b".repeat(64));   // another instance

        clock.advance(Duration.ofMinutes(59));
        assertThat(controller.getSchools()).hasSize(10);

        clock.advance(Duration.ofMinutes(1));
        assertThat(controller.getSchools()).hasSize(9);
        assertThat(controller.getActiveDataset().getDatasetVersion()).isEqualTo("2026-10-05.1");
        assertThat(controller.getActiveManifest().version()).isEqualTo("2026-10-05.1");
        assertThatThrownBy(() -> controller.getSchool("westwood-secondary-school"))
                .isInstanceOf(NotFoundException.class);
        assertThat(fileReads).as("the hourly check never reads files").hasValue(1);
    }

    @Test
    @Tag("FR-SHORTLIST-05")
    @Tag("FR-DATA-05")
    @DisplayName("TC-SchoolDataServing-08: a withdrawn school's last-known name comes from the database; a school still in the dataset and an unknown code give none")
    void lastKnownNameOfAWithdrawnSchool() {
        SchoolDataController controller = controller(Map.of(), store);
        String name = controller.getSchool("westwood-secondary-school").getName();
        LoadedSnapshot v2 = withoutWestwood(controller, "2026-10-05.1");
        store.ensureLoaded(v2, validator.validate(v2), "b".repeat(64));
        clock.advance(Duration.ofHours(1));

        assertThat(controller.getLastKnownSchoolNames(
                List.of("westwood-secondary-school", "catholic-high-school", "no-such-school")))
                .containsExactly(Map.entry("westwood-secondary-school", name));
        assertThat(controller.getLastKnownSchoolNames(List.of())).isEmpty();
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchoolDataServing-03: after recheck-after, an unchanged version and hash keep the same cache, checked again an hour later")
    void hourlyCheckKeepsUnchangedCache() {
        SchoolDataController controller = controller(Map.of(), store);
        SchoolDataCache before = controller.getActiveDataset();

        clock.advance(Duration.ofHours(1));
        SchoolDataCache after = controller.getActiveDataset();

        assertThat(after).isSameAs(before);
        assertThat(after.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(1)));
        assertThat(fileReads).hasValue(1);
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-SchoolDataServing-04: load-on-startup=false reads no snapshot file and writes nothing at start-up; the first use builds the cache from the database")
    void loadOnStartupFalseUsesTheDatabaseOnly() {
        SchoolDataController controller = controller(Map.of("app.dataset.load-on-startup", "false"), store);

        assertThat(fileReads).hasValue(0);
        assertThat(store.activeState()).isEmpty();

        LoadedSnapshot mini = new SnapshotReader(props(Map.of()), new DefaultResourceLoader()).read(MINI);
        store.ensureLoaded(mini, validator.validate(mini), "c".repeat(64));   // e.g. a one-off load run
        assertThat(controller.getSchools()).hasSize(10);
        assertThat(controller.getActiveManifest().version()).isEqualTo("0000-seed");
        assertThat(fileReads).hasValue(0);
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-SchoolDataServing-05: load-on-startup=false with no dataset in the database: the first use fails and says how to load one")
    void loadOnStartupFalseWithEmptyDatabase() {
        SchoolDataController controller = controller(Map.of("app.dataset.load-on-startup", "false"), store);

        assertThatThrownBy(controller::getSchools).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.dataset.load-on-startup");
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchoolDataServing-06: a FAILED snapshot stops start-up and writes nothing to the database")
    void failedSnapshotStopsStartUp() {
        assertThatThrownBy(() -> controller(
                Map.of("app.dataset.snapshot-location", "classpath:fixtures/snapshot-broken/bad-coordinate/"), store))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("failed validation");

        assertThat(store.activeState()).isEmpty();
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchoolDataServing-07: allow-rollback=false: an older snapshot at start-up keeps the newer version in the database, and that version is served")
    void olderSnapshotKeepsNewerVersionWhenRollbackIsOff() {
        SchoolDataController first = controller(Map.of(), store);
        LoadedSnapshot newer = withoutWestwood(first, "2026-10-05.1");
        store.ensureLoaded(newer, validator.validate(newer), "d".repeat(64));

        SchoolDataController oldImage = controller(Map.of("app.dataset.allow-rollback", "false"), strictStore);

        assertThat(oldImage.getActiveDataset().getDatasetVersion()).isEqualTo("2026-10-05.1");
        assertThat(oldImage.getSchools()).hasSize(9);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private SchoolDataController controller(Map<String, String> settings, SchoolDatasetStore datasetStore) {
        Map<String, String> all = new HashMap<>();
        all.put("app.dataset.snapshot-location", MINI);
        all.putAll(settings);
        AppProperties props = props(all);
        return new SchoolDataController(mock(DataGovSgInterface.class), mock(OneMapInterface.class), reader(all),
                validator, new CuratedCsvReader("data/curated"), new SnapshotWriter(), props, clock, datasetStore);
    }

    /** A SnapshotReader that counts how often snapshot files are read. */
    private SnapshotReader reader(Map<String, String> settings) {
        return new SnapshotReader(props(settings), new DefaultResourceLoader()) {
            @Override
            public LoadedSnapshot read(String folderLocation) {
                fileReads.incrementAndGet();
                return super.read(folderLocation);
            }
        };
    }

    private static AppProperties props(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("app", AppProperties.class);
    }

    /** snapshot-mini as a later version without Westwood Secondary (manifest counts follow). */
    private LoadedSnapshot withoutWestwood(SchoolDataController controller, String version) {
        LoadedSnapshot mini = new SnapshotReader(props(Map.of()), new DefaultResourceLoader()).read(MINI);
        List<SchoolRecord> records = mini.records().stream()
                .filter(r -> !r.schoolCode().equals("westwood-secondary-school")).toList();
        SnapshotManifest m = mini.manifest();
        SnapshotManifest manifest = new SnapshotManifest(m.formatVersion(), m.kind(), version,
                m.effectiveDate().plusDays(5), m.importedAt().plus(Duration.ofDays(5)), m.sources(),
                m.validationStatus(), m.warnings(), Map.of("schools", records.size(), "districts",
                mini.districts().size(), "scoreRanges", records.stream().mapToInt(r -> r.scoreRanges().size()).sum()),
                m.notes());
        assertThat(controller.getSchool("westwood-secondary-school")).isNotNull();
        return new LoadedSnapshot(mini.location(), manifest, records,
                records.stream().map(SnapshotReader::toSchool).toList(), mini.districts());
    }
}

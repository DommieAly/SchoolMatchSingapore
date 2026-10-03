package sg.schoolmatch.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.dataset.SnapshotValidator;

/**
 * Every length limit of the snapshot validator equals the length of its column in the migrated database, and every
 * text column of the school dataset tables has such a limit or is filled by the loader itself
 * (docs/database-design.md, section 10; DC-85). So a snapshot that validates always fits the tables.
 * Runs on H2 ({@link H2SchemaLimitsTest}) and embedded PostgreSQL ({@link PostgresSchemaLimitsTest}); no Spring.
 */
abstract class SchemaLimitsTest {

    /** The V2 tables (design section 4.2). */
    static final Set<String> SCHOOL_TABLES = Set.of("dataset_version", "dataset_source", "dataset_warning",
            "active_dataset", "district", "school", "indicative_psle_score_range", "school_cca", "school_programme",
            "school_affiliated_primary", "school_bus_service", "school_mrt_station");

    /**
     * Text columns of the V2 tables that need no validator limit: keys copied from a parent row (checked there),
     * and values the loader computes (kind lower-cased from seed/full, statuses from the enum, the hash, the
     * Higher Chinese grades from MoeRangeText).
     */
    static final Set<String> NOT_FROM_SNAPSHOT_TEXT = Set.of(
            "dataset_version.dataset_kind", "dataset_version.manifest_status", "dataset_version.load_status",
            "dataset_version.content_sha256",
            "dataset_source.dataset_version", "dataset_warning.dataset_version", "active_dataset.dataset_version",
            "district.withdrawn_in_version", "school.withdrawn_in_version",
            "indicative_psle_score_range.school_code", "indicative_psle_score_range.lower_hcl_grade",
            "indicative_psle_score_range.upper_hcl_grade",
            "school_cca.school_code", "school_programme.school_code", "school_affiliated_primary.school_code",
            "school_bus_service.school_code", "school_mrt_station.school_code");

    private Map<String, Integer> lengths;

    /** A JDBC URL of an empty database of this kind, with user and password. */
    abstract String[] emptyDatabase();

    @BeforeAll
    void migrate() throws SQLException {
        String[] db = emptyDatabase();
        Flyway.configure().dataSource(db[0], db[1], db[2]).locations("classpath:db/migration").load().migrate();
        Map<String, Integer> out = new TreeMap<>();
        try (Connection c = DriverManager.getConnection(db[0], db[1], db[2]); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT table_name, column_name, data_type, character_maximum_length"
                     + " FROM information_schema.columns WHERE LOWER(table_schema) = 'public'")) {
            while (rs.next()) {
                String table = rs.getString(1).toLowerCase(Locale.ROOT);
                if (SCHOOL_TABLES.contains(table)
                        && rs.getString(3).toLowerCase(Locale.ROOT).contains("character varying")) {
                    out.put(table + "." + rs.getString(2).toLowerCase(Locale.ROOT), rs.getInt(4));
                }
            }
        }
        lengths = out;
    }

    @Test
    @Tag("FR-DATA-01")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchemaLimits-01: every validator length limit equals the column length in information_schema")
    void limitsEqualColumns() {
        assertThat(lengths).isNotEmpty();
        SnapshotValidator.MAX_LENGTHS.forEach((column, max) ->
                assertThat(lengths.get(column)).as(column).isEqualTo(max));
    }

    @Test
    @Tag("FR-DATA-01")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-SchemaLimits-02: every text column of the school tables has a validator limit or is filled by the loader")
    void everyTextColumnIsCovered() {
        assertThat(lengths.keySet()).allSatisfy(column -> assertThat(
                SnapshotValidator.MAX_LENGTHS.containsKey(column) || NOT_FROM_SNAPSHOT_TEXT.contains(column))
                .as(column + " has no SnapshotValidator.MAX_LENGTHS entry").isTrue());
        assertThat(lengths.keySet()).containsAll(NOT_FROM_SNAPSHOT_TEXT);
    }
}

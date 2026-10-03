package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.dataset.SnapshotReader;
import sg.schoolmatch.support.EmbeddedPostgresSupport;

/**
 * The whole application on PostgreSQL 17 (docs/database-design.md, section 10): Flyway creates the schema, Hibernate
 * validates it, the loader writes the real {@code ACTIVE} snapshot, and the pages are served from the database.
 * Embedded PostgreSQL, no Docker; skipped with {@code -DexcludedGroups=postgres}.
 * <p>
 * The test profile otherwise: offline stubs. {@code app.dataset.snapshot-location} is cleared, so the app reads
 * {@code data/snapshots/ACTIVE} like a real run.
 */
@SpringBootTest(properties = "app.dataset.snapshot-location=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag(EmbeddedPostgresSupport.TAG)
class PostgresApplicationStartsTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.createDatabase().register(registry);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SchoolDataController schoolDataController;

    private static final String ACTIVE = SnapshotReader.readActiveVersion(Path.of("data", "snapshots"));

    @Test
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-PgAppStart-01: on PostgreSQL, Flyway applies the migrations and the loader makes the real ACTIVE snapshot the active dataset, which the app serves")
    void startsOnPostgres() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class))
                .isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT dataset_version FROM active_dataset", String.class)).isEqualTo(ACTIVE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM school", Integer.class)).isEqualTo(147);
        assertThat(schoolDataController.getActiveDataset().getDatasetVersion()).isEqualTo(ACTIVE);
        assertThat(schoolDataController.getSchools()).hasSize(147);
        assertThat(schoolDataController.getDistricts()).hasSize(55);
    }

    @Test
    @Tag("FR-SEARCH-01")
    @Tag("FR-SEARCH-02")
    @Tag("FR-DATA-03")
    @DisplayName("TC-PgAppStart-02: on PostgreSQL the home, search, details, map and about pages are served from the database")
    void servesPages() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/schools\"")));
        mvc.perform(get("/schools").param("q", "catholic")).andExpect(status().isOk())
                .andExpect(content().string(containsString("CATHOLIC HIGH SCHOOL")));
        mvc.perform(get("/schools/catholic-high-school")).andExpect(status().isOk())
                .andExpect(content().string(containsString("CATHOLIC HIGH SCHOOL")))
                .andExpect(content().string(containsString("BISHAN")));
        mvc.perform(get("/schools/map")).andExpect(status().isOk());
        mvc.perform(get("/about/data")).andExpect(status().isOk())
                .andExpect(content().string(containsString(ACTIVE)));
    }
}

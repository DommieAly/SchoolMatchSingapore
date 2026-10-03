package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.support.EmbeddedPostgresSupport;

/**
 * The {@code postgres} profile as a teammate uses it with {@code compose.yaml}: the app builds its JDBC URL from
 * {@code DB_HOST}, {@code DB_PORT}, {@code DB_NAME}, {@code DB_USERNAME} and {@code DB_PASSWORD}
 * (docs/database-design.md, section 9). Here they point at embedded PostgreSQL instead of Docker. The test profile
 * comes first, so the postgres profile's data source wins and the external services stay stubbed.
 * Skipped with {@code -DexcludedGroups=postgres}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "postgres"})
@Tag(EmbeddedPostgresSupport.TAG)
class PostgresProfileStartsTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.Database db = EmbeddedPostgresSupport.createDatabase();
        URI uri = URI.create(db.url().substring("jdbc:".length()));
        registry.add("DB_HOST", uri::getHost);
        registry.add("DB_PORT", uri::getPort);
        registry.add("DB_NAME", () -> uri.getPath().substring(1));
        registry.add("DB_USERNAME", db::username);
        registry.add("DB_PASSWORD", db::password);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private MockMvc mvc;

    @Test
    @Tag("FR-DATA-02")
    @Tag("FR-DATA-03")
    @DisplayName("TC-PostgresProfile-01: with the postgres profile the app connects through the DB_* variables, migrates and serves pages")
    void startsWithThePostgresProfile() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(c.getMetaData().getURL()).endsWith("?reWriteBatchedInserts=true")
                    .contains("/" + environment.getProperty("DB_NAME") + "?");
        }
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.h2.console.enabled", Boolean.class, false)).isFalse();
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/schools/catholic-high-school")).andExpect(status().isOk());
    }
}

package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
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
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.support.EmbeddedPostgresSupport;

/**
 * The {@code prod} profile on embedded PostgreSQL (docs/database-design.md, section 9): the database comes only from
 * {@code DB_URL}, {@code DB_USERNAME} and {@code DB_PASSWORD}; a snapshot older than the active one is not loaded
 * unless {@code DATASET_ALLOW_ROLLBACK=true}; the session cookie is Secure; the H2 console is off.
 * <p>
 * The profile asks the driver for {@code sslmode=verify-full}. The embedded server has no TLS, so this test's
 * {@code DB_URL} says {@code sslmode=disable}: a setting in the URL wins over the profile's default, the same way a
 * real {@code DB_URL} can add {@code sslrootcert=...}. Skipped with {@code -DexcludedGroups=postgres}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "prod"})
@Tag(EmbeddedPostgresSupport.TAG)
class ProdProfileStartsTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.Database db = EmbeddedPostgresSupport.createDatabase();
        registry.add("DB_URL", () -> db.url() + "&sslmode=disable");
        registry.add("DB_USERNAME", db::username);
        registry.add("DB_PASSWORD", db::password);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private AppProperties props;

    @Autowired
    private MockMvc mvc;

    @Test
    @Tag("FR-DATA-02")
    @Tag("NFR-SEC-03")
    @DisplayName("TC-ProdProfile-01: with the prod profile the app connects through DB_URL, asks for verify-full TLS by default, refuses rollback, sets Secure cookies and has no H2 console")
    void startsWithTheProdProfile() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        assertThat(hikari.getDataSourceProperties().getProperty("sslmode")).isEqualTo("verify-full");
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
        assertThat(props.dataset().allowRollback()).isFalse();
        assertThat(props.session().cookieSecure()).isTrue();
        assertThat(environment.getProperty("spring.h2.console.enabled", Boolean.class)).isFalse();
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/h2-console")).andExpect(status().isNotFound());
    }
}

package sg.schoolmatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * The database settings of every profile, read straight from the YAML files (docs/database-design.md, section 9).
 * Plain JUnit: no Spring context, no database. {@code PostgresProfileStartsTest} and {@code ProdProfileStartsTest}
 * start the app with the postgres and prod profiles on embedded PostgreSQL.
 */
class ProfileSettingsTest {

    private static Properties yaml(Resource resource) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource);
        return factory.getObject();
    }

    private static Properties profile(String name) {
        return yaml(new ClassPathResource(name.isEmpty() ? "application.yml" : "application-" + name + ".yml"));
    }

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-ProfileSettings-01: Hibernate only validates (shared file), and no profile changes ddl-auto to anything else")
    void hibernateOnlyValidatesEverywhere() {
        assertThat(profile("").getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        for (String name : List.of("dev", "demo", "import", "postgres", "prod")) {
            String value = profile(name).getProperty("spring.jpa.hibernate.ddl-auto");
            assertThat(value).as(name).isIn(null, "validate");
        }
    }

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-ProfileSettings-02: the postgres profile reaches a local PostgreSQL through DB_* variables with local defaults")
    void postgresProfile() {
        Properties p = profile("postgres");
        assertThat(p.getProperty("spring.datasource.url")).isEqualTo(
                "jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:schoolmatch}?reWriteBatchedInserts=true");
        assertThat(p.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME:schoolmatch}");
        assertThat(p.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD:schoolmatch}");
    }

    @Test
    @Tag("NFR-SEC-01")
    @Tag("NFR-SEC-03")
    @Tag("FR-DATA-02")
    @DisplayName("TC-ProfileSettings-03: the prod profile takes every database value from the environment without a default, checks the server certificate, and turns off the H2 console and rollback")
    void prodProfile() {
        Properties p = profile("prod");
        assertThat(p.getProperty("spring.datasource.url")).isEqualTo("${DB_URL}");
        assertThat(p.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME}");
        assertThat(p.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
        assertThat(p.getProperty("spring.datasource.hikari.data-source-properties.sslmode")).isEqualTo("verify-full");
        assertThat(p.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("10");
        assertThat(p.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(p.getProperty("spring.h2.console.enabled")).isEqualTo("false");
        assertThat(p.getProperty("app.dataset.allow-rollback")).isEqualTo("${DATASET_ALLOW_ROLLBACK:false}");
        assertThat(p.getProperty("app.session.cookie-secure")).isEqualTo("true");
    }

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-ProfileSettings-04: compose.yaml runs PostgreSQL 17 (service db, named volume, local port only) with the postgres profile's defaults")
    void composeFile() {
        Properties c = yaml(new FileSystemResource("compose.yaml"));
        assertThat(c.getProperty("services.db.image")).isEqualTo("postgres:17-alpine");
        assertThat(c.getProperty("services.db.environment.POSTGRES_DB")).isEqualTo("${DB_NAME:-schoolmatch}");
        assertThat(c.getProperty("services.db.environment.POSTGRES_USER")).isEqualTo("${DB_USERNAME:-schoolmatch}");
        assertThat(c.getProperty("services.db.environment.POSTGRES_PASSWORD")).isEqualTo("${DB_PASSWORD:-schoolmatch}");
        assertThat(c.getProperty("services.db.ports[0]")).isEqualTo("127.0.0.1:${DB_PORT:-5432}:5432");
        assertThat(c.getProperty("services.db.volumes[0]")).startsWith("schoolmatch-pg:");
    }

    @Test
    @Tag("NFR-SEC-01")
    @DisplayName("TC-ProfileSettings-05: .env.example names every DB_* variable, commented out, and holds no password value for prod")
    void envExample() throws IOException {
        List<String> lines = Files.readAllLines(Path.of(".env.example"), StandardCharsets.UTF_8);
        for (String name : List.of("DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "DB_URL",
                "DATASET_ALLOW_ROLLBACK")) {
            assertThat(lines).as(name).anyMatch(l -> l.startsWith("#" + name + "="));
            assertThat(lines).as(name + " must stay commented out").noneMatch(l -> l.startsWith(name + "="));
        }
        assertThat(lines).filteredOn(l -> l.startsWith("#DB_URL=")).singleElement().asString()
                .contains("sslmode=verify-full");
    }
}

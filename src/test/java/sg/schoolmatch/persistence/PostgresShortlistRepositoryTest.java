package sg.schoolmatch.persistence;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sg.schoolmatch.support.EmbeddedPostgresSupport;

/**
 * {@link ShortlistRepositoryTest} on embedded PostgreSQL 17 (the postgres and prod database), so the V3 foreign keys
 * and the shortlist mapping are checked on both databases (docs/database-design.md, section 10). Spring's own
 * Flyway migrates the empty database. Skipped with {@code -DexcludedGroups=postgres}.
 */
@Tag(EmbeddedPostgresSupport.TAG)
class PostgresShortlistRepositoryTest extends ShortlistRepositoryTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.createDatabase().register(registry);
    }
}

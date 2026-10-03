package sg.schoolmatch.persistence.dataset;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sg.schoolmatch.support.EmbeddedPostgresSupport;

/**
 * {@link SchoolDatasetRoundTripTest} on embedded PostgreSQL 17 (the postgres and prod database). Skipped with
 * {@code -DexcludedGroups=postgres}.
 */
@Tag(EmbeddedPostgresSupport.TAG)
class PostgresSchoolDatasetRoundTripTest extends SchoolDatasetRoundTripTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.Database db = EmbeddedPostgresSupport.createDatabase();
        migrate(db.url(), db.username(), db.password());
        db.register(registry);
    }
}

package sg.schoolmatch.persistence;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import sg.schoolmatch.support.EmbeddedPostgresSupport;

/** {@link SchemaLimitsTest} on embedded PostgreSQL 17. Skipped with {@code -DexcludedGroups=postgres}. */
@Tag(EmbeddedPostgresSupport.TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresSchemaLimitsTest extends SchemaLimitsTest {

    @Override
    String[] emptyDatabase() {
        EmbeddedPostgresSupport.Database db = EmbeddedPostgresSupport.createDatabase();
        return new String[] {db.url(), db.username(), db.password()};
    }
}

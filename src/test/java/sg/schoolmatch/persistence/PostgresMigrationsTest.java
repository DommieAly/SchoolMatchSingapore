package sg.schoolmatch.persistence;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sg.schoolmatch.support.EmbeddedPostgresSupport;

/**
 * {@link MigrationsTest} on a real PostgreSQL 17 server (embedded, no Docker), the database of the postgres and
 * prod profiles. Its own empty database on the shared server, migrated before Spring starts on a connection that
 * is then closed. Skipped with {@code -DexcludedGroups=postgres}.
 */
@Tag(EmbeddedPostgresSupport.TAG)
class PostgresMigrationsTest extends MigrationsTest {

    private static PreparedDatabase database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.Database db = EmbeddedPostgresSupport.createDatabase();
        database = migrateOnClosedConnection(db.url(), db.username(), db.password(),
                "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database()");
        db.register(registry);
    }

    @Override
    PreparedDatabase database() {
        return database;
    }

    @Override
    String indexNamesSql() {
        return "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'";
    }

    @Override
    boolean namesPrimaryKeysInErrors() {
        return true;
    }
}

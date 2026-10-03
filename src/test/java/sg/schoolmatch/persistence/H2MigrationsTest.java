package sg.schoolmatch.persistence;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link MigrationsTest} on H2 2.4 in PostgreSQL mode, the database of the dev, demo and test profiles. Its own
 * in-memory database, migrated before Spring starts on a connection that is then closed (design section 8).
 */
class H2MigrationsTest extends MigrationsTest {

    private static PreparedDatabase database;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        database = migrateOnClosedConnection(freshH2Url("migrations"), "sa", "",
                "SELECT COUNT(*) FROM information_schema.sessions");
        registry.add("spring.datasource.url", database::url);
    }

    @Override
    PreparedDatabase database() {
        return database;
    }

    @Override
    String indexNamesSql() {
        return "SELECT index_name FROM information_schema.indexes WHERE LOWER(index_schema) = 'public'";
    }

    @Override
    boolean namesPrimaryKeysInErrors() {
        return false;
    }
}

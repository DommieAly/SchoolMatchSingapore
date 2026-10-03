package sg.schoolmatch.persistence;

import org.junit.jupiter.api.TestInstance;

/** {@link SchemaLimitsTest} on H2 2.4 in PostgreSQL mode. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class H2SchemaLimitsTest extends SchemaLimitsTest {

    @Override
    String[] emptyDatabase() {
        return new String[] {MigrationsTest.freshH2Url("limits"), "sa", ""};
    }
}

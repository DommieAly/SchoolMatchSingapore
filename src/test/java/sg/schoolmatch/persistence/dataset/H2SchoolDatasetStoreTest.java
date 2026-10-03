package sg.schoolmatch.persistence.dataset;

import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@link SchoolDatasetStoreTest} on H2 2.4 in PostgreSQL mode (the dev, demo and test database). */
class H2SchoolDatasetStoreTest extends SchoolDatasetStoreTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = "jdbc:h2:mem:store-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        migrate(url, "sa", "");
        registry.add("spring.datasource.url", () -> url);
    }
}

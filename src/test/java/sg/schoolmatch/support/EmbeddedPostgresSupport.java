package sg.schoolmatch.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * A real PostgreSQL server for tests tagged {@code "postgres"} (docs/database-design.md, section 10).
 *
 * <ul>
 *   <li>One server per test JVM, started on first use from the binaries Maven downloaded
 *       ({@code io.zonky.test:embedded-postgres}, PostgreSQL 17); no installation, no Docker. The library's shutdown
 *       hook stops it when the JVM ends.</li>
 *   <li>Each test class asks for its own empty database ({@link #createDatabase()}), so classes never see each
 *       other's rows or schema.</li>
 *   <li>Plain zonky library, not its Spring add-on: test classes pass the database to Spring with
 *       {@code @DynamicPropertySource} and {@link Database#register}.</li>
 * </ul>
 *
 * <p>Skip every PostgreSQL test with {@code ./mvnw verify -DexcludedGroups=postgres}.
 */
public final class EmbeddedPostgresSupport {

    /** The JUnit tag every embedded-PostgreSQL test class carries. */
    public static final String TAG = "postgres";

    private static final String USER = "postgres";   // the embedded server trusts local connections: no password

    private static EmbeddedPostgres server;

    private EmbeddedPostgresSupport() {
    }

    /** One empty database on the shared server. */
    public record Database(String url, String username, String password) {

        /** Points Spring's data source at this database. */
        public void register(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", this::url);
            registry.add("spring.datasource.username", this::username);
            registry.add("spring.datasource.password", this::password);
        }
    }

    /** Creates a new, empty database {@code t_<random>} and returns how to reach it. */
    public static Database createDatabase() {
        EmbeddedPostgres pg = server();
        String name = "t_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create test database " + name, e);
        }
        // reWriteBatchedInserts=true: the same driver setting as the postgres and prod profiles (design section 9)
        String url = "jdbc:postgresql://localhost:" + pg.getPort() + "/" + name + "?reWriteBatchedInserts=true";
        return new Database(url, USER, "");
    }

    private static synchronized EmbeddedPostgres server() {
        if (server == null) {
            try {
                server = EmbeddedPostgres.builder().setRegisterShutdownHook(true).start();
            } catch (IOException e) {
                throw new UncheckedIOException("embedded PostgreSQL did not start; on a machine that cannot run it,"
                        + " skip these tests with -DexcludedGroups=" + TAG, e);
            }
        }
        return server;
    }
}

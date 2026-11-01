package sg.schoolmatch.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import sg.schoolmatch.support.EmbeddedPostgresSupport;

/**
 * {@code deploy/lightsail/delete-account.sql} on a real PostgreSQL 17 server (embedded, no Docker): the privacy page
 * promises that one account can be deleted, and a new table or foreign key must not quietly break that promise. psql
 * fills in {@code :'email'} on the server; here the test does it. Each test gets its own empty database.
 */
@Tag(EmbeddedPostgresSupport.TAG)
class PostgresDeleteAccountScriptTest {

    private EmbeddedPostgresSupport.Database db;

    @BeforeEach
    void migrateAndAddTwoMembers() throws SQLException {
        db = EmbeddedPostgresSupport.createDatabase();
        Flyway.configure().dataSource(db.url(), db.username(), db.password()).load().migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("sql/schools.sql"));
            addMember(s, "a1", "alice", 1, "catholic-high-school");
            addMember(s, "a2", "bob", 51, "raffles-institution");
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(db.url(), db.username(), db.password());
    }

    /** One member with a row in every table that holds member data. */
    private static void addMember(Statement s, String id, String name, int planId, String school) throws SQLException {
        s.execute("INSERT INTO account (account_id, username, username_key, email, password_hash, created_at, status)"
                + " VALUES ('" + id + "', '" + name + "', '" + name + "', '" + name + "@example.com', '$2a$10$hash',"
                + " CURRENT_TIMESTAMP, 'ACTIVE')");
        s.execute("INSERT INTO authenticated_session (session_id, account_id, issued_at, expires_at, invalidated)"
                + " VALUES ('s-" + id + "', '" + id + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)");
        s.execute("INSERT INTO user_profile (account_id, psle_score) VALUES ('" + id + "', 20)");
        s.execute("INSERT INTO user_profile_cca (account_id, cca) VALUES ('" + id + "', 'BADMINTON')");
        s.execute("INSERT INTO user_profile_programme (account_id, programme) VALUES ('" + id + "', 'Robotics')");
        s.execute("INSERT INTO choice_plan (id, based_on_score) VALUES (" + planId + ", 20)");
        s.execute("INSERT INTO choice_plan_choice (choice_plan_id, choice_rank, school_code)"
                + " VALUES (" + planId + ", 1, '" + school + "')");
        s.execute("INSERT INTO shortlist (account_id, choice_plan_id) VALUES ('" + id + "', " + planId + ")");
        s.execute("INSERT INTO shortlist_school (account_id, school_code) VALUES ('" + id + "', '" + school + "')");
    }

    private void runScript(String email) throws IOException, SQLException {
        String script = Files.readString(Path.of("deploy/lightsail/delete-account.sql"), StandardCharsets.UTF_8);
        assertThat(script).contains(":'email'");
        String filledIn = script.replace(":'email'", "'" + email + "'");
        try (Connection c = connect()) {
            ScriptUtils.executeSqlScript(c, new ByteArrayResource(filledIn.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    /** Every table with an account_id column, so a table added later is checked without changing this test. */
    private List<String> tablesWithAccountId() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT table_name FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND column_name = 'account_id'")) {
            while (r.next()) {
                names.add(r.getString(1));
            }
        }
        return names;
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-DeleteAccountScript-01: deleting one account leaves no row of it in any table, its choice plan included, and whatever case the email was typed in")
    void deletesEverythingOfOneAccount() throws IOException, SQLException {
        runScript("Alice@Example.COM");

        List<String> tables = tablesWithAccountId();
        assertThat(tables).contains("account", "authenticated_session", "user_profile", "user_profile_cca",
                "user_profile_programme", "shortlist", "shortlist_school");
        for (String table : tables) {
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE account_id = 'a1'")).as(table).isZero();
        }
        assertThat(count("SELECT COUNT(*) FROM choice_plan WHERE id = 1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM choice_plan_choice WHERE choice_plan_id = 1")).isZero();
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-DeleteAccountScript-02: every other member keeps all their rows, and the schools stay")
    void keepsOtherMembers() throws IOException, SQLException {
        long schools = count("SELECT COUNT(*) FROM school");

        runScript("alice@example.com");

        for (String table : tablesWithAccountId()) {
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE account_id = 'a2'")).as(table).isPositive();
        }
        assertThat(count("SELECT COUNT(*) FROM choice_plan WHERE id = 51")).isOne();
        assertThat(count("SELECT COUNT(*) FROM choice_plan_choice WHERE choice_plan_id = 51")).isOne();
        assertThat(count("SELECT COUNT(*) FROM shortlist WHERE choice_plan_id = 51")).isOne();
        assertThat(count("SELECT COUNT(*) FROM school")).isEqualTo(schools);
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-DeleteAccountScript-03: an email nobody has deletes nothing, and an account without a shortlist can still be deleted")
    void unknownEmailAndAccountWithoutShortlist() throws IOException, SQLException {
        runScript("nobody@example.com");
        assertThat(count("SELECT COUNT(*) FROM account")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM choice_plan")).isEqualTo(2);

        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM shortlist WHERE account_id = 'a1'");
        }
        runScript("alice@example.com");
        assertThat(count("SELECT COUNT(*) FROM account WHERE account_id = 'a1'")).isZero();
    }
}

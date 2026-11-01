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
 * {@code deploy/lightsail/clear-plan-score.sql} on a real PostgreSQL 17 server (embedded, no Docker): the privacy page
 * promises that the score copy can be removed from a choice plan. psql fills in {@code :'email'} on the server; here
 * the test does it. Each test gets its own empty database.
 */
@Tag(EmbeddedPostgresSupport.TAG)
class PostgresClearPlanScoreScriptTest {

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

    /** One member with a choice plan that keeps a score and a posting group. */
    private static void addMember(Statement s, String id, String name, int planId, String school) throws SQLException {
        s.execute("INSERT INTO account (account_id, username, username_key, email, password_hash, created_at, status)"
                + " VALUES ('" + id + "', '" + name + "', '" + name + "', '" + name + "@example.com', '$2a$10$hash',"
                + " CURRENT_TIMESTAMP, 'ACTIVE')");
        s.execute("INSERT INTO user_profile (account_id, psle_score) VALUES ('" + id + "', 20)");
        s.execute("INSERT INTO choice_plan (id, based_on_score, posting_group) VALUES (" + planId + ", 20, 1)");
        s.execute("INSERT INTO choice_plan_choice (choice_plan_id, choice_rank, school_code)"
                + " VALUES (" + planId + ", 1, '" + school + "')");
        s.execute("INSERT INTO shortlist (account_id, choice_plan_id) VALUES ('" + id + "', " + planId + ")");
    }

    private void runScript(String email) throws IOException, SQLException {
        String script = Files.readString(Path.of("deploy/lightsail/clear-plan-score.sql"), StandardCharsets.UTF_8);
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

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-ClearPlanScoreScript-01: the score and posting group go from one person's plan, whatever case the email was typed in; the plan, its schools and the account stay")
    void clearsTheCopyOnly() throws IOException, SQLException {
        runScript("Alice@Example.COM");

        assertThat(count("SELECT COUNT(*) FROM choice_plan WHERE id = 1 AND based_on_score IS NULL"
                + " AND posting_group IS NULL")).isOne();
        assertThat(count("SELECT COUNT(*) FROM choice_plan_choice WHERE choice_plan_id = 1")).isOne();
        assertThat(count("SELECT COUNT(*) FROM shortlist WHERE account_id = 'a1' AND choice_plan_id = 1")).isOne();
        assertThat(count("SELECT COUNT(*) FROM account WHERE account_id = 'a1'")).isOne();
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-ClearPlanScoreScript-02: every other plan keeps its score, and an email nobody has changes nothing")
    void leavesOtherPlansAlone() throws IOException, SQLException {
        runScript("alice@example.com");
        assertThat(count("SELECT COUNT(*) FROM choice_plan WHERE id = 51 AND based_on_score = 20"
                + " AND posting_group = 1")).isOne();

        runScript("nobody@example.com");
        assertThat(count("SELECT COUNT(*) FROM choice_plan WHERE based_on_score IS NOT NULL")).isOne();
    }
}

package sg.schoolmatch.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.groups.Tuple.tuple;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.shortlist.ChoicePlan;
import sg.schoolmatch.entity.shortlist.SchoolChoice;
import sg.schoolmatch.entity.shortlist.Shortlist;
import sg.schoolmatch.support.TestSchools;

/**
 * The Flyway migrations in {@code src/main/resources/db/migration} (docs/database-design.md, sections 4, 8 and 10),
 * run once per database: {@link H2MigrationsTest} (H2 in PostgreSQL mode, as dev, demo and most tests use) and
 * {@link PostgresMigrationsTest} (embedded PostgreSQL 17, as the postgres and prod profiles use).
 *
 * <p>Each subclass migrates an empty database with Flyway on a connection of its own and closes it before Spring
 * starts. That matters on H2 2.4.240: a CHECK that holds a list ({@code IN ('A', 'B')}) fails with "database has
 * been closed" once the connection that created the table is closed (design section 8). The app's connection
 * pool closes Flyway's connection sooner or later, so these tests close it at once and then insert through new
 * connections. Spring's own Flyway then finds nothing to do, and Hibernate validates the entities against the
 * tables ({@code ddl-auto: validate}): if that failed, no test here would run.
 *
 * <p>Each probe runs in its own transaction on top of the valid rows in {@link #VALID_ROWS} and is rolled back,
 * so probes never affect each other.
 */
@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // probes manage their own transactions
abstract class MigrationsTest {

    /** H2 settings shared with the dev, demo, import and test profiles (design section 9). */
    static final String H2_FLAGS = "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";

    /** The secondary indexes of V1 (design section 4.4); keys bring their own indexes. */
    private static final Set<String> V1_INDEXES = Set.of(
            "ix_authenticated_session_account", "ix_authenticated_session_expires",
            "ix_choice_plan_choice_school", "ix_shortlist_school_school");

    /** A content hash of a loaded dataset version (64 lower-case hex digits). */
    private static final String HASH_A = "0123456789abcdef".repeat(4);

    /** Valid rows in every table; each probe runs on top of them. */
    static final List<String> VALID_ROWS = List.of(
            "INSERT INTO account (account_id, username, username_key, email, password_hash, created_at, status)"
                    + " VALUES ('a1', 'Alice_1', 'alice_1', 'alice@example.com', '$2a$10$hash', CURRENT_TIMESTAMP,"
                    + " 'ACTIVE')",
            "INSERT INTO account (account_id, username, username_key, email, password_hash, created_at, status)"
                    + " VALUES ('a2', 'Bob', 'bob', 'bob@example.com', '$2a$10$hash', CURRENT_TIMESTAMP, 'INACTIVE')",
            "INSERT INTO user_profile (account_id, display_name, psle_score, posting_group, primary_school,"
                    + " home_address, home_latitude, home_longitude, max_commute_min, travel_mode)"
                    + " VALUES ('a1', 'Alice', 20, 2, 'Ai Tong School', 'Bishan', 1.35, 103.85, 30, 'TRANSIT')",
            "INSERT INTO user_profile (account_id) VALUES ('a2')",   // a new profile: every field is optional
            "INSERT INTO user_profile_cca (account_id, cca) VALUES ('a1', 'BADMINTON')",
            "INSERT INTO user_profile_programme (account_id, programme) VALUES ('a1', 'Robotics')",
            "INSERT INTO choice_plan (id, based_on_score, posting_group, updated_at)"
                    + " VALUES (1, 20, 2, CURRENT_TIMESTAMP)",
            "INSERT INTO choice_plan (id) VALUES (2)",
            "INSERT INTO shortlist (account_id, updated_at, choice_plan_id) VALUES ('a1', CURRENT_TIMESTAMP, 1)",
            "INSERT INTO shortlist (account_id) VALUES ('a2')",
            "INSERT INTO authenticated_session (session_id, account_id, issued_at, expires_at, invalidated)"
                    + " VALUES ('s1', 'a1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30' MINUTE, FALSE)",
            "INSERT INTO external_usage (usage_day, sku, used) VALUES (DATE '2026-10-04', 'routes', 0)",
            // V2 school dataset (design section 4.2): one loaded version, two areas, one school with every child
            "INSERT INTO dataset_version (dataset_version, dataset_kind, effective_date, imported_at, manifest_status,"
                    + " load_status, notes, content_sha256, loader_format, loaded_at) VALUES ('2026-10-04.1', 'full',"
                    + " DATE '2026-10-04', CURRENT_TIMESTAMP, 'PASSED_WITH_WARNINGS', 'PASSED_WITH_WARNINGS', NULL, '"
                    + HASH_A + "', 1, CURRENT_TIMESTAMP)",
            "INSERT INTO dataset_source (dataset_version, source_no, source_id, source_name, downloaded_at)"
                    + " VALUES ('2026-10-04.1', 1, 'd_688b934f82c1059ed0a6993d2a829089', 'General information of"
                    + " schools', CURRENT_TIMESTAMP)",
            "INSERT INTO dataset_source (dataset_version, source_no, source_id, source_name)"
                    + " VALUES ('2026-10-04.1', 2, 'data/curated', 'curated files')",
            "INSERT INTO dataset_warning (dataset_version, warning_no, message)"
                    + " VALUES ('2026-10-04.1', 1, 'no-ccas: x has no CCAs')",
            "UPDATE active_dataset SET dataset_version = '2026-10-04.1' WHERE singleton_id = 1",
            "INSERT INTO district (planning_area_code, planning_area_name, boundary_geojson)"
                    + " VALUES ('BS', 'BISHAN', '{\"type\":\"Polygon\",\"coordinates\":[]}')",
            "INSERT INTO district (planning_area_code, planning_area_name) VALUES ('SV', 'SEMBAWANG')",   // DC-59
            "INSERT INTO school (school_code, school_name, address, postal_code, latitude, longitude, telephone,"
                    + " website, email, school_type, session_type, school_nature, planning_area_code) VALUES"
                    + " ('catholic-high-school', 'CATHOLIC HIGH SCHOOL', '9 BISHAN STREET 22', '579767', 1.354525,"
                    + " 103.844901, '64582177 (Secondary)', 'http://www.catholichigh.moe.edu.sg', 'chs@moe.edu.sg',"
                    + " 'GOVERNMENT-AIDED SCH', 'SINGLE SESSION', 'BOYS'' SCHOOL', 'BS')",
            "INSERT INTO school (school_code, school_name, latitude, longitude, planning_area_code)"
                    + " VALUES ('raffles-institution', 'RAFFLES INSTITUTION', 1.3467, 103.8436, 'BS')",
            "INSERT INTO indicative_psle_score_range (school_code, admission_year, posting_group, affiliated,"
                    + " integrated_programme, lower_score, upper_score, lower_hcl_grade, upper_hcl_grade, places_left)"
                    + " VALUES ('catholic-high-school', 2025, 3, FALSE, FALSE, 6, 8, 'D', 'M', FALSE)",
            "INSERT INTO indicative_psle_score_range (school_code, admission_year, posting_group, affiliated,"
                    + " integrated_programme, lower_score, upper_score, lower_hcl_grade, upper_hcl_grade, places_left)"
                    + " VALUES ('catholic-high-school', 2025, 3, FALSE, TRUE, 4, 7, NULL, NULL, NULL)",
            "INSERT INTO indicative_psle_score_range (school_code, admission_year, posting_group, affiliated,"
                    + " integrated_programme, lower_score, upper_score, lower_hcl_grade, upper_hcl_grade, places_left)"
                    + " VALUES ('catholic-high-school', 2025, 1, FALSE, FALSE, 26, 30, NULL, NULL, TRUE)",
            "INSERT INTO school_cca (school_code, cca_name) VALUES ('catholic-high-school', 'BADMINTON')",
            "INSERT INTO school_programme (school_code, programme_name) VALUES ('catholic-high-school', 'Robotics')",
            "INSERT INTO school_affiliated_primary (school_code, primary_school_name)"
                    + " VALUES ('catholic-high-school', 'CATHOLIC HIGH SCHOOL (PRIMARY)')",
            "INSERT INTO school_bus_service (school_code, service_no, list_position)"
                    + " VALUES ('catholic-high-school', '13', 1)",
            "INSERT INTO school_bus_service (school_code, service_no, list_position)"
                    + " VALUES ('catholic-high-school', '162M', 2)",
            "INSERT INTO school_mrt_station (school_code, station_name, list_position)"
                    + " VALUES ('catholic-high-school', 'BISHAN MRT', 1)",
            // two more schools: one only saved in a shortlist, one only chosen in a plan (probes 23g and 23h)
            "INSERT INTO district (planning_area_code, planning_area_name) VALUES ('TM', 'TAMPINES')",
            "INSERT INTO school (school_code, school_name, latitude, longitude, planning_area_code)"
                    + " VALUES ('tampines-secondary-school', 'TAMPINES SECONDARY SCHOOL', 1.3546, 103.9533, 'TM')",
            "INSERT INTO school (school_code, school_name, latitude, longitude, planning_area_code)"
                    + " VALUES ('yishun-secondary-school', 'YISHUN SECONDARY SCHOOL', 1.4334, 103.8333, 'SV')",
            // V3: saved and chosen school codes name school rows, so these come after the schools
            "INSERT INTO shortlist_school (account_id, school_code) VALUES ('a1', 'catholic-high-school')",
            "INSERT INTO shortlist_school (account_id, school_code) VALUES ('a1', 'raffles-institution')",
            "INSERT INTO choice_plan_choice (choice_plan_id, choice_rank, school_code)"
                    + " VALUES (1, 1, 'catholic-high-school')",
            "INSERT INTO choice_plan_choice (choice_plan_id, choice_rank, school_code)"
                    + " VALUES (1, 6, 'raffles-institution')",
            "INSERT INTO shortlist_school (account_id, school_code) VALUES ('a2', 'tampines-secondary-school')",
            "INSERT INTO choice_plan_choice (choice_plan_id, choice_rank, school_code)"
                    + " VALUES (2, 1, 'yishun-secondary-school')");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private Environment environment;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ShortlistRepository shortlistRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    // ----------------------------------------------------------------------------------------------------------
    // What each subclass provides

    /** The database the subclass migrated before Spring started. */
    abstract PreparedDatabase database();

    /** SQL that lists the names of the indexes in schema {@code public}, one per row. */
    abstract String indexNamesSql();

    /**
     * Whether this database names a violated primary key in its error message. PostgreSQL does
     * ({@code "pk_account"}); H2 calls every primary-key index {@code PRIMARY_KEY_…}.
     */
    abstract boolean namesPrimaryKeysInErrors();

    /** An empty database, migrated by Flyway on a connection that was then closed. */
    record PreparedDatabase(String url, String username, String password, int migrationsApplied,
                            int otherSessionsAfterMigration) {
    }

    /**
     * Runs every migration on the empty database at {@code url} through Flyway's own connection, which Flyway
     * closes when it is done. Then counts, on a new connection, how many other sessions are still open on that
     * database (expected: none).
     *
     * @param sessionsSql SQL returning the number of sessions open on the current database, this one included
     */
    static PreparedDatabase migrateOnClosedConnection(String url, String username, String password,
                                                      String sessionsSql) {
        int applied = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
                .migrationsExecuted;
        int others = -1;
        try (Connection c = DriverManager.getConnection(url, username, password); Statement s = c.createStatement()) {
            for (int attempt = 0; attempt < 40; attempt++) {   // a closed PostgreSQL session ends a moment later
                try (ResultSet rs = s.executeQuery(sessionsSql)) {
                    rs.next();
                    others = rs.getInt(1) - 1;
                }
                if (others == 0) {
                    break;
                }
                Thread.sleep(50);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not count the sessions on " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new PreparedDatabase(url, username, password, applied, others);
    }

    /** A new, empty in-memory H2 database URL that lives until the JVM ends. */
    static String freshH2Url(String prefix) {
        return "jdbc:h2:mem:" + prefix + "-" + UUID.randomUUID() + ";" + H2_FLAGS + ";DB_CLOSE_DELAY=-1";
    }

    // ----------------------------------------------------------------------------------------------------------
    // Schema

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-Migrations-01: the migrations apply to an empty database whose creating connection is then closed")
    void migrationsApplyAndTheirConnectionIsClosed() throws SQLException {
        assertThat(database().migrationsApplied()).isPositive();
        assertThat(database().otherSessionsAfterMigration())
                .as("sessions still open on the database right after Flyway finished").isZero();

        List<String> versions = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL"
                             + " ORDER BY installed_rank")) {
            while (rs.next()) {
                assertThat(rs.getBoolean("success")).as("migration " + rs.getString("version")).isTrue();
                versions.add(rs.getString("version"));
            }
        }
        assertThat(versions).startsWith("1").hasSize(database().migrationsApplied());
    }

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-Migrations-02: running the migrations again changes nothing, and the files match their checksums")
    void secondRunChangesNothing() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @Tag("FR-DATA-02")
    @Tag("FR-DATA-05")
    @DisplayName("TC-Migrations-03: Hibernate validates the entities against the tables and saves and reloads a shortlist")
    void hibernateValidatesAndRoundTrips() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            insertSchools("tampines-secondary-school", "catholic-high-school");   // V3: saved codes name schools
            Account account = accountRepository.save(
                    new Account("Round_Trip", "Round@Example.com", "$2a$10$notARealHash", Instant.EPOCH));
            Shortlist shortlist = new Shortlist(account);
            shortlist.addSchool(TestSchools.named("tampines-secondary-school", "TAMPINES SECONDARY SCHOOL"));
            ChoicePlan plan = new ChoicePlan(12, 3);   // id from choice_plan_seq
            plan.addChoice(TestSchools.named("catholic-high-school", "CATHOLIC HIGH SCHOOL"), 1);
            shortlist.setChoicePlan(plan);
            shortlistRepository.saveAndFlush(shortlist);
            entityManager.clear();
            status.setRollbackOnly();

            Shortlist reloaded = shortlistRepository.findById(account.getAccountId()).orElseThrow();
            assertThat(reloaded.getSchoolCodes()).containsExactly("tampines-secondary-school");
            assertThat(reloaded.getChoicePlan().getChoices())
                    .extracting(SchoolChoice::getSchoolCode, SchoolChoice::getRank)
                    .containsExactly(tuple("catholic-high-school", 1));
            assertThat(accountRepository.findByUsernameKey("round_trip")).isPresent();
        });
    }

    @Test
    @Tag("FR-DATA-02")
    @Tag("FR-DATA-05")
    @DisplayName("TC-Migrations-27: saving a shortlist or plan through JPA with a code that names no school is refused (V3)")
    void unknownSchoolCodeRefusedThroughJpa() {
        for (boolean inPlan : new boolean[] {false, true}) {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(status -> {
                insertSchools("catholic-high-school");
                Account account = accountRepository.save(
                        new Account("Fk_Probe", "fk@example.com", "$2a$10$notARealHash", Instant.EPOCH));
                Shortlist shortlist = new Shortlist(account);
                shortlist.addSchool(TestSchools.named("catholic-high-school", "CATHOLIC HIGH SCHOOL"));
                School unknown = TestSchools.named("closed-secondary-school", "CLOSED SECONDARY SCHOOL");
                if (inPlan) {
                    ChoicePlan plan = new ChoicePlan(12, 3);
                    plan.addChoice(unknown, 1);
                    shortlist.setChoicePlan(plan);
                } else {
                    shortlist.addSchool(unknown);
                }
                try {
                    shortlistRepository.saveAndFlush(shortlist);
                } finally {
                    status.setRollbackOnly();
                }
            }));
            assertThat(thrown).as(inPlan ? "plan choice" : "saved school")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(thrown.getMessage().toLowerCase(Locale.ROOT))
                    .contains(inPlan ? "fk_choice_plan_choice_school" : "fk_shortlist_school_school");
        }
    }

    @Test
    @Tag("FR-DATA-01")
    @DisplayName("TC-Migrations-04: every column has the same name, type, length and nullability as on a fresh H2 database")
    void columnsEqualTheH2Reference() throws SQLException {
        List<String> actual;
        try (Connection c = dataSource.getConnection()) {
            actual = columns(c);
        }
        assertThat(actual).isNotEmpty().isEqualTo(h2Reference(MigrationsTest::columns));
    }

    @Test
    @Tag("FR-DATA-01")
    @DisplayName("TC-Migrations-05: keys, checks and foreign keys carry the same names as on a fresh H2 database")
    void constraintNamesEqualTheH2Reference() throws SQLException {
        List<String> actual;
        try (Connection c = dataSource.getConnection()) {
            actual = namedConstraints(c);
        }
        assertThat(actual).isEqualTo(h2Reference(MigrationsTest::namedConstraints));
        assertThat(actual).contains(
                "account pk_account PRIMARY KEY", "account uq_account_username_key UNIQUE",
                "account ck_account_status CHECK", "shortlist fk_shortlist_choice_plan FOREIGN KEY");
    }

    @Test
    @Tag("FR-DATA-01")
    @DisplayName("TC-Migrations-06: the clean-up and saved-school indexes exist")
    void indexesExist() throws SQLException {
        Set<String> names = new TreeSet<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(indexNamesSql())) {
            while (rs.next()) {
                names.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        assertThat(names).containsAll(V1_INDEXES);
    }

    @Test
    @Tag("FR-DATA-02")
    @DisplayName("TC-Migrations-07: no CHECK in any migration holds an IN list (H2 2.4.240 closed-connection bug)")
    void noInListInChecks() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql");
        assertThat(files).isNotEmpty();
        Pattern check = Pattern.compile("\\bCHECK\\s*\\(", Pattern.CASE_INSENSITIVE);
        Pattern inList = Pattern.compile("\\bIN\\s*\\(", Pattern.CASE_INSENSITIVE);
        for (Resource file : files) {
            String sql = file.getContentAsString(StandardCharsets.UTF_8).replaceAll("--[^\\n]*", "");
            Matcher m = check.matcher(sql);
            while (m.find()) {
                String body = balancedParentheses(sql, m.end() - 1);
                assertThat(inList.matcher(body).find())
                        .as(file.getFilename() + ": CHECK " + body + " uses IN (...); write REGEXP_LIKE instead")
                        .isFalse();
            }
        }
    }

    @Test
    @Tag("FR-DATA-02")
    @Tag("FR-DATA-06")
    @DisplayName("TC-Migrations-29: every REGEXP_LIKE format CHECK also refuses characters outside printable ASCII, so H2 and PostgreSQL refuse a trailing line break alike")
    void everyFormatCheckRefusesLineBreaks() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql");
        Pattern check = Pattern.compile("\\bCHECK\\s*\\(", Pattern.CASE_INSENSITIVE);
        Pattern format = Pattern.compile("(?<!NOT )REGEXP_LIKE\\(\\s*(\\w+)\\s*,", Pattern.CASE_INSENSITIVE);
        int formatChecks = 0;
        for (Resource file : files) {
            String sql = file.getContentAsString(StandardCharsets.UTF_8).replaceAll("--[^\\n]*", "");
            Matcher m = check.matcher(sql);
            while (m.find()) {
                String body = balancedParentheses(sql, m.end() - 1);
                Matcher f = format.matcher(body);
                while (f.find()) {
                    formatChecks++;
                    assertThat(body).as(file.getFilename() + ": CHECK " + body)
                            .contains("NOT REGEXP_LIKE(" + f.group(1) + ", '[^ -~]')");
                }
            }
        }
        assertThat(formatChecks).as("format CHECKs in V1 and V2").isEqualTo(12);
    }

    // ----------------------------------------------------------------------------------------------------------
    // Rows the database accepts and refuses (design sections 4.1 and 4.3)

    @Test
    @Tag("FR-DATA-02")
    @Tag("FR-DATA-05")
    @DisplayName("TC-Migrations-08: valid rows in every user table are accepted, including the edge values")
    void validRowsAccepted() {
        assertAccepted(
                "UPDATE user_profile SET psle_score = 4, posting_group = 1, max_commute_min = 15, travel_mode = 'WALK'"
                        + " WHERE account_id = 'a1'",
                "UPDATE user_profile SET psle_score = 32, posting_group = 3, max_commute_min = 60,"
                        + " travel_mode = 'DRIVE', home_latitude = 1.48, home_longitude = 104.10"
                        + " WHERE account_id = 'a1'",
                "UPDATE user_profile SET home_latitude = NULL, home_longitude = NULL WHERE account_id = 'a1'",
                "UPDATE account SET status = 'ACTIVE' WHERE account_id = 'a2'",
                "UPDATE external_usage SET used = used + 1",
                "INSERT INTO authenticated_session (session_id, account_id, issued_at, expires_at, invalidated)"
                        + " VALUES ('s2', 'a1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, TRUE)");
    }

    /** One row the database must refuse: {@code expected} is the violated constraint (or column) name. */
    record Refused(String id, String what, String probe, String sqlStateClass, String expected) {

        static Refused by(String id, String what, String constraint, String probe) {
            return new Refused(id, what, probe, "23", constraint);
        }

        @Override
        public String toString() {
            return id + ": " + what;
        }
    }

    static Stream<Refused> refusedRows() {
        return Stream.of(
                Refused.by("09a", "username key that is not the lower-cased username", "ck_account_username_key",
                        "INSERT INTO account (account_id, username, username_key, email, password_hash, created_at,"
                                + " status) VALUES ('a3', 'Cat', 'CAT', 'cat@example.com', 'h', CURRENT_TIMESTAMP,"
                                + " 'ACTIVE')"),
                Refused.by("09b", "unknown account status", "ck_account_status",
                        "UPDATE account SET status = 'LOCKED' WHERE account_id = 'a1'"),
                Refused.by("09c", "account status in lower case", "ck_account_status",
                        "UPDATE account SET status = 'active' WHERE account_id = 'a1'"),
                Refused.by("09d", "username that differs from another only in letter case (DC-70)",
                        "uq_account_username_key",
                        "INSERT INTO account (account_id, username, username_key, email, password_hash, created_at,"
                                + " status) VALUES ('a3', 'ALICE_1', 'alice_1', 'other@example.com', 'h',"
                                + " CURRENT_TIMESTAMP, 'ACTIVE')"),
                Refused.by("09e", "second account with the same email", "uq_account_email",
                        "INSERT INTO account (account_id, username, username_key, email, password_hash, created_at,"
                                + " status) VALUES ('a3', 'Cat', 'cat', 'alice@example.com', 'h', CURRENT_TIMESTAMP,"
                                + " 'ACTIVE')"),
                Refused.by("09f", "second account with the same id", "pk_account",
                        "INSERT INTO account (account_id, username, username_key, email, password_hash, created_at,"
                                + " status) VALUES ('a1', 'Cat', 'cat', 'cat@example.com', 'h', CURRENT_TIMESTAMP,"
                                + " 'ACTIVE')"),
                Refused.by("09g", "account without a password hash", "password_hash",
                        "INSERT INTO account (account_id, username, username_key, email, password_hash, created_at,"
                                + " status) VALUES ('a3', 'Cat', 'cat', 'cat@example.com', NULL, CURRENT_TIMESTAMP,"
                                + " 'ACTIVE')"),
                new Refused("09h", "username longer than 30 characters",
                        "UPDATE account SET username = 'A234567890123456789012345678901', username_key ="
                                + " 'a234567890123456789012345678901' WHERE account_id = 'a1'", "22", null),
                Refused.by("10a", "login session that expires before it was issued", "ck_authenticated_session_times",
                        "UPDATE authenticated_session SET expires_at = issued_at - INTERVAL '1' MINUTE"),
                Refused.by("10b", "login session of an unknown account", "fk_authenticated_session_account",
                        "INSERT INTO authenticated_session (session_id, account_id, issued_at, expires_at,"
                                + " invalidated) VALUES ('s9', 'nobody', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE)"),
                Refused.by("11a", "PSLE score 33", "ck_user_profile_psle_score",
                        "UPDATE user_profile SET psle_score = 33 WHERE account_id = 'a1'"),
                Refused.by("11b", "PSLE score 3", "ck_user_profile_psle_score",
                        "UPDATE user_profile SET psle_score = 3 WHERE account_id = 'a1'"),
                Refused.by("11c", "posting group 4", "ck_user_profile_posting_group",
                        "UPDATE user_profile SET posting_group = 4 WHERE account_id = 'a1'"),
                Refused.by("11d", "commute limit 20 minutes", "ck_user_profile_max_commute",
                        "UPDATE user_profile SET max_commute_min = 20 WHERE account_id = 'a1'"),
                Refused.by("11e", "commute limit 75 minutes", "ck_user_profile_max_commute",
                        "UPDATE user_profile SET max_commute_min = 75 WHERE account_id = 'a1'"),
                Refused.by("11f", "commute limit 0 minutes", "ck_user_profile_max_commute",
                        "UPDATE user_profile SET max_commute_min = 0 WHERE account_id = 'a1'"),
                Refused.by("11g", "unknown travel mode", "ck_user_profile_travel_mode",
                        "UPDATE user_profile SET travel_mode = 'BICYCLE' WHERE account_id = 'a1'"),
                Refused.by("11h", "travel mode in lower case", "ck_user_profile_travel_mode",
                        "UPDATE user_profile SET travel_mode = 'walk' WHERE account_id = 'a1'"),
                Refused.by("11i", "home latitude without a longitude", "ck_user_profile_home_pair",
                        "UPDATE user_profile SET home_longitude = NULL WHERE account_id = 'a1'"),
                Refused.by("11j", "home outside Singapore", "ck_user_profile_home_in_sg",
                        "UPDATE user_profile SET home_latitude = 0, home_longitude = 0 WHERE account_id = 'a1'"),
                Refused.by("11k", "profile of an unknown account", "fk_user_profile_account",
                        "INSERT INTO user_profile (account_id) VALUES ('nobody')"),
                Refused.by("12a", "the same preferred CCA twice", "pk_user_profile_cca",
                        "INSERT INTO user_profile_cca (account_id, cca) VALUES ('a1', 'BADMINTON')"),
                Refused.by("12b", "preferred CCA without a profile", "fk_user_profile_cca_profile",
                        "INSERT INTO user_profile_cca (account_id, cca) VALUES ('nobody', 'BADMINTON')"),
                Refused.by("12c", "the same preferred programme twice", "pk_user_profile_programme",
                        "INSERT INTO user_profile_programme (account_id, programme) VALUES ('a1', 'Robotics')"),
                Refused.by("13a", "plan made for PSLE score 33", "ck_choice_plan_score",
                        "UPDATE choice_plan SET based_on_score = 33 WHERE id = 1"),
                Refused.by("13b", "plan made for posting group 0", "ck_choice_plan_posting_group",
                        "UPDATE choice_plan SET posting_group = 0 WHERE id = 1"),
                Refused.by("13c", "plan choice at rank 7", "ck_choice_plan_choice_rank",
                        "INSERT INTO choice_plan_choice (choice_plan_id, choice_rank, school_code)"
                                + " VALUES (1, 7, 'tampines-secondary-school')"),
                Refused.by("13d", "plan choice at rank 0", "ck_choice_plan_choice_rank",
                        "INSERT INTO choice_plan_choice (choice_plan_id, choice_rank, school_code)"
                                + " VALUES (1, 0, 'tampines-secondary-school')"),
                Refused.by("13e", "the same school twice in one plan", "uq_choice_plan_choice_school",
                        "INSERT INTO choice_plan_choice (choice_plan_id, choice_rank, school_code)"
                                + " VALUES (1, 2, 'catholic-high-school')"),
                Refused.by("13f", "two schools at the same rank", "pk_choice_plan_choice",
                        "INSERT INTO choice_plan_choice (choice_plan_id, choice_rank, school_code)"
                                + " VALUES (1, 1, 'tampines-secondary-school')"),
                Refused.by("13g", "choice in an unknown plan", "fk_choice_plan_choice_plan",
                        "INSERT INTO choice_plan_choice (choice_plan_id, choice_rank, school_code)"
                                + " VALUES (99, 1, 'catholic-high-school')"),
                Refused.by("14a", "two shortlists sharing one plan", "uq_shortlist_choice_plan",
                        "UPDATE shortlist SET choice_plan_id = 1 WHERE account_id = 'a2'"),
                Refused.by("14b", "shortlist of an unknown account", "fk_shortlist_account",
                        "INSERT INTO shortlist (account_id) VALUES ('nobody')"),
                Refused.by("14c", "shortlist pointing at an unknown plan", "fk_shortlist_choice_plan",
                        "UPDATE shortlist SET choice_plan_id = 99 WHERE account_id = 'a2'"),
                Refused.by("14d", "the same school saved twice", "pk_shortlist_school",
                        "INSERT INTO shortlist_school (account_id, school_code)"
                                + " VALUES ('a1', 'catholic-high-school')"),
                Refused.by("14e", "saved school without a shortlist", "fk_shortlist_school_shortlist",
                        "INSERT INTO shortlist_school (account_id, school_code)"
                                + " VALUES ('nobody', 'catholic-high-school')"),
                // V3 school references (design sections 4.1 and 4.3)
                Refused.by("13h", "plan choice of an unknown school (V3)", "fk_choice_plan_choice_school",
                        "INSERT INTO choice_plan_choice (choice_plan_id, choice_rank, school_code)"
                                + " VALUES (1, 2, 'no-such-school')"),
                Refused.by("13i", "plan choice moved to an unknown school (V3)", "fk_choice_plan_choice_school",
                        "UPDATE choice_plan_choice SET school_code = 'no-such-school' WHERE choice_rank = 6"),
                Refused.by("14f", "saved school code that names no school (V3)", "fk_shortlist_school_school",
                        "INSERT INTO shortlist_school (account_id, school_code) VALUES ('a2', 'no-such-school')"),
                Refused.by("15a", "negative Google usage count", "ck_external_usage_used",
                        "UPDATE external_usage SET used = -1"),
                Refused.by("15b", "two usage counters for one day and SKU", "pk_external_usage",
                        "INSERT INTO external_usage (usage_day, sku, used) VALUES (DATE '2026-10-04', 'routes', 5)"),
                // V2 school dataset (design section 4.2)
                Refused.by("20a", "dataset version name with a space", "ck_dataset_version_name",
                        versionRow("bad version", "full", "PASSED", HASH_B)),
                Refused.by("20b", "dataset kind in upper case", "ck_dataset_version_kind",
                        versionRow("v-x", "SEED", "PASSED", HASH_B)),
                Refused.by("20c", "content hash in upper case", "ck_dataset_version_sha256",
                        versionRow("v-x", "full", "PASSED", HASH_B.toUpperCase(Locale.ROOT))),
                Refused.by("20d", "a FAILED snapshot recorded as loaded", "ck_dataset_version_load_status",
                        versionRow("v-x", "full", "FAILED", HASH_B)),
                Refused.by("20e", "two versions with the same content hash", "uq_dataset_version_sha256",
                        versionRow("v-x", "full", "PASSED", HASH_A)),
                Refused.by("20f", "unknown manifest status", "ck_dataset_version_manifest_status",
                        "UPDATE dataset_version SET manifest_status = 'UNKNOWN'"),
                Refused.by("20g", "loader format 0", "ck_dataset_version_loader_format",
                        "UPDATE dataset_version SET loader_format = 0"),
                Refused.by("20h", "the same source id twice in one version", "uq_dataset_source_id",
                        "INSERT INTO dataset_source (dataset_version, source_no, source_id, source_name)"
                                + " VALUES ('2026-10-04.1', 3, 'data/curated', 'again')"),
                Refused.by("20i", "source number 0", "ck_dataset_source_no",
                        "INSERT INTO dataset_source (dataset_version, source_no, source_id, source_name)"
                                + " VALUES ('2026-10-04.1', 0, 'x', 'x')"),
                Refused.by("20j", "warning of an unknown version", "fk_dataset_warning_version",
                        "INSERT INTO dataset_warning (dataset_version, warning_no, message) VALUES ('nope', 1, 'x')"),
                Refused.by("21a", "a second active_dataset row", "ck_active_dataset_singleton",
                        "INSERT INTO active_dataset (singleton_id, dataset_version) VALUES (2, NULL)"),
                Refused.by("21b", "active version that was never loaded", "fk_active_dataset_version",
                        "UPDATE active_dataset SET dataset_version = 'nope' WHERE singleton_id = 1"),
                Refused.by("21c", "deleting the active version", "fk_active_dataset_version",
                        "DELETE FROM dataset_version WHERE dataset_version = '2026-10-04.1'"),
                Refused.by("22a", "two planning areas with one name", "uq_district_name",
                        "INSERT INTO district (planning_area_code, planning_area_name) VALUES ('B2', 'BISHAN')"),
                Refused.by("22b", "deleting a planning area that holds a school", "fk_school_district",
                        "DELETE FROM district WHERE planning_area_code = 'BS'"),
                Refused.by("22c", "planning area withdrawn in an unknown version", "fk_district_withdrawn",
                        "UPDATE district SET withdrawn_in_version = 'nope' WHERE planning_area_code = 'SV'"),
                Refused.by("23a", "school code with capitals and a space", "ck_school_code",
                        schoolRow("'Bad Code'", "NULL", "1.3", "'BS'")),
                Refused.by("23b", "postal code of 5 digits", "ck_school_postal_code",
                        schoolRow("'x-1'", "'12345'", "1.3", "'BS'")),
                Refused.by("23c", "school outside Singapore", "ck_school_in_singapore",
                        schoolRow("'x-2'", "NULL", "2.3", "'BS'")),
                Refused.by("23d", "school in an unknown planning area", "fk_school_district",
                        schoolRow("'x-3'", "NULL", "1.3", "'ZZ'")),
                Refused.by("23e", "school without a name", "school_name",
                        "INSERT INTO school (school_code, school_name, latitude, longitude, planning_area_code)"
                                + " VALUES ('x-4', NULL, 1.3, 103.8, 'BS')"),
                Refused.by("23f", "school withdrawn in an unknown version", "fk_school_withdrawn",
                        "UPDATE school SET withdrawn_in_version = 'nope' WHERE school_code = 'raffles-institution'"),
                Refused.by("23g", "deleting a school saved in a shortlist (V3 RESTRICT)", "fk_shortlist_school_school",
                        "DELETE FROM school WHERE school_code = 'tampines-secondary-school'"),
                Refused.by("23h", "deleting a school chosen in a plan (V3 RESTRICT)", "fk_choice_plan_choice_school",
                        "DELETE FROM school WHERE school_code = 'yishun-secondary-school'"),
                Refused.by("24a", "Integrated Programme range outside PG3 (DC-77)", "ck_indicative_psle_score_range_ip_pg3",
                        rangeRow("2025, 2, FALSE, TRUE, 10, 12, NULL, NULL, NULL")),
                Refused.by("24b", "admission year 2021 (before AL scores)", "ck_indicative_psle_score_range_year",
                        rangeRow("2021, 1, FALSE, FALSE, 10, 12, NULL, NULL, NULL")),
                Refused.by("24c", "lower score above upper score", "ck_indicative_psle_score_range_scores",
                        rangeRow("2024, 1, FALSE, FALSE, 14, 12, NULL, NULL, NULL")),
                Refused.by("24d", "posting group 4", "ck_indicative_psle_score_range_pg",
                        rangeRow("2024, 4, FALSE, FALSE, 10, 12, NULL, NULL, NULL")),
                Refused.by("24e", "Higher Chinese grade X", "ck_indicative_psle_score_range_lower_grade",
                        rangeRow("2024, 1, FALSE, FALSE, 10, 12, 'X', NULL, FALSE")),
                Refused.by("24f", "upper grade A", "ck_indicative_psle_score_range_upper_grade",
                        rangeRow("2024, 1, FALSE, FALSE, 10, 12, NULL, 'A', FALSE")),
                Refused.by("24g", "a grade while the MOE text is unknown", "ck_indicative_psle_score_range_text_known",
                        rangeRow("2024, 1, FALSE, FALSE, 10, 12, 'D', NULL, NULL")),
                Refused.by("24h", "the same range twice", "pk_indicative_psle_score_range",
                        rangeRow("2025, 3, FALSE, FALSE, 7, 9, NULL, NULL, NULL")),
                Refused.by("24i", "range of an unknown school", "fk_indicative_psle_score_range_school",
                        "INSERT INTO indicative_psle_score_range (school_code, admission_year, posting_group,"
                                + " affiliated, integrated_programme, lower_score, upper_score)"
                                + " VALUES ('nope', 2025, 1, FALSE, FALSE, 10, 12)"),
                Refused.by("25a", "two bus services in one row", "ck_school_bus_service_no",
                        "INSERT INTO school_bus_service VALUES ('catholic-high-school', '14E 16', 3)"),
                Refused.by("25b", "bus service text 243G/W", "ck_school_bus_service_no",
                        "INSERT INTO school_bus_service VALUES ('catholic-high-school', '243G/W', 3)"),
                Refused.by("25c", "bus list position 0", "ck_school_bus_service_position",
                        "INSERT INTO school_bus_service VALUES ('catholic-high-school', '54', 0)"),
                Refused.by("25d", "two bus services at one position", "uq_school_bus_service_position",
                        "INSERT INTO school_bus_service VALUES ('catholic-high-school', '54', 2)"),
                Refused.by("25e", "two MRT stations in one row", "ck_school_mrt_station_one_name",
                        "INSERT INTO school_mrt_station VALUES ('catholic-high-school', 'BISHAN MRT, MARYMOUNT MRT', 2)"),
                Refused.by("25f", "MRT list position 0", "ck_school_mrt_station_position",
                        "INSERT INTO school_mrt_station VALUES ('catholic-high-school', 'MARYMOUNT MRT', 0)"),
                Refused.by("25g", "the same CCA twice", "pk_school_cca",
                        "INSERT INTO school_cca VALUES ('catholic-high-school', 'BADMINTON')"),
                Refused.by("25h", "programme of an unknown school", "fk_school_programme_school",
                        "INSERT INTO school_programme VALUES ('nope', 'Robotics')"),
                // A trailing line break (design section 8): H2 uses Java regex, where '$' also matches just before
                // a final line break, so REGEXP_LIKE alone would let these through on H2 and not on PostgreSQL.
                // Only columns long enough to hold the value plus the break are probed; in the others
                // (dataset_kind, content_sha256, postal_code, the two grades) the length already refuses it.
                Refused.by("28a", "account status with a trailing \\n", "ck_account_status",
                        "UPDATE account SET status = 'ACTIVE' || CHR(10) WHERE account_id = 'a1'"),
                Refused.by("28b", "account status with a trailing \\r", "ck_account_status",
                        "UPDATE account SET status = 'ACTIVE' || CHR(13) WHERE account_id = 'a1'"),
                Refused.by("28c", "account status with a trailing \\r\\n", "ck_account_status",
                        "UPDATE account SET status = 'ACTIVE' || CHR(13) || CHR(10) WHERE account_id = 'a1'"),
                Refused.by("28d", "account status with a trailing U+0085 (next line)", "ck_account_status",
                        "UPDATE account SET status = 'ACTIVE' || CHR(133) WHERE account_id = 'a1'"),
                Refused.by("28e", "account status with a trailing U+2028 (line separator)", "ck_account_status",
                        "UPDATE account SET status = 'ACTIVE' || CHR(8232) WHERE account_id = 'a1'"),
                Refused.by("28f", "account status with a trailing U+2029 (paragraph separator)", "ck_account_status",
                        "UPDATE account SET status = 'ACTIVE' || CHR(8233) WHERE account_id = 'a1'"),
                Refused.by("28g", "travel mode with a trailing \\n", "ck_user_profile_travel_mode",
                        "UPDATE user_profile SET travel_mode = 'WALK' || CHR(10) WHERE account_id = 'a1'"),
                Refused.by("28h", "dataset version name with a trailing \\n", "ck_dataset_version_name",
                        versionRow("v-x' || CHR(10) || '", "full", "PASSED", HASH_B)),
                Refused.by("28i", "manifest status with a trailing \\n", "ck_dataset_version_manifest_status",
                        "UPDATE dataset_version SET manifest_status = 'PASSED' || CHR(10)"),
                Refused.by("28j", "load status with a trailing \\n", "ck_dataset_version_load_status",
                        versionRow("v-x", "full", "PASSED' || CHR(10) || '", HASH_B)),
                Refused.by("28k", "school code with a trailing \\n", "ck_school_code",
                        schoolRow("'x-5' || CHR(10)", "NULL", "1.3", "'BS'")),
                Refused.by("28l", "bus service with a trailing \\n", "ck_school_bus_service_no",
                        "INSERT INTO school_bus_service VALUES ('catholic-high-school', '54' || CHR(10), 3)"));
    }

    private static final String HASH_B = "fedcba9876543210".repeat(4);

    private static String versionRow(String version, String kind, String loadStatus, String hash) {
        return "INSERT INTO dataset_version (dataset_version, dataset_kind, effective_date, imported_at, load_status,"
                + " content_sha256, loader_format, loaded_at) VALUES ('" + version + "', '" + kind + "', DATE"
                + " '2026-10-05', CURRENT_TIMESTAMP, '" + loadStatus + "', '" + hash + "', 1, CURRENT_TIMESTAMP)";
    }

    private static String schoolRow(String code, String postalCode, String latitude, String area) {
        return "INSERT INTO school (school_code, school_name, postal_code, latitude, longitude, planning_area_code)"
                + " VALUES (" + code + ", 'X', " + postalCode + ", " + latitude + ", 103.8, " + area + ")";
    }

    private static String rangeRow(String values) {
        return "INSERT INTO indicative_psle_score_range (school_code, admission_year, posting_group, affiliated,"
                + " integrated_programme, lower_score, upper_score, lower_hcl_grade, upper_hcl_grade, places_left)"
                + " VALUES ('catholic-high-school', " + values + ")";
    }

    @ParameterizedTest(name = "TC-Migrations-{0}")
    @MethodSource("refusedRows")
    @Tag("FR-DATA-01")
    @Tag("FR-DATA-02")
    @Tag("FR-DATA-05")
    @Tag("FR-DATA-06")
    @DisplayName("TC-Migrations-09 to 15, 20 to 25, 28: a row that breaks a key, check or foreign key is refused")
    void badRowRefused(Refused row) {
        SQLException e = runOnValidRows(List.of(row.probe()));
        if (e == null) {
            fail("accepted, but should be refused: " + row.probe());
        }
        assertThat(e.getSQLState()).as(e.getMessage()).startsWith(row.sqlStateClass());
        assertThat(e.getMessage()).as("not the H2 closed-connection bug (design section 8)")
                .doesNotContainIgnoringCase("database has been closed");
        boolean checkName = row.expected() != null
                && (namesPrimaryKeysInErrors() || !row.expected().startsWith("pk_"));
        if (checkName) {
            assertThat(e.getMessage().toLowerCase(Locale.ROOT)).contains(row.expected());
        }
    }

    @Test
    @Tag("FR-DATA-02")
    @Tag("FR-DATA-05")
    @DisplayName("TC-Migrations-16: deleting an account removes its sessions, profile and shortlist; its plan row stays")
    void accountDeleteCascades() {
        List<List<Integer>> counts = queryOnValidRows(
                List.of("DELETE FROM account WHERE account_id = 'a1'"),
                "SELECT (SELECT COUNT(*) FROM authenticated_session), (SELECT COUNT(*) FROM user_profile),"
                        + " (SELECT COUNT(*) FROM user_profile_cca), (SELECT COUNT(*) FROM user_profile_programme),"
                        + " (SELECT COUNT(*) FROM shortlist), (SELECT COUNT(*) FROM shortlist_school),"
                        + " (SELECT COUNT(*) FROM choice_plan), (SELECT COUNT(*) FROM choice_plan_choice)");
        // a2's profile, shortlist and saved school remain; both plans and their choices remain (known limit,
        // design section 4.3)
        assertThat(counts).containsExactly(List.of(0, 1, 0, 0, 1, 1, 2, 3));
    }

    @Test
    @Tag("FR-DATA-05")
    @DisplayName("TC-Migrations-17: deleting a plan clears the shortlist's link to it and removes its choices")
    void planDeleteSetsNullAndCascades() {
        List<List<Integer>> counts = queryOnValidRows(
                List.of("DELETE FROM choice_plan WHERE id = 1"),
                "SELECT (SELECT COUNT(*) FROM shortlist WHERE choice_plan_id IS NULL),"
                        + " (SELECT COUNT(*) FROM shortlist_school), (SELECT COUNT(*) FROM choice_plan_choice)");
        assertThat(counts).containsExactly(List.of(2, 3, 1));   // plan 2's choice stays
    }

    @Test
    @Tag("FR-DATA-01")
    @DisplayName("TC-Migrations-18: choice_plan_seq hands out plan ids in blocks of 50, as Hibernate expects")
    void sequenceStepsByFifty() {
        List<List<Integer>> ids = queryOnValidRows(List.of(),
                "SELECT nextval('choice_plan_seq') UNION ALL SELECT nextval('choice_plan_seq')");
        assertThat(ids.get(1).get(0) - ids.get(0).get(0)).isEqualTo(50);
    }

    @Test
    @Tag("FR-DATA-01")
    @Tag("FR-DATA-06")
    @DisplayName("TC-Migrations-19: valid school dataset rows are accepted, including the edge values")
    void validSchoolRowsAccepted() {
        assertAccepted(
                rangeRow("2022, 1, TRUE, FALSE, 4, 4, 'M', 'M', TRUE"),
                rangeRow("2100, 2, FALSE, FALSE, 32, 32, NULL, 'D', FALSE"),
                rangeRow("2025, 3, TRUE, TRUE, 4, 8, NULL, NULL, NULL"),      // affiliated IP range (DC-82)
                "INSERT INTO school_bus_service VALUES ('catholic-high-school', '74e', 3)",
                "INSERT INTO school_bus_service VALUES ('catholic-high-school', 'CT18', 4)",
                "INSERT INTO school_bus_service VALUES ('catholic-high-school', '904', 5)",
                "INSERT INTO school_mrt_station VALUES ('catholic-high-school', 'OUTRAM PARK MRT (EW16)', 2)",
                "INSERT INTO school_affiliated_primary VALUES ('raffles-institution', 'CATHOLIC HIGH SCHOOL (PRIMARY)')",
                "INSERT INTO school (school_code, school_name, latitude, longitude, planning_area_code)"
                        + " VALUES ('edge-1', 'EDGE', 1.15, 103.59, 'SV')",
                "INSERT INTO school (school_code, school_name, latitude, longitude, planning_area_code)"
                        + " VALUES ('edge-2', 'EDGE', 1.48, 104.10, 'SV')",   // names are not unique
                versionRow("0000-seed", "seed", "PASSED_WITH_WARNINGS", HASH_B));
    }

    @Test
    @Tag("FR-DATA-01")
    @Tag("FR-DATA-05")
    @DisplayName("TC-Migrations-26: a school is withdrawn, not deleted; a new code may take its name; it can come back")
    void schoolLifecycle() {
        List<List<Integer>> counts = queryOnValidRows(List.of(
                        versionRow("2026-10-05.1", "full", "PASSED", HASH_B),
                        "UPDATE school SET withdrawn_in_version = '2026-10-05.1' WHERE school_code = 'catholic-high-school'",
                        "UPDATE district SET withdrawn_in_version = '2026-10-05.1' WHERE planning_area_code = 'SV'",
                        "INSERT INTO school (school_code, school_name, latitude, longitude, planning_area_code)"
                                + " SELECT 'catholic-high-school-new', school_name, latitude, longitude,"
                                + " planning_area_code FROM school WHERE school_code = 'catholic-high-school'",
                        "DELETE FROM school_cca", "DELETE FROM school_programme", "DELETE FROM school_affiliated_primary",
                        "DELETE FROM school_bus_service", "DELETE FROM school_mrt_station",
                        "DELETE FROM indicative_psle_score_range",
                        "UPDATE active_dataset SET dataset_version = '2026-10-05.1' WHERE singleton_id = 1",
                        "UPDATE school SET withdrawn_in_version = NULL WHERE school_code = 'catholic-high-school'"),
                "SELECT (SELECT COUNT(*) FROM school), (SELECT COUNT(*) FROM school WHERE withdrawn_in_version IS NULL),"
                        + " (SELECT COUNT(*) FROM district WHERE withdrawn_in_version IS NOT NULL),"
                        + " (SELECT COUNT(*) FROM shortlist_school), (SELECT COUNT(*) FROM dataset_version)");
        assertThat(counts).containsExactly(List.of(5, 5, 1, 3, 2));
    }

    // ----------------------------------------------------------------------------------------------------------
    // Helpers

    /** Inserts one planning area and the given schools inside the current JPA transaction. */
    private void insertSchools(String... codes) {
        entityManager.createNativeQuery("INSERT INTO district (planning_area_code, planning_area_name)"
                + " VALUES ('BS', 'BISHAN')").executeUpdate();
        for (String code : codes) {
            entityManager.createNativeQuery("INSERT INTO school (school_code, school_name, latitude, longitude,"
                    + " planning_area_code) VALUES (?1, ?2, 1.35, 103.85, 'BS')")
                    .setParameter(1, code)
                    .setParameter(2, code.toUpperCase(Locale.ROOT).replace('-', ' '))
                    .executeUpdate();
        }
    }

    private void assertAccepted(String... statements) {
        SQLException e = runOnValidRows(List.of(statements));
        if (e != null) {
            fail("refused, but should be accepted: " + e.getMessage());
        }
    }

    /** Inserts {@link #VALID_ROWS}, runs the statements, rolls back; returns the first failure of a statement. */
    private SQLException runOnValidRows(List<String> statements) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                for (String sql : VALID_ROWS) {
                    s.execute(sql);
                }
                for (String sql : statements) {
                    try {
                        s.execute(sql);
                    } catch (SQLException e) {
                        return e;
                    }
                }
                return null;
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the valid rows were refused: " + e.getMessage(), e);
        }
    }

    /** Like {@link #runOnValidRows} (all statements must pass), then returns the integer columns of a query. */
    private List<List<Integer>> queryOnValidRows(List<String> statements, String query) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                for (String sql : VALID_ROWS) {
                    s.execute(sql);
                }
                for (String sql : statements) {
                    s.execute(sql);
                }
                List<List<Integer>> rows = new ArrayList<>();
                try (ResultSet rs = s.executeQuery(query)) {
                    int n = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        List<Integer> row = new ArrayList<>();
                        for (int i = 1; i <= n; i++) {
                            row.add(rs.getInt(i));
                        }
                        rows.add(row);
                    }
                }
                return rows;
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface SchemaReader {
        List<String> read(Connection c) throws SQLException;
    }

    /** Reads a fresh H2 database migrated with the same files; the reference both subclasses compare with. */
    private static List<String> h2Reference(SchemaReader reader) throws SQLException {
        String url = "jdbc:h2:mem:reference-" + UUID.randomUUID() + ";" + H2_FLAGS;
        try (Connection keepAlive = DriverManager.getConnection(url, "sa", "")) {   // no DB_CLOSE_DELAY: dropped at close
            Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
            return reader.read(keepAlive);
        }
    }

    /** {@code table.column TYPE(length) [precision] NULL|NOT NULL} for every table column, in table and column order. */
    static List<String> columns(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT table_name, column_name, data_type, character_maximum_length, datetime_precision,"
                        + " is_nullable, ordinal_position FROM information_schema.columns"
                        + " WHERE LOWER(table_schema) = 'public' AND table_name <> 'flyway_schema_history'"
                        // base tables only: on H2, Hibernate adds a global temporary table hte_choice_plan at
                        // start-up for bulk deletes (runtime scratch space, not part of the schema)
                        + " AND table_name IN (SELECT table_name FROM information_schema.tables"
                        + " WHERE LOWER(table_schema) = 'public' AND table_type = 'BASE TABLE')")) {
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                String type = rs.getString("data_type").toUpperCase(Locale.ROOT);
                Object length = rs.getObject("character_maximum_length");
                Object precision = type.startsWith("TIMESTAMP") ? rs.getObject("datetime_precision") : null;
                String line = rs.getString("table_name") + "." + rs.getString("column_name") + " " + type
                        + (length == null ? "" : "(" + length + ")")
                        + (precision == null ? "" : " precision " + precision)
                        + ("NO".equals(rs.getString("is_nullable")) ? " NOT NULL" : " NULL");
                rows.add(new Object[] {rs.getString("table_name"), rs.getInt("ordinal_position"), line});
            }
            // sorted in Java: H2 and PostgreSQL order text differently (design section 6.3)
            rows.sort((a, b) -> {
                int byTable = ((String) a[0]).compareTo((String) b[0]);
                return byTable != 0 ? byTable : Integer.compare((Integer) a[1], (Integer) b[1]);
            });
            rows.forEach(r -> out.add((String) r[2]));
        }
        return out;
    }

    /** {@code table constraint TYPE} for every constraint named pk_, uq_, fk_ or ck_ (design section 4). */
    static List<String> namedConstraints(Connection c) throws SQLException {
        Set<String> out = new TreeSet<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT table_name, constraint_name, constraint_type FROM information_schema.table_constraints"
                        + " WHERE LOWER(table_schema) = 'public'")) {
            while (rs.next()) {
                String name = rs.getString("constraint_name").toLowerCase(Locale.ROOT);
                if (name.matches("^(pk|uq|fk|ck)_.*")) {
                    out.add(rs.getString("table_name") + " " + name + " " + rs.getString("constraint_type"));
                }
            }
        }
        return List.copyOf(out);
    }

    /** The text between the parenthesis at {@code open} and its partner (string literals are skipped). */
    private static String balancedParentheses(String sql, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                inString = !inString;
            } else if (!inString && ch == '(') {
                depth++;
            } else if (!inString && ch == ')' && --depth == 0) {
                return sql.substring(open + 1, i);
            }
        }
        throw new IllegalArgumentException("unbalanced CHECK at " + open);
    }
}

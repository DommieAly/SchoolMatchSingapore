package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.AccountStatus;
import sg.schoolmatch.entity.account.AuthenticatedSession;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.persistence.AccountRepository;
import sg.schoolmatch.persistence.AuthenticatedSessionRepository;
import sg.schoolmatch.support.FixedClock;
import sg.schoolmatch.support.TestProperties;
import sg.schoolmatch.support.TestResultLog;

/**
 * Lab 4, section 3.2.2: basis path test of {@link AuthController#login(String, String)} (use case Log In,
 * FR-LOGIN-01..05, DC-46, DC-70). The control flow graph (21 nodes, 29 edges, V(G) = 10) and the ten basis paths
 * P1..P10 are in {@code docs/testing/basis-path-login.md}; test case TC-BP-LOGIN-nn runs path Pnn, and each test's
 * Javadoc gives the node sequence.
 * <p>
 * The two repositories are Mockito mocks. Each test stubs only the lookup its path reaches; any other lookup returns
 * {@code Optional.empty()} (lenient mocks), so a wrong lookup shows up in the actual output instead of stopping the
 * test. The account is a Mockito spy of a real {@link Account}: the real BCrypt check runs, and {@link #run} can list
 * which of decision nodes 17 ({@code verifyPassword}) and 18 ({@code isActive}) ran. A {@link FixedClock} stops
 * "now" at 2026-10-12T02:00:00Z, so the expiry (now + {@code app.session.idle-timeout} = 30 min) is exact.
 * <p>
 * {@link #run} writes everything a test checks into one actual-output string: the session or the field errors, then
 * {@code lookups} (every {@code AccountRepository} call), {@code checks} (the calls of {@code verifyPassword} and
 * {@code isActive}, in order) and {@code saved} (what {@code AuthenticatedSessionRepository.save} was given). Each
 * test records input, expected and actual output with {@link TestResultLog} (file {@code basis-path-login}) and then
 * makes one assertion, actual equals expected. So the recorded row and the JUnit verdict always agree.
 * <p>
 * Expected values: the session or the error comes from the specification (FR-LOGIN-01..05, the Log In use case
 * (step 5, AF-1), DC-46, DC-70, {@code app.session.idle-timeout} in application.yml and the message constants of
 * {@link AuthController}). The {@code lookups}, {@code checks} and {@code saved} parts come from the path the test
 * is designed to run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)   // a lookup a path should not reach must return empty, not fail
class AuthControllerBasisPathTest {

    private static final String FILE = "basis-path-login";

    /** {@code app.session.idle-timeout: 30m} in application.yml (FR-LOGIN-04, use case "expire after inactivity"). */
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final Instant NOW = FixedClock.DEFAULT_INSTANT;
    private static final String PASSWORD = "Passw0rd";
    /** Cost 4 keeps the tests fast; BCrypt reads the cost from the hash, so {@code verifyPassword} still works. */
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    /** The account methods that decision nodes 17 and 18 call. */
    private static final Set<String> ACCOUNT_CHECKS = Set.of("verifyPassword", "isActive");

    private static final String IDENTIFIER_ERROR = "identifier=" + AuthController.IDENTIFIER_MISSING_MESSAGE;
    private static final String PASSWORD_ERROR = "password=" + AuthController.PASSWORD_MISSING_MESSAGE;
    /** DC-46: one generic error under the key "login" with the text of FR-LOGIN-05. */
    private static final String LOGIN_FAILED = "InvalidInputException {" + AuthController.LOGIN_ERROR + "="
            + AuthController.LOGIN_FAILED_MESSAGE + "}";
    private static final String SESSION_FOR_ALICE = "session for alice_01, issuedAt=" + NOW + ", expiresAt="
            + NOW.plus(IDLE_TIMEOUT);
    /** Step 5 failed: no lookup, no account check, nothing saved (nodes 10 to 20 not reached). */
    private static final String STOPPED_AT_LINE_81 = "; lookups=[]; checks=[]; saved=[]";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AuthenticatedSessionRepository sessionRepository;

    private final FixedClock clock = FixedClock.atDefault();
    /** Registered as "alice_01" / "alice@example.com" with password "Passw0rd"; ACTIVE unless a test changes it. */
    private Account alice;
    private AuthController authController;

    @BeforeEach
    void setUp() {
        alice = spy(new Account("alice_01", "alice@example.com", HASH, NOW));
        authController = new AuthController(accountRepository, sessionRepository, clock, TestProperties.defaults());
        when(sessionRepository.save(any(AuthenticatedSession.class))).thenAnswer(call -> call.getArgument(0));
    }

    /**
     * P1 (baseline) = 1-2-3-5-6-8-10-11-13-14-16-17-18-20-21: every decision false. The username is typed as
     * "Alice_01" and must be looked up by its key "alice_01" (DC-70).
     */
    @Test
    @Tag("FR-LOGIN-01")
    @Tag("FR-LOGIN-03")
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-BP-LOGIN-01: P1 baseline - username in another case, right password, ACTIVE: session saved, expires in 30 min")
    void p1_username_rightPassword_active_createsSession() {
        when(accountRepository.findByUsernameKey("alice_01")).thenReturn(Optional.of(alice));

        // node 13 (username lookup), nodes 17 and 18 both ran and were false, node 20 saved the returned session
        record("TC-BP-LOGIN-01", "identifier=Alice_01; password=Passw0rd; findByUsernameKey(alice_01) -> alice_01 "
                        + "(ACTIVE, password Passw0rd)",
                SESSION_FOR_ALICE + "; lookups=[findByUsernameKey(alice_01)]; "
                        + "checks=[verifyPassword(Passw0rd), isActive()]; saved=[returned session]",
                run("Alice_01", PASSWORD));
    }

    /** P2 = 1-2-4-5-6-8-9-21: identifier null; the password checks still run, then line 81 throws (8 forced true). */
    @Test
    @Tag("FR-LOGIN-02")
    @DisplayName("TC-BP-LOGIN-02: P2 - identifier null, password given: identifier error only, no lookup, no session")
    void p2_identifierNull_identifierError() {
        // only the identifier error: node 4 ran, nodes 5 and 6 false; nothing after line 81 ran
        record("TC-BP-LOGIN-02", "identifier=null; password=Passw0rd",
                "InvalidInputException {" + IDENTIFIER_ERROR + "}" + STOPPED_AT_LINE_81, run(null, PASSWORD));
    }

    /** P3 = 1-2-3-4-5-6-8-9-21: identifier only spaces (decision 3 true). */
    @Test
    @Tag("FR-LOGIN-02")
    @DisplayName("TC-BP-LOGIN-03: P3 - identifier only spaces, password given: identifier error only, no lookup, no session")
    void p3_identifierBlank_identifierError() {
        record("TC-BP-LOGIN-03", "identifier=\"   \" (3 spaces); password=Passw0rd",
                "InvalidInputException {" + IDENTIFIER_ERROR + "}" + STOPPED_AT_LINE_81, run("   ", PASSWORD));
    }

    /** P4 = 1-2-3-5-7-8-9-21: password null (decision 5 true). */
    @Test
    @Tag("FR-LOGIN-02")
    @DisplayName("TC-BP-LOGIN-04: P4 - identifier given, password null: password error only, no lookup, no session")
    void p4_passwordNull_passwordError() {
        // only the password error: nodes 2 and 3 false, node 7 ran
        record("TC-BP-LOGIN-04", "identifier=Alice_01; password=null",
                "InvalidInputException {" + PASSWORD_ERROR + "}" + STOPPED_AT_LINE_81, run("Alice_01", null));
    }

    /** P5 = 1-2-3-5-6-7-8-9-21: password empty (decision 6 true). */
    @Test
    @Tag("FR-LOGIN-02")
    @DisplayName("TC-BP-LOGIN-05: P5 - identifier given, password empty: password error only, no lookup, no session")
    void p5_passwordEmpty_passwordError() {
        record("TC-BP-LOGIN-05", "identifier=Alice_01; password=\"\" (empty)",
                "InvalidInputException {" + PASSWORD_ERROR + "}" + STOPPED_AT_LINE_81, run("Alice_01", ""));
    }

    /** P6 = 1-2-3-5-6-8-10-11-12-14-16-17-18-20-21: identifier with "@" (decision 11 true). */
    @Test
    @Tag("FR-LOGIN-01")
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-BP-LOGIN-06: P6 - email, right password, ACTIVE: looked up as an email only, session saved")
    void p6_email_rightPassword_active_createsSession() {
        when(accountRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(alice));

        // node 12, not 13: the only lookup is by email; then as P1
        record("TC-BP-LOGIN-06", "identifier=alice@example.com; password=Passw0rd; "
                        + "findByEmailIgnoreCase(alice@example.com) -> alice_01 (ACTIVE, password Passw0rd)",
                SESSION_FOR_ALICE + "; lookups=[findByEmailIgnoreCase(alice@example.com)]; "
                        + "checks=[verifyPassword(Passw0rd), isActive()]; saved=[returned session]",
                run("alice@example.com", PASSWORD));
    }

    /**
     * P7 = 1-2-3-5-6-8-10-11-13-14-15-21: no account has this username (decision 14 true). If the code went on to
     * node 16 with the empty result, {@code found.get()} would throw NoSuchElementException and the output would
     * differ.
     */
    @Test
    @Tag("FR-LOGIN-05")
    @DisplayName("TC-BP-LOGIN-07: P7 - unknown username: generic login error (DC-46), no session")
    void p7_unknownUsername_genericError() {
        // findByUsernameKey("nobody") is not stubbed: the mock answers Optional.empty(), as the database would.
        record("TC-BP-LOGIN-07", "identifier=nobody; password=Passw0rd; findByUsernameKey(nobody) -> empty",
                LOGIN_FAILED + "; lookups=[findByUsernameKey(nobody)]; checks=[]; saved=[]",
                run("nobody", PASSWORD));
    }

    /** P8 = 1-2-3-5-6-8-10-11-13-14-16-17-19-21: wrong password (decision 17 true; 18 is skipped by "||"). */
    @Test
    @Tag("FR-LOGIN-03")
    @Tag("FR-LOGIN-05")
    @DisplayName("TC-BP-LOGIN-08: P8 - known username, wrong password: generic login error (DC-46), no session")
    void p8_wrongPassword_genericError() {
        when(accountRepository.findByUsernameKey("alice_01")).thenReturn(Optional.of(alice));

        // checks has verifyPassword only: node 17 ran and was true, node 18 was skipped
        record("TC-BP-LOGIN-08", "identifier=Alice_01; password=Passw0rD; findByUsernameKey(alice_01) -> alice_01 "
                        + "(ACTIVE, password Passw0rd)",
                LOGIN_FAILED + "; lookups=[findByUsernameKey(alice_01)]; checks=[verifyPassword(Passw0rD)]; saved=[]",
                run("Alice_01", "Passw0rD"));
    }

    /** P9 = 1-2-3-5-6-8-10-11-13-14-16-17-18-19-21: right password but INACTIVE account (decision 18 true). */
    @Test
    @Tag("FR-LOGIN-05")
    @DisplayName("TC-BP-LOGIN-09: P9 - right password, INACTIVE account: generic login error (DC-46), no session")
    void p9_inactiveAccount_genericError() {
        alice.setStatus(AccountStatus.INACTIVE);
        when(accountRepository.findByUsernameKey("alice_01")).thenReturn(Optional.of(alice));

        // checks has both calls: node 17 ran and was false, node 18 ran and was true
        record("TC-BP-LOGIN-09", "identifier=Alice_01; password=Passw0rd; findByUsernameKey(alice_01) -> alice_01 "
                        + "(INACTIVE, password Passw0rd)",
                LOGIN_FAILED + "; lookups=[findByUsernameKey(alice_01)]; "
                        + "checks=[verifyPassword(Passw0rd), isActive()]; saved=[]",
                run("Alice_01", PASSWORD));
    }

    /** P10 = 1-2-4-5-7-8-9-21: both fields null (decisions 2 and 5 true together); both errors in one exception. */
    @Test
    @Tag("FR-LOGIN-02")
    @DisplayName("TC-BP-LOGIN-10: P10 - identifier and password null: both field errors at once, no lookup, no session")
    void p10_bothNull_bothErrors() {
        // both errors, in field order: nodes 4 and 7 both ran
        record("TC-BP-LOGIN-10", "identifier=null; password=null",
                "InvalidInputException {" + IDENTIFIER_ERROR + ", " + PASSWORD_ERROR + "}" + STOPPED_AT_LINE_81,
                run(null, null));
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Calls {@code login} and describes what it did in the words of the expected strings: the returned session (its
     * account, issue time and expiry) or the thrown exception (its field errors), then {@code lookups},
     * {@code checks} and {@code saved}, read from the mocks and the account spy. A session handed to {@code save} is
     * shown as "returned session" when it is the object {@code login} returned, otherwise as "other session".
     */
    private String run(String identifier, String password) {
        AuthenticatedSession session = null;
        String result;
        try {
            session = authController.login(identifier, password);
            result = "session for " + session.getAccount().getUsername() + ", issuedAt=" + session.getIssuedAt()
                    + ", expiresAt=" + session.getExpiresAt();
        } catch (InvalidInputException e) {
            result = "InvalidInputException " + e.getFieldErrors();
        } catch (RuntimeException e) {
            result = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        AuthenticatedSession returned = session;
        List<String> saved = mockingDetails(sessionRepository).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("save"))
                .map(call -> call.getArgument(0) == returned ? "returned session" : "other session")
                .toList();
        return result
                + "; lookups=" + calls(accountRepository, call -> true)
                + "; checks=" + calls(alice, call -> ACCOUNT_CHECKS.contains(call.getMethod().getName()))
                + "; saved=" + saved;
    }

    /** The calls made on a mock or spy, in order, e.g. {@code [findByUsernameKey(alice_01)]}. */
    private static List<String> calls(Object mock, Predicate<Invocation> which) {
        return mockingDetails(mock).getInvocations().stream()
                .filter(which)
                .map(call -> call.getMethod().getName() + "(" + Arrays.stream(call.getArguments())
                        .map(String::valueOf).collect(Collectors.joining(", ")) + ")")
                .toList();
    }

    /** Records the case with the actual output, then asserts that the actual output is the expected one. */
    private static void record(String tcId, String input, String expected, String actual) {
        TestResultLog.record(FILE, tcId, input, expected, actual);
        assertThat(actual).as(tcId).isEqualTo(expected);
    }
}

package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.AccountStatus;
import sg.schoolmatch.entity.account.AuthenticatedSession;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotAuthenticatedException;
import sg.schoolmatch.persistence.AccountRepository;
import sg.schoolmatch.persistence.AuthenticatedSessionRepository;
import sg.schoolmatch.support.FixedClock;

/**
 * Unit test of the control class AuthController (use cases Log In, Log Out; FR-LOGIN-01..05, FR-LOGOUT-02, DC-13).
 * The repositories are Mockito mocks backed by maps; a {@link FixedClock} replaces the real time, so the
 * 30-minute idle timeout is tested without waiting. Login cases come from {@code testcases/TC-LOGIN.csv}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)   // not every test reaches every stubbed lookup
class AuthControllerTest {

    private static final Duration IDLE = Duration.ofMinutes(30);
    private static final String HASH = new BCryptPasswordEncoder(4).encode("Passw0rd");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AuthenticatedSessionRepository sessionRepository;

    private final FixedClock clock = FixedClock.atDefault();
    private final Map<String, AuthenticatedSession> sessions = new HashMap<>();
    private final Account alice = new Account("alice_01", "alice@example.com", HASH, FixedClock.DEFAULT_INSTANT);
    private final Account bob = new Account("bob_inactive", "bob@example.com", HASH, FixedClock.DEFAULT_INSTANT);
    private AuthController authController;

    @BeforeEach
    void setUp() {
        bob.setStatus(AccountStatus.INACTIVE);
        AppProperties props = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("app", AppProperties.class);   // defaults: idle timeout 30m
        authController = new AuthController(accountRepository, sessionRepository, clock, props);

        List<Account> accounts = List.of(alice, bob);
        when(accountRepository.findByUsernameKey(anyString())).thenAnswer(call -> accounts.stream()
                .filter(a -> a.getUsernameKey().equals(call.getArgument(0))).findFirst());
        when(accountRepository.findByEmailIgnoreCase(anyString())).thenAnswer(call -> accounts.stream()
                .filter(a -> a.getEmail().equals(call.<String>getArgument(0).toLowerCase(Locale.ROOT))).findFirst());
        when(sessionRepository.save(any(AuthenticatedSession.class))).thenAnswer(call -> {
            AuthenticatedSession s = call.getArgument(0);
            sessions.put(s.getSessionId(), s);
            return s;
        });
        when(sessionRepository.findById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(sessions.get(call.<String>getArgument(0))));
    }

    static List<AccountTestCases.Row> loginRows() {
        return AccountTestCases.rows("TC-LOGIN.csv", row -> true);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loginRows")
    @Tag("FR-LOGIN-01")
    @Tag("FR-LOGIN-02")
    @Tag("FR-LOGIN-03")
    @Tag("FR-LOGIN-05")
    @DisplayName("TC-LOGIN-xx-yy: login cases from TC-LOGIN.csv")
    void login_followsTheTestCaseTable(AccountTestCases.Row row) {
        Map<String, String> fields = row.fields();

        if (row.expectsError()) {
            InvalidInputException e = catchThrowableOfType(InvalidInputException.class,
                    () -> authController.login(fields.get("identifier"), fields.get("password")));
            assertThat(e).as("login throws InvalidInputException").isNotNull();
            assertThat(e.getFieldErrors().keySet()).containsExactlyElementsOf(row.errorFields());
            verify(sessionRepository, never()).save(any());   // FR-LOGIN-05: no session on failure
        } else {
            AuthenticatedSession session = authController.login(fields.get("identifier"), fields.get("password"));
            assertThat(session.getAccount()).isEqualTo(alice);
            verify(sessionRepository).save(session);
        }
    }

    @Test
    @Tag("FR-LOGIN-05")
    @DisplayName("TC-LOGIN-05-07: wrong password, unknown user and INACTIVE account all get the same message")
    void login_failures_sameGenericMessage() {
        Set<Map<String, String>> errors = new HashSet<>();
        errors.add(loginErrors("alice_01", "wrong-Passw0rd"));
        errors.add(loginErrors("nobody", "Passw0rd"));
        errors.add(loginErrors("nobody@example.com", "Passw0rd"));
        errors.add(loginErrors("bob_inactive", "Passw0rd"));

        assertThat(errors).containsExactly(Map.of(AuthController.LOGIN_ERROR, AuthController.LOGIN_FAILED_MESSAGE));
        assertThat(AuthController.LOGIN_FAILED_MESSAGE).isEqualTo("Incorrect username/email or password");
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthController-01: a new session id is 32 random bytes in base64url without padding, never reused")
    void login_sessionIdIsRandomBase64Url() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            ids.add(authController.login("alice_01", "Passw0rd").getSessionId());
        }

        assertThat(ids).hasSize(20).allSatisfy(id -> assertThat(id).matches("[A-Za-z0-9_-]{43}"));
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthController-02: a new session expires after the idle timeout (30 min) from now")
    void login_sessionExpiresAfterIdleTimeout() {
        AuthenticatedSession session = authController.login("alice_01", "Passw0rd");

        assertThat(session.getIssuedAt()).isEqualTo(clock.instant());
        assertThat(session.getExpiresAt()).isEqualTo(clock.instant().plus(IDLE));
        assertThat(session.isInvalidated()).isFalse();
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthController-03: verifySession is true before the expiry and false from the expiry on")
    void verifySession_expiry() {
        String id = authController.login("alice_01", "Passw0rd").getSessionId();

        clock.advance(IDLE.minusSeconds(1));
        assertThat(authController.verifySession(id)).isTrue();

        clock.advance(IDLE);   // 30 minutes after the last use (the call above slid the expiry)
        assertThat(authController.verifySession(id)).isFalse();
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthController-04: each use slides the expiry forward, so an active user stays logged in")
    void verifySession_slidesExpiry() {
        String id = authController.login("alice_01", "Passw0rd").getSessionId();

        for (int i = 0; i < 4; i++) {           // 4 × 20 min = 80 min in total, never 30 min idle
            clock.advance(Duration.ofMinutes(20));
            assertThat(authController.verifySession(id)).as("after %d × 20 min", i + 1).isTrue();
        }
        assertThat(sessions.get(id).getExpiresAt()).isEqualTo(clock.instant().plus(IDLE));
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthController-05: the sliding expiry is saved at most once a minute")
    void verifySession_savesAtMostOncePerMinute() {
        String id = authController.login("alice_01", "Passw0rd").getSessionId();
        clearInvocations(sessionRepository);

        clock.advance(Duration.ofSeconds(30));
        authController.verifySession(id);
        verify(sessionRepository, never()).save(any());

        clock.advance(Duration.ofSeconds(30));   // one minute after login
        authController.verifySession(id);
        authController.verifySession(id);
        verify(sessionRepository, times(1)).save(any());
    }

    @Test
    @Tag("FR-LOGOUT-02")
    @DisplayName("TC-AuthController-06: logout invalidates the session; it is then no longer valid")
    void logout_invalidates() {
        String id = authController.login("alice_01", "Passw0rd").getSessionId();

        authController.logout(id);

        assertThat(sessions.get(id).isInvalidated()).isTrue();
        assertThat(authController.verifySession(id)).isFalse();
        assertThatThrownBy(() -> authController.getAccount(id)).isInstanceOf(NotAuthenticatedException.class);
    }

    @Test
    @Tag("FR-LOGOUT-02")
    @DisplayName("TC-AuthController-07: logout is idempotent and ignores unknown, blank and null ids")
    void logout_idempotent() {
        String id = authController.login("alice_01", "Passw0rd").getSessionId();

        authController.logout(id);
        authController.logout(id);
        authController.logout("no-such-session");
        authController.logout("");
        authController.logout(null);

        assertThat(sessions.get(id).isInvalidated()).isTrue();
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-AuthController-08: getAccount returns the session's account (DC-13)")
    void getAccount_validSession() {
        String id = authController.login("alice@example.com", "Passw0rd").getSessionId();

        assertThat(authController.getAccount(id)).isEqualTo(alice);
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-AuthController-09: getAccount throws NotAuthenticatedException for an unknown, expired or null session")
    void getAccount_invalidSession() {
        String id = authController.login("alice_01", "Passw0rd").getSessionId();
        clock.advance(IDLE);

        assertThatThrownBy(() -> authController.getAccount(id)).isInstanceOf(NotAuthenticatedException.class);
        assertThatThrownBy(() -> authController.getAccount("no-such-session"))
                .isInstanceOf(NotAuthenticatedException.class);
        assertThatThrownBy(() -> authController.getAccount(null)).isInstanceOf(NotAuthenticatedException.class);
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthController-10: a session of an account that became INACTIVE is no longer valid")
    void verifySession_inactiveAccount() {
        String id = authController.login("alice_01", "Passw0rd").getSessionId();

        alice.setStatus(AccountStatus.INACTIVE);

        assertThat(authController.verifySession(id)).isFalse();
        assertThatThrownBy(() -> authController.getAccount(id)).isInstanceOf(NotAuthenticatedException.class);
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthController-11: verifySession is false for a null or blank id without a database lookup")
    void verifySession_blankId() {
        assertThat(authController.verifySession(null)).isFalse();
        assertThat(authController.verifySession(" ")).isFalse();
        verify(sessionRepository, never()).findById(anyString());
    }

    @Test
    @Tag("FR-LOGOUT-02")
    @Tag("NFR-SEC-05")
    @DisplayName("TC-AuthController-12: removeEndedSessions deletes ended sessions as of the clock's time and runs on a schedule (DC-73)")
    void removeEndedSessions_usesClockAndIsScheduled() throws Exception {
        when(sessionRepository.deleteEndedSessions(any())).thenReturn(3);

        assertThat(authController.removeEndedSessions()).isEqualTo(3);
        verify(sessionRepository).deleteEndedSessions(clock.instant());

        org.springframework.scheduling.annotation.Scheduled scheduled = AuthController.class
                .getMethod("removeEndedSessions")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(scheduled).as("@Scheduled on removeEndedSessions").isNotNull();
        assertThat(scheduled.fixedDelayString()).contains("app.session.cleanup-interval");
    }

    private Map<String, String> loginErrors(String identifier, String password) {
        return catchThrowableOfType(InvalidInputException.class, () -> authController.login(identifier, password))
                .getFieldErrors();
    }
}

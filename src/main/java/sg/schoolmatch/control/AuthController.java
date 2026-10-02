package sg.schoolmatch.control;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.AuthenticatedSession;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotAuthenticatedException;
import sg.schoolmatch.persistence.AccountRepository;
import sg.schoolmatch.persistence.AuthenticatedSessionRepository;

/**
 * Design class «control» AuthController — login, session checks and logout (use cases Log In, Log Out;
 * FR-LOGIN-01..05, FR-LOGOUT-01..04). Called by LoginUI, UserProfileUI and AuthInterceptor.
 * Session idle timeout: {@code app.session.idle-timeout}; every valid use slides the expiry forward.
 */
@Service
public class AuthController {

    /** Field key of the one generic login error; it is not a form field, so no input is highlighted. */
    public static final String LOGIN_ERROR = "login";   // DC-46
    /** FR-LOGIN-05: the same text for an unknown user, a wrong password and an inactive account. */
    public static final String LOGIN_FAILED_MESSAGE = "Incorrect username/email or password";
    public static final String IDENTIFIER_MISSING_MESSAGE = "Enter your username or email";
    public static final String PASSWORD_MISSING_MESSAGE = "Enter your password";

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** Save the sliding expiry at most this often, so browsing does not write to the database on every page. */
    private static final Duration SLIDE_STEP = Duration.ofMinutes(1);
    private static final int SESSION_ID_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Checked when the user is unknown, so that case takes as long as a wrong password (FR-LOGIN-05). */
    private static final BCryptPasswordEncoder DUMMY_ENCODER = new BCryptPasswordEncoder();
    private static final String DUMMY_HASH = DUMMY_ENCODER.encode("not-a-real-password");

    private final AccountRepository accountRepository;
    private final AuthenticatedSessionRepository sessionRepository;
    private final Clock clock;
    private final AppProperties props;

    public AuthController(AccountRepository accountRepository, AuthenticatedSessionRepository sessionRepository,
                          Clock clock, AppProperties props) {
        this.accountRepository = accountRepository;
        this.sessionRepository = sessionRepository;
        this.clock = clock;
        this.props = props;
    }

    /**
     * Logs in with username or email + password and creates a session (FR-LOGIN-01..04).
     * An identifier with "@" is looked up as an email, otherwise as a username; both ignore case.
     *
     * @throws InvalidInputException missing fields → {@code identifier} / {@code password} errors (AF-1);
     *                               wrong credentials or an INACTIVE account → {@link #LOGIN_ERROR} with
     *                               {@link #LOGIN_FAILED_MESSAGE} (FR-LOGIN-05)
     */
    @Transactional
    public AuthenticatedSession login(String identifier, String password) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (identifier == null || identifier.isBlank()) {
            errors.put("identifier", IDENTIFIER_MISSING_MESSAGE);
        }
        if (password == null || password.isEmpty()) {
            errors.put("password", PASSWORD_MISSING_MESSAGE);
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        String id = identifier.trim();
        Optional<Account> found = id.contains("@")
                ? accountRepository.findByEmailIgnoreCase(id)
                : accountRepository.findByUsernameKey(Account.usernameKey(id));   // DC-70
        if (found.isEmpty()) {
            DUMMY_ENCODER.matches(password, DUMMY_HASH);   // takes as long as checking a real password
            throw loginFailed();
        }
        Account account = found.get();
        if (!account.verifyPassword(password) || !account.isActive()) {
            throw loginFailed();
        }
        Instant now = clock.instant();
        AuthenticatedSession session = new AuthenticatedSession(newSessionId(), account, now, now.plus(idleTimeout()));
        return sessionRepository.save(session);
    }

    /**
     * True when the session exists, is not logged out or expired, and its account is still ACTIVE.
     * Then the expiry slides to now + idle timeout (saved at most once a minute).
     */
    @Transactional
    public boolean verifySession(String sessionId) {
        Optional<AuthenticatedSession> session = validSession(sessionId);
        if (session.isEmpty()) {
            return false;
        }
        Instant newExpiry = clock.instant().plus(idleTimeout());
        if (!newExpiry.isBefore(session.get().getExpiresAt().plus(SLIDE_STEP))) {
            session.get().extendUntil(newExpiry);
            sessionRepository.save(session.get());
        }
        return true;
    }

    /** Invalidates the session (FR-LOGOUT-02). Unknown, blank and already logged-out ids are ignored. */
    @Transactional
    public void logout(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionRepository.findById(sessionId).filter(s -> !s.isInvalidated()).ifPresent(session -> {
            session.invalidate();
            sessionRepository.save(session);
        });
    }

    /**
     * DC-13: the account of a valid session.
     *
     * @throws NotAuthenticatedException when the session is missing, expired or logged out
     */
    @Transactional(readOnly = true)
    public Account getAccount(String sessionId) {
        return validSession(sessionId).map(AuthenticatedSession::getAccount)
                .orElseThrow(NotAuthenticatedException::new);
    }

    private Optional<AuthenticatedSession> validSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return sessionRepository.findById(sessionId)
                .filter(s -> s.isValid(now) && s.getAccount().isActive());
    }

    /**
     * DC-73: deletes the session rows that are logged out or expired, so {@code authenticated_session} does not
     * grow forever. Runs every {@code app.session.cleanup-interval} (default 1 hour; the first run one interval
     * after start-up).
     *
     * @return the number of rows deleted
     */
    @Scheduled(fixedDelayString = "${app.session.cleanup-interval:1h}",
            initialDelayString = "${app.session.cleanup-interval:1h}")
    @Transactional
    public int removeEndedSessions() {
        int removed = sessionRepository.deleteEndedSessions(clock.instant());
        if (removed > 0) {
            log.info("Removed {} ended login session(s)", removed);
        }
        return removed;
    }

    private Duration idleTimeout() {
        return props.session().idleTimeout();
    }

    /** 32 random bytes as base64url without padding (43 characters): cannot be guessed. */
    private static String newSessionId() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static InvalidInputException loginFailed() {
        return new InvalidInputException(LOGIN_ERROR, LOGIN_FAILED_MESSAGE);
    }
}

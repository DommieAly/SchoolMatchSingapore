package sg.schoolmatch.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.AuthenticatedSession;

/**
 * Database rules of the account tables: usernames and emails are unique ignoring case in the database itself
 * (DC-70), and ended login sessions can be deleted in one statement (DC-73). In-memory H2, rolled back after
 * each test.
 */
@DataJpaTest
@ActiveProfiles("test")
class AccountRepositoryTest {

    private static final String HASH = "$2a$10$notARealHashJustForTheTest";
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuthenticatedSessionRepository sessionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Tag("FR-REG-04")
    @Tag("FR-DATA-02")
    @DisplayName("TC-AccountRepository-01: a second username that differs only in letter case is refused by the database")
    void usernameUniqueIgnoringCase() {
        accountRepository.saveAndFlush(new Account("Racer1", "racer1@example.com", HASH, NOW));

        // The duplicate check in AccountController is skipped here, as when two registrations arrive together.
        assertThatThrownBy(() -> accountRepository.saveAndFlush(
                new Account("racer1", "other@example.com", HASH, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Tag("FR-REG-04")
    @Tag("FR-DATA-02")
    @DisplayName("TC-AccountRepository-02: a second email that differs only in letter case is refused by the database")
    void emailUniqueIgnoringCase() {
        accountRepository.saveAndFlush(new Account("first", "Same@Example.com", HASH, NOW));

        assertThatThrownBy(() -> accountRepository.saveAndFlush(new Account("second", "same@EXAMPLE.com", HASH, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Tag("FR-LOGIN-01")
    @DisplayName("TC-AccountRepository-03: the username key keeps the typed spelling and finds the account in any case")
    void usernameKeyLookup() {
        accountRepository.saveAndFlush(new Account("MixedCase_1", "mixed@example.com", HASH, NOW));
        entityManager.clear();

        Account found = accountRepository.findByUsernameKey(Account.usernameKey("MIXEDcase_1")).orElseThrow();
        assertThat(found.getUsername()).isEqualTo("MixedCase_1");
        assertThat(found.getUsernameKey()).isEqualTo("mixedcase_1");
        assertThat(accountRepository.existsByUsernameKey("mixedcase_1")).isTrue();
        assertThat(accountRepository.findByUsernameIgnoreCase("mixedcase_1")).contains(found);
    }

    @Test
    @Tag("FR-LOGOUT-02")
    @Tag("NFR-SEC-05")
    @DisplayName("TC-AccountRepository-04: deleteEndedSessions removes logged-out and expired sessions and keeps valid ones")
    void deleteEndedSessions() {
        Account account = accountRepository.saveAndFlush(new Account("sessions", "s@example.com", HASH, NOW));
        AuthenticatedSession valid = new AuthenticatedSession("valid", account, NOW, NOW.plus(Duration.ofMinutes(10)));
        AuthenticatedSession expired = new AuthenticatedSession("expired", account, NOW.minus(Duration.ofHours(2)),
                NOW.minus(Duration.ofMinutes(1)));
        AuthenticatedSession loggedOut = new AuthenticatedSession("logged-out", account, NOW,
                NOW.plus(Duration.ofMinutes(10)));
        loggedOut.invalidate();
        sessionRepository.saveAllAndFlush(java.util.List.of(valid, expired, loggedOut));
        entityManager.clear();

        int removed = sessionRepository.deleteEndedSessions(NOW);

        assertThat(removed).isEqualTo(2);
        assertThat(sessionRepository.findAll()).extracting(AuthenticatedSession::getSessionId).containsExactly("valid");
    }
}

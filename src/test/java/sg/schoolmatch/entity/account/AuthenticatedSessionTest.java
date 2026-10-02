package sg.schoolmatch.entity.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.support.FixedClock;

/** Unit tests for «entity» AuthenticatedSession, with a {@link FixedClock} instead of the real time (DC-25). */
class AuthenticatedSessionTest {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final FixedClock clock = FixedClock.atDefault();
    private final Account account = new Account("alice", "alice@example.com", "bcrypt-hash-not-used-here", clock.instant());
    private final AuthenticatedSession session =
            new AuthenticatedSession("session-1", account, clock.instant(), clock.instant().plus(IDLE_TIMEOUT));

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthenticatedSession-01: a new session is valid until just before it expires")
    void validBeforeExpiry() {
        assertThat(session.isValid(clock.instant())).isTrue();

        clock.advance(IDLE_TIMEOUT.minusNanos(1));

        assertThat(session.isValid(clock.instant())).isTrue();
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthenticatedSession-02: a session is not valid at or after its expiry time")
    void invalidFromExpiry() {
        clock.advance(IDLE_TIMEOUT);
        assertThat(session.isValid(clock.instant())).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(session.isValid(clock.instant())).isFalse();
    }

    @Test
    @Tag("FR-LOGOUT-02")
    @DisplayName("TC-AuthenticatedSession-03: invalidate() ends the session before it expires")
    void invalidateEndsSession() {
        session.invalidate();

        assertThat(session.isInvalidated()).isTrue();
        assertThat(session.isValid(clock.instant())).isFalse();
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AuthenticatedSession-04: extendUntil moves the expiry forward (sliding idle timeout)")
    void extendUntilSlidesExpiry() {
        clock.advance(Duration.ofMinutes(20));
        Instant newExpiry = clock.instant().plus(IDLE_TIMEOUT);
        session.extendUntil(newExpiry);

        clock.advance(Duration.ofMinutes(20));   // 40 min after login, 20 min after the last request
        assertThat(session.getExpiresAt()).isEqualTo(newExpiry);
        assertThat(session.isValid(clock.instant())).isTrue();

        clock.advance(Duration.ofMinutes(10));   // 30 min idle
        assertThat(session.isValid(clock.instant())).isFalse();
    }

    @Test
    @Tag("FR-LOGOUT-02")
    @DisplayName("TC-AuthenticatedSession-05: an invalidated session stays invalid when its expiry is extended")
    void extendDoesNotRevive() {
        session.invalidate();
        session.extendUntil(clock.instant().plus(Duration.ofHours(1)));

        assertThat(session.isValid(clock.instant())).isFalse();
    }
}

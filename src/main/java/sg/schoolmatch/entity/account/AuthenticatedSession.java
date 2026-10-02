package sg.schoolmatch.entity.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * Design class «entity» AuthenticatedSession — a logged-in session, referenced by the session cookie
 * (FR-LOGIN-04, FR-LOGOUT-02). DC-25: {@link #isValid(Instant)} takes the current time so it is testable.
 */
@Entity
@Table(name = "authenticated_session")
public class AuthenticatedSession {

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean invalidated;

    /** For JPA only. */
    protected AuthenticatedSession() {
    }

    /** {@code sessionId} is a random, unguessable token created by AuthController. */
    public AuthenticatedSession(String sessionId, Account account, Instant issuedAt, Instant expiresAt) {
        this.sessionId = sessionId;
        this.account = account;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.invalidated = false;
    }

    /** Valid = not logged out and not yet expired at {@code now}. */
    public boolean isValid(Instant now) {
        return !invalidated && now.isBefore(expiresAt);
    }

    /** Logs the session out (FR-LOGOUT-02). */
    public void invalidate() {
        this.invalidated = true;
    }

    /** Sliding idle timeout: pushes the expiry to {@code newExpiry}. */
    public void extendUntil(Instant newExpiry) {
        this.expiresAt = newExpiry;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Account getAccount() {
        return account;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isInvalidated() {
        return invalidated;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof AuthenticatedSession other && Objects.equals(sessionId, other.sessionId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(sessionId);
    }

    /** Does not print the session id (it is a credential). */
    @Override
    public String toString() {
        return "AuthenticatedSession{account=" + (account == null ? null : account.getAccountId())
                + ", expiresAt=" + expiresAt + ", invalidated=" + invalidated + "}";
    }
}

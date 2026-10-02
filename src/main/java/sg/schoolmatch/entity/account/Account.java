package sg.schoolmatch.entity.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Design class «entity» Account — a registered user's login identity (FR-REG-05, FR-LOGIN-03, FR-DATA-02).
 * Only the BCrypt hash of the password is stored (NFR-SEC-01, NFR-SEC-02).
 * DC-26: {@link #verifyPassword} uses BCrypt inside the entity (library only, no app dependency).
 */
@Entity
@Table(name = "account")
public class Account {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Id
    @Column(name = "account_id", length = 36)
    private String accountId;

    @NotBlank
    @Size(min = 3, max = 30)
    @Column(nullable = false, unique = true, length = 30)
    private String username;

    /**
     * DC-70: {@code username} lower-cased ({@link Locale#ROOT}), with its own unique key, so the database itself
     * refuses "Alice" when "alice" exists (FR-REG-04), even when two registrations arrive at the same moment.
     * Logins look the username up by this key, so a lookup can never find two rows.
     */
    @Column(name = "username_key", nullable = false, unique = true, length = 30)
    private String usernameKey;

    /**
     * Stored lower-case. No {@code @Email} here: the format rule is the data dictionary's (one @, no spaces,
     * non-empty local part and domain), checked by AccountController. Hibernate's {@code @Email} is stricter
     * (it rejects e.g. "a..b@c"), so an address the form accepts would fail when saved.
     */
    @NotBlank
    @Size(max = 254)
    @Column(nullable = false, unique = true, length = 254)
    private String email;   // DC-45

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    /** For JPA only. */
    protected Account() {
    }

    /** Creates an ACTIVE account with a new UUID id. {@code passwordHash} must already be BCrypt-hashed. */
    public Account(String username, String email, String passwordHash, Instant createdAt) {
        this.accountId = UUID.randomUUID().toString();
        this.username = username;
        this.usernameKey = usernameKey(username);
        this.email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
        this.status = AccountStatus.ACTIVE;
    }

    /** DC-70: the case-insensitive key of a username (trimmed, lower-case); null stays null. */
    public static String usernameKey(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    /** True when {@code rawPassword} matches the stored BCrypt hash (FR-LOGIN-03). */
    public boolean verifyPassword(String rawPassword) {
        return rawPassword != null && passwordHash != null && ENCODER.matches(rawPassword, passwordHash);
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getUsername() {
        return username;
    }

    public String getUsernameKey() {
        return usernameKey;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Account other && Objects.equals(accountId, other.accountId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(accountId);
    }

    /** Never includes the password hash. */
    @Override
    public String toString() {
        return "Account{accountId=" + accountId + ", username=" + username + ", status=" + status + "}";
    }
}

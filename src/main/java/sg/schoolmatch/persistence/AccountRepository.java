package sg.schoolmatch.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import sg.schoolmatch.entity.account.Account;

/**
 * Stores {@link Account} rows (FR-DATA-02). Username and email lookups ignore case: the username through its
 * lower-case {@code username_key} column (DC-70), the email because it is stored lower-case. Both columns have
 * a unique key, so the database itself refuses a duplicate that differs only in letter case.
 */
public interface AccountRepository extends JpaRepository<Account, String> {

    /** @param usernameKey {@link Account#usernameKey(String)} of the typed username */
    Optional<Account> findByUsernameKey(String usernameKey);

    boolean existsByUsernameKey(String usernameKey);

    /** Ignores case (the email is stored lower-case). */
    Optional<Account> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** Ignores case; kept for tests and tools. Logins use {@link #findByUsernameKey}. */
    default Optional<Account> findByUsernameIgnoreCase(String username) {
        return findByUsernameKey(Account.usernameKey(username));
    }
}

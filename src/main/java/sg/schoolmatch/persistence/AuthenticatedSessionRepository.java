package sg.schoolmatch.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sg.schoolmatch.entity.account.AuthenticatedSession;

/** Stores {@link AuthenticatedSession} rows, keyed by session id (FR-LOGIN-04, FR-LOGOUT-02). */
public interface AuthenticatedSessionRepository extends JpaRepository<AuthenticatedSession, String> {

    /**
     * DC-73: deletes every session that is logged out or has expired at {@code now} (they can never be used
     * again). Run inside a transaction.
     *
     * @return the number of rows deleted
     */
    @Modifying
    @Query("delete from AuthenticatedSession s where s.invalidated = true or s.expiresAt <= :now")
    int deleteEndedSessions(@Param("now") Instant now);
}

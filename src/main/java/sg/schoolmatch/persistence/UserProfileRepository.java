package sg.schoolmatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import sg.schoolmatch.entity.account.UserProfile;

/** Stores {@link UserProfile} rows, keyed by account id (FR-PROFILE-01). */
public interface UserProfileRepository extends JpaRepository<UserProfile, String> {
}

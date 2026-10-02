package sg.schoolmatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import sg.schoolmatch.entity.shortlist.Shortlist;

/** Stores {@link Shortlist} rows (with their ChoicePlan), keyed by account id (FR-SHORTLIST-03, FR-DATA-05). */
public interface ShortlistRepository extends JpaRepository<Shortlist, String> {
}

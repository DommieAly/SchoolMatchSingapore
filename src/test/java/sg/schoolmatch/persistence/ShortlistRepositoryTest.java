package sg.schoolmatch.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.shortlist.ChoicePlan;
import sg.schoolmatch.entity.shortlist.SchoolChoice;
import sg.schoolmatch.entity.shortlist.Shortlist;
import sg.schoolmatch.support.TestSchools;

/**
 * Save-and-reload test for Shortlist and its ChoicePlan (example of a database test).
 * <p>
 * {@code @DataJpaTest} starts only JPA and the repositories on an in-memory H2, and rolls back after each test.
 * {@code flush()} writes the SQL and {@code clear()} forgets the loaded objects, so {@code findById} really
 * reads from the database. It checks the mapping notes in docs/design-changes.md: create a Shortlist with
 * {@code save(new Shortlist(account))} (accountId stays null until then), school codes come back sorted by code
 * (DC-35), and choices come back ordered by rank (column {@code choice_rank}).
 */
@DataJpaTest
@ActiveProfiles("test")
class ShortlistRepositoryTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ShortlistRepository shortlistRepository;

    @Autowired
    private EntityManager entityManager;

    private final School tampines = TestSchools.named("tampines-secondary-school", "TAMPINES SECONDARY SCHOOL");
    private final School catholic = TestSchools.named("catholic-high-school", "CATHOLIC HIGH SCHOOL");

    @Test
    @Tag("FR-DATA-05")
    @Tag("FR-SHORTLIST-03")
    @DisplayName("TC-ShortlistRepository-01: a saved shortlist and plan reload with codes sorted and choices by rank")
    void saveAndReload_keepsSchoolsAndChoiceOrder() {
        Account account = accountRepository.save(
                new Account("tester", "Tester@Example.com", "$2a$10$notARealHashJustForTheTest", Instant.EPOCH));

        Shortlist shortlist = new Shortlist(account);
        shortlist.addSchool(tampines);    // added first, but "catholic…" sorts first after reload (DC-35)
        shortlist.addSchool(catholic);
        ChoicePlan plan = new ChoicePlan(12, 3);
        plan.addChoice(tampines, 1);
        plan.addChoice(catholic, 1);      // inserted at rank 1: tampines moves to rank 2
        shortlist.setChoicePlan(plan);
        shortlistRepository.save(shortlist);
        entityManager.flush();
        entityManager.clear();

        Shortlist reloaded = shortlistRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(reloaded.getAccountId()).isEqualTo(account.getAccountId());
        assertThat(reloaded.getAccount().getEmail()).isEqualTo("tester@example.com");
        assertThat(reloaded.getSchoolCodes()).containsExactly("catholic-high-school", "tampines-secondary-school");
        assertThat(reloaded.getChoicePlan().getChoices())
                .extracting(SchoolChoice::getSchoolCode, SchoolChoice::getRank)
                .containsExactly(
                        tuple("catholic-high-school", 1),
                        tuple("tampines-secondary-school", 2));
        // school and affiliated are @Transient: the controls fill them after loading (DC-21)
        assertThat(reloaded.getChoicePlan().getChoices()).allSatisfy(c -> assertThat(c.getSchool()).isNull());
    }
}

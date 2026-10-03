package sg.schoolmatch.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
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
 * <p>
 * Since V3 every saved or chosen school code must name a {@code school} row (docs/database-design.md, step 7), so
 * {@code @Sql} inserts the schools first, inside the test's transaction. Runs on H2 here and on embedded
 * PostgreSQL in {@link PostgresShortlistRepositoryTest}.
 */
@DataJpaTest
@ActiveProfiles("test")
@Sql("/sql/schools.sql")
class ShortlistRepositoryTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ShortlistRepository shortlistRepository;

    @Autowired
    private EntityManager entityManager;

    private final School tampines = TestSchools.named("tampines-secondary-school", "TAMPINES SECONDARY SCHOOL");
    private final School catholic = TestSchools.named("catholic-high-school", "CATHOLIC HIGH SCHOOL");
    private final School raffles = TestSchools.named("raffles-institution", "RAFFLES INSTITUTION");
    private final School closed = TestSchools.named("closed-secondary-school", "CLOSED SECONDARY SCHOOL");

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

    @Test
    @Tag("FR-DATA-05")
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-ShortlistRepository-02: a saved school or plan choice whose code names no school row is refused (V3)")
    void unknownSchoolCodeRefused() {
        Account account = accountRepository.save(
                new Account("tester", "tester@example.com", "$2a$10$notARealHashJustForTheTest", Instant.EPOCH));
        Shortlist shortlist = new Shortlist(account);
        shortlist.addSchool(TestSchools.named("no-such-school", "NO SUCH SCHOOL"));

        assertThatThrownBy(() -> shortlistRepository.saveAndFlush(shortlist))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_shortlist_school_school");
    }

    @Test
    @Tag("FR-DATA-05")
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ShortlistRepository-03: a plan choice whose code names no school row is refused (V3)")
    void unknownPlanChoiceRefused() {
        Account account = accountRepository.save(
                new Account("tester", "tester@example.com", "$2a$10$notARealHashJustForTheTest", Instant.EPOCH));
        Shortlist shortlist = new Shortlist(account);
        shortlist.addSchool(catholic);
        ChoicePlan plan = new ChoicePlan(12, 3);
        plan.addChoice(TestSchools.named("no-such-school", "NO SUCH SCHOOL"), 1);
        shortlist.setChoicePlan(plan);

        assertThatThrownBy(() -> shortlistRepository.saveAndFlush(shortlist))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_choice_plan_choice_school");
    }

    @Test
    @Tag("FR-DATA-05")
    @Tag("FR-SHORTLIST-06")
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ShortlistRepository-04: reordering and removing choices and schools still save with the foreign keys in place")
    void reorderAndRemoveStillWork() {
        Account account = accountRepository.save(
                new Account("tester", "tester@example.com", "$2a$10$notARealHashJustForTheTest", Instant.EPOCH));
        Shortlist shortlist = new Shortlist(account);
        shortlist.addSchool(catholic);
        shortlist.addSchool(tampines);
        shortlist.addSchool(raffles);
        ChoicePlan plan = new ChoicePlan(12, 3);
        plan.addChoice(catholic, 1);
        plan.addChoice(tampines, 2);
        plan.addChoice(raffles, 3);
        shortlist.setChoicePlan(plan);
        shortlistRepository.saveAndFlush(shortlist);
        entityManager.clear();

        Shortlist loaded = shortlistRepository.findById(account.getAccountId()).orElseThrow();
        loaded.getChoicePlan().reorder(3, 1);              // raffles to the top
        loaded.getChoicePlan().removeChoice("tampines-secondary-school");
        loaded.removeSchool("tampines-secondary-school");
        shortlistRepository.saveAndFlush(loaded);
        entityManager.clear();

        Shortlist reloaded = shortlistRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(reloaded.getSchoolCodes()).containsExactly("catholic-high-school", "raffles-institution");
        assertThat(reloaded.getChoicePlan().getChoices())
                .extracting(SchoolChoice::getSchoolCode, SchoolChoice::getRank)
                .containsExactly(tuple("raffles-institution", 1), tuple("catholic-high-school", 2));
    }

    @Test
    @Tag("FR-DATA-05")
    @Tag("FR-SHORTLIST-05")
    @DisplayName("TC-ShortlistRepository-05: a school that left the dataset keeps its row, so a shortlist and plan that saved it still save and reload")
    void withdrawnSchoolStillSaves() {
        Account account = accountRepository.save(
                new Account("tester", "tester@example.com", "$2a$10$notARealHashJustForTheTest", Instant.EPOCH));
        Shortlist shortlist = new Shortlist(account);
        shortlist.addSchool(closed);
        ChoicePlan plan = new ChoicePlan(12, 3);
        plan.addChoice(closed, 1);
        shortlist.setChoicePlan(plan);
        shortlistRepository.saveAndFlush(shortlist);
        entityManager.clear();

        Shortlist reloaded = shortlistRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(reloaded.getSchoolCodes()).containsExactly("closed-secondary-school");
        assertThat(reloaded.getChoicePlan().getChoices()).extracting(SchoolChoice::getSchoolCode)
                .containsExactly("closed-secondary-school");
    }
}

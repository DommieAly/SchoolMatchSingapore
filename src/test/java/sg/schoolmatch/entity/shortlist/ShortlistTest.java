package sg.schoolmatch.entity.shortlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.support.FixedClock;
import sg.schoolmatch.support.TestSchools;

/** Unit tests for «entity» Shortlist (in memory; saving and reloading is a repository/flow test). */
class ShortlistTest {

    private final Account account = new Account("alice", "alice@example.com", "bcrypt-hash-not-used-here",
            FixedClock.DEFAULT_INSTANT);
    private final Shortlist shortlist = new Shortlist(account);

    private final School catholic = TestSchools.school("catholic-high-school").build();
    private final School raffles = TestSchools.school("raffles-institution").build();

    @Test
    @Tag("FR-SHORTLIST-03")
    @Tag("FR-SHORTLIST-07")
    @DisplayName("TC-Shortlist-01: a new shortlist is empty and belongs to its account")
    void newShortlistIsEmpty() {
        assertThat(shortlist.isEmpty()).isTrue();
        assertThat(shortlist.getSchools()).isEmpty();
        assertThat(shortlist.getAccountId()).isEqualTo(account.getAccountId());
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-Shortlist-02: addSchool adds the school and keeps the order of adding")
    void addSchool() {
        assertThat(shortlist.addSchool(raffles)).isTrue();
        assertThat(shortlist.addSchool(catholic)).isTrue();

        assertThat(shortlist.contains("catholic-high-school")).isTrue();
        assertThat(shortlist.getSchoolCodes()).containsExactly("raffles-institution", "catholic-high-school");
        assertThat(shortlist.getSchools()).containsExactly(raffles, catholic);
    }

    @Test
    @Tag("FR-SHORTLIST-02")
    @DisplayName("TC-Shortlist-03: adding a school that is already shortlisted returns false and keeps one entry")
    void noDuplicates() {
        shortlist.addSchool(catholic);

        assertThat(shortlist.addSchool(catholic)).isFalse();
        assertThat(shortlist.addSchool(TestSchools.named("catholic-high-school", "Same code, other object"))).isFalse();
        assertThat(shortlist.getSchoolCodes()).containsExactly("catholic-high-school");
    }

    @Test
    @Tag("FR-SHORTLIST-06")
    @DisplayName("TC-Shortlist-04: removeSchool also removes the school from the choice plan")
    void removeAlsoRemovesFromPlan() {
        shortlist.addSchool(catholic);
        shortlist.addSchool(raffles);
        ChoicePlan plan = new ChoicePlan(10, 3);
        plan.addChoice(catholic, 1);
        plan.addChoice(raffles, 2);
        shortlist.setChoicePlan(plan);

        shortlist.removeSchool("catholic-high-school");

        assertThat(shortlist.getSchoolCodes()).containsExactly("raffles-institution");
        assertThat(plan.contains("catholic-high-school")).isFalse();
        assertThat(plan.getChoices()).extracting(SchoolChoice::getSchoolCode, SchoolChoice::getRank)
                .containsExactly(tuple("raffles-institution", 1));
    }

    @Test
    @Tag("FR-SHORTLIST-06")
    @Tag("FR-SHORTLIST-07")
    @DisplayName("TC-Shortlist-05: removing the last school leaves an empty shortlist (no plan needed)")
    void removeLastSchool() {
        shortlist.addSchool(catholic);

        shortlist.removeSchool("catholic-high-school");

        assertThat(shortlist.isEmpty()).isTrue();
        assertThat(shortlist.getChoicePlan()).isNull();
    }

    @Test
    @Tag("FR-SHORTLIST-05")
    @DisplayName("TC-Shortlist-06: after resolveSchools, codes no longer in the dataset are skipped but kept")
    void resolveSkipsUnknownCodes() {
        shortlist.addSchool(catholic);
        shortlist.addSchool(raffles);

        shortlist.resolveSchools(TestSchools.byCode(raffles));   // catholic-high-school left the dataset

        assertThat(shortlist.getSchools()).containsExactly(raffles);
        assertThat(shortlist.getUnresolvedSchoolCodes()).containsExactly("catholic-high-school");
        assertThat(shortlist.getSchoolCodes()).containsExactly("catholic-high-school", "raffles-institution");
    }
}

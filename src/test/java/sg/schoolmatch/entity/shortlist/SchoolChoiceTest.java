package sg.schoolmatch.entity.shortlist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.support.TestSchools;

/**
 * Unit tests for «entity» SchoolChoice.assess, the DC-20 rule (docs/recommendation-scoring.md §2):
 * U = upperScore of the applicable range, m = {@link SchoolChoice#SAFE_MARGIN} (2);
 * SAFE if score ≤ U − m, MATCH if U − m &lt; score ≤ U, REACH if score &gt; U, null without a range.
 * The boundary rows are in {@code testcases/TC-PLAN.csv}.
 */
class SchoolChoiceTest {

    /** PG3 non-affiliated: 2024 8–11 (older) and 2025 8–12 (latest, so U = 12); PG2 2025 10–14. */
    private final School school = TestSchools.school("catholic-high-school")
            .range(2024, 3, 8, 11)
            .range(2025, 3, 8, 12)
            .range(2025, 2, 10, 14)
            .build();

    @ParameterizedTest(name = "{0}: {2}")
    @CsvFileSource(resources = "/testcases/TC-PLAN.csv", numLinesToSkip = 1)
    @Tag("FR-PLAN-01")
    @DisplayName("TC-PLAN-01-xx: SAFE / MATCH / REACH boundaries from TC-PLAN.csv (U = 12, m = 2)")
    void assess_followsTheTestCaseTable(String tcId, String requirement, String technique,
                                        int score, AdmissionChance expected) {
        assertThat(choiceFor(school).assess(score, 3)).isEqualTo(expected);
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-SchoolChoice-01: the latest admission year of the student's posting group is used")
    void assess_usesLatestRangeOfPostingGroup() {
        SchoolChoice choice = choiceFor(school);

        assertThat(choice.assess(12, 3)).isEqualTo(AdmissionChance.MATCH);    // 2025 PG3 U = 12, not 2024's 11
        assertThat(choice.assess(12, 2)).isEqualTo(AdmissionChance.SAFE);     // PG2 U = 14
        assertThat(choice.getApplicableRange(3)).hasValueSatisfying(r -> assertThat(r.getUpperScore()).isEqualTo(12));
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-SchoolChoice-02: no range for the posting group gives null (\"Not available\")")
    void assess_noRange_isNull() {
        SchoolChoice choice = choiceFor(school);
        SchoolChoice noRanges = choiceFor(TestSchools.school("westwood-secondary-school").build());

        assertThat(choice.assess(12, 1)).isNull();       // the school has no PG1 range
        assertThat(noRanges.assess(12, 3)).isNull();
        assertThat(noRanges.getApplicableRange(3)).isEmpty();
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-SchoolChoice-03: a choice whose school left the dataset gives null")
    void assess_unresolvedSchool_isNull() {
        SchoolChoice choice = new SchoolChoice("gone-secondary-school", 1);

        assertThat(choice.assess(12, 3)).isNull();
        assertThat(choice.getApplicableRange(3)).isEmpty();
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-SchoolChoice-04: an affiliated student is assessed with the affiliated range (DC-21/22)")
    void assess_affiliated_usesAffiliatedRange() {
        School withAffiliation = TestSchools.school("kuo-chuan-presbyterian-secondary-school")
                .range(2025, 3, 14, 17)
                .affiliatedRange(2025, 3, 14, 19)
                .build();
        SchoolChoice notAffiliated = choiceFor(withAffiliation);
        SchoolChoice affiliated = choiceFor(withAffiliation);
        affiliated.setAffiliated(true);

        assertThat(notAffiliated.assess(18, 3)).isEqualTo(AdmissionChance.REACH);   // U = 17
        assertThat(affiliated.assess(18, 3)).isEqualTo(AdmissionChance.MATCH);      // U = 19
        assertThat(affiliated.assess(17, 3)).isEqualTo(AdmissionChance.SAFE);
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-SchoolChoice-05: an affiliated student falls back to the non-affiliated range when there is no affiliated one")
    void assess_affiliatedWithoutAffiliatedRange_fallsBack() {
        SchoolChoice choice = choiceFor(school);
        choice.setAffiliated(true);

        assertThat(choice.assess(13, 3)).isEqualTo(AdmissionChance.REACH);
    }

    private static SchoolChoice choiceFor(School school) {
        SchoolChoice choice = new SchoolChoice(school.getSchoolCode(), 1);
        choice.setSchool(school);
        return choice;
    }
}

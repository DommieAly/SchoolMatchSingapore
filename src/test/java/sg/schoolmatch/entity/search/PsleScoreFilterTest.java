package sg.schoolmatch.entity.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.support.TestSchools;

/**
 * Unit tests for «entity» PsleScoreFilter (DC-22, DC-40; docs/recommendation-scoring.md §3):
 * a school is kept when score ≤ U, the upper score of its applicable range.
 */
class PsleScoreFilterTest {

    /** Latest PG3 non-affiliated range 8–12 (2025); an older 2024 range must be ignored. */
    private final School school = TestSchools.school("bishan-park-secondary-school")
            .range(2024, 3, 9, 14)
            .range(2025, 3, 8, 12)
            .range(2025, 1, 14, 18)
            .build();

    /** Affiliated with ROSYTH SCHOOL: affiliated PG3 range 10–16, non-affiliated PG3 range 6–9. */
    private final School affiliatedSchool = TestSchools.school("kuo-chuan-presbyterian-secondary-school")
            .affiliatedPrimarySchools("ROSYTH SCHOOL")
            .range(2025, 3, 6, 9)
            .affiliatedRange(2025, 3, 10, 16)
            .build();

    @ParameterizedTest(name = "score {0} → {1}")
    @CsvSource({
            "7,  true",    // better than lowerScore: still kept (SAFE)
            "8,  true",    // = lowerScore
            "12, true",    // = U: kept
            "13, false"    // = U + 1: dropped
    })
    @Tag("FR-FILTER-03")
    @DisplayName("TC-PsleFilter-01: boundaries around the latest range 8–12 (score ≤ U is kept)")
    void boundaries(int score, boolean kept) {
        assertThat(new PsleScoreFilter(score, 3, null).matches(school)).isEqualTo(kept);
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-PsleFilter-02: the posting group chooses the range (PG1 14–18)")
    void postingGroupChoosesRange() {
        assertThat(new PsleScoreFilter(18, 1, null).matches(school)).isTrue();
        assertThat(new PsleScoreFilter(18, 3, null).matches(school)).isFalse();
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-PsleFilter-03: a school without a range for the posting group is left out")
    void noRangeIsExcluded() {
        PsleScoreFilter pg2 = new PsleScoreFilter(4, 2, null);

        assertThat(pg2.matches(school)).isFalse();
        assertThat(pg2.hasApplicableRange(school)).isFalse();
        assertThat(new PsleScoreFilter(4, 3, null).hasApplicableRange(school)).isTrue();
        assertThat(pg2.matches(TestSchools.school("westwood-secondary-school").build())).isFalse();
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-PsleFilter-04: a user from an affiliated primary school gets the affiliated range (DC-22)")
    void affiliatedUserGetsAffiliatedRange() {
        assertThat(new PsleScoreFilter(14, 3, "ROSYTH SCHOOL").matches(affiliatedSchool)).isTrue();
        assertThat(new PsleScoreFilter(17, 3, "ROSYTH SCHOOL").matches(affiliatedSchool)).isFalse();
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-PsleFilter-05: a guest or a user from another primary school gets the non-affiliated range")
    void notAffiliatedGetsNonAffiliatedRange() {
        assertThat(new PsleScoreFilter(14, 3, null).matches(affiliatedSchool)).isFalse();
        assertThat(new PsleScoreFilter(14, 3, "NANYANG PRIMARY SCHOOL").matches(affiliatedSchool)).isFalse();
        assertThat(new PsleScoreFilter(9, 3, "NANYANG PRIMARY SCHOOL").matches(affiliatedSchool)).isTrue();
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-PsleFilter-06: the primary school name is compared ignoring case and extra spaces")
    void primarySchoolNameIsNormalised() {
        assertThat(new PsleScoreFilter(14, 3, "  rosyth   school ").isAffiliatedWith(affiliatedSchool)).isTrue();
        assertThat(new PsleScoreFilter(14, 3, "ROSYTH").isAffiliatedWith(affiliatedSchool)).isFalse();
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-PsleFilter-07: affiliated user, but the school has no affiliated range → non-affiliated range")
    void affiliatedWithoutAffiliatedRangeFallsBack() {
        School onlyNonAffiliated = TestSchools.school("x-secondary-school")
                .affiliatedPrimarySchools("ROSYTH SCHOOL").range(2025, 3, 6, 9).build();

        assertThat(new PsleScoreFilter(9, 3, "ROSYTH SCHOOL").matches(onlyNonAffiliated)).isTrue();
        assertThat(new PsleScoreFilter(10, 3, "ROSYTH SCHOOL").matches(onlyNonAffiliated)).isFalse();
    }

    @ParameterizedTest(name = "score {0}, PG {1} → {2}")
    @CsvSource({"3, 3, false", "4, 3, true", "32, 3, true", "33, 3, false", "12, 0, false", "12, 1, true",
            "12, 4, false"})
    @Tag("FR-FILTER-03")
    @DisplayName("TC-PsleFilter-08: valid = score 4..32 and posting group 1..3")
    void validity(int score, int postingGroup, boolean valid) {
        assertThat(new PsleScoreFilter(score, postingGroup, null).isValid()).isEqualTo(valid);
    }

    @Test
    @Tag("FR-FILTER-08")
    @DisplayName("TC-PsleFilter-09: describe gives the score and posting group, e.g. \"PSLE 12 (PG3)\"")
    void describe() {
        assertThat(new PsleScoreFilter(12, 3, null).describe()).isEqualTo("PSLE 12 (PG3)");
        assertThat(new PsleScoreFilter(20, 1, "ROSYTH SCHOOL").describe()).isEqualTo("PSLE 20 (PG1)");
    }
}

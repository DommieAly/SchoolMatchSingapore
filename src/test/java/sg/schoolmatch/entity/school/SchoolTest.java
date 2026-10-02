package sg.schoolmatch.entity.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sg.schoolmatch.support.TestSchools;

/** Unit tests for «entity» School (name matching, applicable PSLE range, read-only data). */
class SchoolTest {

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({
            "catholic,                          true",
            "CATHOLIC HIGH,                     true",
            "tholic hi,                         true",
            "Catholic High School,              true",
            "raffles,                           false",
            "Catholic High School (Secondary),  false"
    })
    @Tag("FR-SEARCH-02")
    @DisplayName("TC-School-01: matchesName is a case-insensitive substring match on the name")
    void matchesNameIgnoringCase(String term, boolean expected) {
        School school = TestSchools.named("catholic-high-school", "Catholic High School");

        assertThat(school.matchesName(term)).isEqualTo(expected);
    }

    @Test
    @Tag("FR-SEARCH-02")
    @DisplayName("TC-School-02: matchesName treats apostrophes and dots as plain characters")
    void matchesNameWithPunctuation() {
        School school = TestSchools.named("st-andrews-secondary-school", "St. Andrew's Secondary School");

        assertThat(school.matchesName("andrew's")).isTrue();
        assertThat(school.matchesName("st. a")).isTrue();
        assertThat(school.matchesName("andrews")).isFalse();
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-School-03: getScoreRange picks the latest admission year of the posting group")
    void scoreRangeLatestYear() {
        School school = TestSchools.school("bishan-park-secondary-school")
                .range(2023, 3, 6, 10)
                .range(2025, 3, 8, 12)
                .range(2024, 3, 7, 11)
                .range(2025, 2, 13, 16)
                .build();

        assertThat(school.getScoreRange(3, false)).get()
                .extracting(IndicativePsleScoreRange::getAdmissionYear, IndicativePsleScoreRange::getLowerScore,
                        IndicativePsleScoreRange::getUpperScore)
                .containsExactly(2025, 8, 12);
        assertThat(school.getScoreRange(2, false)).get()
                .extracting(IndicativePsleScoreRange::getUpperScore).isEqualTo(16);
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-School-04: getScoreRange returns the affiliated range only for an affiliated student (DC-21)")
    void scoreRangeByAffiliation() {
        School school = TestSchools.school("catholic-high-school")
                .range(2025, 3, 8, 12)
                .affiliatedRange(2024, 3, 9, 13)
                .affiliatedRange(2025, 3, 10, 14)
                .build();

        assertThat(school.getScoreRange(3, true)).get().satisfies(r -> {
            assertThat(r.isAffiliated()).isTrue();
            assertThat(r.getAdmissionYear()).isEqualTo(2025);
            assertThat(r.getUpperScore()).isEqualTo(14);
        });
        assertThat(school.getScoreRange(3, false)).get().satisfies(r -> {
            assertThat(r.isAffiliated()).isFalse();
            assertThat(r.getUpperScore()).isEqualTo(12);
        });
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-School-05: an affiliated student falls back to the non-affiliated range (DC-22), never the reverse")
    void scoreRangeAffiliationFallback() {
        School nonAffiliatedOnly = TestSchools.school("bishan-park-secondary-school").range(2025, 3, 8, 12).build();
        School affiliatedOnly = TestSchools.school("catholic-high-school").affiliatedRange(2025, 2, 13, 16).build();

        assertThat(nonAffiliatedOnly.getScoreRange(3, true)).get()
                .satisfies(r -> assertThat(r.isAffiliated()).isFalse());
        assertThat(affiliatedOnly.getScoreRange(2, false)).isEmpty();
    }

    @Test
    @Tag("FR-SCHOOL-03")
    @DisplayName("TC-School-06: no range for the posting group gives empty (shown as Not available)")
    void scoreRangeMissing() {
        School noRanges = TestSchools.school("new-town-secondary-school").build();
        School pg3Only = TestSchools.school("bishan-park-secondary-school").range(2025, 3, 8, 12).build();

        assertThat(noRanges.getScoreRange(3, false)).isEmpty();
        assertThat(pg3Only.getScoreRange(1, false)).isEmpty();
        assertThat(pg3Only.getScoreRange(1, true)).isEmpty();
    }

    @Test
    @Tag("FR-DATA-07")
    @DisplayName("TC-School-07: collections are read-only, so search and filter cannot change stored data")
    void collectionsAreReadOnly() {
        School school = TestSchools.school("catholic-high-school")
                .ccas("Basketball").programmes("Music Elective Programme").range(2025, 3, 8, 12).build();

        assertThatThrownBy(() -> school.getCcas().add("Chess")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> school.getProgrammes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> school.getScoreRanges().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(school.getCcas()).containsExactly("Basketball");
    }

    @Test
    @Tag("FR-MAP-03")
    @Tag("NFR-DATA-02")
    @DisplayName("TC-School-08: hasValidCoordinate is false when the coordinate is missing or outside Singapore")
    void validCoordinate() {
        assertThat(TestSchools.school("a-school").at(TestSchools.BISHAN).build().hasValidCoordinate()).isTrue();
        assertThat(TestSchools.school("b-school").noCoordinate().build().hasValidCoordinate()).isFalse();
        assertThat(TestSchools.school("c-school").at(TestSchools.OUTSIDE_SINGAPORE).build().hasValidCoordinate())
                .isFalse();
    }

    @Test
    @Tag("FR-FILTER-05")
    @DisplayName("TC-School-09: distanceTo needs a coordinate (IllegalStateException without one)")
    void distanceNeedsCoordinate() {
        School located = TestSchools.school("a-school").at(TestSchools.BISHAN).build();
        School unlocated = TestSchools.school("b-school").noCoordinate().build();

        assertThat(located.distanceTo(TestSchools.BISHAN)).isZero();
        assertThatIllegalStateException().isThrownBy(() -> unlocated.distanceTo(TestSchools.BISHAN));
    }
}

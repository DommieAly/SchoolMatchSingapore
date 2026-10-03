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
    @DisplayName("TC-School-02: matchesName ignores apostrophes, dots and brackets, and needs every word of the term (DC-75)")
    void matchesNameWithPunctuation() {
        School dotted = TestSchools.named("st-andrews-secondary-school", "St. Andrew's Secondary School");
        School undotted = TestSchools.named("st-andrews-school-secondary", "ST ANDREW'S SCHOOL (SECONDARY)");
        School bracketed = TestSchools.named("chij-secondary-toa-payoh", "CHIJ SECONDARY (TOA PAYOH)");
        School govt = TestSchools.named("bukit-panjang-govt-high-school", "BUKIT PANJANG GOVT. HIGH SCHOOL");

        assertThat(dotted.matchesName("andrew's")).isTrue();
        assertThat(dotted.matchesName("st. a")).isTrue();
        assertThat(dotted.matchesName("andrews")).isTrue();
        assertThat(dotted.matchesName("st andrews")).isTrue();
        assertThat(undotted.matchesName("St. Andrew")).isTrue();
        assertThat(undotted.matchesName("st andrews secondary")).isTrue();
        assertThat(bracketed.matchesName("chij toa payoh")).isTrue();
        assertThat(bracketed.matchesName("(toa payoh)")).isTrue();
        assertThat(govt.matchesName("government")).isTrue();
        assertThat(govt.matchesName("govt high")).isTrue();
        assertThat(bracketed.matchesName("chij katong")).isFalse();
        assertThat(dotted.matchesName("'.")).isFalse();
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
    @Tag("FR-FILTER-03")
    @Tag("FR-SCHOOL-02")
    @DisplayName("TC-School-10: an IP-only school uses its IP range for PG3 only; other posting groups stay Not available")
    void ipRangeIsThePg3Fallback() {
        School ipOnly = withRanges("dunman-high-school", new IndicativePsleScoreRange(2025, 3, false, 4, 8, true));

        assertThat(ipOnly.getScoreRange(3, false)).get().satisfies(r -> {
            assertThat(r.isIntegratedProgramme()).isTrue();
            assertThat(r.getUpperScore()).isEqualTo(8);
        });
        assertThat(ipOnly.getScoreRange(3, true)).get()
                .satisfies(r -> assertThat(r.isIntegratedProgramme()).isTrue());
        assertThat(ipOnly.getScoreRange(2, false)).isEmpty();
        assertThat(ipOnly.getScoreRange(1, true)).isEmpty();
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-School-11: a non-IP PG3 range always wins over the IP range, even an older one (IP is only a fallback)")
    void nonIpRangeWinsOverIp() {
        School ipAndPg3 = withRanges("catholic-high-school",
                new IndicativePsleScoreRange(2025, 3, false, 4, 7, true),
                new IndicativePsleScoreRange(2024, 3, false, 6, 8),
                new IndicativePsleScoreRange(2025, 3, true, 7, 12));

        assertThat(ipAndPg3.getScoreRange(3, false)).get().satisfies(r -> {
            assertThat(r.isIntegratedProgramme()).isFalse();
            assertThat(r.getAdmissionYear()).isEqualTo(2024);
        });
        assertThat(ipAndPg3.getScoreRange(3, true)).get().satisfies(r -> {
            assertThat(r.isIntegratedProgramme()).isFalse();
            assertThat(r.isAffiliated()).isTrue();
        });
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-School-12: with several IP ranges the latest admission year is used")
    void latestIpRange() {
        School ipOnly = withRanges("hwa-chong-institution",
                new IndicativePsleScoreRange(2024, 3, false, 4, 7, true),
                new IndicativePsleScoreRange(2025, 3, false, 4, 6, true));

        assertThat(ipOnly.getScoreRange(3, false)).map(IndicativePsleScoreRange::getAdmissionYear).contains(2025);
    }

    @Test
    @Tag("FR-FILTER-03")
    @Tag("FR-SCHOOL-02")
    @DisplayName("TC-School-13: an affiliated IP range is used for an affiliated student, the non-affiliated IP range for others (DC-82, Nanyang Girls')")
    void affiliatedIpRange() {
        School nanyang = withRanges("nanyang-girls-high-school",
                new IndicativePsleScoreRange(2025, 3, false, 4, 6, true),
                new IndicativePsleScoreRange(2025, 3, true, 4, 8, true));

        assertThat(nanyang.getScoreRange(3, true)).get().satisfies(r -> {
            assertThat(r.isIntegratedProgramme()).isTrue();
            assertThat(r.isAffiliated()).isTrue();
            assertThat(r.getUpperScore()).isEqualTo(8);
        });
        assertThat(nanyang.getScoreRange(3, false)).get().satisfies(r -> {
            assertThat(r.isIntegratedProgramme()).isTrue();
            assertThat(r.isAffiliated()).isFalse();
            assertThat(r.getUpperScore()).isEqualTo(6);
        });
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-School-14: the IP fallback is used only when the school has no non-IP PG3 range at all, not when only the affiliated one exists")
    void ipFallbackOnlyWithoutAnyNonIpPg3Range() {
        School affiliatedOnly = withRanges("x-high-school",
                new IndicativePsleScoreRange(2025, 3, true, 7, 12),
                new IndicativePsleScoreRange(2025, 3, false, 4, 7, true));

        assertThat(affiliatedOnly.getScoreRange(3, false)).isEmpty();
        assertThat(affiliatedOnly.getScoreRange(3, true)).get().satisfies(r -> {
            assertThat(r.isIntegratedProgramme()).isFalse();
            assertThat(r.isAffiliated()).isTrue();
        });
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "Catholic High School (Primary)|true",
            "Catholic High School|true",
            "  catholic   high school (primary) |true",
            "CHIJ Kellock|true",
            "CHIJ (Kellock)|true",
            "St Anthony's Canossian Primary School|true",
            "St. Anthonys Canossian Primary School|true",
            "Catholic High|false",
            "Kellock|false",
            "Rosyth School|false"})
    @Tag("FR-FILTER-03")
    @Tag("FR-PLAN-02")
    @DisplayName("TC-School-15: hasAffiliatedPrimarySchool ignores case, spaces, dots, apostrophes, brackets and a trailing (Primary), but not missing words")
    void affiliatedPrimarySchoolNames(String profileName, boolean expected) {
        School school = TestSchools.school("x-secondary").build();
        school.setAffiliatedPrimarySchools(java.util.List.of("CATHOLIC HIGH SCHOOL (PRIMARY)", "CHIJ (KELLOCK)",
                "ST. ANTHONY'S CANOSSIAN PRIMARY SCHOOL"));

        assertThat(school.hasAffiliatedPrimarySchool(profileName)).isEqualTo(expected);
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-School-16: no primary school (guest, blank profile field) is never affiliated")
    void noPrimarySchoolIsNotAffiliated() {
        School school = TestSchools.school("x-secondary").build();
        school.setAffiliatedPrimarySchools(java.util.List.of("NGEE ANN PRIMARY SCHOOL"));

        assertThat(school.hasAffiliatedPrimarySchool(null)).isFalse();
        assertThat(school.hasAffiliatedPrimarySchool("   ")).isFalse();
        assertThat(school.hasAffiliatedPrimarySchool("(Primary)")).isFalse();
    }

    /** A school with exactly these ranges (TestSchools has no IP shorthand). */
    private static School withRanges(String code, IndicativePsleScoreRange... ranges) {
        School school = TestSchools.school(code).build();
        school.setScoreRanges(java.util.List.of(ranges));
        return school;
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

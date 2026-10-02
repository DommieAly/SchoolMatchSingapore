package sg.schoolmatch.entity.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.support.TestSchools;

/**
 * Unit tests for «entity» CurrentResultSet.applyFilters (FR-FILTER-07: AND across categories, OR within one)
 * and clearFilters (FR-FILTER-09).
 */
class CurrentResultSetTest {

    private final School catholic = TestSchools.school("catholic-high-school").name("CATHOLIC HIGH SCHOOL")
            .type("GOVERNMENT-AIDED SCH").planningArea("BISHAN").ccas("BOWLING", "CHOIR")
            .range(2025, 3, 6, 9).build();
    private final School peirce = TestSchools.school("peirce-secondary-school").name("PEIRCE SECONDARY SCHOOL")
            .type("GOVERNMENT SCHOOL").planningArea("BISHAN").ccas("RUGBY", "CHOIR")
            .range(2025, 3, 18, 21).build();
    private final School tampines = TestSchools.school("tampines-secondary-school").name("TAMPINES SECONDARY SCHOOL")
            .type("GOVERNMENT SCHOOL").planningArea("TAMPINES").ccas("CHOIR").at(TestSchools.TAMPINES)
            .range(2025, 3, 22, 25).build();
    private final School westwood = TestSchools.school("westwood-secondary-school").name("WESTWOOD SECONDARY SCHOOL")
            .type("GOVERNMENT SCHOOL").planningArea("JURONG WEST").ccas("BOWLING").build();   // no PSLE ranges

    private CurrentResultSet all() {
        return new CurrentResultSet(null, List.of(catholic, peirce, tampines, westwood));
    }

    private static SchoolAttributeFilter attr(AttributeCategory category, String... values) {
        return new SchoolAttributeFilter(category, List.of(values));
    }

    @Test
    @Tag("FR-FILTER-07")
    @DisplayName("TC-ResultSet-01: values in one category combine with OR (BOWLING or RUGBY)")
    void orWithinCategory() {
        CurrentResultSet results = all();
        results.addFilter(attr(AttributeCategory.CCA, "BOWLING", "RUGBY"));

        results.applyFilters();

        assertThat(results.getSchools()).containsExactly(catholic, peirce, westwood);
    }

    @Test
    @Tag("FR-FILTER-07")
    @DisplayName("TC-ResultSet-02: categories combine with AND (CCA BOWLING and type GOVERNMENT SCHOOL)")
    void andAcrossCategories() {
        CurrentResultSet results = all();
        results.addFilter(attr(AttributeCategory.CCA, "BOWLING"));
        results.addFilter(attr(AttributeCategory.SCHOOL_TYPE, "GOVERNMENT SCHOOL"));

        results.applyFilters();

        assertThat(results.getSchools()).containsExactly(westwood);
    }

    @Test
    @Tag("FR-FILTER-07")
    @DisplayName("TC-ResultSet-03: two filters of the same category still combine with OR")
    void sameCategoryTwiceIsStillOr() {
        CurrentResultSet results = all();
        results.addFilter(attr(AttributeCategory.DISTRICT, "BISHAN"));
        results.addFilter(attr(AttributeCategory.DISTRICT, "TAMPINES"));

        results.applyFilters();

        assertThat(results.getSchools()).containsExactly(catholic, peirce, tampines);
    }

    @Test
    @Tag("FR-FILTER-07")
    @DisplayName("TC-ResultSet-04: attribute, PSLE and distance filters all have to match")
    void mixedFilters() {
        ReferenceLocation bishan = new ReferenceLocation(TestSchools.BISHAN, LocationSource.MANUAL_ENTRY, "BISHAN");
        CurrentResultSet results = all();
        results.addFilter(attr(AttributeCategory.CCA, "CHOIR"));
        results.addFilter(new PsleScoreFilter(20, 3, null));
        results.addFilter(new ProximityFilter(bishan, 5));

        results.applyFilters();

        assertThat(results.getSchools()).containsExactly(peirce);
    }

    @Test
    @Tag("FR-FILTER-09")
    @DisplayName("TC-ResultSet-05: clearFilters restores the unfiltered schools and drops the filters")
    void clearRestores() {
        CurrentResultSet results = all();
        results.addFilter(attr(AttributeCategory.CCA, "RUGBY"));
        results.applyFilters();
        assertThat(results.size()).isEqualTo(1);

        results.clearFilters();

        assertThat(results.getSchools()).containsExactly(catholic, peirce, tampines, westwood);
        assertThat(results.getActiveFilters()).isEmpty();
    }

    @Test
    @Tag("FR-FILTER-07")
    @DisplayName("TC-ResultSet-06: applying with no filters keeps every school; applying twice gives the same result")
    void idempotent() {
        CurrentResultSet results = all();
        results.applyFilters();
        assertThat(results.size()).isEqualTo(4);

        results.addFilter(attr(AttributeCategory.DISTRICT, "BISHAN"));
        results.applyFilters();
        results.applyFilters();

        assertThat(results.getSchools()).containsExactly(catholic, peirce);
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-ResultSet-07: counts the schools hidden only because they have no PSLE range")
    void countsSchoolsWithoutPsleData() {
        CurrentResultSet results = all();
        results.addFilter(new PsleScoreFilter(4, 3, null));
        results.applyFilters();
        assertThat(results.getSchools()).containsExactly(catholic, peirce, tampines);
        assertThat(results.getHiddenWithoutPsleData()).isEqualTo(1);   // westwood

        CurrentResultSet bishanOnly = all();
        bishanOnly.addFilter(new PsleScoreFilter(4, 3, null));
        bishanOnly.addFilter(attr(AttributeCategory.DISTRICT, "BISHAN"));
        bishanOnly.applyFilters();
        assertThat(bishanOnly.getHiddenWithoutPsleData()).isZero();     // westwood is hidden by the district anyway
    }

    @Test
    @Tag("FR-DATA-07")
    @DisplayName("TC-ResultSet-08: filtering never changes the School objects")
    void schoolsUnchanged() {
        CurrentResultSet results = all();
        results.addFilter(attr(AttributeCategory.CCA, "RUGBY"));
        results.applyFilters();

        assertThat(catholic.getCcas()).containsExactly("BOWLING", "CHOIR");
        assertThat(results.getUnfilteredSchools()).containsExactly(catholic, peirce, tampines, westwood);
    }

    @Test
    @Tag("FR-FILTER-05")
    @DisplayName("TC-ResultSet-09: copyWith keeps the starting point, commute minutes and filters")
    void copyKeepsExtras() {
        ReferenceLocation bishan = new ReferenceLocation(TestSchools.BISHAN, LocationSource.MANUAL_ENTRY, "BISHAN");
        CurrentResultSet results = all();
        results.setReferenceLocation(bishan);
        results.setCommuteMinutes(Map.of("peirce-secondary-school", 14));
        results.addFilter(attr(AttributeCategory.CCA, "CHOIR"));

        CurrentResultSet copy = results.copyWith(List.of(peirce), SortOrder.COMMUTE_ASC);

        assertThat(copy.getReferenceLocation()).isSameAs(bishan);
        assertThat(copy.getCommuteMinutes()).containsExactly(Map.entry("peirce-secondary-school", 14));
        assertThat(copy.getActiveFilters()).hasSize(1);
        assertThat(copy.getSortOrder()).isEqualTo(SortOrder.COMMUTE_ASC);
        assertThat(copy.getSchools()).containsExactly(peirce);
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-ResultSet-10: no starting point and no commute times by default")
    void defaults() {
        CurrentResultSet results = all();

        assertThat(results.getReferenceLocation()).isNull();
        assertThat(results.getCommuteMinutes()).isEmpty();
        assertThat(results.getHiddenWithoutPsleData()).isZero();
    }
}

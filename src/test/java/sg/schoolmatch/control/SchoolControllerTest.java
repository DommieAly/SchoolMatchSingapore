package sg.schoolmatch.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.entity.search.CurrentResultSet;
import sg.schoolmatch.entity.search.SortOrder;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotFoundException;
import sg.schoolmatch.support.TestSchools;

/**
 * Unit test of the control class SchoolController (example of a control test: Mockito, no Spring).
 * <p>
 * The only dependency, {@link SchoolDataController}, is a mock that returns a small hand-made dataset;
 * entities are real objects. The black-box cases come from {@code testcases/TC-SEARCH.csv}
 * (the Lab 4 table), so the report and the executed tests cannot disagree.
 * <p>
 * Test-first: add the rows to the CSV, watch them fail against the {@code TODO} body, then write the body.
 */
@ExtendWith(MockitoExtension.class)
class SchoolControllerTest {

    private static final ReferenceLocation BISHAN_MRT =
            new ReferenceLocation(TestSchools.BISHAN, LocationSource.MANUAL_ENTRY, "BISHAN MRT");

    @Mock
    private SchoolDataController schoolDataController;

    private SchoolController schoolController;

    /** Six schools, deliberately not in A–Z order. Names are upper-case, as data.gov.sg publishes them. */
    private final SchoolDataCache sixSchools = dataset(
            school("tampines-secondary-school", "TAMPINES SECONDARY SCHOOL", "252 TAMPINES STREET 12"),
            school("catholic-high-school", "CATHOLIC HIGH SCHOOL", "9 BISHAN STREET 22"),
            school("bishan-park-secondary-school", "BISHAN PARK SECONDARY SCHOOL", "2 SIN MING WALK"),
            school("anglican-high-school", "ANGLICAN HIGH SCHOOL", "600 UPPER CHANGI ROAD"),
            school("chij-st-theresas-convent", "CHIJ ST. THERESA'S CONVENT", "160 LOWER DELTA ROAD"),
            school("raffles-institution", "RAFFLES INSTITUTION", "1 RAFFLES INSTITUTION LANE"));

    @BeforeEach
    void setUp() {
        schoolController = new SchoolController(schoolDataController);
    }

    /**
     * One run per row of TC-SEARCH.csv. {@code expected} is a result count, or {@code error} for
     * InvalidInputException. In the input column, {@code a*100} means the letter a repeated 100 times.
     */
    @ParameterizedTest(name = "{0}: {2}")
    @CsvFileSource(resources = "/testcases/TC-SEARCH.csv", numLinesToSkip = 1)
    @Tag("FR-SEARCH-01")
    @Tag("FR-SEARCH-02")
    @Tag("FR-SEARCH-04")
    @Tag("FR-SEARCH-05")
    @Tag("FR-SEARCH-06")
    @DisplayName("TC-SEARCH-xx-yy: search term cases from TC-SEARCH.csv")
    void searchSchools_followsTheTestCaseTable(String tcId, String requirement, String technique,
                                               String input, String expected) {
        String term = expand(input);

        if (expected.equals("error")) {
            assertThrows(InvalidInputException.class, () -> schoolController.searchSchools(term));
            verifyNoInteractions(schoolDataController);          // invalid input never reaches the data
        } else {
            when(schoolDataController.getActiveDataset()).thenReturn(sixSchools);

            CurrentResultSet results = schoolController.searchSchools(term);

            assertEquals(Integer.parseInt(expected), results.size());
            assertInNameOrder(results.getSchools());             // FR-SEARCH-06 holds for every row
        }
    }

    @Test
    @Tag("FR-SEARCH-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-SEARCH-01-04: a too-long term is reported on field q with a clear message")
    void searchSchools_tooLongTerm_namesTheField() {
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> schoolController.searchSchools("a".repeat(101)));

        assertEquals(Map.of("q", "Enter 1–100 characters"), e.getFieldErrors());
    }

    @Test
    @Tag("FR-SEARCH-01")
    @DisplayName("TC-SEARCH-01-05: the length is counted after trimming (100 characters plus spaces is valid)")
    void searchSchools_lengthCountedAfterTrimming() {
        when(schoolDataController.getActiveDataset()).thenReturn(sixSchools);

        CurrentResultSet results = schoolController.searchSchools("  " + "a".repeat(100) + "  ");

        assertEquals("a".repeat(100), results.getSearchTerm());
        assertTrue(results.isEmpty());
    }

    @Test
    @Tag("FR-SEARCH-02")
    @DisplayName("TC-SEARCH-02-07: the result set keeps the trimmed term and the default order")
    void searchSchools_keepsTrimmedTerm() {
        when(schoolDataController.getActiveDataset()).thenReturn(sixSchools);

        CurrentResultSet results = schoolController.searchSchools("  High ");

        assertEquals("High", results.getSearchTerm());
        assertEquals(SortOrder.NAME_ASC, results.getSortOrder());
        assertEquals(List.of("ANGLICAN HIGH SCHOOL", "CATHOLIC HIGH SCHOOL"), names(results.getSchools()));
    }

    @Test
    @Tag("FR-SEARCH-04")
    @Tag("FR-SEARCH-06")
    @DisplayName("TC-SEARCH-04-04: a blank term lists every school A–Z and has no search term (DC-02)")
    void searchSchools_blankTerm_listsAllSchoolsAtoZ() {
        when(schoolDataController.getActiveDataset()).thenReturn(sixSchools);

        CurrentResultSet results = schoolController.searchSchools(" ");

        assertNull(results.getSearchTerm());
        assertEquals(List.of(
                "ANGLICAN HIGH SCHOOL",
                "BISHAN PARK SECONDARY SCHOOL",
                "CATHOLIC HIGH SCHOOL",
                "CHIJ ST. THERESA'S CONVENT",
                "RAFFLES INSTITUTION",
                "TAMPINES SECONDARY SCHOOL"), names(results.getSchools()));
    }

    @Test
    @Tag("FR-SEARCH-06")
    @DisplayName("TC-SEARCH-06-02: schools with the same name are ordered by school code")
    void searchSchools_sameName_orderedByCode() {
        when(schoolDataController.getActiveDataset()).thenReturn(dataset(
                school("north-view-b", "NORTH VIEW SECONDARY SCHOOL", null),
                school("north-view-a", "NORTH VIEW SECONDARY SCHOOL", null)));

        CurrentResultSet results = schoolController.searchSchools("north");

        assertEquals(List.of("north-view-a", "north-view-b"),
                results.getSchools().stream().map(School::getSchoolCode).toList());
    }

    @Test
    @Tag("FR-SEARCH-06")
    @DisplayName("TC-SEARCH-06-03: sortResults(NAME_ASC) returns a sorted copy and leaves the input unchanged (DC-15)")
    void sortResults_byName_returnsSortedCopy() {
        School tampines = school("tampines-secondary-school", "TAMPINES SECONDARY SCHOOL", null);
        School anglican = school("anglican-high-school", "ANGLICAN HIGH SCHOOL", null);
        CurrentResultSet unsorted = new CurrentResultSet("s", List.of(tampines, anglican));

        CurrentResultSet sorted = schoolController.sortResults(unsorted, SortOrder.NAME_ASC);

        assertEquals(List.of(anglican, tampines), sorted.getSchools());
        assertEquals(List.of(tampines, anglican), unsorted.getSchools());
        assertEquals("s", sorted.getSearchTerm());
    }

    @Test
    @Tag("FR-SEARCH-06")
    @Tag("FR-FILTER-05")
    @DisplayName("TC-SEARCH-06-04: sortResults(DISTANCE_ASC) orders by straight-line distance; schools without a coordinate come last")
    void sortResults_byDistance() {
        School far = TestSchools.school("tampines-secondary-school").name("TAMPINES SECONDARY SCHOOL")
                .at(TestSchools.TAMPINES).build();
        School near = TestSchools.school("zhonghua-secondary-school").name("ZHONGHUA SECONDARY SCHOOL")
                .at(TestSchools.BISHAN).build();
        School noCoordinate = TestSchools.school("anglican-high-school").name("ANGLICAN HIGH SCHOOL")
                .noCoordinate().build();
        CurrentResultSet results = new CurrentResultSet(null, List.of(noCoordinate, far, near));
        results.setReferenceLocation(BISHAN_MRT);

        CurrentResultSet sorted = schoolController.sortResults(results, SortOrder.DISTANCE_ASC);

        assertEquals(List.of(near, far, noCoordinate), sorted.getSchools());
        assertEquals(SortOrder.DISTANCE_ASC, sorted.getSortOrder());
        assertEquals(List.of(noCoordinate, far, near), results.getSchools());   // input unchanged (DC-15)
    }

    @Test
    @Tag("FR-SEARCH-06")
    @DisplayName("TC-SEARCH-06-05: DISTANCE_ASC without a starting point falls back to NAME_ASC")
    void sortResults_byDistanceWithoutLocation() {
        School tampines = school("tampines-secondary-school", "TAMPINES SECONDARY SCHOOL", null);
        School anglican = school("anglican-high-school", "ANGLICAN HIGH SCHOOL", null);

        CurrentResultSet sorted = schoolController.sortResults(
                new CurrentResultSet(null, List.of(tampines, anglican)), SortOrder.DISTANCE_ASC);

        assertEquals(SortOrder.NAME_ASC, sorted.getSortOrder());
        assertEquals(List.of(anglican, tampines), sorted.getSchools());
    }

    @Test
    @Tag("FR-SEARCH-06")
    @Tag("FR-FILTER-06")
    @DisplayName("TC-SEARCH-06-06: sortResults(COMMUTE_ASC) orders by commute minutes, ties by name, unknown last")
    void sortResults_byCommute() {
        School a = school("a-school", "A SCHOOL", null);
        School b = school("b-school", "B SCHOOL", null);
        School c = school("c-school", "C SCHOOL", null);
        School d = school("d-school", "D SCHOOL", null);
        CurrentResultSet results = new CurrentResultSet(null, List.of(a, b, c, d));
        results.setCommuteMinutes(Map.of("a-school", 25, "b-school", 12, "d-school", 12));

        CurrentResultSet sorted = schoolController.sortResults(results, SortOrder.COMMUTE_ASC);

        assertEquals(List.of(b, d, a, c), sorted.getSchools());
        assertEquals(SortOrder.COMMUTE_ASC, sorted.getSortOrder());
    }

    @Test
    @Tag("FR-SEARCH-06")
    @DisplayName("TC-SEARCH-06-07: COMMUTE_ASC before the travel-time filter ran falls back to NAME_ASC")
    void sortResults_byCommuteWithoutTimes() {
        School tampines = school("tampines-secondary-school", "TAMPINES SECONDARY SCHOOL", null);
        School anglican = school("anglican-high-school", "ANGLICAN HIGH SCHOOL", null);

        CurrentResultSet sorted = schoolController.sortResults(
                new CurrentResultSet(null, List.of(tampines, anglican)), SortOrder.COMMUTE_ASC);

        assertEquals(SortOrder.NAME_ASC, sorted.getSortOrder());
        assertEquals(List.of(anglican, tampines), sorted.getSchools());
    }

    @Test
    @Tag("FR-SCHOOL-01")
    @DisplayName("TC-SCHOOL-01-01: getSchoolDetails returns the school from the active dataset")
    void getSchoolDetails_knownCode_returnsSchool() {
        School catholic = school("catholic-high-school", "CATHOLIC HIGH SCHOOL", "9 BISHAN STREET 22");
        when(schoolDataController.getSchool("catholic-high-school")).thenReturn(catholic);

        assertSame(catholic, schoolController.getSchoolDetails("catholic-high-school"));
    }

    @Test
    @Tag("FR-SCHOOL-01")
    @DisplayName("TC-SCHOOL-01-02: an unknown school code raises NotFoundException")
    void getSchoolDetails_unknownCode_throwsNotFound() {
        when(schoolDataController.getSchool("does-not-exist"))
                .thenThrow(new NotFoundException("No school with code does-not-exist"));

        assertThrows(NotFoundException.class, () -> schoolController.getSchoolDetails("does-not-exist"));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static School school(String code, String name, String address) {
        School school = new School(code, name);
        school.setAddress(address);
        return school;
    }

    private static SchoolDataCache dataset(School... schools) {
        return new SchoolDataCache("test", Instant.EPOCH, Instant.MAX, List.of(schools), List.of());
    }

    /** CSV shorthand: "a*100" is the letter a repeated 100 times; any other value is used as it is. */
    private static String expand(String input) {
        if (input != null && input.matches(".\\*\\d+")) {
            return input.substring(0, 1).repeat(Integer.parseInt(input.substring(2)));
        }
        return input;
    }

    private static List<String> names(List<School> schools) {
        return schools.stream().map(School::getName).toList();
    }

    private static void assertInNameOrder(List<School> schools) {
        for (int i = 1; i < schools.size(); i++) {
            String before = schools.get(i - 1).getName();
            String after = schools.get(i).getName();
            assertTrue(before.compareToIgnoreCase(after) <= 0, before + " should come before " + after);
        }
    }
}

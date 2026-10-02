package sg.schoolmatch.control;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.entity.search.CurrentResultSet;
import sg.schoolmatch.entity.search.SortOrder;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Design class «control» SchoolController — search schools by name and show one school
 * (use cases Search for Schools, View School Details; FR-SEARCH-01..06, FR-SCHOOL-01..03).
 * DC-15: stateless — the result set is rebuilt per request. Called by SchoolSearchUI, SchoolDetailsUI, and
 * (DC-37) SchoolMapUI, FacilityMapUI, NearbyFacilitiesUI, DirectionsUI to load the school or results from the URL.
 * <p>
 * Reference vertical slice (SPEC §7): {@code SchoolSearchUI} → this control → {@link SchoolDataController}.
 * Tests: {@code SchoolControllerTest}, driven by the table {@code src/test/resources/testcases/TC-SEARCH.csv}.
 */
@Service
public class SchoolController {

    /** FR-SEARCH-01: a search term has 1–100 characters after trimming. */
    public static final int MAX_TERM_LENGTH = 100;

    private final SchoolDataController schoolDataController;

    public SchoolController(SchoolDataController schoolDataController) {
        this.schoolDataController = schoolDataController;
    }

    /**
     * Schools whose name contains {@code term}, ignoring case (FR-SEARCH-02), sorted A–Z (FR-SEARCH-06).
     * A term that is null or blank after trimming means "no term": all schools A–Z (DC-02, FR-SEARCH-04).
     * No match is not an error: the result set is empty and the page says so (FR-SEARCH-05).
     *
     * @throws InvalidInputException (field {@code q}) when the trimmed term is longer than 100 characters
     */
    public CurrentResultSet searchSchools(String term) {
        String trimmed = term == null ? "" : term.strip();
        boolean noTerm = trimmed.isEmpty();                                   // DC-02
        if (!noTerm && !validateSearchTerm(trimmed)) {
            throw new InvalidInputException("q", "Enter 1–" + MAX_TERM_LENGTH + " characters");
        }
        SchoolDataCache dataset = schoolDataController.getActiveDataset();   // one snapshot per request
        List<School> found = noTerm ? dataset.getSchools() : dataset.findByName(trimmed);
        return new CurrentResultSet(noTerm ? null : trimmed, SortOrder.NAME_ASC, sortByName(found));
    }

    /**
     * The school to show on the details page (FR-SCHOOL-01).
     *
     * @throws sg.schoolmatch.error.NotFoundException for an unknown code (the page answers 404)
     */
    public School getSchoolDetails(String schoolCode) {
        return schoolDataController.getSchool(schoolCode);
    }

    /**
     * DC-15: returns a sorted copy; {@code results} is not changed. Ties are broken by name, then school code.
     * <ul>
     *   <li>NAME_ASC: A–Z (FR-SEARCH-06).</li>
     *   <li>DISTANCE_ASC: nearest first, straight line from {@code results.getReferenceLocation()}; schools without a
     *       valid coordinate last (NFR-DATA-02).</li>
     *   <li>COMMUTE_ASC: shortest commute first, from {@code results.getCommuteMinutes()} (filled by the
     *       travel-time filter); schools without a time last.</li>
     * </ul>
     * When the order is not available (no resolved starting point, or no commute times) the result is sorted
     * NAME_ASC instead; the returned {@code getSortOrder()} says which order was used, so the page can tell the user.
     */
    public CurrentResultSet sortResults(CurrentResultSet results, SortOrder order) {   // DC-15, DC-56
        SortOrder wanted = order == null ? SortOrder.NAME_ASC : order;
        ReferenceLocation start = results.getReferenceLocation();
        Map<String, Integer> minutes = results.getCommuteMinutes();
        if (wanted == SortOrder.DISTANCE_ASC && start != null && start.isResolved()) {
            return results.copyWith(sortByDistance(results.getSchools(), start.getCoordinate()), wanted);
        }
        if (wanted == SortOrder.COMMUTE_ASC && !minutes.isEmpty()) {
            return results.copyWith(sortByCommute(results.getSchools(), minutes), wanted);
        }
        return results.copyWith(sortByName(results.getSchools()), SortOrder.NAME_ASC);
    }

    /** FR-SEARCH-01: true when the (already trimmed) term has 1–100 characters. */
    private boolean validateSearchTerm(String term) {
        return !term.isEmpty() && term.length() <= MAX_TERM_LENGTH;
    }

    /** A–Z by name, then by school code, so the same query always gives the same order (FR-SEARCH-06). */
    private static List<School> sortByName(List<School> schools) {
        return schools.stream().sorted(SchoolDataCache.BY_NAME_THEN_CODE).toList();
    }

    /** Nearest first; schools without a valid coordinate last (A–Z among themselves). */
    private static List<School> sortByDistance(List<School> schools, Coordinate start) {
        Comparator<School> byDistance = Comparator.comparing(
                (School s) -> s.hasValidCoordinate() ? s.distanceTo(start) : null,
                Comparator.nullsLast(Comparator.naturalOrder()));
        return schools.stream().sorted(byDistance.thenComparing(SchoolDataCache.BY_NAME_THEN_CODE)).toList();
    }

    /** Shortest commute first; schools without a commute time last (A–Z among themselves). */
    private static List<School> sortByCommute(List<School> schools, Map<String, Integer> minutes) {
        Comparator<School> byMinutes = Comparator.comparing(
                (School s) -> minutes.get(s.getSchoolCode()), Comparator.nullsLast(Comparator.naturalOrder()));
        return schools.stream().sorted(byMinutes.thenComparing(SchoolDataCache.BY_NAME_THEN_CODE)).toList();
    }
}

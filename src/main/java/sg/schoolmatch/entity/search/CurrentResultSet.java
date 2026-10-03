package sg.schoolmatch.entity.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.school.School;

/**
 * Design class «entity» CurrentResultSet — the schools shown for one search, with its filters and order
 * (FR-SEARCH-03, FR-FILTER-01, FR-FILTER-09).
 * <p>
 * DC-15: rebuilt for every request from the URL (search → filters → sort); it is never kept in a session.
 * It remembers the unfiltered schools of the search so {@link #clearFilters()} can restore them.
 * It also carries the user's starting point (for the distance sort and the cards) and the commute minutes found by
 * the travel-time filter (for the commute sort and the cards). It never changes the School objects (FR-DATA-07).
 */
public class CurrentResultSet {

    private final String searchTerm;                      // null = no term (all schools)
    private final SortOrder sortOrder;
    private final List<School> unfilteredSchools;         // result of the search, before any filter
    private List<School> schools;
    private final List<Filter> activeFilters = new ArrayList<>();
    private ReferenceLocation referenceLocation;          // null = no starting point; DC-55
    private Map<String, Integer> commuteMinutes = Map.of();   // schoolCode → minutes, from the travel-time filter
    private int hiddenWithoutPsleData;                    // see getHiddenWithoutPsleData()

    /** A result set in the default order (NAME_ASC). */
    public CurrentResultSet(String searchTerm, List<School> schools) {
        this(searchTerm, SortOrder.NAME_ASC, schools);
    }

    public CurrentResultSet(String searchTerm, SortOrder sortOrder, List<School> schools) {
        this.searchTerm = searchTerm;
        this.sortOrder = sortOrder == null ? SortOrder.NAME_ASC : sortOrder;
        this.unfilteredSchools = List.copyOf(schools);
        this.schools = new ArrayList<>(schools);
    }

    /**
     * A copy with other schools and order, keeping the term, the unfiltered schools, the active filters, the
     * starting point and the commute minutes. Handy for stateless {@code sortResults}/{@code applyFilters} (DC-15).
     */
    public CurrentResultSet copyWith(List<School> newSchools, SortOrder newOrder) {   // DC-36 helper
        CurrentResultSet copy = new CurrentResultSet(searchTerm, newOrder, unfilteredSchools);
        copy.schools = new ArrayList<>(newSchools);
        copy.activeFilters.addAll(activeFilters);
        copy.referenceLocation = referenceLocation;
        copy.commuteMinutes = commuteMinutes;
        copy.hiddenWithoutPsleData = hiddenWithoutPsleData;
        return copy;
    }

    /** The schools currently shown (read-only). */
    public List<School> getSchools() {
        return Collections.unmodifiableList(schools);
    }

    public void addFilter(Filter filter) {
        activeFilters.add(filter);
    }

    /**
     * Applies the active filters to the unfiltered schools, keeping their order.
     * Rule (FR-FILTER-07): AND across categories, OR within one category
     * (e.g. type ∈ {A, B} AND PSLE fits AND within 3 km). Attribute filters of the same category form one
     * OR group, even when there are several of them; every other filter must match on its own.
     * Also counts the schools left out only because a PSLE filter found no range ({@link #getHiddenWithoutPsleData}).
     */
    public void applyFilters() {
        Map<AttributeCategory, List<SchoolAttributeFilter>> attributeGroups = new EnumMap<>(AttributeCategory.class);
        List<Filter> otherFilters = new ArrayList<>();
        for (Filter filter : activeFilters) {
            if (filter instanceof SchoolAttributeFilter attribute && attribute.getCategory() != null) {
                attributeGroups.computeIfAbsent(attribute.getCategory(), c -> new ArrayList<>()).add(attribute);
            } else {
                otherFilters.add(filter);
            }
        }

        List<School> kept = new ArrayList<>();
        int noPsleData = 0;
        for (School school : unfilteredSchools) {
            boolean attributesMatch = attributeGroups.values().stream()
                    .allMatch(group -> group.stream().anyMatch(f -> f.matches(school)));
            boolean othersMatch = true;
            boolean onlyMissingPsleData = attributesMatch;   // stays true if every failure is "no PSLE range"
            for (Filter filter : otherFilters) {
                if (!filter.matches(school)) {
                    othersMatch = false;
                    if (!(filter instanceof PsleScoreFilter psle && !psle.hasApplicableRange(school))) {
                        onlyMissingPsleData = false;
                    }
                }
            }
            if (attributesMatch && othersMatch) {
                kept.add(school);
            } else if (!othersMatch && onlyMissingPsleData) {
                noPsleData++;
            }
        }
        schools = kept;
        hiddenWithoutPsleData = noPsleData;
    }

    /** Removes every filter and restores the unfiltered schools (FR-FILTER-09). */
    public void clearFilters() {
        activeFilters.clear();
        schools = new ArrayList<>(unfilteredSchools);
        commuteMinutes = Map.of();
        hiddenWithoutPsleData = 0;
    }

    public boolean isEmpty() {
        return schools.isEmpty();
    }

    public int size() {   // DC-36 helper
        return schools.size();
    }

    public String getSearchTerm() {
        return searchTerm;
    }

    public SortOrder getSortOrder() {
        return sortOrder;
    }

    public List<Filter> getActiveFilters() {
        return Collections.unmodifiableList(activeFilters);
    }

    /** The search result before filters (read-only). */
    public List<School> getUnfilteredSchools() {   // DC-36 helper
        return unfilteredSchools;
    }

    /** The user's starting point, or null; needed for DISTANCE_ASC and the distance on the cards. */
    public ReferenceLocation getReferenceLocation() {
        return referenceLocation;
    }

    public void setReferenceLocation(ReferenceLocation referenceLocation) {
        this.referenceLocation = referenceLocation;
    }

    /** School code → commute minutes found by the travel-time filter; empty when it did not run (read-only). */
    public Map<String, Integer> getCommuteMinutes() {
        return commuteMinutes;
    }

    public void setCommuteMinutes(Map<String, Integer> commuteMinutes) {
        this.commuteMinutes = commuteMinutes == null ? Map.of() : Map.copyOf(commuteMinutes);
    }

    /**
     * After {@link #applyFilters()}: how many schools passed every other filter but were left out because a PSLE
     * filter found no range for them (page: "N schools have no PSLE range for PG3 and are hidden").
     */
    public int getHiddenWithoutPsleData() {
        return hiddenWithoutPsleData;
    }
}

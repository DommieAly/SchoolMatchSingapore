package sg.schoolmatch.entity.search;

import sg.schoolmatch.entity.school.School;

/**
 * Design class «entity» Filter — one active criterion on the Current Result Set (FR-FILTER-01, FR-FILTER-08).
 * Subclasses: SchoolAttributeFilter, PsleScoreFilter, ProximityFilter, TransportationFilter.
 */
public abstract class Filter {

    /** True when {@code school} passes this filter. */
    public abstract boolean matches(School school);

    /** True when the filter's inputs are within the allowed values. */
    public abstract boolean isValid();

    /** Short text for the "active filters" list (FR-FILTER-08). */
    public abstract String describe();
}

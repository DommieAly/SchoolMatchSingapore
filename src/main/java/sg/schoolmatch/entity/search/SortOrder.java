package sg.schoolmatch.entity.search;

/**
 * Design class «enumeration» SortOrder — order of the Current Result Set (FR-SEARCH-06).
 * DISTANCE_ASC needs a reference location; COMMUTE_ASC needs the transport filter to have run.
 */
public enum SortOrder {
    NAME_ASC,
    DISTANCE_ASC,
    COMMUTE_ASC
}

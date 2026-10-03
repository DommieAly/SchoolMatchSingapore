package sg.schoolmatch.persistence.dataset;

/** One row of {@code school_affiliated_primary}. */
public record SchoolAffiliatedPrimaryRow(
        String schoolCode,
        String primarySchoolName) {
}

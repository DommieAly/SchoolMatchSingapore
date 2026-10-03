package sg.schoolmatch.persistence.dataset;

/** One row of {@code school_programme}. */
public record SchoolProgrammeRow(
        String schoolCode,
        String programmeName) {
}

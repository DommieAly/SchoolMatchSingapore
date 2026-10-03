package sg.schoolmatch.persistence.dataset;

/** One row of {@code school_cca}. */
public record SchoolCcaRow(
        String schoolCode,
        String ccaName) {
}

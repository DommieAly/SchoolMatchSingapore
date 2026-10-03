package sg.schoolmatch.persistence.dataset;

/** One row of {@code dataset_warning}: a manifest warning, numbered from 1 in manifest order. */
public record DatasetWarningRow(
        String datasetVersion,
        int warningNo,
        String message) {
}

package sg.schoolmatch.persistence.dataset;

import java.time.Instant;
import java.time.LocalDate;

/** One row of {@code dataset_version}: a loaded snapshot version (manifest plus what the loader computed). */
public record DatasetVersionRow(
        String datasetVersion,
        String datasetKind,
        LocalDate effectiveDate,
        Instant importedAt,
        String manifestStatus,
        String loadStatus,
        String notes,
        String contentSha256,
        int loaderFormat,
        Instant loadedAt) {
}

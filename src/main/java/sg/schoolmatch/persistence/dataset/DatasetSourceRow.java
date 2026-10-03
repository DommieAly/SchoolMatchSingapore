package sg.schoolmatch.persistence.dataset;

import java.time.Instant;

/** One row of {@code dataset_source}: a manifest source, numbered from 1 in manifest order. */
public record DatasetSourceRow(
        String datasetVersion,
        int sourceNo,
        String sourceId,
        String sourceName,
        Instant downloadedAt) {
}

package sg.schoolmatch.persistence.dataset;

/** One row of {@code district} (entity {@code District}); {@code withdrawnInVersion} null = in the active dataset. */
public record DistrictRow(
        String planningAreaCode,
        String planningAreaName,
        String boundaryGeoJson,
        String withdrawnInVersion) {
}

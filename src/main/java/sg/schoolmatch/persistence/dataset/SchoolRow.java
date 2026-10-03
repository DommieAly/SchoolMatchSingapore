package sg.schoolmatch.persistence.dataset;

/** One row of {@code school} (entity {@code School} with its {@code Place} fields); {@code withdrawnInVersion} null = in the active dataset. */
public record SchoolRow(
        String schoolCode,
        String schoolName,
        String address,
        String postalCode,
        double latitude,
        double longitude,
        String telephone,
        String website,
        String email,
        String schoolType,
        String sessionType,
        String schoolNature,
        String planningAreaCode,
        String withdrawnInVersion) {
}

package sg.schoolmatch.dataset;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import sg.schoolmatch.entity.school.ValidationStatus;

/**
 * {@code manifest.json} of one snapshot folder (DC-09): what the snapshot is and where it came from.
 * {@code kind} is "seed" (small test snapshot) or "full" (the importer's output, 140–160 schools).
 * <p>
 * DC-84: {@code formatVersion} is the layout of {@code schools.json}. Format 2 has the {@code busServices} and
 * {@code mrtStations} arrays; format 1 (no {@code formatVersion} in the file) had the {@code busInfo} and
 * {@code nearestMrt} texts and is refused by {@link SnapshotValidator}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SnapshotManifest(
        Integer formatVersion,
        String kind,
        String version,
        LocalDate effectiveDate,
        Instant importedAt,
        List<Source> sources,
        ValidationStatus validationStatus,
        List<String> warnings,
        Map<String, Integer> counts,
        String notes) {

    /** The only snapshot format this app reads and the importer writes (DC-84). */
    public static final int FORMAT_VERSION = 2;
    /** What a manifest without {@code formatVersion} is: the format before DC-84. */
    public static final int FORMAT_VERSION_MISSING = 1;

    public static final String KIND_SEED = "seed";
    public static final String KIND_FULL = "full";

    public SnapshotManifest {
        sources = sources == null ? List.of() : List.copyOf(sources);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }

    /** {@code formatVersion}, or 1 when the file has none. */
    @JsonIgnore   // a derived value, not a manifest.json field
    public int formatVersionOrDefault() {
        return formatVersion == null ? FORMAT_VERSION_MISSING : formatVersion;
    }

    /** True for an importer snapshot, which must hold 140–160 schools. */
    @JsonIgnore   // a derived value, not a manifest.json field
    public boolean isFull() {
        return KIND_FULL.equalsIgnoreCase(kind);
    }

    /** One downloaded source, e.g. a data.gov.sg dataset id and when it was fetched. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String name, String datasetId, Instant downloadedAt) {
    }
}

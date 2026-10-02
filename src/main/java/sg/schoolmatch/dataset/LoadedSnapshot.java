package sg.schoolmatch.dataset;

import java.util.List;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.School;

/**
 * One snapshot folder as read by {@link SnapshotReader}: the raw records (checked by
 * {@link SnapshotValidator}) and the same data mapped to entities (used by SchoolDataController).
 *
 * @param location where it was read from, e.g. {@code classpath:fixtures/snapshot-mini/}
 */
public record LoadedSnapshot(
        String location,
        SnapshotManifest manifest,
        List<SchoolRecord> records,
        List<School> schools,
        List<District> districts) {

    public LoadedSnapshot {
        records = List.copyOf(records);
        schools = List.copyOf(schools);
        districts = List.copyOf(districts);
    }

    /** The manifest version, e.g. "0000-seed". */
    public String version() {
        return manifest.version();
    }
}

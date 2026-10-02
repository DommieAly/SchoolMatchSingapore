package sg.schoolmatch.dataset;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import sg.schoolmatch.config.AppProperties;

/** Helpers for the dataset tests: real AppProperties (with their defaults) without starting Spring. */
final class DatasetTestSupport {

    static final String MINI = "classpath:fixtures/snapshot-mini/";

    /** The error rules that have a broken fixture in fixtures/snapshot-broken/<rule>/. */
    static final List<String> BROKEN_RULES = List.of(
            SnapshotValidator.DUPLICATE_CODE, SnapshotValidator.BAD_COORDINATE, SnapshotValidator.BAD_PSLE_RANGE,
            SnapshotValidator.MISSING_NAME, SnapshotValidator.UNKNOWN_PLANNING_AREA);

    private DatasetTestSupport() {
    }

    /** {@code app.*} settings bound exactly as Spring would, e.g. {@code Map.of("app.dataset.dir", "x")}. */
    static AppProperties props(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("app", AppProperties.class);
    }

    static SnapshotReader reader(Map<String, String> values) {
        return new SnapshotReader(props(values), new DefaultResourceLoader());
    }

    static String broken(String rule) {
        return "classpath:fixtures/snapshot-broken/" + rule + "/";
    }

    /** A copy of {@code r} with other score ranges (records are immutable). */
    static SchoolRecord withRanges(SchoolRecord r, List<ScoreRangeRecord> ranges) {
        return new SchoolRecord(r.schoolCode(), r.name(), r.address(), r.postalCode(), r.latitude(), r.longitude(),
                r.telephone(), r.website(), r.email(), r.schoolType(), r.planningAreaCode(), r.planningAreaName(),
                r.nearestMrt(), r.busInfo(), r.sessionType(), r.schoolNature(), r.programmes(), r.ccas(),
                r.affiliatedPrimarySchools(), r.ipRangeNote(), ranges);
    }

    /** A copy of {@code r} with another code and coordinate. */
    static SchoolRecord withCodeAndCoordinate(SchoolRecord r, String code, Double latitude, Double longitude) {
        return new SchoolRecord(code, r.name(), r.address(), r.postalCode(), latitude, longitude,
                r.telephone(), r.website(), r.email(), r.schoolType(), r.planningAreaCode(), r.planningAreaName(),
                r.nearestMrt(), r.busInfo(), r.sessionType(), r.schoolNature(), r.programmes(), r.ccas(),
                r.affiliatedPrimarySchools(), r.ipRangeNote(), r.scoreRanges());
    }
}

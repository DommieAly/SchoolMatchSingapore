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
            SnapshotValidator.MISSING_NAME, SnapshotValidator.UNKNOWN_PLANNING_AREA, SnapshotValidator.TRANSPORT_LIST,
            SnapshotValidator.MOE_TEXT_FORMAT, SnapshotValidator.TOO_LONG, SnapshotValidator.DUPLICATE_NAME);

    /** DC-84: a format-1 snapshot (busInfo / nearestMrt text, no formatVersion), refused with rule "manifest". */
    static final String FORMAT_1 = "classpath:fixtures/snapshot-broken/format-1/";

    /**
     * snapshot-mini with one MRT station listed again with a trailing space, refused with rule "transport-list".
     * The loader trims each element, so without the rule the two copies would clash in pk_school_mrt_station.
     */
    static final String TRANSPORT_LIST_PADDED = "classpath:fixtures/snapshot-broken/transport-list-padded/";

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
                r.mrtStations(), r.busServices(), r.sessionType(), r.schoolNature(), r.programmes(), r.ccas(),
                r.affiliatedPrimarySchools(), r.ipRangeNote(), ranges);
    }

    /** A copy of {@code r} with another code and coordinate. */
    static SchoolRecord withCodeAndCoordinate(SchoolRecord r, String code, Double latitude, Double longitude) {
        return new SchoolRecord(code, r.name(), r.address(), r.postalCode(), latitude, longitude,
                r.telephone(), r.website(), r.email(), r.schoolType(), r.planningAreaCode(), r.planningAreaName(),
                r.mrtStations(), r.busServices(), r.sessionType(), r.schoolNature(), r.programmes(), r.ccas(),
                r.affiliatedPrimarySchools(), r.ipRangeNote(), r.scoreRanges());
    }

    /** A copy of {@code r} with other MRT stations and bus services (DC-84). */
    static SchoolRecord withTransport(SchoolRecord r, List<String> mrtStations, List<String> busServices) {
        return new SchoolRecord(r.schoolCode(), r.name(), r.address(), r.postalCode(), r.latitude(), r.longitude(),
                r.telephone(), r.website(), r.email(), r.schoolType(), r.planningAreaCode(), r.planningAreaName(),
                mrtStations, busServices, r.sessionType(), r.schoolNature(), r.programmes(), r.ccas(),
                r.affiliatedPrimarySchools(), r.ipRangeNote(), r.scoreRanges());
    }

    /** A copy of {@code r} with other text fields (DC-85 limits); null keeps the value. */
    static SchoolRecord withTexts(SchoolRecord r, String name, String postalCode, String telephone, List<String> ccas) {
        return new SchoolRecord(r.schoolCode(), name != null ? name : r.name(), r.address(),
                postalCode != null ? postalCode : r.postalCode(), r.latitude(), r.longitude(),
                telephone != null ? telephone : r.telephone(), r.website(), r.email(), r.schoolType(),
                r.planningAreaCode(), r.planningAreaName(), r.mrtStations(), r.busServices(), r.sessionType(),
                r.schoolNature(), r.programmes(), ccas != null ? ccas : r.ccas(), r.affiliatedPrimarySchools(),
                r.ipRangeNote(), r.scoreRanges());
    }

    /** A copy of {@code m} with other version, dates, sources and notes (DC-85). */
    static SnapshotManifest withManifest(SnapshotManifest m, String version, java.time.LocalDate effectiveDate,
                                         java.time.Instant importedAt, List<SnapshotManifest.Source> sources,
                                         String notes) {
        return new SnapshotManifest(m.formatVersion(), m.kind(), version, effectiveDate, importedAt, sources,
                m.validationStatus(), m.warnings(), m.counts(), notes);
    }

    /** A copy of {@code m} with another formatVersion (null = the field is missing). */
    static SnapshotManifest withFormat(SnapshotManifest m, Integer formatVersion) {
        return new SnapshotManifest(formatVersion, m.kind(), m.version(), m.effectiveDate(), m.importedAt(),
                m.sources(), m.validationStatus(), m.warnings(), m.counts(), m.notes());
    }
}

package sg.schoolmatch.persistence.dataset;

import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.dataset.LoadedSnapshot;
import sg.schoolmatch.dataset.ValidationReport;

/**
 * Writes a validated school snapshot into the school dataset tables (V2), versioned, idempotent and in one
 * transaction (docs/database-design.md, section 6.2; DC-83, DC-86). Plain JDBC with the same SQL on H2 and
 * PostgreSQL: no MERGE and no ON CONFLICT.
 * <p>
 * <p>
 * The read path ({@link #readActive()}, {@link #activeState()}) gives the active dataset back as rows; {@link
 * SchoolDatasetMapper} turns them into the entities. Only {@code SchoolDataController} uses this class
 * (ArchitectureTest rule 9).
 */
@Repository
public class SchoolDatasetStore {

    /**
     * The mapping from snapshot JSON to rows, stored as {@code dataset_version.loader_format}. Raise it by one
     * whenever that mapping changes: an unchanged snapshot is then loaded again, so new columns get filled.
     */
    public static final int FORMAT = 1;

    /** Rows per JDBC batch. */
    static final int BATCH_SIZE = 500;

    /**
     * The active version and its content hash ({@code active_dataset} joined with {@code dataset_version}). The
     * hourly check compares it with the cached one and rebuilds the cache when either differs (section 6.3).
     */
    public record ActiveState(String datasetVersion, String contentSha256) {
    }

    /** What {@link #ensureLoaded} did. */
    public enum Outcome {
        /** The snapshot was written and is now the active version. */
        LOADED,
        /** The snapshot was already the active version (same version, hash and loader format): nothing written. */
        UNCHANGED,
        /** The snapshot is older than the active version and rollback is off: the active version was kept. */
        KEPT_NEWER
    }

    private static final Logger log = LoggerFactory.getLogger(SchoolDatasetStore.class);

    /** The six school child tables, children of {@code school} only. */
    private static final List<String> CHILD_TABLES = List.of("indicative_psle_score_range", "school_cca",
            "school_programme", "school_affiliated_primary", "school_bus_service", "school_mrt_station");

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final boolean allowRollback;

    /** The app's store: {@code app.dataset.allow-rollback} from {@link AppProperties}. */
    @Autowired
    public SchoolDatasetStore(NamedParameterJdbcTemplate jdbc, Clock clock, AppProperties props) {
        this(jdbc, clock, props.dataset().allowRollback());
    }

    /**
     * @param allowRollback whether a snapshot older than the active version may replace it. Design section 6.2,
     *                      step 4: false only in {@code prod}.
     */
    public SchoolDatasetStore(NamedParameterJdbcTemplate jdbc, Clock clock, boolean allowRollback) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.allowRollback = allowRollback;
    }

    /**
     * Makes {@code snapshot} the active dataset unless it already is (design section 6.2, steps 2 to 9). One
     * transaction: any failure rolls everything back, and the previous version stays active.
     *
     * @param report the snapshot's validation; a FAILED snapshot is refused
     * @param sha256 {@code SnapshotReader.contentSha256(snapshot)}
     */
    @Transactional
    public Outcome ensureLoaded(LoadedSnapshot snapshot, ValidationReport report, String sha256) {
        if (!report.isUsable()) {
            throw new IllegalArgumentException("snapshot " + snapshot.version() + " FAILED validation and is never "
                    + "loaded: " + report);
        }
        String version = snapshot.version();

        // Step 2: lock the one active_dataset row; a second app instance waits here until this one commits.
        String active = jdbc.queryForObject(
                "SELECT dataset_version FROM active_dataset WHERE singleton_id = 1 FOR UPDATE", Map.of(), String.class);

        DatasetVersionRow known = findVersion(version);
        if (active != null) {
            DatasetVersionRow current = Objects.requireNonNull(findVersion(active), active);
            // Step 3: nothing to do (the normal start-up).
            if (active.equals(version) && current.contentSha256().equals(sha256)
                    && current.loaderFormat() == FORMAT) {
                return Outcome.UNCHANGED;
            }
            // Step 4: an older snapshot (an old image restarting during a rolling deploy) does not win.
            Instant importedAt = snapshot.manifest().importedAt();
            if (!allowRollback && importedAt != null && importedAt.isBefore(current.importedAt())) {
                log.warn("School dataset {} (imported {}) is older than the active {} (imported {}); kept {}. Set "
                        + "app.dataset.allow-rollback=true to load it.", version, importedAt, active,
                        current.importedAt(), active);
                return Outcome.KEPT_NEWER;
            }
        }

        SnapshotRows rows = SnapshotRows.of(snapshot, report, sha256, FORMAT, clock.instant());
        rows.checkCounts(snapshot.manifest().counts());

        // Step 5: the version row, its sources and warnings.
        if (known == null) {
            jdbc.update("INSERT INTO dataset_version (dataset_version, dataset_kind, effective_date, imported_at,"
                    + " manifest_status, load_status, notes, content_sha256, loader_format, loaded_at) VALUES (:v,"
                    + " :kind, :effective, :imported, :manifestStatus, :loadStatus, :notes, :sha, :format, :loaded)",
                    versionParams(rows.version()));
        } else {
            if (!known.contentSha256().equals(sha256)) {
                log.warn("School dataset {} was loaded before with other content (hash {}, now {}): the folder was "
                        + "rebuilt in place; loading it again", version, known.contentSha256(), sha256);
            }
            jdbc.update("UPDATE dataset_version SET dataset_kind = :kind, effective_date = :effective, imported_at ="
                    + " :imported, manifest_status = :manifestStatus, load_status = :loadStatus, notes = :notes,"
                    + " content_sha256 = :sha, loader_format = :format, loaded_at = :loaded WHERE dataset_version = :v",
                    versionParams(rows.version()));
        }
        MapSqlParameterSource byVersion = new MapSqlParameterSource().addValue("v", version, Types.VARCHAR);
        jdbc.update("DELETE FROM dataset_source WHERE dataset_version = :v", byVersion);
        jdbc.update("DELETE FROM dataset_warning WHERE dataset_version = :v", byVersion);
        batch("INSERT INTO dataset_source (dataset_version, source_no, source_id, source_name, downloaded_at)"
                + " VALUES (:v, :no, :id, :name, :downloaded)", rows.sources(), s -> new MapSqlParameterSource()
                .addValue("v", s.datasetVersion(), Types.VARCHAR).addValue("no", s.sourceNo(), Types.INTEGER)
                .addValue("id", s.sourceId(), Types.VARCHAR).addValue("name", s.sourceName(), Types.VARCHAR)
                .addValue("downloaded", timestamp(s.downloadedAt()), Types.TIMESTAMP_WITH_TIMEZONE));
        batch("INSERT INTO dataset_warning (dataset_version, warning_no, message) VALUES (:v, :no, :message)",
                rows.warnings(), w -> new MapSqlParameterSource()
                        .addValue("v", w.datasetVersion(), Types.VARCHAR).addValue("no", w.warningNo(), Types.INTEGER)
                        .addValue("message", w.message(), Types.VARCHAR));

        // Step 6: planning areas. Update the known codes, insert new ones, withdraw the missing ones.
        Set<String> knownAreas = codes("SELECT planning_area_code FROM district");
        Set<String> snapshotAreas = new HashSet<>();
        List<DistrictRow> newAreas = new ArrayList<>();
        List<DistrictRow> knownRows = new ArrayList<>();
        for (DistrictRow d : rows.districts()) {
            snapshotAreas.add(d.planningAreaCode());
            (knownAreas.contains(d.planningAreaCode()) ? knownRows : newAreas).add(d);
        }
        Function<DistrictRow, SqlParameterSource> districtParams = d -> new MapSqlParameterSource()
                .addValue("code", d.planningAreaCode(), Types.VARCHAR)
                .addValue("name", d.planningAreaName(), Types.VARCHAR)
                .addValue("boundary", d.boundaryGeoJson(), Types.VARCHAR);
        batch("UPDATE district SET planning_area_name = :name, boundary_geojson = :boundary,"
                + " withdrawn_in_version = NULL WHERE planning_area_code = :code", knownRows, districtParams);
        batch("INSERT INTO district (planning_area_code, planning_area_name, boundary_geojson) VALUES (:code, :name,"
                + " :boundary)", newAreas, districtParams);
        List<String> withdrawnAreas = withdraw("district", "planning_area_code", snapshotAreas, version);
        if (!withdrawnAreas.isEmpty()) {
            log.warn("School dataset {}: planning areas withdrawn (not in this snapshot): {}", version, withdrawnAreas);
        }

        // Step 7: schools, by the same rule; log who saved a withdrawn school.
        Set<String> knownSchools = codes("SELECT school_code FROM school");
        Set<String> snapshotSchools = new HashSet<>();
        List<SchoolRow> newSchools = new ArrayList<>();
        List<SchoolRow> knownSchoolRows = new ArrayList<>();
        for (SchoolRow s : rows.schools()) {
            snapshotSchools.add(s.schoolCode());
            (knownSchools.contains(s.schoolCode()) ? knownSchoolRows : newSchools).add(s);
        }
        batch("UPDATE school SET school_name = :name, address = :address, postal_code = :postal, latitude = :lat,"
                + " longitude = :lng, telephone = :telephone, website = :website, email = :email, school_type ="
                + " :type, session_type = :session, school_nature = :nature, planning_area_code = :area,"
                + " withdrawn_in_version = NULL WHERE school_code = :code", knownSchoolRows, SchoolDatasetStore::schoolParams);
        batch("INSERT INTO school (school_code, school_name, address, postal_code, latitude, longitude, telephone,"
                + " website, email, school_type, session_type, school_nature, planning_area_code) VALUES (:code,"
                + " :name, :address, :postal, :lat, :lng, :telephone, :website, :email, :type, :session, :nature,"
                + " :area)", newSchools, SchoolDatasetStore::schoolParams);
        for (String code : withdraw("school", "school_code", snapshotSchools, version)) {
            MapSqlParameterSource byCode = new MapSqlParameterSource().addValue("code", code, Types.VARCHAR);
            Integer shortlists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM shortlist_school WHERE school_code = :code", byCode, Integer.class);
            Integer plans = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM choice_plan_choice WHERE school_code = :code", byCode, Integer.class);
            log.warn("School dataset {}: school {} withdrawn (not in this snapshot); saved in {} shortlists and {} "
                    + "choice plans", version, code, shortlists, plans);
        }

        // Step 8: child rows always belong to the active dataset; a withdrawn school keeps only its school row.
        for (String table : CHILD_TABLES) {
            jdbc.update("DELETE FROM " + table, Map.of());
        }
        batch("INSERT INTO indicative_psle_score_range (school_code, admission_year, posting_group, affiliated,"
                + " integrated_programme, lower_score, upper_score, lower_hcl_grade, upper_hcl_grade, places_left)"
                + " VALUES (:code, :year, :pg, :affiliated, :ip, :lower, :upper, :lowerGrade, :upperGrade,"
                + " :placesLeft)", rows.scoreRanges(), r -> new MapSqlParameterSource()
                .addValue("code", r.schoolCode(), Types.VARCHAR).addValue("year", r.admissionYear(), Types.INTEGER)
                .addValue("pg", r.postingGroup(), Types.INTEGER).addValue("affiliated", r.affiliated(), Types.BOOLEAN)
                .addValue("ip", r.integratedProgramme(), Types.BOOLEAN)
                .addValue("lower", r.lowerScore(), Types.INTEGER).addValue("upper", r.upperScore(), Types.INTEGER)
                .addValue("lowerGrade", r.lowerHclGrade(), Types.VARCHAR)
                .addValue("upperGrade", r.upperHclGrade(), Types.VARCHAR)
                .addValue("placesLeft", r.placesLeft(), Types.BOOLEAN));
        batch("INSERT INTO school_cca (school_code, cca_name) VALUES (:code, :name)", rows.ccas(),
                r -> pair(r.schoolCode(), r.ccaName()));
        batch("INSERT INTO school_programme (school_code, programme_name) VALUES (:code, :name)", rows.programmes(),
                r -> pair(r.schoolCode(), r.programmeName()));
        batch("INSERT INTO school_affiliated_primary (school_code, primary_school_name) VALUES (:code, :name)",
                rows.affiliations(), r -> pair(r.schoolCode(), r.primarySchoolName()));
        batch("INSERT INTO school_bus_service (school_code, service_no, list_position) VALUES (:code, :name, :pos)",
                rows.busServices(), r -> pair(r.schoolCode(), r.serviceNo())
                        .addValue("pos", r.listPosition(), Types.INTEGER));
        batch("INSERT INTO school_mrt_station (school_code, station_name, list_position) VALUES (:code, :name, :pos)",
                rows.mrtStations(), r -> pair(r.schoolCode(), r.stationName())
                        .addValue("pos", r.listPosition(), Types.INTEGER));

        // Step 9: switch.
        jdbc.update("UPDATE active_dataset SET dataset_version = :v WHERE singleton_id = 1", byVersion);
        log.info("School dataset {} loaded ({} schools, {} planning areas, {} PSLE ranges; {} schools and {} areas "
                + "withdrawn){}", version, rows.schools().size(), rows.districts().size(), rows.scoreRanges().size(),
                knownSchools.size() + newSchools.size() - snapshotSchools.size(),
                knownAreas.size() + newAreas.size() - snapshotAreas.size(),
                active == null || active.equals(version) ? "" : "; was " + active);
        return Outcome.LOADED;
    }

    // ------------------------------------------------------------------------------------------------ read path

    /**
     * The stored name of each given school code that has a {@code school} row, active or withdrawn: the last-known
     * name of a school that left the dataset (design section 6.4, open decision 3). Codes without a row are left out.
     * One SELECT; an empty input runs none.
     */
    @Transactional(readOnly = true)
    public Map<String, String> schoolNames(Collection<String> schoolCodes) {
        if (schoolCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        jdbc.query("SELECT school_code, school_name FROM school WHERE school_code IN (:codes)",
                new MapSqlParameterSource("codes", Set.copyOf(schoolCodes)),
                (RowCallbackHandler) rs -> names.put(rs.getString(1), rs.getString(2)));
        return Map.copyOf(names);
    }

    /** The active version and its hash; empty before the first load. */
    @Transactional(readOnly = true)
    public Optional<ActiveState> activeState() {
        List<ActiveState> found = jdbc.query("SELECT a.dataset_version, v.content_sha256 FROM active_dataset a"
                + " JOIN dataset_version v ON v.dataset_version = a.dataset_version WHERE a.singleton_id = 1",
                Map.of(), (rs, n) -> new ActiveState(rs.getString(1), rs.getString(2)));
        return found.stream().findFirst();
    }

    /**
     * The active dataset as rows (design section 6.3): its version row, sources and warnings, the planning areas and
     * schools that are not withdrawn, and all child rows (they always belong to the active dataset, section 6.2 step
     * 8). Empty before the first load. About ten SELECTs in one read-only transaction; REPEATABLE READ, so a load
     * committed by another instance in between cannot mix two versions. The rows come in no particular order: the
     * mapper sorts in Java, because H2 and PostgreSQL sort text differently.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<SnapshotRows> readActive() {
        String active = jdbc.queryForObject("SELECT dataset_version FROM active_dataset WHERE singleton_id = 1",
                Map.of(), String.class);
        if (active == null) {
            return Optional.empty();
        }
        DatasetVersionRow version = Objects.requireNonNull(findVersion(active), active);
        MapSqlParameterSource byVersion = new MapSqlParameterSource().addValue("v", active, Types.VARCHAR);
        List<DatasetSourceRow> sources = jdbc.query("SELECT dataset_version, source_no, source_id, source_name,"
                + " downloaded_at FROM dataset_source WHERE dataset_version = :v", byVersion, (rs, n) ->
                new DatasetSourceRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        instant(rs.getObject(5, OffsetDateTime.class))));
        List<DatasetWarningRow> warnings = jdbc.query("SELECT dataset_version, warning_no, message FROM"
                + " dataset_warning WHERE dataset_version = :v", byVersion, (rs, n) ->
                new DatasetWarningRow(rs.getString(1), rs.getInt(2), rs.getString(3)));
        List<DistrictRow> districts = jdbc.query("SELECT planning_area_code, planning_area_name, boundary_geojson,"
                + " withdrawn_in_version FROM district WHERE withdrawn_in_version IS NULL", Map.of(), (rs, n) ->
                new DistrictRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        List<SchoolRow> schools = jdbc.query("SELECT school_code, school_name, address, postal_code, latitude,"
                + " longitude, telephone, website, email, school_type, session_type, school_nature,"
                + " planning_area_code, withdrawn_in_version FROM school WHERE withdrawn_in_version IS NULL",
                Map.of(), (rs, n) -> new SchoolRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getDouble(5), rs.getDouble(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13),
                        rs.getString(14)));
        List<ScoreRangeRow> ranges = jdbc.query("SELECT school_code, admission_year, posting_group, affiliated,"
                + " integrated_programme, lower_score, upper_score, lower_hcl_grade, upper_hcl_grade, places_left"
                + " FROM indicative_psle_score_range", Map.of(), (rs, n) -> new ScoreRangeRow(rs.getString(1),
                rs.getInt(2), rs.getInt(3), rs.getBoolean(4), rs.getBoolean(5), rs.getInt(6), rs.getInt(7),
                rs.getString(8), rs.getString(9), rs.getObject(10, Boolean.class)));
        List<SchoolCcaRow> ccas = jdbc.query("SELECT school_code, cca_name FROM school_cca", Map.of(),
                (rs, n) -> new SchoolCcaRow(rs.getString(1), rs.getString(2)));
        List<SchoolProgrammeRow> programmes = jdbc.query("SELECT school_code, programme_name FROM school_programme",
                Map.of(), (rs, n) -> new SchoolProgrammeRow(rs.getString(1), rs.getString(2)));
        List<SchoolAffiliatedPrimaryRow> affiliations = jdbc.query("SELECT school_code, primary_school_name FROM"
                + " school_affiliated_primary", Map.of(),
                (rs, n) -> new SchoolAffiliatedPrimaryRow(rs.getString(1), rs.getString(2)));
        List<SchoolBusServiceRow> buses = jdbc.query("SELECT school_code, service_no, list_position FROM"
                + " school_bus_service", Map.of(),
                (rs, n) -> new SchoolBusServiceRow(rs.getString(1), rs.getString(2), rs.getInt(3)));
        List<SchoolMrtStationRow> stations = jdbc.query("SELECT school_code, station_name, list_position FROM"
                + " school_mrt_station", Map.of(),
                (rs, n) -> new SchoolMrtStationRow(rs.getString(1), rs.getString(2), rs.getInt(3)));
        return Optional.of(new SnapshotRows(version, sources, warnings, districts, schools, ranges, ccas, programmes,
                affiliations, buses, stations));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private DatasetVersionRow findVersion(String version) {
        List<DatasetVersionRow> found = jdbc.query("SELECT * FROM dataset_version WHERE dataset_version = :v",
                new MapSqlParameterSource().addValue("v", version, Types.VARCHAR), (rs, n) -> new DatasetVersionRow(
                        rs.getString("dataset_version"), rs.getString("dataset_kind"),
                        rs.getObject("effective_date", java.time.LocalDate.class),
                        instant(rs.getObject("imported_at", OffsetDateTime.class)), rs.getString("manifest_status"),
                        rs.getString("load_status"), rs.getString("notes"), rs.getString("content_sha256"),
                        rs.getInt("loader_format"), instant(rs.getObject("loaded_at", OffsetDateTime.class))));
        return found.isEmpty() ? null : found.getFirst();
    }

    private Set<String> codes(String sql) {
        return new HashSet<>(jdbc.queryForList(sql, Map.of(), String.class));
    }

    /**
     * Sets {@code withdrawn_in_version} on every active row of {@code table} whose key is not in {@code keep}
     * (rows are never deleted, design section 6.4); returns the withdrawn keys, sorted.
     */
    private List<String> withdraw(String table, String keyColumn, Set<String> keep, String version) {
        List<String> missing = new ArrayList<>(codes("SELECT " + keyColumn + " FROM " + table
                + " WHERE withdrawn_in_version IS NULL"));
        missing.removeAll(keep);
        missing.sort(null);   // in Java: H2 and PostgreSQL sort text differently (design section 6.3)
        batch("UPDATE " + table + " SET withdrawn_in_version = :v WHERE " + keyColumn + " = :code", missing,
                code -> new MapSqlParameterSource().addValue("v", version, Types.VARCHAR)
                        .addValue("code", code, Types.VARCHAR));
        return missing;
    }

    private <T> void batch(String sql, List<T> rows, Function<T, SqlParameterSource> params) {
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            List<T> chunk = rows.subList(from, Math.min(rows.size(), from + BATCH_SIZE));
            jdbc.batchUpdate(sql, chunk.stream().map(params).toArray(SqlParameterSource[]::new));
        }
    }

    private static MapSqlParameterSource versionParams(DatasetVersionRow r) {
        return new MapSqlParameterSource()
                .addValue("v", r.datasetVersion(), Types.VARCHAR).addValue("kind", r.datasetKind(), Types.VARCHAR)
                .addValue("effective", r.effectiveDate(), Types.DATE)
                .addValue("imported", timestamp(r.importedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("manifestStatus", r.manifestStatus(), Types.VARCHAR)
                .addValue("loadStatus", r.loadStatus(), Types.VARCHAR).addValue("notes", r.notes(), Types.VARCHAR)
                .addValue("sha", r.contentSha256(), Types.VARCHAR).addValue("format", r.loaderFormat(), Types.INTEGER)
                .addValue("loaded", timestamp(r.loadedAt()), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    private static SqlParameterSource schoolParams(SchoolRow s) {
        return new MapSqlParameterSource()
                .addValue("code", s.schoolCode(), Types.VARCHAR).addValue("name", s.schoolName(), Types.VARCHAR)
                .addValue("address", s.address(), Types.VARCHAR).addValue("postal", s.postalCode(), Types.VARCHAR)
                .addValue("lat", s.latitude(), Types.DOUBLE).addValue("lng", s.longitude(), Types.DOUBLE)
                .addValue("telephone", s.telephone(), Types.VARCHAR).addValue("website", s.website(), Types.VARCHAR)
                .addValue("email", s.email(), Types.VARCHAR).addValue("type", s.schoolType(), Types.VARCHAR)
                .addValue("session", s.sessionType(), Types.VARCHAR).addValue("nature", s.schoolNature(), Types.VARCHAR)
                .addValue("area", s.planningAreaCode(), Types.VARCHAR);
    }

    private static MapSqlParameterSource pair(String code, String name) {
        return new MapSqlParameterSource().addValue("code", code, Types.VARCHAR).addValue("name", name, Types.VARCHAR);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}

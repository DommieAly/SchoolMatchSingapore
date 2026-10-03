package sg.schoolmatch.control;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import sg.schoolmatch.boundary.external.DataGovSgInterface;
import sg.schoolmatch.boundary.external.DataGovSgRecord;
import sg.schoolmatch.boundary.external.OneMapInterface;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.dataset.CuratedCsvReader;
import sg.schoolmatch.dataset.CuratedCsvReader.CuratedData;
import sg.schoolmatch.dataset.CuratedCsvReader.PsleRangeRow;
import sg.schoolmatch.dataset.DistrictLocator;
import sg.schoolmatch.dataset.ImportLog;
import sg.schoolmatch.dataset.IpRangeNotes;
import sg.schoolmatch.dataset.LoadedSnapshot;
import sg.schoolmatch.dataset.NameNormaliser;
import sg.schoolmatch.dataset.SchoolGeocoder;
import sg.schoolmatch.dataset.SchoolGeocoder.GeocodeResult;
import sg.schoolmatch.dataset.SchoolRecord;
import sg.schoolmatch.dataset.SchoolRecordJoiner;
import sg.schoolmatch.dataset.SchoolRecordJoiner.JoinedSchool;
import sg.schoolmatch.dataset.ScoreRangeRecord;
import sg.schoolmatch.dataset.SnapshotManifest;
import sg.schoolmatch.dataset.SnapshotReader;
import sg.schoolmatch.dataset.SnapshotValidator;
import sg.schoolmatch.dataset.SnapshotWriter;
import sg.schoolmatch.dataset.TransportLists;
import sg.schoolmatch.dataset.ValidationReport;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.error.NotFoundException;
import sg.schoolmatch.persistence.dataset.SchoolDatasetMapper;
import sg.schoolmatch.persistence.dataset.SchoolDatasetStore;
import sg.schoolmatch.persistence.dataset.SnapshotRows;

/**
 * Design class «control» SchoolDataController — serves the active, validated school dataset
 * (FR-DATA-03, NFR-DATA-01). Every other control reads schools through this class.
 * DC-09: exposes the dataset metadata; DC-12: {@link #importDataset()} builds a new snapshot from data.gov.sg.
 * <p>
 * DC-83 (docs/database-design.md, sections 6.2 and 6.3): the school data is served from the database.
 * <ul>
 *   <li>Start-up ({@code app.dataset.load-on-startup}, default true): the snapshot ({@code ACTIVE}, or
 *       {@code app.dataset.snapshot-location}) is read and validated; a FAILED snapshot stops the app from starting.
 *       {@link SchoolDatasetStore#ensureLoaded} then makes it the database's active version unless it already is
 *       (normally it is, and nothing is written).</li>
 *   <li>The cache and the manifest are always built from the database ({@link SchoolDatasetStore#readActive()},
 *       {@link SchoolDatasetMapper}), never from the files.</li>
 *   <li>After {@code app.dataset.recheck-after} (1 h) the next call reads the database's active version and hash, and
 *       rebuilds the cache when either changed (another instance loaded a version). It never reads files: a changed
 *       {@code ACTIVE} is picked up on restart.</li>
 *   <li>{@code load-on-startup: false} (the import profile) reads no file at start-up and builds the cache from the
 *       database on first use.</li>
 * </ul>
 */
@Service
public class SchoolDataController {

    static final String SOURCE_NAME = "SchoolMatch snapshot";

    /** DC-74: why the PSLE filter, SAFE/MATCH/REACH and PSLE fit are not used when {@link #hasPsleData()} is false. */
    public static final String NO_PSLE_DATA_MESSAGE = "PSLE score ranges are not available in the current dataset";

    // data.gov.sg datasets used by the importer (data/README.md)
    static final String SCHOOLS_DATASET = "d_688b934f82c1059ed0a6993d2a829089";
    static final String CCAS_DATASET = "d_9aba12b5527843afb0b2e8e4ed6ac6bd";
    static final String SUBJECTS_DATASET = "d_f1d144e423570c9d84dbc5102c2e664d";
    static final String PLANNING_AREAS_DATASET = "d_4765db0e87b9c86336792efe8a1f7a66";
    static final String ONEMAP_SOURCE_ID = "onemap-elastic-search";
    static final String CURATED_SOURCE_ID = "data/curated";

    private static final Logger log = LoggerFactory.getLogger(SchoolDataController.class);
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    private final DataGovSgInterface dataGovSg;
    private final OneMapInterface oneMap;
    private final SnapshotReader snapshotReader;
    private final SnapshotValidator snapshotValidator;
    private final CuratedCsvReader curatedCsvReader;
    private final SnapshotWriter snapshotWriter;
    private final AppProperties props;
    private final Clock clock;
    private final SchoolDatasetStore datasetStore;

    /** What is served; replaced as a whole by {@link #rebuildFromDatabase} (null until the first build). */
    private volatile Served served;

    /** The cache, its manifest, and the database state they were built from. */
    private record Served(SchoolDataCache dataset, SnapshotManifest manifest, SchoolDatasetStore.ActiveState state) {
    }

    public SchoolDataController(DataGovSgInterface dataGovSg, OneMapInterface oneMap, SnapshotReader snapshotReader,
                                SnapshotValidator snapshotValidator, CuratedCsvReader curatedCsvReader,
                                SnapshotWriter snapshotWriter, AppProperties props, Clock clock,
                                SchoolDatasetStore datasetStore) {
        this.dataGovSg = dataGovSg;
        this.oneMap = oneMap;
        this.snapshotReader = snapshotReader;
        this.snapshotValidator = snapshotValidator;
        this.curatedCsvReader = curatedCsvReader;
        this.snapshotWriter = snapshotWriter;
        this.props = props;
        this.clock = clock;
        this.datasetStore = datasetStore;
        if (props.dataset().loadOnStartup()) {
            loadAtStartUp();
        }
    }

    /** All schools, sorted by name then code. */
    public List<School> getSchools() {
        return current().dataset().getSchools();
    }

    /**
     * One school by code.
     *
     * @throws sg.schoolmatch.error.NotFoundException when the code is not in the dataset
     */
    public School getSchool(String schoolCode) {
        return current().dataset().findByCode(schoolCode)
                .orElseThrow(() -> new NotFoundException("No school with code '" + schoolCode + "'"));
    }

    /** Planning areas with their boundaries (FR-MAP-06, DC-04). */
    public List<District> getDistricts() {
        return current().dataset().getDistricts();
    }

    /**
     * DC-74: true when the active dataset has at least one PSLE score range ({@link SchoolDataCache#hasPsleData()}).
     * The one place every page and control asks; false for a snapshot built without curated ranges.
     */
    public boolean hasPsleData() {
        return current().dataset().hasPsleData();
    }

    /**
     * True when the active dataset has curated affiliated primary schools ({@link SchoolDataCache#hasAffiliationData()}):
     * a school with none then has none (the details page says "None"), not unknown ("Not available").
     */
    public boolean hasAffiliationData() {
        return current().dataset().hasAffiliationData();
    }

    /** DC-09: the loaded dataset with its version, date and status (footer, about page). */
    public SchoolDataCache getActiveDataset() {
        return current().dataset();
    }

    /**
     * The manifest of the active snapshot: sources with dataset ids and download times, counts, warnings, notes
     * (the {@code /about/data} page and the Singapore Open Data Licence notice).
     */
    public SnapshotManifest getActiveManifest() {   // DC-52
        return current().manifest();
    }

    /**
     * The last-known name of each given code that is not in the active dataset but still has a database row: a
     * school that was withdrawn (design section 6.4, open decision 3; DC-86). Shortlist and plan pages show it next
     * to the code. Codes in the active dataset and codes the database never had are left out. When the database
     * cannot be read, the answer is empty, so the pages fall back to showing the code alone.
     */
    public Map<String, String> getLastKnownSchoolNames(Collection<String> schoolCodes) {
        SchoolDataCache dataset = current().dataset();
        List<String> missing = schoolCodes.stream().filter(code -> dataset.findByCode(code).isEmpty()).toList();
        if (missing.isEmpty()) {
            return Map.of();
        }
        try {
            return datasetStore.schoolNames(missing);
        } catch (DataAccessException e) {
            log.warn("Could not read the last-known names of {} school codes: {}", missing.size(), e.getMessage());
            return Map.of();
        }
    }

    // ------------------------------------------------------------------ importer (DC-12)

    /**
     * DC-12: builds a new full snapshot from data.gov.sg, OneMap and {@code data/curated/*.csv}, and writes it to
     * {@code <app.dataset.import-output-dir>/<yyyy-MM-dd>.<n>/} (manifest.json, schools.json, districts.geojson,
     * validation-report.md, import-log.txt). Run by the import profile ({@code DatasetImportRunner}).
     * <ol>
     *   <li>Fetch the four datasets; join CCAs and subjects by normalised name ({@link SchoolRecordJoiner}).</li>
     *   <li>schoolCode from {@code school-codes.csv}, else a slug of the name (warning).</li>
     *   <li>Coordinate by postal code ({@link SchoolGeocoder}); planning area by {@link District#contains},
     *       with a warning when it disagrees with the dataset's {@code dgp_code}.</li>
     *   <li>Checked PSLE ranges and affiliations from the curated CSVs (none yet → no school has ranges).</li>
     *   <li>DC-84: bus and MRT texts split into lists ({@link TransportLists}, with {@code transport-overrides.csv});
     *       the snapshot is written in format {@value SnapshotManifest#FORMAT_VERSION}.</li>
     *   <li>Validate as kind "full" with {@link SnapshotValidator}; write the folder even when FAILED, for review.</li>
     *   <li>Update {@code ACTIVE} only when {@code app.dataset.activate-on-import=true} and the status is not FAILED.</li>
     * </ol>
     * The running app keeps serving the database's active dataset; the new snapshot is loaded into the database when an
     * app starts with ACTIVE pointing at it (DC-83). This method never reads or writes the database.
     *
     * @return the validator's errors, plus the importer's and validator's warnings
     * @throws sg.schoolmatch.error.ExternalServiceUnavailableException when data.gov.sg cannot be read (nothing is written)
     */
    public ValidationReport importDataset() {
        ImportLog importLog = new ImportLog();
        Instant started = clock.instant();
        importLog.info("SchoolMatch dataset import started " + started);
        CuratedData curated = curatedCsvReader.read();
        curated.problems().forEach(p -> importLog.warn(ImportLog.CURATED, p));

        List<SnapshotManifest.Source> sources = new ArrayList<>();
        List<DataGovSgRecord> schoolRows = dataGovSg.fetchSchools();
        sources.add(new SnapshotManifest.Source("data.gov.sg General information of schools", SCHOOLS_DATASET,
                clock.instant()));
        List<DataGovSgRecord> ccaRows = dataGovSg.fetchSchoolCcas();
        sources.add(new SnapshotManifest.Source("data.gov.sg Co-curricular activities (CCAs)", CCAS_DATASET,
                clock.instant()));
        List<DataGovSgRecord> subjectRows = dataGovSg.fetchSchoolSubjects();
        sources.add(new SnapshotManifest.Source("data.gov.sg Subjects Offered", SUBJECTS_DATASET, clock.instant()));
        List<District> fullDistricts = DistrictLocator.parseFeatureCollection(dataGovSg.fetchDistrictsGeoJson());
        sources.add(new SnapshotManifest.Source("data.gov.sg Master Plan 2019 Planning Area Boundary (No Sea)",
                PLANNING_AREAS_DATASET, clock.instant()));
        importLog.info("data.gov.sg: " + schoolRows.size() + " secondary and mixed-level schools, " + ccaRows.size()
                + " CCA rows, " + subjectRows.size() + " subject rows, " + fullDistricts.size() + " planning areas");

        NameNormaliser names = new NameNormaliser(curated.aliases());
        List<JoinedSchool> joined = new SchoolRecordJoiner(names, curated.subjectExclusions())
                .join(schoolRows, ccaRows, subjectRows, importLog);
        CuratedLookups lookups = curatedLookups(curated, names, importLog);
        SchoolGeocoder geocoder = new SchoolGeocoder(oneMap, curated.geocodeOverrides());
        DistrictLocator locator = new DistrictLocator(fullDistricts);
        TransportLists transport = new TransportLists(curated.transportOverrides());
        List<SchoolRecord> records = new ArrayList<>();
        for (JoinedSchool school : joined) {
            records.add(buildRecord(school, lookups, geocoder, locator, transport, importLog));
        }
        sources.add(new SnapshotManifest.Source("OneMap search (coordinates by postal code)", ONEMAP_SOURCE_ID,
                clock.instant()));
        sources.add(new SnapshotManifest.Source("SchoolMatch curated CSV files (school codes, name aliases, "
                + "PSLE ranges, geocode overrides, affiliations, subject exclusions, transport overrides)",
                CURATED_SOURCE_ID, started));
        warnUnknownCodes(curated, records, importLog);

        String districtsGeoJson = snapshotWriter.districtsGeoJson(fullDistricts, SnapshotWriter.MAX_DISTRICTS_BYTES);
        List<District> storedDistricts = DistrictLocator.parseFeatureCollection(districtsGeoJson);
        checkSimplifiedBoundaries(records, storedDistricts, importLog);

        // Validate exactly what will be written.
        LocalDate today = LocalDate.ofInstant(clock.instant(), SINGAPORE);
        Path outputDir = Path.of(props.dataset().resolvedImportOutputDir());
        String version = snapshotWriter.nextVersion(outputDir, today);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("schools", records.size());
        counts.put("districts", storedDistricts.size());
        counts.put("scoreRanges", records.stream().mapToInt(r -> r.scoreRanges().size()).sum());
        String notes = counts.get("scoreRanges") == 0
                ? "No PSLE ranges yet: data/curated/psle-ranges.csv has no checked rows, so every page shows the "
                        + "ranges as Not available."
                : "PSLE ranges come from data/curated/psle-ranges.csv (MOE SchoolFinder; each value read twice by "
                        + "independent scripts and spot-checked by hand, see data/README.md). They are historical, "
                        + "not a guarantee.";
        SnapshotManifest draft = new SnapshotManifest(SnapshotManifest.FORMAT_VERSION, SnapshotManifest.KIND_FULL,
                version, today, clock.instant(), sources, null, List.of(), counts, notes);
        LoadedSnapshot candidate = new LoadedSnapshot(outputDir.resolve(version).toUri().toString(), draft, records,
                records.stream().map(SnapshotReader::toSchool).toList(), storedDistricts);
        ValidationReport validation = snapshotValidator.validate(candidate);
        List<String> allWarnings = new ArrayList<>(importLog.warnings());
        allWarnings.addAll(validation.getWarnings());
        ValidationReport report = new ValidationReport(validation.getErrors(), allWarnings);

        List<String> manifestWarnings = new ArrayList<>(importLog.summary());
        manifestWarnings.addAll(summariseByRule(validation.getWarnings()));
        SnapshotManifest manifest = new SnapshotManifest(SnapshotManifest.FORMAT_VERSION, SnapshotManifest.KIND_FULL,
                version, today, clock.instant(), sources, report.getStatus(), manifestWarnings, counts, notes);

        boolean activate = props.dataset().activateOnImport() && report.isUsable();
        importLog.info("Validation: " + report.getStatus() + " (" + report.getErrors().size() + " errors, "
                + report.getWarnings().size() + " warnings)");
        report.getErrors().forEach(e -> importLog.info("ERROR " + e));
        importLog.info(activate ? "ACTIVE updated to " + version
                : "ACTIVE not changed (" + (report.isUsable() ? "app.dataset.activate-on-import is false"
                        : "the snapshot FAILED validation") + ")");
        Map<String, Integer> reportCounts = new LinkedHashMap<>(counts);
        reportCounts.putAll(importLog.counts());
        Path folder = snapshotWriter.write(outputDir, version, manifest, records, districtsGeoJson,
                SnapshotWriter.validationReportMarkdown(version, report, reportCounts), importLog.text());
        if (activate) {
            snapshotWriter.activate(outputDir, version);
        }
        log.info("Imported snapshot {} to {}: {} ({} schools, {} errors, {} warnings); ACTIVE {}", version,
                folder.toAbsolutePath(), report.getStatus(), records.size(), report.getErrors().size(),
                report.getWarnings().size(), activate ? "updated" : "not changed");
        return report;
    }

    /** One joined school → its schools.json record (code, coordinate, planning area, curated data). */
    private SchoolRecord buildRecord(JoinedSchool school, CuratedLookups lookups, SchoolGeocoder geocoder,
                                     DistrictLocator locator, TransportLists transport, ImportLog importLog) {
        DataGovSgRecord row = school.row();
        String name = clean(row.get("school_name"));
        String code = lookups.codesByName().get(school.name());
        if (code == null) {
            code = NameNormaliser.slug(name);
            importLog.warn(ImportLog.SCHOOL_CODE_SLUG, name + " → " + code + " (no row in "
                    + CuratedCsvReader.SCHOOL_CODES + "; add a row to it with this code, so the code stays the same"
                    + " in later imports)");
        }

        GeocodeResult geocode = geocoder.locate(code, name, row.get("postal_code"));
        importLog.increment("geocode-outcome-" + geocode.outcome().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        switch (geocode.outcome()) {
            case SINGLE_HIT -> importLog.warn(ImportLog.GEOCODE_SINGLE_HIT, code + ": " + geocode.detail());
            case SAME_PLACE -> importLog.warn(ImportLog.GEOCODE_SAME_PLACE, code + ": " + geocode.detail());
            case AMBIGUOUS -> importLog.warn(ImportLog.GEOCODE_AMBIGUOUS, code + ": " + geocode.detail());
            case FAILED -> importLog.warn(ImportLog.GEOCODE_FAILED, code + ": " + geocode.detail());
            default -> importLog.info(code + ": coordinate " + geocode.outcome() + " (" + geocode.detail() + ")");
        }
        Coordinate point = geocode.coordinate();

        String dgpCode = clean(row.get("dgp_code"));
        District district = point == null ? null : locator.locate(point).orElse(null);
        if (point != null && district == null) {
            district = locator.byName(dgpCode).orElse(null);
            importLog.warn(ImportLog.DISTRICT_NOT_FOUND, code + ": " + point + " is in no planning area"
                    + (district != null ? "; used its dgp_code " + dgpCode : " and its dgp_code " + dgpCode
                    + " names none"));
        } else if (district != null && dgpCode != null && !DistrictLocator.sameArea(dgpCode, district.getPlanningAreaName())) {
            importLog.warn(ImportLog.DISTRICT_MISMATCH, code + " is in " + district.getPlanningAreaName()
                    + " by its coordinate, but its dgp_code says " + dgpCode);
        }

        return new SchoolRecord(code, name, clean(row.get("address")),
                SchoolGeocoder.normalisePostalCode(row.get("postal_code")),
                point == null ? null : round6(point.getLatitude()), point == null ? null : round6(point.getLongitude()),
                clean(row.get("telephone_no")), clean(row.get("url_address")), clean(row.get("email_address")),
                clean(row.get("type_code")),
                district == null ? null : district.getPlanningAreaCode(),
                district == null ? null : district.getPlanningAreaName(),
                transport.elements(TransportLists.Kind.MRT, code, clean(row.get("mrt_desc")), importLog),
                transport.elements(TransportLists.Kind.BUS, code, clean(row.get("bus_desc")), importLog),
                clean(row.get("session_code")),
                clean(row.get("nature_code")), school.programmes(), school.ccas(),
                lookups.affiliationsByCode().getOrDefault(code, List.of()),
                IpRangeNotes.of(lookups.rangesByCode().getOrDefault(code, List.of())),   // DC-85: computed
                lookups.rangesByCode().getOrDefault(code, List.of()));
    }

    /** The curated CSV rows indexed for {@link #buildRecord}; unusable rows become warnings. */
    private record CuratedLookups(Map<String, String> codesByName, Map<String, List<ScoreRangeRecord>> rangesByCode,
                                  Map<String, List<String>> affiliationsByCode) {
    }

    private static CuratedLookups curatedLookups(CuratedData curated, NameNormaliser names, ImportLog importLog) {
        Map<String, String> codes = new HashMap<>();
        curated.schoolCodes().forEach(row -> {
            String key = names.canonical(NameNormaliser.SCHOOLS, row.schoolName());
            if (codes.putIfAbsent(key, row.schoolCode()) != null) {
                importLog.warn(ImportLog.CURATED, CuratedCsvReader.SCHOOL_CODES + ": " + key
                        + " has more than one row; the first is used");
            }
        });
        Map<String, List<ScoreRangeRecord>> ranges = new HashMap<>();
        Map<String, List<ScoreRangeRecord>> ipRanges = new HashMap<>();
        for (PsleRangeRow row : curated.psleRanges()) {
            String label = row.schoolCode() + " " + row.admissionYear() + " PG" + row.postingGroup() + " " + row.track();
            if (!row.isChecked()) {
                importLog.warn(ImportLog.CURATED, CuratedCsvReader.PSLE_RANGES + ": " + label
                        + " not used: not checked by a second person");
            } else if (CuratedCsvReader.TRACK_IP.equals(row.track())
                    || CuratedCsvReader.TRACK_IP_AFFILIATED.equals(row.track())) {
                // DC-77: an IP row is a range too (the PG3 fallback; MOE files IP under PG3), and its MOE text
                // stays the details-page note (DC-18, built by IpRangeNotes), e.g. "4(D) - 8(M)". DC-82:
                // IP_AFFILIATED is the affiliated IP value (Nanyang Girls').
                boolean affiliatedIp = CuratedCsvReader.TRACK_IP_AFFILIATED.equals(row.track());
                ipRanges.computeIfAbsent(row.schoolCode(), c -> new ArrayList<>()).add(new ScoreRangeRecord(
                        row.admissionYear(), row.postingGroup(), affiliatedIp, row.lower(), row.upper(), true,
                        row.rawText()));
            } else {
                ranges.computeIfAbsent(row.schoolCode(), c -> new ArrayList<>()).add(new ScoreRangeRecord(
                        row.admissionYear(), row.postingGroup(), CuratedCsvReader.TRACK_AFFILIATED.equals(row.track()),
                        row.lower(), row.upper(), false, row.rawText()));
            }
        }
        // IP ranges after the others, so the details table lists them last within a year and posting group.
        ipRanges.forEach((code, list) -> ranges.computeIfAbsent(code, c -> new ArrayList<>()).addAll(list));
        Map<String, List<String>> affiliations = new HashMap<>();
        curated.affiliations().forEach(a -> affiliations.computeIfAbsent(a.schoolCode(), c -> new ArrayList<>())
                .add(NameNormaliser.normalise(a.primarySchool())));
        return new CuratedLookups(codes, ranges, affiliations);
    }

    /** Curated rows that name a school code the import did not produce (a typo, or a school that closed). */
    private static void warnUnknownCodes(CuratedData curated, List<SchoolRecord> records, ImportLog importLog) {
        Set<String> codes = new HashSet<>();
        records.forEach(r -> codes.add(r.schoolCode()));
        Set<String> reported = new HashSet<>();
        curated.psleRanges().forEach(r -> warnUnknown(CuratedCsvReader.PSLE_RANGES, r.schoolCode(), codes, reported, importLog));
        curated.affiliations().forEach(a -> warnUnknown(CuratedCsvReader.AFFILIATIONS, a.schoolCode(), codes, reported, importLog));
        curated.geocodeOverrides().forEach(o -> warnUnknown(CuratedCsvReader.GEOCODE_OVERRIDES, o.schoolCode(), codes, reported, importLog));
        curated.transportOverrides().forEach(o -> warnUnknown(CuratedCsvReader.TRANSPORT_OVERRIDES, o.schoolCode(), codes, reported, importLog));
    }

    private static void warnUnknown(String file, String code, Set<String> codes, Set<String> reported, ImportLog importLog) {
        if (code != null && !codes.contains(code) && reported.add(file + code)) {
            importLog.warn(ImportLog.CURATED, file + ": school_code " + code + " is not in the imported schools");
        }
    }

    /** The snapshot stores simplified boundaries; report a school that ends up just outside its own area. */
    private static void checkSimplifiedBoundaries(List<SchoolRecord> records, List<District> storedDistricts,
                                                  ImportLog importLog) {
        Map<String, District> byCode = new HashMap<>();
        storedDistricts.forEach(d -> byCode.put(d.getPlanningAreaCode(), d));
        for (SchoolRecord r : records) {
            District area = r.planningAreaCode() == null ? null : byCode.get(r.planningAreaCode());
            if (area != null && r.latitude() != null && r.longitude() != null
                    && !area.contains(new Coordinate(r.latitude(), r.longitude()))) {
                importLog.warn(ImportLog.DISTRICT_SIMPLIFIED, r.schoolCode() + " lies just outside the simplified "
                        + area.getPlanningAreaName() + " boundary (the map may draw it outside)");
            }
        }
    }

    /** "rule: N warnings, e.g. …" per rule, like {@link ImportLog#summary()}, for the validator's warnings. */
    private static List<String> summariseByRule(List<String> warnings) {
        Map<String, List<String>> byRule = new LinkedHashMap<>();
        warnings.forEach(w -> byRule.computeIfAbsent(w.substring(0, Math.max(0, w.indexOf(':'))), r -> new ArrayList<>())
                .add(w));
        List<String> summary = new ArrayList<>();
        byRule.forEach((rule, list) -> summary.add(list.size() == 1 ? list.getFirst()
                : rule + ": " + list.size() + " warnings, e.g. " + list.getFirst().substring(rule.length() + 2)));
        return summary;
    }

    /** Published value trimmed with single spaces; null for missing, "na", "n/a", "nil" or "-". */
    private static String clean(String value) {
        return SchoolRecordJoiner.cleanValue(value);
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    // ------------------------------------------------------------------ serving the active dataset

    /**
     * What to serve now. Builds it from the database on first use ({@code load-on-startup: false}); once
     * {@code expiresAt} has passed, checks the database's active version and hash (NFR-DATA-01, section 6.3).
     */
    private Served current() {
        Served now = served;
        if (now == null || now.dataset().isExpired(clock.instant())) {
            now = refresh();
        }
        return now;
    }

    /**
     * The hourly check: rebuild from the database when its active version or hash differs from what is served;
     * otherwise check again after {@code recheck-after}. On any problem the current dataset stays and is checked
     * again later. Never reads snapshot files.
     */
    private synchronized Served refresh() {
        Served now = served;
        Instant instant = clock.instant();
        if (now == null) {
            return rebuildFromDatabase();   // first use; a problem here is the caller's error
        }
        if (!now.dataset().isExpired(instant)) {
            return now;   // another thread just checked
        }
        try {
            Optional<SchoolDatasetStore.ActiveState> state = datasetStore.activeState();
            if (state.isEmpty() || state.get().equals(now.state())) {
                now.dataset().setExpiresAt(instant.plus(props.dataset().recheckAfter()));
                return now;
            }
            log.info("The database's active school dataset changed ({} -> {}); rebuilding the cache",
                    now.state().datasetVersion(), state.get().datasetVersion());
            return rebuildFromDatabase();
        } catch (RuntimeException e) {
            log.warn("Could not check the database's active school dataset, keeping {}: {}",
                    now.dataset().getDatasetVersion(), e.getMessage());
            now.dataset().setExpiresAt(instant.plus(props.dataset().recheckAfter()));
            return now;
        }
    }

    /**
     * Start-up (design section 6.2, step 1): read and validate the configured snapshot (a FAILED one stops
     * start-up), load it into the database unless it is already the active version, then build the cache from the
     * database.
     */
    private void loadAtStartUp() {
        LoadedSnapshot snapshot = snapshotReader.read();
        ValidationReport report = snapshotValidator.validate(snapshot);
        if (!report.isUsable()) {
            throw new IllegalStateException("School snapshot " + snapshot.location() + " failed validation: " + report);
        }
        report.getWarnings().forEach(w -> log.debug("Snapshot warning: {}", w));
        SchoolDatasetStore.Outcome outcome = datasetStore.ensureLoaded(snapshot, report,
                snapshotReader.contentSha256(snapshot));
        Served built = rebuildFromDatabase();
        log.info("School snapshot {} from {}: {} ({}); serving {} ({}, {} schools, {} districts) from the database",
                snapshot.version(), snapshot.location(), report.getStatus(), outcome,
                built.dataset().getDatasetVersion(), built.dataset().getDatasetKind(), built.dataset().size(),
                built.dataset().getDistricts().size());
    }

    /** DC-83: the cache and the manifest built from the database's active dataset (design section 6.3). */
    private synchronized Served rebuildFromDatabase() {
        SnapshotRows rows = datasetStore.readActive().orElseThrow(() -> new IllegalStateException("The database has "
                + "no active school dataset. Start the app once with app.dataset.load-on-startup=true (the default) "
                + "to load data/snapshots/ACTIVE into it."));
        Instant now = clock.instant();
        Served built = new Served(SchoolDatasetMapper.cache(rows, SOURCE_NAME, now,
                now.plus(props.dataset().recheckAfter())), SchoolDatasetMapper.manifest(rows),
                new SchoolDatasetStore.ActiveState(rows.version().datasetVersion(), rows.version().contentSha256()));
        served = built;
        return built;
    }
}

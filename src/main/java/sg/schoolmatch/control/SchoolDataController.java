package sg.schoolmatch.control;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import sg.schoolmatch.dataset.ValidationReport;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.error.NotFoundException;

/**
 * Design class «control» SchoolDataController — serves the active, validated school snapshot
 * (FR-DATA-03, NFR-DATA-01). Every other control reads schools through this class.
 * DC-09: exposes the dataset metadata; DC-12: {@link #importDataset()} builds a new snapshot from data.gov.sg.
 * <p>
 * The snapshot is read and validated once, in the constructor. A FAILED snapshot stops the app from starting.
 * After {@code app.dataset.recheck-after} (1 h) the next call re-reads it and switches when the version changed.
 */
@Service
public class SchoolDataController {

    static final String SOURCE_NAME = "SchoolMatch snapshot";

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

    private volatile SchoolDataCache activeDataset;
    private volatile SnapshotManifest activeManifest;

    public SchoolDataController(DataGovSgInterface dataGovSg, OneMapInterface oneMap, SnapshotReader snapshotReader,
                                SnapshotValidator snapshotValidator, CuratedCsvReader curatedCsvReader,
                                SnapshotWriter snapshotWriter, AppProperties props, Clock clock) {
        this.dataGovSg = dataGovSg;
        this.oneMap = oneMap;
        this.snapshotReader = snapshotReader;
        this.snapshotValidator = snapshotValidator;
        this.curatedCsvReader = curatedCsvReader;
        this.snapshotWriter = snapshotWriter;
        this.props = props;
        this.clock = clock;
        loadValidated();
    }

    /** All schools, sorted by name then code. */
    public List<School> getSchools() {
        refreshIfExpired();
        return activeDataset.getSchools();
    }

    /**
     * One school by code.
     *
     * @throws sg.schoolmatch.error.NotFoundException when the code is not in the dataset
     */
    public School getSchool(String schoolCode) {
        refreshIfExpired();
        return activeDataset.findByCode(schoolCode)
                .orElseThrow(() -> new NotFoundException("No school with code '" + schoolCode + "'"));
    }

    /** Planning areas with their boundaries (FR-MAP-06, DC-04). */
    public List<District> getDistricts() {
        refreshIfExpired();
        return activeDataset.getDistricts();
    }

    /** DC-09: the loaded dataset with its version, date and status (footer, about page). */
    public SchoolDataCache getActiveDataset() {
        refreshIfExpired();
        return activeDataset;
    }

    /**
     * The manifest of the active snapshot: sources with dataset ids and download times, counts, warnings, notes
     * (the {@code /about/data} page and the Singapore Open Data Licence notice).
     */
    public SnapshotManifest getActiveManifest() {   // DC-52
        refreshIfExpired();
        return activeManifest;
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
     *   <li>Validate as kind "full" with {@link SnapshotValidator}; write the folder even when FAILED, for review.</li>
     *   <li>Update {@code ACTIVE} only when {@code app.dataset.activate-on-import=true} and the status is not FAILED.</li>
     * </ol>
     * The running app keeps serving its current snapshot; it switches only after ACTIVE changes (recheck).
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
        sources.add(new SnapshotManifest.Source("data.gov.sg Subjects offered", SUBJECTS_DATASET, clock.instant()));
        List<District> fullDistricts = DistrictLocator.parseFeatureCollection(dataGovSg.fetchDistrictsGeoJson());
        sources.add(new SnapshotManifest.Source("data.gov.sg Master Plan 2019 Planning Area Boundary (No Sea)",
                PLANNING_AREAS_DATASET, clock.instant()));
        importLog.info("data.gov.sg: " + schoolRows.size() + " secondary and mixed-level schools, " + ccaRows.size()
                + " CCA rows, " + subjectRows.size() + " subject rows, " + fullDistricts.size() + " planning areas");

        NameNormaliser names = new NameNormaliser(curated.aliases());
        List<JoinedSchool> joined = new SchoolRecordJoiner(names).join(schoolRows, ccaRows, subjectRows, importLog);
        CuratedLookups lookups = curatedLookups(curated, names, importLog);
        SchoolGeocoder geocoder = new SchoolGeocoder(oneMap, curated.geocodeOverrides());
        DistrictLocator locator = new DistrictLocator(fullDistricts);
        List<SchoolRecord> records = new ArrayList<>();
        for (JoinedSchool school : joined) {
            records.add(buildRecord(school, lookups, geocoder, locator, importLog));
        }
        sources.add(new SnapshotManifest.Source("OneMap search (coordinates by postal code)", ONEMAP_SOURCE_ID,
                clock.instant()));
        sources.add(new SnapshotManifest.Source("SchoolMatch curated CSV files (school codes, name aliases, "
                + "PSLE ranges, geocode overrides, affiliations)", CURATED_SOURCE_ID, started));
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
                : "PSLE ranges come from data/curated/psle-ranges.csv (MOE SchoolFinder, typed and checked by the "
                        + "team). They are historical, not a guarantee.";
        SnapshotManifest draft = new SnapshotManifest(SnapshotManifest.KIND_FULL, version, today, clock.instant(),
                sources, null, List.of(), counts, notes);
        LoadedSnapshot candidate = new LoadedSnapshot(outputDir.resolve(version).toUri().toString(), draft, records,
                records.stream().map(SnapshotReader::toSchool).toList(), storedDistricts);
        ValidationReport validation = snapshotValidator.validate(candidate);
        List<String> allWarnings = new ArrayList<>(importLog.warnings());
        allWarnings.addAll(validation.getWarnings());
        ValidationReport report = new ValidationReport(validation.getErrors(), allWarnings);

        List<String> manifestWarnings = new ArrayList<>(importLog.summary());
        manifestWarnings.addAll(summariseByRule(validation.getWarnings()));
        SnapshotManifest manifest = new SnapshotManifest(SnapshotManifest.KIND_FULL, version, today, clock.instant(),
                sources, report.getStatus(), manifestWarnings, counts, notes);

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
                                     DistrictLocator locator, ImportLog importLog) {
        DataGovSgRecord row = school.row();
        String name = clean(row.get("school_name"));
        String code = lookups.codesByName().get(school.name());
        if (code == null) {
            code = NameNormaliser.slug(name);
            importLog.warn(ImportLog.SCHOOL_CODE_SLUG, name + " → " + code + " (no row in "
                    + CuratedCsvReader.SCHOOL_CODES + "; verify against the MOE SchoolFinder URL)");
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
                clean(row.get("mrt_desc")), clean(row.get("bus_desc")), clean(row.get("session_code")),
                clean(row.get("nature_code")), school.programmes(), school.ccas(),
                lookups.affiliationsByCode().getOrDefault(code, List.of()),
                lookups.ipNotesByCode().get(code),
                lookups.rangesByCode().getOrDefault(code, List.of()));
    }

    /** The curated CSV rows indexed for {@link #buildRecord}; unusable rows become warnings. */
    private record CuratedLookups(Map<String, String> codesByName, Map<String, List<ScoreRangeRecord>> rangesByCode,
                                  Map<String, String> ipNotesByCode, Map<String, List<String>> affiliationsByCode) {
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
        Map<String, List<String>> ipNotes = new HashMap<>();
        for (PsleRangeRow row : curated.psleRanges()) {
            String label = row.schoolCode() + " " + row.admissionYear() + " PG" + row.postingGroup() + " " + row.track();
            if (!row.isChecked()) {
                importLog.warn(ImportLog.CURATED, CuratedCsvReader.PSLE_RANGES + ": " + label
                        + " not used: not checked by a second person");
            } else if (CuratedCsvReader.TRACK_IP.equals(row.track())) {
                ipNotes.computeIfAbsent(row.schoolCode(), c -> new ArrayList<>()).add("IP " + row.admissionYear()
                        + " PG" + row.postingGroup() + ": "
                        + (row.rawText() != null ? row.rawText() : row.lower() + "–" + row.upper()));
            } else {
                ranges.computeIfAbsent(row.schoolCode(), c -> new ArrayList<>()).add(new ScoreRangeRecord(
                        row.admissionYear(), row.postingGroup(), CuratedCsvReader.TRACK_AFFILIATED.equals(row.track()),
                        row.lower(), row.upper()));
            }
        }
        Map<String, String> ipNoteText = new HashMap<>();
        ipNotes.forEach((code, list) -> ipNoteText.put(code, String.join("; ", list)));
        Map<String, List<String>> affiliations = new HashMap<>();
        curated.affiliations().forEach(a -> affiliations.computeIfAbsent(a.schoolCode(), c -> new ArrayList<>())
                .add(NameNormaliser.normalise(a.primarySchool())));
        return new CuratedLookups(codes, ranges, ipNoteText, affiliations);
    }

    /** Curated rows that name a school code the import did not produce (a typo, or a school that closed). */
    private static void warnUnknownCodes(CuratedData curated, List<SchoolRecord> records, ImportLog importLog) {
        Set<String> codes = new HashSet<>();
        records.forEach(r -> codes.add(r.schoolCode()));
        Set<String> reported = new HashSet<>();
        curated.psleRanges().forEach(r -> warnUnknown(CuratedCsvReader.PSLE_RANGES, r.schoolCode(), codes, reported, importLog));
        curated.affiliations().forEach(a -> warnUnknown(CuratedCsvReader.AFFILIATIONS, a.schoolCode(), codes, reported, importLog));
        curated.geocodeOverrides().forEach(o -> warnUnknown(CuratedCsvReader.GEOCODE_OVERRIDES, o.schoolCode(), codes, reported, importLog));
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

    // ------------------------------------------------------------------ serving the active snapshot

    /**
     * NFR-DATA-01: once {@code expiresAt} has passed, re-reads the snapshot and switches to it when its version
     * changed and it validates. On any problem the current dataset stays and is checked again later.
     */
    private synchronized void refreshIfExpired() {
        Instant now = clock.instant();
        if (!activeDataset.isExpired(now)) {
            return;
        }
        Instant nextCheck = now.plus(props.dataset().recheckAfter());
        try {
            LoadedSnapshot snapshot = snapshotReader.read();
            if (Objects.equals(snapshot.version(), activeDataset.getDatasetVersion())) {
                activeDataset.setExpiresAt(nextCheck);
                return;
            }
            ValidationReport report = snapshotValidator.validate(snapshot);
            if (report.isUsable()) {
                log.info("Switching school snapshot {} -> {}", activeDataset.getDatasetVersion(), snapshot.version());
                activeManifest = snapshot.manifest();
                activeDataset = toCache(snapshot, report);
            } else {
                log.warn("New school snapshot {} ignored, keeping {}: {}", snapshot.version(),
                        activeDataset.getDatasetVersion(), report);
                activeDataset.setExpiresAt(nextCheck);
            }
        } catch (RuntimeException e) {
            log.warn("Could not re-read the school snapshot, keeping {}: {}", activeDataset.getDatasetVersion(),
                    e.getMessage());
            activeDataset.setExpiresAt(nextCheck);
        }
    }

    /** Reads and validates the configured snapshot; a FAILED one stops start-up. */
    private void loadValidated() {
        LoadedSnapshot snapshot = snapshotReader.read();
        ValidationReport report = snapshotValidator.validate(snapshot);
        if (!report.isUsable()) {
            throw new IllegalStateException("School snapshot " + snapshot.location() + " failed validation: " + report);
        }
        SchoolDataCache cache = toCache(snapshot, report);
        log.info("Loaded school snapshot {} ({}, {} schools, {} districts) from {}: {}", cache.getDatasetVersion(),
                cache.getDatasetKind(), cache.size(), cache.getDistricts().size(), snapshot.location(),
                report.getStatus());
        report.getWarnings().forEach(w -> log.debug("Snapshot warning: {}", w));
        activeManifest = snapshot.manifest();
        activeDataset = cache;
    }

    /** DC-09: metadata from the manifest; the status is the one just computed by the validator. */
    private SchoolDataCache toCache(LoadedSnapshot snapshot, ValidationReport report) {
        Instant now = clock.instant();
        SchoolDataCache cache = new SchoolDataCache(SOURCE_NAME, now, now.plus(props.dataset().recheckAfter()),
                snapshot.schools(), snapshot.districts());
        cache.setDatasetVersion(snapshot.manifest().version());
        cache.setDatasetKind(snapshot.manifest().kind());
        cache.setEffectiveDate(snapshot.manifest().effectiveDate());
        cache.setImportedAt(snapshot.manifest().importedAt());
        cache.setValidationStatus(report.getStatus());
        return cache;
    }
}

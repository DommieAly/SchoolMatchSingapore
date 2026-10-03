package sg.schoolmatch.persistence.dataset;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import sg.schoolmatch.dataset.IpRangeNotes;
import sg.schoolmatch.dataset.MoeRangeText;
import sg.schoolmatch.dataset.SnapshotManifest;
import sg.schoolmatch.dataset.TransportLists;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.entity.school.ValidationStatus;

/**
 * Rows of the active dataset ({@link SchoolDatasetStore#readActive()}) → {@code School}, {@code District},
 * {@code IndicativePsleScoreRange}, {@code SchoolDataCache} and the {@code SnapshotManifest} of the about page
 * (docs/database-design.md, sections 6.3 and 7.2). The objects are new on every call and are never written back.
 * Besides {@code SnapshotReader}, this is the only class that calls {@code School} setters.
 * <p>
 * Computed, not stored (section 2.3): the planning-area name (through the code), MOE's range text
 * ({@link MoeRangeText}), the IP note ({@link IpRangeNotes}), {@code busInfo} and {@code nearestMrt} (the lists
 * joined with {@code ", "}) and the manifest counts.
 * <p>
 * Every order is made here in Java, never by a text {@code ORDER BY}: H2 and PostgreSQL sort text differently.
 */
public final class SchoolDatasetMapper {

    /**
     * PSLE ranges: newest year first, then PG3 to PG1, ordinary ranges before IP ones, non-affiliated before
     * affiliated. The order of {@code SchoolDetailsUI.NEWEST_FIRST}, which sorts the details table this way anyway;
     * it is total over the table's key, so every database gives the same list.
     */
    public static final Comparator<IndicativePsleScoreRange> RANGE_ORDER = Comparator
            .comparingInt(IndicativePsleScoreRange::getAdmissionYear).reversed()
            .thenComparing(Comparator.comparingInt(IndicativePsleScoreRange::getPostingGroup).reversed())
            .thenComparing(IndicativePsleScoreRange::isIntegratedProgramme)
            .thenComparing(IndicativePsleScoreRange::isAffiliated);

    /**
     * Planning areas by name, then code. The table keeps no position; the importer's {@code districts.geojson} has
     * data.gov.sg's order, which no page shows (the map draws every area, filters sort the names).
     */
    public static final Comparator<District> DISTRICT_ORDER = Comparator
            .comparing(District::getPlanningAreaName, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(District::getPlanningAreaCode);

    private SchoolDatasetMapper() {
    }

    /** The active planning areas, sorted by {@link #DISTRICT_ORDER}. */
    public static List<District> districts(SnapshotRows rows) {
        return rows.districts().stream()
                .map(d -> new District(d.planningAreaCode(), d.planningAreaName(), d.boundaryGeoJson()))
                .sorted(DISTRICT_ORDER)
                .toList();
    }

    /**
     * The active schools, sorted by {@link SchoolDataCache#BY_NAME_THEN_CODE}; CCAs, programmes and affiliated
     * primary schools in natural order (the importer's order), bus services and MRT stations by
     * {@code list_position}, PSLE ranges by {@link #RANGE_ORDER}.
     *
     * @throws IllegalStateException when a school's planning area is not among the active areas (the loader never
     *                               leaves the tables like this)
     */
    public static List<School> schools(SnapshotRows rows) {
        Map<String, String> areaNames = new HashMap<>();
        rows.districts().forEach(d -> areaNames.put(d.planningAreaCode(), d.planningAreaName()));
        Map<String, List<IndicativePsleScoreRange>> ranges = group(rows.scoreRanges(), ScoreRangeRow::schoolCode,
                r -> new IndicativePsleScoreRange(r.admissionYear(), r.postingGroup(), r.affiliated(), r.lowerScore(),
                        r.upperScore(), r.integratedProgramme(), MoeRangeText.format(r.lowerScore(),
                        r.lowerHclGrade(), r.upperScore(), r.upperHclGrade(), r.placesLeft())));
        Map<String, List<String>> ccas = group(rows.ccas(), SchoolCcaRow::schoolCode, SchoolCcaRow::ccaName);
        Map<String, List<String>> programmes = group(rows.programmes(), SchoolProgrammeRow::schoolCode,
                SchoolProgrammeRow::programmeName);
        Map<String, List<String>> affiliations = group(rows.affiliations(), SchoolAffiliatedPrimaryRow::schoolCode,
                SchoolAffiliatedPrimaryRow::primarySchoolName);
        Map<String, List<String>> buses = group(rows.busServices().stream()
                        .sorted(Comparator.comparingInt(SchoolBusServiceRow::listPosition)).toList(),
                SchoolBusServiceRow::schoolCode, SchoolBusServiceRow::serviceNo);
        Map<String, List<String>> stations = group(rows.mrtStations().stream()
                        .sorted(Comparator.comparingInt(SchoolMrtStationRow::listPosition)).toList(),
                SchoolMrtStationRow::schoolCode, SchoolMrtStationRow::stationName);

        List<School> schools = new ArrayList<>();
        for (SchoolRow row : rows.schools()) {
            String code = row.schoolCode();
            if (!areaNames.containsKey(row.planningAreaCode())) {
                throw new IllegalStateException("School " + code + " is in planning area " + row.planningAreaCode()
                        + ", which is not in the active dataset " + rows.version().datasetVersion());
            }
            School school = new School(code, row.schoolName());
            school.setAddress(row.address());
            school.setCoordinate(new Coordinate(row.latitude(), row.longitude()));
            school.setTelephone(row.telephone());
            school.setWebsite(row.website());
            school.setEmail(row.email());
            school.setSchoolType(row.schoolType());
            school.setPlanningArea(areaNames.get(row.planningAreaCode()));
            school.setNearestMrt(TransportLists.join(stations.getOrDefault(code, List.of())));
            school.setBusInfo(TransportLists.join(buses.getOrDefault(code, List.of())));
            school.setSessionType(row.sessionType());
            school.setSchoolNature(row.schoolNature());
            school.setProgrammes(sorted(programmes.get(code)));
            school.setCcas(sorted(ccas.get(code)));
            school.setAffiliatedPrimarySchools(sorted(affiliations.get(code)));
            List<IndicativePsleScoreRange> schoolRanges = ranges.getOrDefault(code, List.of()).stream()
                    .sorted(RANGE_ORDER).toList();
            school.setScoreRanges(schoolRanges);
            school.setIpRangeNote(IpRangeNotes.ofRanges(schoolRanges));
            schools.add(school);
        }
        schools.sort(SchoolDataCache.BY_NAME_THEN_CODE);
        return schools;
    }

    /**
     * The manifest of the active version, as {@code manifest.json} had it: format {@value SnapshotManifest#FORMAT_VERSION}
     * (only format-2 snapshots are ever loaded), sources and warnings in manifest order, and the counts of the
     * active rows (the loader checked that they equal the manifest's).
     */
    public static SnapshotManifest manifest(SnapshotRows rows) {
        DatasetVersionRow v = rows.version();
        List<SnapshotManifest.Source> sources = rows.sources().stream()
                .sorted(Comparator.comparingInt(DatasetSourceRow::sourceNo))
                .map(s -> new SnapshotManifest.Source(s.sourceName(), s.sourceId(), s.downloadedAt()))
                .toList();
        List<String> warnings = rows.warnings().stream()
                .sorted(Comparator.comparingInt(DatasetWarningRow::warningNo))
                .map(DatasetWarningRow::message)
                .toList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("schools", rows.schools().size());
        counts.put("districts", rows.districts().size());
        counts.put("scoreRanges", rows.scoreRanges().size());
        return new SnapshotManifest(SnapshotManifest.FORMAT_VERSION, v.datasetKind(), v.datasetVersion(),
                v.effectiveDate(), v.importedAt(), sources,
                v.manifestStatus() == null ? null : ValidationStatus.valueOf(v.manifestStatus()), warnings, counts,
                v.notes());
    }

    /**
     * The in-memory dataset (DC-09): the schools and areas with the version's metadata; {@code validationStatus} is
     * the status the loader recorded ({@code load_status}).
     */
    public static SchoolDataCache cache(SnapshotRows rows, String sourceName, Instant retrievedAt, Instant expiresAt) {
        DatasetVersionRow v = rows.version();
        SchoolDataCache cache = new SchoolDataCache(sourceName, retrievedAt, expiresAt, schools(rows), districts(rows));
        cache.setDatasetVersion(v.datasetVersion());
        cache.setDatasetKind(v.datasetKind());
        cache.setEffectiveDate(v.effectiveDate());
        cache.setImportedAt(v.importedAt());
        cache.setValidationStatus(ValidationStatus.valueOf(v.loadStatus()));
        return cache;
    }

    private static <R, V> Map<String, List<V>> group(List<R> rows, Function<R, String> code, Function<R, V> value) {
        Map<String, List<V>> byCode = new HashMap<>();
        rows.forEach(r -> byCode.computeIfAbsent(code.apply(r), c -> new ArrayList<>()).add(value.apply(r)));
        return byCode;
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }
}

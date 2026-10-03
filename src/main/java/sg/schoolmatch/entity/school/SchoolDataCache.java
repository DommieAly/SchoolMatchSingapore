package sg.schoolmatch.entity.school;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;
import sg.schoolmatch.entity.search.AttributeCategory;

/**
 * Design class «entity» SchoolDataCache — the active school dataset held in memory (FR-DATA-03, NFR-DATA-01).
 * <p>
 * DC-09: also carries the snapshot metadata ({@code datasetVersion}, {@code effectiveDate},
 * {@code importedAt}, {@code validationStatus}, plus {@code datasetKind} "seed"/"full") for the footer.
 * {@code retrievedAt} = load time; {@code expiresAt} = when to check for a newer snapshot.
 */
public class SchoolDataCache {

    /** Schools A–Z, then by code. Used for every list this cache returns (FR-SEARCH-06). */
    public static final Comparator<School> BY_NAME_THEN_CODE = Comparator
            .comparing(School::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(School::getSchoolCode, Comparator.nullsLast(Comparator.naturalOrder()));

    private final String sourceName;
    private final Instant retrievedAt;
    private Instant expiresAt;

    // DC-09 metadata, copied from the snapshot manifest.
    private String datasetVersion;
    private String datasetKind;          // DC-34: "seed" or "full"
    private LocalDate effectiveDate;
    private Instant importedAt;
    private ValidationStatus validationStatus;

    private final Map<String, School> schoolsByCode = new LinkedHashMap<>();   // ordered by name, then code
    private final List<District> districts;
    private final boolean psleData;      // DC-74: at least one school has a PSLE score range

    public SchoolDataCache(String sourceName, Instant retrievedAt, Instant expiresAt,
                           Collection<School> schools, Collection<District> districts) {
        this.sourceName = sourceName;
        this.retrievedAt = retrievedAt;
        this.expiresAt = expiresAt;
        schools.stream().sorted(BY_NAME_THEN_CODE).forEach(s -> schoolsByCode.put(s.getSchoolCode(), s));
        this.districts = List.copyOf(districts);
        this.psleData = hasPsleData(schoolsByCode.values());
    }

    /**
     * DC-74: true when at least one school of the dataset has a PSLE score range. False for a snapshot built
     * before any range was curated (data/curated/psle-ranges.csv empty): the PSLE filter, SAFE/MATCH/REACH and
     * PSLE fit then have nothing to work with, and the pages say the ranges are not available yet.
     */
    public boolean hasPsleData() {
        return psleData;
    }

    /**
     * True when at least one school has an affiliated primary school, i.e. the snapshot has curated affiliations
     * (data/curated/affiliations.csv). Then a school with none has none, rather than unknown.
     */
    public boolean hasAffiliationData() {
        return schoolsByCode.values().stream().anyMatch(s -> !s.getAffiliatedPrimarySchools().isEmpty());
    }

    /** DC-74: the rule behind {@link #hasPsleData()}: at least one of {@code schools} has a score range. */
    public static boolean hasPsleData(Collection<School> schools) {
        return schools.stream().anyMatch(s -> !s.getScoreRanges().isEmpty());
    }

    /** True when it is time to check for a newer snapshot. DC-25: the caller passes the time. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** Schools whose name contains {@code term} (case-insensitive), sorted by name then code (FR-SEARCH-02). */
    public List<School> findByName(String term) {
        return schoolsByCode.values().stream().filter(s -> s.matchesName(term)).toList();
    }

    /** DC-29: empty when no school has this code (the control turns that into NotFoundException). */
    public Optional<School> findByCode(String schoolCode) {
        return Optional.ofNullable(schoolCode == null ? null : schoolsByCode.get(schoolCode));
    }

    /** Distinct, sorted values present in the dataset for a filter category (FR-FILTER-02, DC-04). */
    public Set<String> getAttributeValues(AttributeCategory category) {
        Function<School, Stream<String>> values = switch (category) {
            case SCHOOL_TYPE -> s -> Stream.of(s.getSchoolType());
            case PROGRAMME -> s -> s.getProgrammes().stream();
            case CCA -> s -> s.getCcas().stream();
            case DISTRICT -> s -> Stream.of(s.getPlanningArea());
        };
        TreeSet<String> result = new TreeSet<>();
        schoolsByCode.values().stream().flatMap(values).filter(Objects::nonNull).forEach(result::add);
        return result;
    }

    /** All schools, sorted by name then code (read-only). */
    public List<School> getSchools() {
        return List.copyOf(schoolsByCode.values());
    }

    public List<District> getDistricts() {
        return districts;
    }

    public int size() {
        return schoolsByCode.size();
    }

    /** True when the dataset is the small seed snapshot with test PSLE values (footer badge). */
    public boolean isSeedData() {
        return "seed".equalsIgnoreCase(datasetKind);
    }

    public String getSourceName() {
        return sourceName;
    }

    public Instant getRetrievedAt() {
        return retrievedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }

    public void setDatasetVersion(String datasetVersion) {
        this.datasetVersion = datasetVersion;
    }

    public String getDatasetKind() {
        return datasetKind;
    }

    public void setDatasetKind(String datasetKind) {
        this.datasetKind = datasetKind;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(Instant importedAt) {
        this.importedAt = importedAt;
    }

    public ValidationStatus getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(ValidationStatus validationStatus) {
        this.validationStatus = validationStatus;
    }

    @Override
    public String toString() {
        return "SchoolDataCache{version=" + datasetVersion + ", schools=" + schoolsByCode.size()
                + ", districts=" + districts.size() + ", psleData=" + psleData + "}";
    }
}

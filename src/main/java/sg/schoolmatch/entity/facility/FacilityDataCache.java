package sg.schoolmatch.entity.facility;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sg.schoolmatch.entity.common.Coordinate;

/**
 * Design class «entity» FacilityDataCache — facilities fetched from Google, kept in memory with an
 * expiry and de-duplicated by placeId (FR-DATA-04, DC-17).
 */
public class FacilityDataCache {

    private final Instant retrievedAt;
    private final Instant expiresAt;
    private final Map<String, Facility> facilitiesByPlaceId = new LinkedHashMap<>();

    public FacilityDataCache(Instant retrievedAt, Instant expiresAt) {
        this.retrievedAt = retrievedAt;
        this.expiresAt = expiresAt;
    }

    /** True when the cached facilities are too old (DC-17: 24 h). DC-25: the caller passes the time. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * Facilities with a valid coordinate within {@code radiusKm} (straight line) of {@code centre},
     * nearest first (FR-FACILITY-03, FR-FACILITY-05).
     */
    public List<Facility> findNearby(Coordinate centre, int radiusKm) {
        return facilitiesByPlaceId.values().stream()
                .filter(Facility::hasValidCoordinate)
                .filter(f -> f.distanceTo(centre) <= radiusKm)
                .sorted(Comparator.comparingDouble(f -> f.distanceTo(centre)))
                .toList();
    }

    /** DC-29: empty when the placeId is not cached. */
    public Optional<Facility> findByPlaceId(String placeId) {
        return Optional.ofNullable(placeId == null ? null : facilitiesByPlaceId.get(placeId));
    }

    /** Adds or replaces facilities by placeId (DC-36 helper). */
    public void putAll(Collection<Facility> facilities) {
        facilities.forEach(f -> facilitiesByPlaceId.put(f.getPlaceId(), f));
    }

    public int size() {   // DC-36 helper
        return facilitiesByPlaceId.size();
    }

    public Instant getRetrievedAt() {
        return retrievedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}

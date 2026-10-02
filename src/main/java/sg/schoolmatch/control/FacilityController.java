package sg.schoolmatch.control;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import sg.schoolmatch.boundary.external.GoogleMapsPlatformInterface;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityDataCache;
import sg.schoolmatch.entity.facility.FacilityFilterCriteria;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotFoundException;

/**
 * Design class «control» FacilityController — libraries and tuition centres near a school
 * (use cases View Nearby Facilities, View Facility Details; FR-FACILITY-01..06, FR-FACFILTER-01..04,
 * FR-FACDETAIL-01..03). DC-30: the filter takes the School as its origin.
 * Called by NearbyFacilitiesUI, FacilityDetailsUI, FacilityMapUI, DirectionsUI.
 * <p>
 * DC-17: the facilities near each school are kept in memory in a {@link FacilityDataCache} per school code,
 * for {@code app.facility.cache-ttl} (24 h); nothing is stored in the database.
 */
@Service
public class FacilityController {

    static final String RADIUS_FIELD = "radiusKm";
    static final String RADIUS_MESSAGE = "Choose 1, 2 or 3 km";

    /** Google place ids (and the stub's) use only these characters; anything else is not looked up. */
    private static final Pattern PLACE_ID = Pattern.compile("[A-Za-z0-9_-]{1,300}");

    private final int defaultRadiusKm = FacilityFilterCriteria.DEFAULT_RADIUS_KM;   // design value 3 (DC-24)

    private final GoogleMapsPlatformInterface googleMaps;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Map<String, FacilityDataCache> cacheBySchool = new ConcurrentHashMap<>();

    public FacilityController(GoogleMapsPlatformInterface googleMaps, AppProperties props, Clock clock) {   // DC-62
        this.googleMaps = googleMaps;
        this.cacheTtl = props.facility().cacheTtl();
        this.clock = clock;
    }

    /**
     * Libraries and tuition centres within 3 km (straight line) of the school, nearest first
     * (FR-FACILITY-01..05). Only facilities with a valid coordinate are kept (FR-FACMAP-05), each place once.
     * Empty when the school has no valid coordinate or nothing is nearby (FR-FACILITY-06).
     *
     * @throws sg.schoolmatch.error.ExternalServiceUnavailableException when Google cannot be reached
     */
    public List<Facility> getNearbyFacilities(School school) {
        if (school == null || !school.hasValidCoordinate()) {
            return List.of();
        }
        Coordinate centre = school.getCoordinate();
        return sortByDistance(cacheFor(school).findNearby(centre, defaultRadiusKm), centre);
    }

    /**
     * Facilities near the school that have a selected type (none selected = all types) AND lie within the
     * selected radius, nearest first (FR-FACFILTER-01..04).
     *
     * @throws InvalidInputException ("radiusKm") when the radius is not 1, 2 or 3 km (DC-24)
     * @throws sg.schoolmatch.error.ExternalServiceUnavailableException when Google cannot be reached
     */
    public List<Facility> filterFacilities(School school, FacilityFilterCriteria criteria) {
        if (criteria == null || !criteria.isValid()) {
            throw new InvalidInputException(RADIUS_FIELD, RADIUS_MESSAGE);
        }
        if (school == null || !school.hasValidCoordinate()) {
            return List.of();
        }
        Coordinate centre = school.getCoordinate();
        return getNearbyFacilities(school).stream().filter(f -> criteria.matches(f, centre)).toList();
    }

    /**
     * All Facility Details fields of one place (FR-FACDETAIL-01..03): the summary found by an earlier nearby
     * search (it has the facility type), completed with Google's place details (phone, website, opening hours).
     * Details are always asked for, so a bookmarked page still works after a restart. Missing fields stay null.
     *
     * @throws NotFoundException when neither the cache nor Google knows the place
     * @throws sg.schoolmatch.error.ExternalServiceUnavailableException when Google cannot be reached
     */
    public Facility getFacilityDetails(String placeId) {
        if (placeId == null || !PLACE_ID.matcher(placeId).matches()) {
            throw new NotFoundException("No facility with this id");
        }
        Optional<Facility> summary = findCached(placeId);
        Optional<Facility> details = googleMaps.getPlaceDetails(placeId);
        if (summary.isEmpty() && details.isEmpty()) {
            throw new NotFoundException("No facility with id " + placeId);
        }
        return merge(placeId, summary.orElse(null), details.orElse(null));
    }

    /** Nearest first; equal distances by name, then placeId, so the order never changes between requests. */
    private List<Facility> sortByDistance(List<Facility> list, Coordinate origin) {   // DC-31: origin added
        Comparator<Facility> byDistance = Comparator.comparingDouble(f -> f.distanceTo(origin));
        return list.stream()
                .filter(Facility::hasValidCoordinate)
                .sorted(byDistance
                        .thenComparing(f -> f.getName() == null ? "" : f.getName().toLowerCase(Locale.ROOT))
                        .thenComparing(Facility::getPlaceId))
                .toList();
    }

    /** The school's cache; asks Google for both types when it is missing or older than the TTL (DC-17). */
    private FacilityDataCache cacheFor(School school) {
        Instant now = clock.instant();
        FacilityDataCache cache = cacheBySchool.get(school.getSchoolCode());
        if (cache != null && !cache.isExpired(now)) {
            return cache;
        }
        Coordinate centre = school.getCoordinate();
        Map<String, Facility> nearby = new LinkedHashMap<>();   // first one wins: a library stays a library
        for (FacilityType type : FacilityType.values()) {
            for (Facility facility : googleMaps.searchPlaces(type, centre)) {
                if (facility != null && facility.getPlaceId() != null && facility.hasValidCoordinate()
                        && facility.distanceTo(centre) <= defaultRadiusKm) {
                    nearby.putIfAbsent(facility.getPlaceId(), facility);
                }
            }
        }
        FacilityDataCache fresh = new FacilityDataCache(now, now.plus(cacheTtl));
        fresh.putAll(nearby.values());
        cacheBySchool.put(school.getSchoolCode(), fresh);
        return fresh;
    }

    private Optional<Facility> findCached(String placeId) {
        Instant now = clock.instant();
        return cacheBySchool.values().stream()
                .filter(cache -> !cache.isExpired(now))
                .map(cache -> cache.findByPlaceId(placeId))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** A new Facility: details win where present, the summary fills the gaps and gives the type. */
    private static Facility merge(String placeId, Facility summary, Facility details) {
        FacilityType type = summary != null && summary.getFacilityType() != null ? summary.getFacilityType()
                : details != null ? details.getFacilityType() : null;
        Facility merged = new Facility(placeId, pick(details, summary, Facility::getName), type);
        merged.setAddress(pick(details, summary, Facility::getAddress));
        merged.setCoordinate(pick(details, summary, Facility::getCoordinate));
        merged.setTelephone(pick(details, summary, Facility::getTelephone));
        merged.setWebsite(pick(details, summary, Facility::getWebsite));
        merged.setOpeningHours(pick(details, summary, Facility::getOpeningHours));
        return merged;
    }

    private static <T> T pick(Facility first, Facility second, Function<Facility, T> field) {
        T value = first == null ? null : field.apply(first);
        if (value instanceof String text && text.isBlank()) {
            value = null;
        }
        return value != null || second == null ? value : field.apply(second);
    }
}

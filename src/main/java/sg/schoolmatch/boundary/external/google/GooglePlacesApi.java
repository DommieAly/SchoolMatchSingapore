package sg.schoolmatch.boundary.external.google;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import sg.schoolmatch.boundary.external.ExternalCallBudget;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.config.CacheConfig;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityFilterCriteria;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.error.ExternalServiceUnavailableException;

/**
 * Live calls to the Google Places API (New) for {@link GoogleMapsPlatformClient}
 * (FR-FACILITY-01, FR-FACDETAIL-02, NFR-MAIN-02).
 * <ul>
 *   <li>Libraries: {@code searchNearby} with type {@code library}. Tuition centres: {@code searchText}
 *       "tuition centre" (Google has no place type for them). Both within 3 km of the centre, at most 20.</li>
 *   <li>Search field mask {@code places.id,places.displayName,places.formattedAddress,places.location}:
 *       the cheapest tier that still has the location (Nearby Search Pro / Text Search Pro). Details ask for
 *       phone, website and opening hours too (Place Details Enterprise). Tiers checked on Google's pricing
 *       page on 2026-10-02.</li>
 *   <li>Every request first calls {@link ExternalCallBudget#charge}; the key goes in the {@code X-Goog-Api-Key}
 *       header only. 4xx/5xx, timeouts and unreadable answers → {@code ExternalServiceUnavailableException
 *       ("Google Places")} (cause {@link GoogleApiFailure}, for the log line), except that details for an unknown place id
 *       give an empty result.</li>
 *   <li>Cached 24 h: searches by (type, centre) in {@code places}, details by id in {@code placeDetails}.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.external.google.mode", havingValue = "live")
public class GooglePlacesApi {

    static final String SERVICE = "Google Places";
    static final String SEARCH_NEARBY_PATH = "/v1/places:searchNearby";
    static final String SEARCH_TEXT_PATH = "/v1/places:searchText";
    static final String DETAILS_PATH = "/v1/places/{placeId}";
    static final String SEARCH_FIELD_MASK = "places.id,places.displayName,places.formattedAddress,places.location";
    static final String DETAILS_FIELD_MASK = "id,displayName,formattedAddress,location,nationalPhoneNumber,"
            + "websiteUri,regularOpeningHours.weekdayDescriptions";
    static final String LIBRARY_TYPE = "library";
    static final String TUITION_QUERY = "tuition centre";
    static final int MAX_RESULTS = 20;
    /** Search radius in metres: the largest facility radius (DC-24). */
    static final double RADIUS_METRES = FacilityFilterCriteria.DEFAULT_RADIUS_KM * 1000.0;

    private static final Logger log = LoggerFactory.getLogger(GooglePlacesApi.class);

    private final RestClient restClient;
    private final ExternalCallBudget budget;

    public GooglePlacesApi(RestClient.Builder restClientBuilder, AppProperties props, ExternalCallBudget budget) {
        this.restClient = restClientBuilder
                .baseUrl(props.google().placesBaseUrl())
                .defaultHeader(GoogleHeaders.API_KEY, props.google().serverKey())
                .build();
        this.budget = budget;
    }

    /** Facilities of {@code type} around {@code centre} (summary fields only). Cached 24 h. */
    @Cacheable(CacheConfig.PLACES)
    public List<Facility> searchPlaces(FacilityType type, Coordinate centre) {
        PlacesJson.Area area = new PlacesJson.Area(new PlacesJson.Circle(PlacesJson.LatLng.of(centre), RADIUS_METRES));
        boolean library = type == FacilityType.LIBRARY;
        Object body = library
                ? new PlacesJson.SearchNearbyRequest(List.of(LIBRARY_TYPE), MAX_RESULTS, area)
                : new PlacesJson.SearchTextRequest(TUITION_QUERY, MAX_RESULTS, area);
        budget.charge(ExternalCallBudget.PLACES_SEARCH, 1);
        try {
            PlacesJson.SearchResponse response = restClient.post()
                    .uri(library ? SEARCH_NEARBY_PATH : SEARCH_TEXT_PATH)
                    .header(GoogleHeaders.FIELD_MASK, SEARCH_FIELD_MASK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(PlacesJson.SearchResponse.class);
            return response == null ? List.of() : response.toFacilities(type);
        } catch (RestClientException e) {   // HTTP 4xx/5xx, timeout or I/O error, unreadable JSON
            throw new ExternalServiceUnavailableException(SERVICE, GoogleApiFailure.of(e));
        }
    }

    /**
     * Details of one place; empty when Google does not know the id (HTTP 404, or 400 for a malformed id).
     * The facility type is not part of the answer (it stays null; FacilityController keeps the type from
     * the search). Cached 24 h.
     */
    @Cacheable(CacheConfig.PLACE_DETAILS)
    public Optional<Facility> getPlaceDetails(String placeId) {
        budget.charge(ExternalCallBudget.PLACE_DETAILS, 1);
        try {
            PlacesJson.Place place = restClient.get()
                    .uri(DETAILS_PATH, placeId)
                    .header(GoogleHeaders.FIELD_MASK, DETAILS_FIELD_MASK)
                    .retrieve()
                    .body(PlacesJson.Place.class);
            return Optional.ofNullable(place == null ? null : place.toFacility(null));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)
                    || e.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
                log.warn("Google Places has no place with id {} (HTTP {})", placeId, e.getStatusCode().value());
                return Optional.empty();
            }
            throw new ExternalServiceUnavailableException(SERVICE, GoogleApiFailure.of(e));
        } catch (RestClientException e) {
            throw new ExternalServiceUnavailableException(SERVICE, GoogleApiFailure.of(e));
        }
    }
}

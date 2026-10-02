package sg.schoolmatch.boundary.external.google;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import sg.schoolmatch.boundary.external.GoogleMapsPlatformInterface;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.TravelMode;

/**
 * Live {@link GoogleMapsPlatformInterface} (app.external.google.mode=live; FR-ROUTE-05, FR-FACILITY-01,
 * FR-FACDETAIL-02, NFR-MAIN-02). A thin delegate: {@link GoogleRoutesApi} sends the Routes requests and
 * {@link GooglePlacesApi} the Places requests, with the server key, the daily budget and the caches.
 * <p>
 * This class rounds coordinates before they become cache keys and request values: route origin 3 decimal
 * places (about 100 m), route destination 5 (about 1 m), places search centre 4 (about 10 m). So nearby
 * starting points share one cached answer and one charge.
 */
@Component
@ConditionalOnProperty(name = "app.external.google.mode", havingValue = "live")
public class GoogleMapsPlatformClient implements GoogleMapsPlatformInterface {

    static final int ORIGIN_DECIMALS = 3;
    static final int DESTINATION_DECIMALS = 5;
    static final int SEARCH_CENTRE_DECIMALS = 4;

    private final GoogleRoutesApi routesApi;
    private final GooglePlacesApi placesApi;

    /** Stops start-up with a clear message when live mode has no server key (instead of failing on every page). */
    public GoogleMapsPlatformClient(AppProperties props, GoogleRoutesApi routesApi, GooglePlacesApi placesApi) {
        if (!props.google().hasServerKey()) {
            throw new IllegalStateException("GOOGLE_MODE=live needs GOOGLE_MAPS_SERVER_KEY in .env (key holders only, "
                    + "see README 'Google keys'). Everyone else: set GOOGLE_MODE=stub.");
        }
        this.routesApi = routesApi;
        this.placesApi = placesApi;
    }

    @Override
    public Route computeRoute(Coordinate origin, Coordinate destination, TravelMode mode) {
        if (origin == null || destination == null) {
            return Route.unavailable(mode);
        }
        return routesApi.computeRoute(round(origin, ORIGIN_DECIMALS), round(destination, DESTINATION_DECIMALS), mode);
    }

    @Override
    public List<Route> computeRouteMatrix(Coordinate origin, List<Coordinate> destinations, TravelMode mode) {
        if (destinations.isEmpty()) {
            return List.of();
        }
        if (origin == null) {
            return Collections.nCopies(destinations.size(), Route.unavailable(mode));
        }
        List<Coordinate> rounded = new ArrayList<>(destinations.size());
        for (Coordinate destination : destinations) {
            rounded.add(destination == null ? null : round(destination, DESTINATION_DECIMALS));
        }
        return routesApi.computeRouteMatrix(round(origin, ORIGIN_DECIMALS), rounded, mode);
    }

    @Override
    public List<Facility> searchPlaces(FacilityType type, Coordinate centre) {
        if (type == null || centre == null) {
            return List.of();
        }
        return placesApi.searchPlaces(type, round(centre, SEARCH_CENTRE_DECIMALS));
    }

    @Override
    public Optional<Facility> getPlaceDetails(String placeId) {
        if (placeId == null || placeId.isBlank()) {
            return Optional.empty();
        }
        return placesApi.getPlaceDetails(placeId.strip());
    }

    /** The coordinate rounded to {@code decimals} decimal places. */
    static Coordinate round(Coordinate c, int decimals) {
        double factor = Math.pow(10, decimals);
        return new Coordinate(Math.round(c.getLatitude() * factor) / factor,
                Math.round(c.getLongitude() * factor) / factor);
    }
}

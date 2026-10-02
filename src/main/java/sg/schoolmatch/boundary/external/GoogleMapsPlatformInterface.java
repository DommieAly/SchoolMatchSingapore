package sg.schoolmatch.boundary.external;

import java.util.List;
import java.util.Optional;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.TravelMode;

/**
 * Design class «boundary» GoogleMapsPlatformInterface — server-side calls to Google Routes and Places
 * (FR-ROUTE-05, FR-FACILITY-01, NFR-MAIN-02). Uses the server key.
 * DC-11: geocode moved to OneMapInterface. DC-39: loadMap(places) lives in static/js (browser key).
 * Implementations: StubGoogleMapsPlatform (app.external.google.mode=stub, default) and
 * GoogleMapsPlatformClient (live). Failures throw ExternalServiceUnavailableException.
 */
public interface GoogleMapsPlatformInterface {

    /** One route; {@code Route.unavailable(mode)} when Google finds none (FR-ROUTE-08). */
    Route computeRoute(Coordinate origin, Coordinate destination, TravelMode mode);

    /** DC-05: origin added. One Route per destination, in the same order (unavailable ones included). */
    List<Route> computeRouteMatrix(Coordinate origin, List<Coordinate> destinations, TravelMode mode);

    /** Facilities of {@code type} near {@code centre} (summary fields only). */
    List<Facility> searchPlaces(FacilityType type, Coordinate centre);

    /** Full details of one place (DC-29: Optional, empty when unknown). */
    Optional<Facility> getPlaceDetails(String placeId);
}

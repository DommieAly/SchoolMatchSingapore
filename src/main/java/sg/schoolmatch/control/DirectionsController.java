package sg.schoolmatch.control;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import sg.schoolmatch.boundary.external.GoogleMapsPlatformInterface;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.common.Place;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.RouteStep;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Design class «control» DirectionsController — routes and commute times via Google Routes
 * (use cases Get Directions, Calculate Route; FR-ROUTE-01..08). DC-05: both public methods take the origin.
 * Called by DirectionsUI, FilterController, RecommendationController.
 * <p>
 * The Route objects from the interface may be shared (the live client caches them), so this class never
 * changes them: it returns new Route objects with the origin and destination set.
 */
@Service
public class DirectionsController {

    static final String LOCATION_FIELD = "location";
    static final String LOCATION_MESSAGE = "Set a starting point in Singapore first";
    static final String DESTINATION_FIELD = "destination";
    static final String DESTINATION_MESSAGE = "This place has no map location, so directions are not available";
    static final String MODE_FIELD = "mode";
    static final String MODE_MESSAGE = "Choose walk, drive or public transport";

    private final GoogleMapsPlatformInterface googleMaps;

    public DirectionsController(GoogleMapsPlatformInterface googleMaps) {
        this.googleMaps = googleMaps;
    }

    /**
     * Directions from {@code origin} to {@code destination} (FR-ROUTE-01..07). Returns
     * {@code Route.unavailable(mode)} when no usable route exists, so the page shows "No route found" and no
     * partial route (FR-ROUTE-08). The result's origin and destination are set.
     *
     * @throws InvalidInputException when the origin is not resolved, the destination has no valid coordinate
     *                               or the mode is missing (Get Directions EX-2)
     * @throws sg.schoolmatch.error.ExternalServiceUnavailableException when Google cannot be reached or today's
     *                               budget is used up
     */
    public Route getDirections(ReferenceLocation origin, Place destination, TravelMode mode) {
        checkOriginAndMode(origin, mode);
        if (destination == null || !destination.hasValidCoordinate()) {
            throw new InvalidInputException(DESTINATION_FIELD, DESTINATION_MESSAGE);
        }
        Route route = calculateRoute(origin.getCoordinate(), destination.getCoordinate(), mode);
        Route result = validateRoute(route) ? copy(route, mode) : Route.unavailable(mode);
        result.setOrigin(origin);
        result.setDestination(destination);
        return result;
    }

    /**
     * Commute time (and distance) from {@code origin} to each destination, one Route per destination in the
     * same order (DC-05; FR-FILTER-06, FR-REC-01). A destination without a valid coordinate, or with no route,
     * gets an unavailable Route. Only the destinations with a coordinate are sent, in one matrix call (the live
     * client splits it into batches). Each Route's destination is set, so callers can match it to the place.
     *
     * @throws InvalidInputException when the origin is not resolved or the mode is missing
     * @throws sg.schoolmatch.error.ExternalServiceUnavailableException when Google cannot be reached or today's
     *                               budget is used up
     */
    public List<Route> getCommuteTimes(ReferenceLocation origin, List<? extends Place> destinations, TravelMode mode) {
        checkOriginAndMode(origin, mode);
        if (destinations == null || destinations.isEmpty()) {
            return List.of();
        }
        List<Coordinate> coordinates = new ArrayList<>();
        for (Place place : destinations) {
            if (place != null && place.hasValidCoordinate()) {
                coordinates.add(place.getCoordinate());
            }
        }
        List<Route> found = coordinates.isEmpty() ? List.of()
                : googleMaps.computeRouteMatrix(origin.getCoordinate(), coordinates, mode);
        if (found.size() != coordinates.size()) {
            throw new IllegalStateException("Route matrix returned " + found.size() + " routes for "
                    + coordinates.size() + " destinations");
        }
        List<Route> result = new ArrayList<>(destinations.size());
        int next = 0;
        for (Place place : destinations) {
            Route route = Route.unavailable(mode);
            if (place != null && place.hasValidCoordinate()) {
                Route answer = found.get(next++);
                if (answer != null && answer.isAvailable() && answer.getDurationSeconds() != null
                        && answer.getDurationSeconds() >= 0) {
                    route = copy(answer, mode);
                }
            }
            route.setOrigin(origin);
            route.setDestination(place);
            result.add(route);
        }
        return result;
    }

    /** Asks Google for one route between two coordinates (FR-ROUTE-05). */
    private Route calculateRoute(Coordinate origin, Coordinate destination, TravelMode mode) {
        return googleMaps.computeRoute(origin, destination, mode);
    }

    /**
     * True when the route can be shown in full (FR-ROUTE-06, FR-ROUTE-08): available, distance and duration
     * above 0, and at least one step, numbered 1..n in order with an instruction each.
     */
    private boolean validateRoute(Route route) {   // DC-64
        if (route == null || !route.isAvailable()) {
            return false;
        }
        if (route.getDistanceMetres() == null || route.getDistanceMetres() <= 0
                || route.getDurationSeconds() == null || route.getDurationSeconds() <= 0) {
            return false;
        }
        List<RouteStep> steps = route.getSteps();
        if (steps.isEmpty()) {
            return false;
        }
        for (int i = 0; i < steps.size(); i++) {
            RouteStep step = steps.get(i);
            if (step == null || step.getSequenceNo() != i + 1 || step.getInstruction() == null
                    || step.getInstruction().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static void checkOriginAndMode(ReferenceLocation origin, TravelMode mode) {
        if (origin == null || !origin.isResolved()) {
            throw new InvalidInputException(LOCATION_FIELD, LOCATION_MESSAGE);
        }
        if (mode == null) {
            throw new InvalidInputException(MODE_FIELD, MODE_MESSAGE);
        }
    }

    /** A new Route with the same values, so setting origin/destination never changes a shared object. */
    private static Route copy(Route route, TravelMode mode) {
        return new Route(mode, route.getDistanceMetres(), route.getDurationSeconds(), route.getEncodedPolyline(),
                route.getSteps());
    }
}

package sg.schoolmatch.entity.route;

import java.util.List;
import sg.schoolmatch.entity.common.Place;
import sg.schoolmatch.entity.location.ReferenceLocation;

/**
 * Design class «entity» Route — directions or a commute time from an origin to a place
 * (FR-ROUTE-06, FR-ROUTE-07, FR-ROUTE-08). DC-05: adds {@code encodedPolyline} for drawing the path.
 * An unavailable route has no distance, duration or steps (nothing partial is shown, FR-ROUTE-08).
 */
public class Route {

    private final TravelMode travelMode;
    private final Integer distanceMetres;
    private final Integer durationSeconds;
    private final boolean available;
    private final String encodedPolyline;   // DC-05; null when not provided (e.g. stub)
    private final List<RouteStep> steps;
    private ReferenceLocation origin;
    private Place destination;

    /** An available route. */
    public Route(TravelMode travelMode, Integer distanceMetres, Integer durationSeconds,
                 String encodedPolyline, List<RouteStep> steps) {
        this(travelMode, distanceMetres, durationSeconds, true, encodedPolyline, steps);
    }

    private Route(TravelMode travelMode, Integer distanceMetres, Integer durationSeconds, boolean available,
                  String encodedPolyline, List<RouteStep> steps) {
        this.travelMode = travelMode;
        this.distanceMetres = distanceMetres;
        this.durationSeconds = durationSeconds;
        this.available = available;
        this.encodedPolyline = encodedPolyline;
        this.steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** "No route found" for {@code mode} (FR-ROUTE-08). */
    public static Route unavailable(TravelMode mode) {   // DC-36 helper
        return new Route(mode, null, null, false, null, List.of());
    }

    public boolean isAvailable() {
        return available;
    }

    public TravelMode getTravelMode() {
        return travelMode;
    }

    public Integer getDistanceMetres() {
        return distanceMetres;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public String getEncodedPolyline() {
        return encodedPolyline;
    }

    public List<RouteStep> getSteps() {
        return steps;
    }

    public ReferenceLocation getOrigin() {
        return origin;
    }

    /** Set by DirectionsController (the interface only works with coordinates). */
    public void setOrigin(ReferenceLocation origin) {
        this.origin = origin;
    }

    public Place getDestination() {
        return destination;
    }

    /** Set by DirectionsController (the interface only works with coordinates). */
    public void setDestination(Place destination) {
        this.destination = destination;
    }

    @Override
    public String toString() {
        return "Route{" + travelMode + ", available=" + available + ", " + distanceMetres + " m, "
                + durationSeconds + " s}";
    }
}

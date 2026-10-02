package sg.schoolmatch.boundary.external.google;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.RouteStep;
import sg.schoolmatch.entity.route.TravelMode;

/**
 * JSON bodies of the Google Routes API v2 (computeRoutes, computeRouteMatrix), only the fields we ask for in
 * the field masks, and their mapping to {@link Route}. Shapes checked against the REST reference on 2026-10-02.
 * <p>
 * Google leaves out fields that have their default value (proto3 JSON): an index of 0 or a distance of 0 m is
 * simply missing, so missing numbers are read as 0 where that is what they mean.
 */
final class RoutesJson {

    private RoutesJson() {
    }

    // ---- request -----------------------------------------------------------------------------------------

    record LatLng(double latitude, double longitude) {

        static LatLng of(Coordinate c) {
            return new LatLng(c.getLatitude(), c.getLongitude());
        }
    }

    record Location(LatLng latLng) {
    }

    record Waypoint(Location location) {

        static Waypoint of(Coordinate c) {
            return new Waypoint(new Location(LatLng.of(c)));
        }
    }

    /** Body of {@code POST /directions/v2:computeRoutes}. */
    record ComputeRoutesRequest(Waypoint origin, Waypoint destination, String travelMode,
                                boolean computeAlternativeRoutes, String languageCode, String units) {

        static ComputeRoutesRequest of(Coordinate origin, Coordinate destination, TravelMode mode) {
            return new ComputeRoutesRequest(Waypoint.of(origin), Waypoint.of(destination), mode.name(), false,
                    "en-GB", "METRIC");
        }
    }

    record MatrixWaypoint(Waypoint waypoint) {
    }

    /** Body of {@code POST /distanceMatrix/v2:computeRouteMatrix}. */
    record ComputeRouteMatrixRequest(List<MatrixWaypoint> origins, List<MatrixWaypoint> destinations,
                                     String travelMode) {

        static ComputeRouteMatrixRequest of(Coordinate origin, List<Coordinate> destinations, TravelMode mode) {
            List<MatrixWaypoint> to = destinations.stream().map(d -> new MatrixWaypoint(Waypoint.of(d))).toList();
            return new ComputeRouteMatrixRequest(List.of(new MatrixWaypoint(Waypoint.of(origin))), to, mode.name());
        }
    }

    // ---- computeRoutes response --------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ComputeRoutesResponse(List<RouteJson> routes) {

        /** The first route, or {@code Route.unavailable(mode)} when Google returned none (FR-ROUTE-08). */
        Route toRoute(TravelMode mode) {
            if (routes == null || routes.isEmpty() || routes.getFirst() == null) {
                return Route.unavailable(mode);
            }
            return routes.getFirst().toRoute(mode);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RouteJson(Integer distanceMeters, String duration, Polyline polyline, List<Leg> legs) {

        Route toRoute(TravelMode mode) {
            List<RouteStep> steps = new ArrayList<>();
            for (Leg leg : legs == null ? List.<Leg>of() : legs) {
                for (Step step : leg.steps() == null ? List.<Step>of() : leg.steps()) {
                    steps.add(new RouteStep(steps.size() + 1, step.instruction(), orZero(step.distanceMeters())));
                }
            }
            return new Route(mode, orZero(distanceMeters), parseSeconds(duration),
                    polyline == null ? null : polyline.encodedPolyline(), steps);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Polyline(String encodedPolyline) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Leg(List<Step> steps) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Step(Integer distanceMeters, NavigationInstruction navigationInstruction, String travelMode,
                TransitDetails transitDetails) {

        /** Google's instruction text; for a bus or train step the line name is added ("… (Line 52)"). */
        String instruction() {
            String text = navigationInstruction == null ? null : blankToNull(navigationInstruction.instructions());
            String line = transitDetails == null || transitDetails.transitLine() == null ? null
                    : blankToNull(transitDetails.transitLine().nameShort());
            if (line != null) {
                return text == null ? "Take " + line : text + " (" + line + ")";
            }
            if (text != null) {
                return text;
            }
            return "TRANSIT".equals(travelMode) ? "Take public transport" : "Continue";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NavigationInstruction(String instructions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransitDetails(TransitLine transitLine) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransitLine(String nameShort) {
    }

    // ---- computeRouteMatrix response (a JSON array of these) ---------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MatrixElement(Integer originIndex, Integer destinationIndex, Status status, String condition,
                         Integer distanceMeters, String duration) {

        int destination() {
            return destinationIndex == null ? 0 : destinationIndex;
        }

        /** True when Google found a route ({@code ROUTE_EXISTS}) and reported no error for this element. */
        boolean routeExists() {
            boolean ok = status == null || status.code() == null || status.code() == 0;
            return ok && "ROUTE_EXISTS".equals(condition);
        }

        /** Distance and duration only; the matrix has no steps or polyline. */
        Route toRoute(TravelMode mode) {
            Integer seconds = parseSeconds(duration);
            if (!routeExists() || seconds == null) {
                return Route.unavailable(mode);
            }
            return new Route(mode, orZero(distanceMeters), seconds, null, List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Status(Integer code, String message) {
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** {@code "1234s"} or {@code "3.5s"} → whole seconds (rounded); null when missing or not in that format. */
    static Integer parseSeconds(String duration) {
        if (duration == null || !duration.endsWith("s")) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(duration.substring(0, duration.length() - 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

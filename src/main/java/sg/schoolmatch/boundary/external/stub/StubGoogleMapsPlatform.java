package sg.schoolmatch.boundary.external.stub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import sg.schoolmatch.boundary.external.GoogleMapsPlatformInterface;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.RouteStep;
import sg.schoolmatch.entity.route.TravelMode;

/**
 * Offline {@link GoogleMapsPlatformInterface} (app.external.google.mode=stub, the default). Deterministic fake
 * data, so pages work without keys or costs; pages show a "Demo data (stub)" badge. The stub never charges
 * the ExternalCallBudget and is not cached.
 * <ul>
 *   <li>Routes: straight line × 1.3 at WALK 5 / TRANSIT 20 / DRIVE 35 km/h, three "(stub)" steps, and an
 *       encoded polyline of the straight line so the page can draw the route line (FR-ROUTE-07, DC-05).</li>
 *   <li>Places: two obviously fake facilities per type near the centre, named "(stub) …". The placeId encodes
 *       type, number and centre ({@code stub-library-1-1354525-103844901}), so details work after a restart.</li>
 * </ul>
 */
@Component
// Any mode except "live" (also an empty or missing value) selects the stub, so exactly one bean always exists.
@ConditionalOnExpression("!'live'.equalsIgnoreCase('${app.external.google.mode:stub}')")
public class StubGoogleMapsPlatform implements GoogleMapsPlatformInterface {

    static final double ROAD_FACTOR = 1.3;

    /** Share of the route distance in each of the three steps (start, main part, end). */
    private static final double[] STEP_SHARES = {0.1, 0.8, 0.1};

    /** Offsets (degrees lat, lng) of the fake facilities from the centre: about 0.6 km and 1.2 km away. */
    private static final double[][] OFFSETS = {{0.0045, 0.0030}, {-0.0090, -0.0060}};
    private static final Pattern PLACE_ID = Pattern.compile("stub-(library|tuition-centre)-([12])-(-?\\d+)-(-?\\d+)");
    private static final double MICRO = 1_000_000d;

    @Override
    public Route computeRoute(Coordinate origin, Coordinate destination, TravelMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (origin == null || destination == null) {
            return Route.unavailable(mode);
        }
        double km = origin.distanceTo(destination) * ROAD_FACTOR;
        int metres = (int) Math.round(km * 1000);
        int seconds = (int) Math.round(km / speedKmh(mode) * 3600);
        return new Route(mode, metres, seconds, encodePolyline(List.of(origin, destination)), steps(mode, metres));
    }

    @Override
    public List<Route> computeRouteMatrix(Coordinate origin, List<Coordinate> destinations, TravelMode mode) {
        return destinations.stream().map(d -> computeRoute(origin, d, mode)).toList();
    }

    @Override
    public List<Facility> searchPlaces(FacilityType type, Coordinate centre) {
        if (type == null || centre == null) {
            return List.of();
        }
        long lat = Math.round(centre.getLatitude() * MICRO);
        long lng = Math.round(centre.getLongitude() * MICRO);
        List<Facility> result = new ArrayList<>();
        for (int n = 1; n <= OFFSETS.length; n++) {
            result.add(fake(type, n, lat, lng));
        }
        return result;
    }

    @Override
    public Optional<Facility> getPlaceDetails(String placeId) {
        if (placeId == null) {
            return Optional.empty();
        }
        Matcher m = PLACE_ID.matcher(placeId);
        if (!m.matches()) {
            return Optional.empty();
        }
        FacilityType type = FacilityType.valueOf(m.group(1).replace('-', '_').toUpperCase(Locale.ROOT));
        return Optional.of(fake(type, Integer.parseInt(m.group(2)), Long.parseLong(m.group(3)),
                Long.parseLong(m.group(4))));
    }

    /**
     * Encodes points with Google's polyline algorithm (5 decimal places), the format the browser decodes with
     * {@code google.maps.geometry.encoding.decodePath}.
     */
    static String encodePolyline(List<Coordinate> points) {
        StringBuilder out = new StringBuilder();
        long previousLat = 0;
        long previousLng = 0;
        for (Coordinate point : points) {
            long lat = Math.round(point.getLatitude() * 1e5);
            long lng = Math.round(point.getLongitude() * 1e5);
            encodeValue(lat - previousLat, out);
            encodeValue(lng - previousLng, out);
            previousLat = lat;
            previousLng = lng;
        }
        return out.toString();
    }

    /** One signed value: shift left, invert if negative, then 5-bit chunks with a continuation bit, plus 63. */
    private static void encodeValue(long delta, StringBuilder out) {
        long value = delta < 0 ? ~(delta << 1) : delta << 1;
        while (value >= 0x20) {
            out.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        out.append((char) (value + 63));
    }

    /** Three plausible steps whose distances add up to {@code metres}. */
    private static List<RouteStep> steps(TravelMode mode, int metres) {
        String[] texts = switch (mode) {
            case WALK -> new String[] {"(stub) Walk to the main road", "(stub) Continue straight ahead",
                    "(stub) Arrive at the destination"};
            case DRIVE -> new String[] {"(stub) Drive to the main road", "(stub) Continue straight ahead",
                    "(stub) Park near the destination"};
            case TRANSIT -> new String[] {"(stub) Walk to the nearest stop", "(stub) Take public transport",
                    "(stub) Walk to the destination"};
        };
        int first = (int) Math.round(metres * STEP_SHARES[0]);
        int middle = (int) Math.round(metres * STEP_SHARES[1]);
        int last = metres - first - middle;
        return List.of(new RouteStep(1, texts[0], first), new RouteStep(2, texts[1], middle),
                new RouteStep(3, texts[2], last));
    }

    /** Facility number {@code n} of {@code type} around the centre given in micro-degrees. */
    private static Facility fake(FacilityType type, int n, long latMicro, long lngMicro) {
        String typeSlug = type.name().toLowerCase(Locale.ROOT).replace('_', '-');
        String label = type == FacilityType.LIBRARY ? "Library" : "Tuition Centre";
        Facility facility = new Facility("stub-" + typeSlug + "-" + n + "-" + latMicro + "-" + lngMicro,
                "(stub) Nearby " + label + " " + n, type);
        double[] offset = OFFSETS[n - 1];
        // Tuition centres mirror the libraries east–west: same distance from the centre, but their map pins do not
        // sit on top of each other.
        double lngOffset = type == FacilityType.TUITION_CENTRE ? -offset[1] : offset[1];
        facility.setCoordinate(new Coordinate(latMicro / MICRO + offset[0], lngMicro / MICRO + lngOffset));
        facility.setAddress("(stub) Demo address " + n + ", not a real place");
        if (n == 1) {   // facility 2 keeps null telephone / hours, to show "Not available"
            facility.setTelephone("(stub) 6000 000" + n);
            facility.setOpeningHours("(stub) Mon–Sun 10:00–21:00");
        }
        return facility;
    }

    private static double speedKmh(TravelMode mode) {
        return switch (mode) {
            case WALK -> 5;
            case TRANSIT -> 20;
            case DRIVE -> 35;
        };
    }
}

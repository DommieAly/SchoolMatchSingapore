package sg.schoolmatch.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import sg.schoolmatch.boundary.external.OneMapHit;
import sg.schoolmatch.boundary.external.OneMapInterface;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Design class «control» LocationController — turns device positions and typed addresses into a
 * ReferenceLocation (FR-FILTER-04, FR-ROUTE-02, NFR-SEC-06). DC-11: uses OneMap, not Google.
 * DC-14: depends on SchoolDataController (planning-area check). Called by DirectionsUI (/location*, DC-23),
 * ProfileController, FilterController, RecommendationController.
 * <p>
 * Rules: an address has 1–200 characters after trimming; OneMap hits are kept only when their point is inside
 * Singapore; hits at the same point (rounded to 5 decimal places, about 1 m) count once; at most 5 candidates.
 * When OneMap cannot be reached its client throws {@code ExternalServiceUnavailableException}, which reaches the
 * caller unchanged.
 */
@Service
public class LocationController {

    /** Field name in {@code InvalidInputException} for a typed address (the form field is {@code address}). */
    public static final String ADDRESS = "address";
    /** Field name in {@code InvalidInputException} for a device position. */
    public static final String LOCATION = "location";

    public static final int MAX_ADDRESS_LENGTH = 200;
    /** DC-11: the picker shows at most this many matches. */
    public static final int MAX_CANDIDATES = 5;

    /** Label of a starting point that came from the browser (the location picker shows it). */
    public static final String DEVICE_LABEL = "Your current location";

    public static final String ADDRESS_LENGTH_MESSAGE = "Enter an address or postal code (1–200 characters)";
    public static final String OUTSIDE_SINGAPORE_MESSAGE = "That location is outside Singapore";
    public static final String NO_POSITION_MESSAGE = "Your location could not be read";
    public static final String NO_MATCH_MESSAGE = "No match: no address in Singapore matches that text";
    public static final String SEVERAL_MATCHES_MESSAGE = "Several matches — choose one";

    private static final double FIVE_DECIMALS = 1e5;

    private final SchoolDataController schoolDataController;
    private final OneMapInterface oneMap;

    public LocationController(SchoolDataController schoolDataController, OneMapInterface oneMap) {
        this.schoolDataController = schoolDataController;
        this.oneMap = oneMap;
    }

    /**
     * DC-11: wraps the position sent by the browser (after the user allowed it, NFR-SEC-06).
     *
     * @throws InvalidInputException (field {@code location}) when the point is missing or outside Singapore
     */
    public ReferenceLocation getDeviceLocation(Coordinate fromBrowser) {
        if (fromBrowser == null) {
            throw new InvalidInputException(LOCATION, NO_POSITION_MESSAGE);
        }
        if (!fromBrowser.isWithinSingapore()) {
            throw new InvalidInputException(LOCATION, OUTSIDE_SINGAPORE_MESSAGE);
        }
        return new ReferenceLocation(fromBrowser, LocationSource.DEVICE_LOCATION, DEVICE_LABEL);
    }

    /**
     * The single location for {@code address}.
     *
     * @throws InvalidInputException (field {@code address}) when the text is not valid, or when there is no match
     *                               or more than one
     */
    public ReferenceLocation resolveAddress(String address) {
        List<ReferenceLocation> candidates = findCandidates(address);
        if (candidates.isEmpty()) {
            throw new InvalidInputException(ADDRESS, NO_MATCH_MESSAGE);
        }
        if (candidates.size() > 1) {
            throw new InvalidInputException(ADDRESS, SEVERAL_MATCHES_MESSAGE);
        }
        return candidates.getFirst();
    }

    /**
     * DC-11: up to 5 distinct candidate locations for an address or postal code, in OneMap's order.
     * Each one is a MANUAL_ENTRY location labelled with OneMap's address. Empty when nothing in Singapore matches.
     *
     * @throws InvalidInputException (field {@code address}) unless the trimmed text has 1–200 characters
     * @throws sg.schoolmatch.error.ExternalServiceUnavailableException when OneMap cannot be reached
     */
    public List<ReferenceLocation> findCandidates(String address) {
        String text = address == null ? "" : address.strip();
        if (text.isEmpty() || text.length() > MAX_ADDRESS_LENGTH) {
            throw new InvalidInputException(ADDRESS, ADDRESS_LENGTH_MESSAGE);
        }
        List<OneMapHit> hits = oneMap.search(text);
        Map<String, ReferenceLocation> byPoint = new LinkedHashMap<>();   // keeps OneMap's order
        for (OneMapHit hit : hits == null ? List.<OneMapHit>of() : hits) {
            Coordinate point = toCoordinate(hit);
            if (point == null || !point.isWithinSingapore()) {
                continue;
            }
            byPoint.putIfAbsent(pointKey(point),
                    new ReferenceLocation(point, LocationSource.MANUAL_ENTRY, label(hit, text)));
            if (byPoint.size() == MAX_CANDIDATES) {
                break;
            }
        }
        return List.copyOf(byPoint.values());
    }

    /** The hit's point, or null when OneMap sent impossible numbers. */
    private static Coordinate toCoordinate(OneMapHit hit) {
        if (hit == null) {
            return null;
        }
        try {
            return new Coordinate(hit.latitude(), hit.longitude());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Points less than about 1 m apart (same value at 5 decimal places) are the same place. */
    private static String pointKey(Coordinate point) {
        return Math.round(point.getLatitude() * FIVE_DECIMALS) + "," + Math.round(point.getLongitude() * FIVE_DECIMALS);
    }

    /** OneMap's address, else the building name, else the search value. OneMap writes "NIL" for no value. */
    private static String label(OneMapHit hit, String typed) {
        for (String value : new String[] {hit.address(), hit.building(), hit.searchVal()}) {
            if (value != null && !value.isBlank() && !"NIL".equalsIgnoreCase(value.strip())) {
                return value.strip();
            }
        }
        return typed;
    }
}

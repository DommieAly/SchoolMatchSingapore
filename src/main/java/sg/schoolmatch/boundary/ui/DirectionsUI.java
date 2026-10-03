package sg.schoolmatch.boundary.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import sg.schoolmatch.boundary.ui.support.AuthInterceptor;
import sg.schoolmatch.boundary.ui.support.FilterParams;
import sg.schoolmatch.boundary.ui.support.MapMarker;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.control.DirectionsController;
import sg.schoolmatch.control.FacilityController;
import sg.schoolmatch.control.LocationController;
import sg.schoolmatch.control.MapController;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.common.Place;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.RouteStep;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalFailureLog;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Design class «boundary» DirectionsUI — starting location, travel mode and the route
 * (DM-19 DirectionsInput, DM-20 DirectionsLoading, DM-21 Directions; use case Get Directions, FR-ROUTE-01..04,
 * FR-ROUTE-07, FR-ROUTE-08). DC-23: also owns the starting-point forms {@code POST /location*}, which the filter
 * and recommendation pages use too (fragment {@code location-picker}). The starting point lives in the HTTP
 * session through {@link ReferenceLocationStore}, never in the database.
 * <p>
 * {@code to} = {@code school:<code>} (a bare school code also works) or {@code facility:<placeId>};
 * {@code from} = the local page that Cancel returns to (default: the destination's details page).
 * <ul>
 *   <li>No starting point → back to the input page with "Set a starting point first" (AF-1).</li>
 *   <li>No route → input page with "No route found", nothing partial is shown (AF-2, FR-ROUTE-08).</li>
 *   <li>Routing service down → input page with "Directions are temporarily unavailable" (EX-1).</li>
 *   <li>Locations refused by the control → input page with the reason (EX-2).</li>
 * </ul>
 * Privacy (UC Filter Schools, NFR-SEC-06): typed addresses and positions are never logged.
 */
@Controller
public class DirectionsUI {

    static final String SCHOOL_PREFIX = "school:";
    static final String FACILITY_PREFIX = "facility:";

    // Messages (NFR-USE-03).
    static final String NO_DESTINATION_MESSAGE = "Choose a school or facility first, then select Get directions.";
    static final String NO_START_MESSAGE = "Set a starting point first.";
    static final String NO_ROUTE_MESSAGE =
            "No route found for this travel mode. Try another travel mode or another starting point.";
    static final String DIRECTIONS_UNAVAILABLE_MESSAGE =
            "Directions are temporarily unavailable. Please try again in a few minutes.";
    static final String INVALID_LOCATIONS_MESSAGE =
            "Directions cannot be calculated from this starting point to this place. Try another starting point.";
    static final String ADDRESS_NOT_FOUND_MESSAGE =
            "No address in Singapore matches that text. Check the spelling or enter a postal code.";
    static final String SEVERAL_FOUND_MESSAGE = "Several places match. Choose one from the list.";
    static final String ADDRESS_SEARCH_UNAVAILABLE_MESSAGE =
            "Address search is temporarily unavailable. Please try again later, or use your current location.";
    static final String DEVICE_UNREADABLE_MESSAGE =
            "Your location could not be read. Type an address or postal code instead.";
    static final String CHOICE_GONE_MESSAGE = "That choice is no longer available. Enter the address again.";
    static final String CLEARED_MESSAGE = "Starting point cleared.";
    static final String SET_MESSAGE_PREFIX = "Starting point set: ";

    /** Flash attribute that refills the picker's address field after a failed search. */
    static final String LOCATION_ADDRESS = "locationAddress";

    private static final Logger log = LoggerFactory.getLogger(DirectionsUI.class);

    private final SchoolController schoolController;           // DC-37: loads the destination school
    private final FacilityController facilityController;       // loads the destination facility
    private final DirectionsController directionsController;
    private final LocationController locationController;
    private final MapController mapController;
    private final ReferenceLocationStore locationStore;
    private final JsonMapper jsonMapper;

    public DirectionsUI(SchoolController schoolController, FacilityController facilityController,
                        DirectionsController directionsController, LocationController locationController,
                        MapController mapController, ReferenceLocationStore locationStore, JsonMapper jsonMapper) {
        this.schoolController = schoolController;
        this.facilityController = facilityController;
        this.directionsController = directionsController;
        this.locationController = locationController;
        this.mapController = mapController;
        this.locationStore = locationStore;
        this.jsonMapper = jsonMapper;
    }

    /** A travel mode as the pages show it; {@code url} opens the route for that mode. */
    public record ModeOption(TravelMode mode, String label, String url) {
    }

    /** One numbered step of the route (FR-ROUTE-07); {@code distance} is null when unknown. */
    public record StepView(int number, String instruction, String distance) {
    }

    // ---- DM-19..21: the directions pages ----------------------------------------------------------------------

    /** GET /directions?to=&amp;from= — from "Get directions" on a school or facility page (DM-19, T-28, T-43). */
    @GetMapping("/directions")
    public String selectGetDirections(@RequestParam(required = false) String to,
                                      @RequestParam(required = false) String from,
                                      HttpServletRequest request, Model model) {
        Place destination = loadDestination(to);
        addInputAttributes(model, request, to, from, destination, null);
        return "directions-input";
    }

    /**
     * GET /directions?to=&amp;mode=&amp;from= — the route (T-53; the loading overlay shows while it loads, DM-20).
     * Renders the route page (DM-21, T-57) or, when something is missing or fails, the input page with a message
     * (T-56, T-58, T-59).
     */
    @GetMapping(value = "/directions", params = "mode")
    public String selectTravelMode(@RequestParam(required = false) String to, @RequestParam String mode,
                                   @RequestParam(required = false) String from,
                                   HttpServletRequest request, Model model) {
        Place destination = loadDestination(to);
        Optional<TravelMode> travelMode = parseMode(mode);
        ReferenceLocation start = addInputAttributes(model, request, to, from, destination, travelMode.orElse(null));
        if (destination == null) {
            model.addAttribute(PageMessages.FLASH_ERROR, NO_DESTINATION_MESSAGE);
            return "directions-input";
        }
        if (travelMode.isEmpty()) {
            model.addAttribute(PageMessages.FIELD_ERRORS, Map.of(FilterParams.MODE, FilterParams.MODE_MESSAGE));
            return "directions-input";
        }
        if (!destination.hasValidCoordinate()) {
            return "directions-input";   // the page explains that this place has no map location (EX-2)
        }
        if (start == null || !start.isResolved()) {
            model.addAttribute(PageMessages.FLASH_ERROR, NO_START_MESSAGE);   // AF-1
            return "directions-input";
        }

        Route route;
        try {
            route = directionsController.getDirections(start, destination, travelMode.get());
        } catch (ExternalServiceUnavailableException e) {
            ExternalFailureLog.warn(log, "Directions", e);
            model.addAttribute(PageMessages.FLASH_ERROR, DIRECTIONS_UNAVAILABLE_MESSAGE);   // EX-1, T-59
            return "directions-input";
        } catch (InvalidInputException e) {
            model.addAttribute(PageMessages.FLASH_ERROR, INVALID_LOCATIONS_MESSAGE);        // EX-2
            model.addAttribute(PageMessages.FIELD_ERRORS, e.getFieldErrors());
            return "directions-input";
        }
        if (route == null || !route.isAvailable()) {
            model.addAttribute(PageMessages.FLASH_ERROR, NO_ROUTE_MESSAGE);                 // AF-2, T-58
            return "directions-input";
        }
        displayDirections(model, route, start, destination, travelMode.get(), to, from);
        return "directions";
    }

    // ---- DC-23: the starting-point forms (fragments/location-picker.html) -------------------------------------

    /**
     * POST /location (address, returnTo) — enterManualLocation (T-79). One match becomes the starting point;
     * several are kept for the picker to choose from (DC-11); none is an error message.
     */
    @PostMapping("/location")
    public RedirectView enterManualLocation(@RequestParam(required = false) String address,
                                            @RequestParam(required = false) String returnTo,
                                            HttpServletRequest request, RedirectAttributes redirect) {
        try {
            List<ReferenceLocation> candidates = locationController.findCandidates(address);
            if (candidates.isEmpty()) {
                locationStore.clearCandidates(request.getSession(false));
                failed(redirect, ADDRESS_NOT_FOUND_MESSAGE, address);
            } else if (candidates.size() == 1) {
                locationStore.set(request.getSession(), candidates.getFirst());
                redirect.addFlashAttribute(PageMessages.FLASH_MESSAGE, setMessage(candidates.getFirst()));
            } else {
                locationStore.setCandidates(request.getSession(), candidates);
                redirect.addFlashAttribute(PageMessages.FLASH_MESSAGE, SEVERAL_FOUND_MESSAGE);
            }
        } catch (InvalidInputException e) {
            failed(redirect, firstMessage(e), address);
        } catch (ExternalServiceUnavailableException e) {
            ExternalFailureLog.warn(log, "Address search", e);
            failed(redirect, ADDRESS_SEARCH_UNAVAILABLE_MESSAGE, address);
        }
        return backTo(returnTo);
    }

    /**
     * POST /location/device (latitude, longitude, returnTo) — requestStartingLocation (T-81). Sent by
     * DeviceLocationInterface.js only after the user clicked and the browser asked for permission (NFR-SEC-06).
     */
    @PostMapping("/location/device")
    public RedirectView requestStartingLocation(@RequestParam(required = false) String latitude,
                                                @RequestParam(required = false) String longitude,
                                                @RequestParam(required = false) String returnTo,
                                                HttpServletRequest request, RedirectAttributes redirect) {
        Coordinate position = parseCoordinate(latitude, longitude);
        if (position == null) {
            redirect.addFlashAttribute(PageMessages.FLASH_ERROR, DEVICE_UNREADABLE_MESSAGE);
            return backTo(returnTo);
        }
        try {
            ReferenceLocation location = locationController.getDeviceLocation(position);
            locationStore.set(request.getSession(), location);
            redirect.addFlashAttribute(PageMessages.FLASH_MESSAGE, setMessage(location));
        } catch (InvalidInputException e) {
            redirect.addFlashAttribute(PageMessages.FLASH_ERROR, firstMessage(e));
        }
        return backTo(returnTo);
    }

    /** POST /location/choose (index, returnTo) — selectLocationCandidate (DC-23, T-80): index 0..n-1 of the list. */
    @PostMapping("/location/choose")
    public RedirectView selectLocationCandidate(@RequestParam(required = false) String index,
                                                @RequestParam(required = false) String returnTo,
                                                HttpServletRequest request, RedirectAttributes redirect) {
        HttpSession session = request.getSession(false);
        List<ReferenceLocation> candidates = locationStore.getCandidates(session);
        Integer chosen = parseIndex(index);
        if (chosen == null || chosen >= candidates.size()) {
            redirect.addFlashAttribute(PageMessages.FLASH_ERROR, CHOICE_GONE_MESSAGE);
            return backTo(returnTo);
        }
        ReferenceLocation location = candidates.get(chosen);
        locationStore.set(session, location);   // also drops the list
        redirect.addFlashAttribute(PageMessages.FLASH_MESSAGE, setMessage(location));
        return backTo(returnTo);
    }

    /**
     * POST /location/clear (returnTo, candidatesOnly) — clearLocation (DC-23, T-82): forgets the starting point and
     * the candidate list. With {@code candidatesOnly=true} ("None of these") only the list is dropped.
     */
    @PostMapping("/location/clear")
    public RedirectView clearLocation(@RequestParam(required = false) String returnTo,
                                      @RequestParam(required = false) String candidatesOnly,
                                      HttpServletRequest request, RedirectAttributes redirect) {
        HttpSession session = request.getSession(false);
        if ("true".equalsIgnoreCase(candidatesOnly)) {
            locationStore.clearCandidates(session);
        } else {
            locationStore.clear(session);
            redirect.addFlashAttribute(PageMessages.FLASH_MESSAGE, CLEARED_MESSAGE);
        }
        return backTo(returnTo);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** The model of the input page (also the base of the route page). Returns the stored starting point. */
    private ReferenceLocation addInputAttributes(Model model, HttpServletRequest request, String to, String from,
                                                 Place destination, TravelMode mode) {
        ReferenceLocation start = locationStore.get(request).orElse(null);
        String safeFrom = AuthInterceptor.safeLocalPath(from);
        String detailsUrl = detailsUrl(destination, safeFrom);
        model.addAttribute("to", to);
        model.addAttribute("from", safeFrom);
        model.addAttribute("destination", destination);
        model.addAttribute("destinationUrl", detailsUrl);
        model.addAttribute("destinationMappable", destination != null && destination.hasValidCoordinate());
        model.addAttribute("startLocation", start);
        model.addAttribute("modes", modeOptions(to, safeFrom));
        model.addAttribute("mode", mode == null ? TravelMode.WALK : mode);
        model.addAttribute("returnTo", directionsUrl(to, mode, safeFrom));   // picker comes back here
        model.addAttribute("cancelUrl", safeFrom != null ? safeFrom : detailsUrl != null ? detailsUrl : "/schools");
        return start;
    }

    /** displayDirections (FR-ROUTE-07): distance, travel time, numbered steps, and the map with the route line. */
    private void displayDirections(Model model, Route route, ReferenceLocation start, Place destination,
                                   TravelMode mode, String to, String from) {
        String safeFrom = AuthInterceptor.safeLocalPath(from);
        boolean interactive = mapController.displayInteractiveMap(List.of(destination));
        List<MapMarker> markers = List.of(MapMarker.start(start), marker(destination));
        model.addAttribute("route", route);
        model.addAttribute("modeLabel", label(mode));
        model.addAttribute("distanceKm", route.getDistanceMetres() == null ? null : km(route.getDistanceMetres()));
        model.addAttribute("durationText", duration(route.getDurationSeconds()));
        model.addAttribute("steps", steps(route));
        model.addAttribute("changeUrl", directionsUrl(to, null, safeFrom));
        model.addAttribute("interactive", interactive);
        model.addAttribute("markersJson", MapMarker.toJson(jsonMapper, markers));
        if (interactive && route.getEncodedPolyline() != null) {
            model.addAttribute("routePolyline", route.getEncodedPolyline());   // read by map.js (drawRoute)
        }
    }

    /** The school or facility named by {@code to}; null when there is none. Unknown ids → 404 (NotFoundException). */
    private Place loadDestination(String to) {
        if (to == null || to.isBlank()) {
            return null;
        }
        String text = to.strip();
        if (text.startsWith(FACILITY_PREFIX)) {
            String placeId = text.substring(FACILITY_PREFIX.length()).strip();
            return placeId.isEmpty() ? null : facilityController.getFacilityDetails(placeId);
        }
        String code = text.startsWith(SCHOOL_PREFIX) ? text.substring(SCHOOL_PREFIX.length()).strip() : text;
        return code.isEmpty() ? null : schoolController.getSchoolDetails(code);
    }

    /**
     * The destination's details page. For a facility the school it was found near is kept ({@code ?from=code}),
     * read from {@code from} ({@code /schools/{code}/facilities…} or {@code /facilities/{id}?from={code}}), so the
     * facility page still has "Back to facilities" and the distance from the school.
     */
    private static String detailsUrl(Place destination, String safeFrom) {
        if (destination instanceof School school) {
            return UriComponentsBuilder.fromPath("/schools/{code}").buildAndExpand(school.getSchoolCode())
                    .encode().toUriString();
        }
        if (destination instanceof Facility facility) {
            UriComponentsBuilder url = UriComponentsBuilder.fromPath("/facilities/{placeId}");
            String schoolCode = schoolCodeIn(safeFrom);
            if (schoolCode != null) {
                url.queryParam("from", schoolCode);
            }
            return url.buildAndExpand(facility.getPlaceId()).encode().toUriString();
        }
        return null;
    }

    private static final Pattern SCHOOL_FACILITIES_PATH = Pattern.compile("^/schools/([^/?#]+)/facilities(?:[/?#].*)?$");
    private static final Pattern FROM_PARAM = Pattern.compile("[?&]from=([^&#]+)");

    /** The school code in a "from" path of the facility pages, or null. */
    static String schoolCodeIn(String safeFrom) {
        if (safeFrom == null) {
            return null;
        }
        Matcher path = SCHOOL_FACILITIES_PATH.matcher(safeFrom);
        if (path.matches()) {
            return AuthInterceptor.safeSchoolCode(path.group(1));
        }
        if (safeFrom.startsWith("/facilities/")) {
            Matcher param = FROM_PARAM.matcher(safeFrom);
            if (param.find()) {
                return AuthInterceptor.safeSchoolCode(param.group(1));
            }
        }
        return null;
    }

    private static MapMarker marker(Place destination) {
        return destination instanceof Facility facility ? MapMarker.of(facility) : MapMarker.of((School) destination);
    }

    /** /directions with the given parameters (null ones left out); every reserved character is encoded. */
    static String directionsUrl(String to, TravelMode mode, String from) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath("/directions");
        Map<String, Object> values = new LinkedHashMap<>();
        if (to != null) {
            url.queryParam("to", "{to}");
            values.put("to", to);
        }
        if (mode != null) {
            url.queryParam("mode", "{mode}");
            values.put("mode", mode.name());
        }
        if (from != null) {
            url.queryParam("from", "{from}");
            values.put("from", from);
        }
        return url.encode().buildAndExpand(values).toUriString();
    }

    private static List<ModeOption> modeOptions(String to, String from) {
        List<ModeOption> options = new ArrayList<>();
        for (TravelMode mode : TravelMode.values()) {
            options.add(new ModeOption(mode, label(mode), directionsUrl(to, mode, from)));
        }
        return options;
    }

    /** FR-ROUTE-04: the three modes as the use case names them. */
    static String label(TravelMode mode) {
        return switch (mode) {
            case WALK -> "Walk";
            case DRIVE -> "Drive";
            case TRANSIT -> "Public transport";
        };
    }

    private static Optional<TravelMode> parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(TravelMode.valueOf(mode.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static List<StepView> steps(Route route) {
        return route.getSteps().stream()
                .sorted(Comparator.comparingInt(RouteStep::getSequenceNo))
                .map(step -> new StepView(step.getSequenceNo(), step.getInstruction(),
                        step.getDistanceMetres() == null ? null : stepDistance(step.getDistanceMetres())))
                .toList();
    }

    /** "2.4" (km, one decimal place). */
    static String km(int metres) {
        return String.format(Locale.ROOT, "%.1f", metres / 1000.0);
    }

    /** "350 m" below 1 km, else "2.0 km". */
    static String stepDistance(int metres) {
        return metres < 1000 ? metres + " m" : km(metres) + " km";
    }

    /** "12 min", or "1 h 5 min" from an hour; at least 1 min; null when unknown. */
    static String duration(Integer seconds) {
        if (seconds == null) {
            return null;
        }
        long minutes = Math.max(1, Math.round(seconds / 60.0));
        if (minutes < 60) {
            return minutes + " min";
        }
        long rest = minutes % 60;
        return (minutes / 60) + " h" + (rest == 0 ? "" : " " + rest + " min");
    }

    private static Coordinate parseCoordinate(String latitude, String longitude) {
        try {
            double lat = Double.parseDouble(latitude.strip());
            double lng = Double.parseDouble(longitude.strip());
            if (!Double.isFinite(lat) || !Double.isFinite(lng)) {
                return null;
            }
            return new Coordinate(lat, lng);   // checks the ranges
        } catch (RuntimeException e) {         // null, not a number, out of range
            return null;
        }
    }

    private static Integer parseIndex(String index) {
        try {
            int value = Integer.parseInt(index.strip());
            return value < 0 ? null : value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String setMessage(ReferenceLocation location) {
        return SET_MESSAGE_PREFIX + location.getInputText();
    }

    private static String firstMessage(InvalidInputException e) {
        return e.getFieldErrors().values().stream().findFirst().orElse(DEVICE_UNREADABLE_MESSAGE);
    }

    private static void failed(RedirectAttributes redirect, String message, String typedAddress) {
        redirect.addFlashAttribute(PageMessages.FLASH_ERROR, message);
        if (typedAddress != null) {
            redirect.addFlashAttribute(LOCATION_ADDRESS, typedAddress.strip());
        }
    }

    /** Redirect to {@code returnTo} when it is a safe local path, otherwise to /directions. */
    private static RedirectView backTo(String returnTo) {
        String target = AuthInterceptor.safeLocalPath(returnTo);
        RedirectView view = new RedirectView(target == null ? "/directions" : target, true);
        view.setExpandUriTemplateVariables(false);
        return view;
    }
}

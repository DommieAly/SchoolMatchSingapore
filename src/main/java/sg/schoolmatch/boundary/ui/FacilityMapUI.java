package sg.schoolmatch.boundary.ui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import sg.schoolmatch.boundary.ui.support.MapMarker;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.control.FacilityController;
import sg.schoolmatch.control.MapController;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.entity.common.Place;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityFilterCriteria;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalFailureLog;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Design class «boundary» FacilityMapUI — nearby facilities as map markers around the school
 * (DM-15 FacilityMap; use case View Facilities on Map, FR-FACMAP-01..05). The school is the reference point
 * (FR-FACILITY-02). The page takes the same {@code type} and {@code radiusKm} as the facilities list
 * (NearbyFacilitiesUI), so the markers match the list (FR-FACMAP-04).
 * <ul>
 *   <li>Facilities without a valid location are left out and counted (AF-1, FR-FACMAP-05).</li>
 *   <li>No facility → a message; the school marker still shows (AF-2).</li>
 *   <li>Places service down → "temporarily unavailable" (EX-1 of View Nearby Facilities).</li>
 *   <li>No browser key / limit reached → "Map unavailable"; the list beside it still works (EX-1).</li>
 * </ul>
 * Model: {@code school}, {@code facilities} (rows with a location), {@code omittedCount}, {@code radiusKm},
 * {@code typesLabel}, {@code interactive}, {@code markersJson}, {@code listUrl}, and {@code fieldErrors} /
 * {@code serviceUnavailable} / {@code schoolNotMappable} when something went wrong.
 */
@Controller
public class FacilityMapUI {

    static final String TYPE = "type";
    static final String RADIUS_KM = "radiusKm";
    static final String TYPE_MESSAGE = "Choose library or tuition centre";
    static final String RADIUS_MESSAGE = "Choose 1, 2 or 3 km";

    private static final Logger log = LoggerFactory.getLogger(FacilityMapUI.class);

    private final SchoolController schoolController;   // DC-37: loads the school from the URL
    private final FacilityController facilityController;
    private final MapController mapController;
    private final JsonMapper jsonMapper;

    public FacilityMapUI(SchoolController schoolController, FacilityController facilityController,
                         MapController mapController, JsonMapper jsonMapper) {
        this.schoolController = schoolController;
        this.facilityController = facilityController;
        this.mapController = mapController;
        this.jsonMapper = jsonMapper;
    }

    /** One facility in the list beside the map. */
    public record FacilityRow(Facility facility, String typeLabel, String distanceKm, String detailsUrl) {
    }

    /**
     * GET /schools/{code}/facilities/map?type=&amp;radiusKm= — "View on map" from the facilities list
     * (selectViewOnMap, T-37). Unknown code → 404.
     */
    @GetMapping("/schools/{code}/facilities/map")
    public String displayFacilityMarkers(@PathVariable String code,
                                         @RequestParam(name = TYPE, required = false) List<String> types,
                                         @RequestParam(name = RADIUS_KM, required = false) String radiusKm,
                                         Model model) {
        School school = schoolController.getSchoolDetails(code);
        Map<String, String> errors = new LinkedHashMap<>();
        Set<FacilityType> selectedTypes = parseTypes(types, errors);
        int radius = parseRadius(radiusKm, errors);
        FacilityFilterCriteria criteria = new FacilityFilterCriteria(selectedTypes, radius);

        List<Facility> facilities = List.of();
        if (!school.hasValidCoordinate()) {
            model.addAttribute("schoolNotMappable", true);
        } else {
            try {
                facilities = facilityController.filterFacilities(school, criteria);
            } catch (InvalidInputException e) {
                errors.putAll(e.getFieldErrors());
            } catch (ExternalServiceUnavailableException e) {
                ExternalFailureLog.warn(log, "Nearby facilities map", e);
                model.addAttribute("serviceUnavailable", true);
            }
        }

        List<Facility> onMap = facilities.isEmpty() ? List.of() : mapController.showFacilitiesOnMap(facilities);
        List<Place> places = new ArrayList<>();
        List<MapMarker> markers = new ArrayList<>();
        if (school.hasValidCoordinate()) {
            places.add(school);
            markers.add(MapMarker.of(school));
        }
        places.addAll(onMap);
        onMap.forEach(f -> markers.add(MapMarker.of(f, school.getSchoolCode())));

        model.addAttribute("school", school);
        model.addAttribute("facilities", onMap.stream().map(f -> row(f, school)).toList());
        model.addAttribute("foundCount", facilities.size());
        model.addAttribute("omittedCount", facilities.size() - onMap.size());
        model.addAttribute("radiusKm", radius);
        model.addAttribute("typesLabel", typesLabel(selectedTypes));
        model.addAttribute("interactive", mapController.displayInteractiveMap(places));
        model.addAttribute("markersJson", MapMarker.toJson(jsonMapper, markers));
        model.addAttribute("listUrl", listUrl(code, selectedTypes, radiusKm == null || errors.containsKey(RADIUS_KM)
                ? null : radius));
        if (!errors.isEmpty()) {
            model.addAttribute(PageMessages.FIELD_ERRORS, errors);
        }
        return "facility-map";
    }

    /** "Library" / "Tuition centre" for the list and the legend. */
    static String typeLabel(FacilityType type) {
        return type == FacilityType.TUITION_CENTRE ? "Tuition centre" : "Library";
    }

    private static Set<FacilityType> parseTypes(List<String> types, Map<String, String> errors) {
        Set<FacilityType> selected = EnumSet.noneOf(FacilityType.class);
        if (types == null) {
            return selected;
        }
        for (String text : types) {
            if (text == null || text.isBlank()) {
                continue;
            }
            try {
                selected.add(FacilityType.valueOf(text.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                errors.put(TYPE, TYPE_MESSAGE);
            }
        }
        return selected;
    }

    /** 1, 2 or 3 km (DC-24); blank means the default (3 km); anything else is an error and uses the default. */
    private static int parseRadius(String text, Map<String, String> errors) {
        if (text == null || text.isBlank()) {
            return FacilityFilterCriteria.DEFAULT_RADIUS_KM;
        }
        try {
            int radius = Integer.parseInt(text.strip());
            if (FacilityFilterCriteria.RADIUS_OPTIONS_KM.contains(radius)) {
                return radius;
            }
        } catch (NumberFormatException e) {
            // falls through to the error below
        }
        errors.put(RADIUS_KM, RADIUS_MESSAGE);
        return FacilityFilterCriteria.DEFAULT_RADIUS_KM;
    }

    private static FacilityRow row(Facility facility, School school) {
        String distance = String.format(Locale.ROOT, "%.1f", facility.distanceTo(school.getCoordinate()));
        String detailsUrl = UriComponentsBuilder.fromPath("/facilities/{placeId}").queryParam("from", "{from}")
                .encode().buildAndExpand(facility.getPlaceId(), school.getSchoolCode()).toUriString();
        return new FacilityRow(facility, typeLabel(facility.getFacilityType()), distance, detailsUrl);
    }

    /** What the map shows, e.g. "Libraries and tuition centres". */
    private static String typesLabel(Set<FacilityType> types) {
        if (types.size() == 1) {
            return types.contains(FacilityType.LIBRARY) ? "Libraries" : "Tuition centres";
        }
        return "Libraries and tuition centres";
    }

    /** The facilities list with the same filters, e.g. /schools/x/facilities?type=LIBRARY&amp;radiusKm=1. */
    private static String listUrl(String code, Set<FacilityType> types, Integer radiusKm) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath("/schools/{code}/facilities");
        types.forEach(type -> url.queryParam(TYPE, type.name()));
        if (radiusKm != null) {
            url.queryParam(RADIUS_KM, radiusKm);
        }
        return url.buildAndExpand(code).encode().toUriString();
    }
}

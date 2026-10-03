package sg.schoolmatch.boundary.ui;

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
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.control.FacilityController;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityFilterCriteria;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalFailureLog;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Design class «boundary» NearbyFacilitiesUI — libraries and tuition centres near a school, with the filter
 * panel (DM-12 NearbyFacilities, DM-13 FacilityFilter; use case View Nearby Facilities,
 * FR-FACILITY-01..06, FR-FACFILTER-01..04). DC-24: radius 1/2/3 km, default 3.
 * The design's display operations are page sections of {@code nearby-facilities.html}: displayFacilities,
 * displayFacilityFilters, displayNoNearbyFacilities, displayServiceUnavailable.
 */
@Controller
public class NearbyFacilitiesUI {

    static final String TYPE_MESSAGE = "Choose libraries or tuition centres";
    static final String UNAVAILABLE_MESSAGE = "Nearby facility information is temporarily unavailable. "
            + "Please try again in a few minutes.";

    /** Plural labels for the filter and the active-filter badges. (Not an EnumMap: SpEL cannot index one.) */
    private static final Map<FacilityType, String> PLURAL = Map.of(
            FacilityType.LIBRARY, "Libraries", FacilityType.TUITION_CENTRE, "Tuition centres");
    /** Singular labels for one facility. */
    private static final Map<FacilityType, String> SINGULAR = Map.of(
            FacilityType.LIBRARY, "Library", FacilityType.TUITION_CENTRE, "Tuition centre");

    private static final Logger log = LoggerFactory.getLogger(NearbyFacilitiesUI.class);

    private final SchoolController schoolController;   // DC-37: loads the school from the URL
    private final FacilityController facilityController;

    public NearbyFacilitiesUI(SchoolController schoolController, FacilityController facilityController) {
        this.schoolController = schoolController;
        this.facilityController = facilityController;
    }

    /**
     * GET /schools/{code}/facilities?type=&radiusKm= — the first visit uses all types and 3 km; the filter
     * panel submits here too (applyFacilityFilter). Unknown code → 404. An invalid radius or type shows a
     * field message and the list for the valid values (NFR-USE-03). Google down → EX-1 message, no list.
     */
    @GetMapping("/schools/{code}/facilities")
    public String applyFacilityFilter(@PathVariable String code,
                                      @RequestParam(name = "type", required = false) List<String> typeParams,
                                      @RequestParam(name = "radiusKm", required = false) String radiusParam,
                                      Model model) {
        School school = schoolController.getSchoolDetails(code);
        Map<String, String> errors = new LinkedHashMap<>();
        Set<FacilityType> types = parseTypes(typeParams, errors);
        FacilityFilterCriteria criteria = new FacilityFilterCriteria(types, parseRadius(radiusParam));

        model.addAttribute("school", school);
        model.addAttribute("facilityTypes", FacilityType.values());
        model.addAttribute("typeLabels", PLURAL);
        model.addAttribute("typeNames", SINGULAR);
        model.addAttribute("radiusOptions", FacilityFilterCriteria.RADIUS_OPTIONS_KM);
        model.addAttribute("schoolHasLocation", school.hasValidCoordinate());
        try {
            List<Facility> facilities;
            try {
                facilities = facilityController.filterFacilities(school, criteria);
            } catch (InvalidInputException e) {   // radius not 1, 2 or 3 km: say so and use the default
                errors.putAll(e.getFieldErrors());
                criteria = new FacilityFilterCriteria(types, FacilityFilterCriteria.DEFAULT_RADIUS_KM);
                facilities = facilityController.filterFacilities(school, criteria);
            }
            model.addAttribute("facilities", facilities);
            model.addAttribute("distances", distancesKm(facilities, school));
            if (facilities.isEmpty()) {
                model.addAttribute("noFacilitiesMessage", noFacilitiesMessage(school, criteria));
            }
        } catch (ExternalServiceUnavailableException e) {
            ExternalFailureLog.warn(log, "Nearby facilities", e);
            model.addAttribute("serviceUnavailable", true);
            model.addAttribute(PageMessages.FLASH_ERROR, UNAVAILABLE_MESSAGE);
        }
        if (!errors.isEmpty()) {
            model.addAttribute(PageMessages.FIELD_ERRORS, errors);
        }
        Set<FacilityType> shown = criteria.isEmpty() ? EnumSet.allOf(FacilityType.class) : criteria.getSelectedTypes();
        model.addAttribute("selectedTypes", shown);
        model.addAttribute("radiusKm", criteria.getRadiusKm());
        model.addAttribute("filtersActive", !criteria.isEmpty() && shown.size() < FacilityType.values().length
                || criteria.getRadiusKm() != FacilityFilterCriteria.DEFAULT_RADIUS_KM);
        model.addAttribute("pageUrl", withFilters("/schools/{code}/facilities", school, criteria));
        model.addAttribute("mapUrl", withFilters("/schools/{code}/facilities/map", school, criteria));
        return "nearby-facilities";
    }

    /** Known types only; an unknown value is reported and ignored. Empty = all types. */
    private static Set<FacilityType> parseTypes(List<String> values, Map<String, String> errors) {
        Set<FacilityType> types = EnumSet.noneOf(FacilityType.class);
        for (String value : values == null ? List.<String>of() : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                types.add(FacilityType.valueOf(value.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                errors.put("type", TYPE_MESSAGE);
            }
        }
        return types;
    }

    /** The radius in km; a missing value is the default, a non-number becomes 0 (rejected by the control). */
    private static int parseRadius(String value) {
        if (value == null || value.isBlank()) {
            return FacilityFilterCriteria.DEFAULT_RADIUS_KM;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** placeId → straight-line km from the school (the list holds only facilities with a valid coordinate). */
    private static Map<String, Double> distancesKm(List<Facility> facilities, School school) {
        Map<String, Double> distances = new LinkedHashMap<>();
        for (Facility facility : facilities) {
            distances.put(facility.getPlaceId(), facility.distanceTo(school.getCoordinate()));
        }
        return distances;
    }

    /** AF-1 of View Nearby Facilities, worded for the active filters. */
    private static String noFacilitiesMessage(School school, FacilityFilterCriteria criteria) {
        if (!school.hasValidCoordinate()) {
            return "This school has no map location, so nearby facilities cannot be found.";
        }
        boolean allTypes = criteria.isEmpty() || criteria.getSelectedTypes().size() == FacilityType.values().length;
        if (allTypes && criteria.getRadiusKm() == FacilityFilterCriteria.DEFAULT_RADIUS_KM) {
            return "No libraries or tuition centres found near " + school.getName() + ".";
        }
        String what = allTypes ? "libraries or tuition centres"
                : PLURAL.get(criteria.getSelectedTypes().iterator().next()).toLowerCase(Locale.ROOT);
        return "No " + what + " found within " + criteria.getRadiusKm() + " km of " + school.getName()
                + ". Try a larger distance or clear the filters.";
    }

    /** {@code path} for this school with the current type and radius parameters. */
    private static String withFilters(String path, School school, FacilityFilterCriteria criteria) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath(path);
        for (FacilityType type : criteria.getSelectedTypes()) {
            url.queryParam("type", type.name());
        }
        url.queryParam("radiusKm", criteria.getRadiusKm());
        return url.buildAndExpand(school.getSchoolCode()).encode().toUriString();
    }
}

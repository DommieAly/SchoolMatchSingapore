package sg.schoolmatch.boundary.ui;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import sg.schoolmatch.boundary.ui.support.AuthInterceptor;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.control.FacilityController;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalFailureLog;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.NotFoundException;

/**
 * Design class «boundary» FacilityDetailsUI — one library or tuition centre (DM-14 FacilityDetails;
 * use case View Facility Details, FR-FACDETAIL-01..03). {@code from} = the code of the school the user came
 * from: it gives the distance and the "Back to facilities" link. The design's displayUnavailable is the shared
 * {@code value} fragment ("Not available"); selectGetDirections is the "Get directions" link.
 */
@Controller
public class FacilityDetailsUI {

    static final String UNAVAILABLE_MESSAGE = "Facility details are temporarily unavailable. "
            + "Please try again in a few minutes.";

    private static final Logger log = LoggerFactory.getLogger(FacilityDetailsUI.class);

    private final FacilityController facilityController;
    private final SchoolController schoolController;   // DC-37-style arrow: the school in "from" (distance, back link)

    public FacilityDetailsUI(FacilityController facilityController, SchoolController schoolController) {
        this.facilityController = facilityController;
        this.schoolController = schoolController;
    }

    /**
     * GET /facilities/{placeId}?from={code}. Unknown place → 404 page (EX-1). An unknown or malformed
     * {@code from} is ignored. Google down → the page with a "temporarily unavailable" message.
     */
    @GetMapping("/facilities/{placeId}")
    public String displayFacilityDetails(@PathVariable String placeId,
                                         @RequestParam(required = false) String from, Model model) {
        School school = findSchool(AuthInterceptor.safeSchoolCode(from));
        model.addAttribute("placeId", placeId);
        model.addAttribute("school", school);
        model.addAttribute("from", school == null ? null : school.getSchoolCode());
        model.addAttribute("directionsFrom", thisPage(placeId, school));
        try {
            Facility facility = facilityController.getFacilityDetails(placeId);   // NotFoundException → 404
            model.addAttribute("facility", facility);
            model.addAttribute("typeName", typeName(facility.getFacilityType()));
            model.addAttribute("openingHours", openingHourLines(facility.getOpeningHours()));
            model.addAttribute("websiteLink", isWebLink(facility.getWebsite()));
            model.addAttribute("coordinateText", facility.getCoordinate() == null ? null
                    : String.format("%.5f, %.5f", facility.getCoordinate().getLatitude(),
                            facility.getCoordinate().getLongitude()));
            if (school != null && school.hasValidCoordinate() && facility.hasValidCoordinate()) {
                model.addAttribute("distanceText",
                        String.format("%.1f km from %s (straight line)", facility.distanceTo(school.getCoordinate()),
                                school.getName()));
            }
        } catch (ExternalServiceUnavailableException e) {
            ExternalFailureLog.warn(log, "Facility details", e);
            model.addAttribute("serviceUnavailable", true);
            model.addAttribute(PageMessages.FLASH_ERROR, UNAVAILABLE_MESSAGE);
        }
        return "facility-details";
    }

    private School findSchool(String code) {
        if (code == null) {
            return null;
        }
        try {
            return schoolController.getSchoolDetails(code);
        } catch (NotFoundException e) {
            return null;   // a stale or edited link: show the facility without the school parts
        }
    }

    /** This page's path, for the directions page's Cancel button (FR-ROUTE-01, T-55). */
    private static String thisPage(String placeId, School school) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath("/facilities/{placeId}");
        if (school != null) {
            url.queryParam("from", school.getSchoolCode());
        }
        return url.buildAndExpand(placeId).encode().toUriString();
    }

    private static String typeName(FacilityType type) {
        if (type == null) {
            return null;
        }
        return type == FacilityType.LIBRARY ? "Library" : "Tuition centre";
    }

    /** Google's "Monday: 10:00 AM – 9:00 PM; Tuesday: …" as one line per day. */
    private static List<String> openingHourLines(String openingHours) {
        if (openingHours == null || openingHours.isBlank()) {
            return List.of();
        }
        return Arrays.stream(openingHours.split(";\\s*")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** Only http(s) URLs become links, so data can never inject a javascript: link. */
    private static boolean isWebLink(String website) {
        return website != null && (website.startsWith("https://") || website.startsWith("http://"));
    }
}

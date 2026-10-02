package sg.schoolmatch.boundary.ui.support;

import java.util.List;
import org.springframework.web.util.UriComponentsBuilder;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.school.School;
import tools.jackson.databind.json.JsonMapper;

/**
 * One map marker as the browser needs it. Map pages embed a JSON array of these in the {@code data-markers}
 * attribute of {@code #map}; {@code static/js/map.js} reads it (FR-MAP-03, FR-MAP-05, FR-FACMAP-03).
 * Only build markers for places with a valid coordinate (see {@code MapController.showSchoolsOnMap}).
 * <p>
 * {@code kind} picks the pin colour and letter in {@code GoogleMapsPlatformInterface.js}, so the facility types
 * can be told apart (UC View Facilities on Map, special requirement): {@link #SCHOOL}, {@link #LIBRARY},
 * {@link #TUITION_CENTRE}, {@link #START} (the starting point of a route).
 *
 * @param href the details page the marker's info window links to; null for the starting point
 */
public record MapMarker(String id, String kind, String name, String address, double lat, double lng, String href) {

    public static final String SCHOOL = "school";
    public static final String LIBRARY = "library";
    public static final String TUITION_CENTRE = "tuition-centre";
    public static final String START = "start";

    public static MapMarker of(School school) {
        return new MapMarker(school.getSchoolCode(), SCHOOL, school.getName(), school.getAddress(),
                school.getCoordinate().getLatitude(), school.getCoordinate().getLongitude(),
                path("/schools/{code}", school.getSchoolCode()));
    }

    /** A facility marker linking to {@code /facilities/{placeId}}. */
    public static MapMarker of(Facility facility) {
        return of(facility, null);
    }

    /**
     * A facility marker linking to {@code /facilities/{placeId}?from={schoolCode}}, so the details page can lead
     * back to that school's facilities (DC-08). {@code fromSchoolCode} may be null.
     */
    public static MapMarker of(Facility facility, String fromSchoolCode) {   // DC-61
        UriComponentsBuilder href = UriComponentsBuilder.fromPath("/facilities/{placeId}");
        if (fromSchoolCode != null) {
            href.queryParam("from", "{from}");
        }
        return new MapMarker(facility.getPlaceId(), kind(facility.getFacilityType()), facility.getName(),
                facility.getAddress(), facility.getCoordinate().getLatitude(), facility.getCoordinate().getLongitude(),
                href.encode().buildAndExpand(facility.getPlaceId(), fromSchoolCode).toUriString());
    }

    /** The starting point of a route (FR-ROUTE-07); it has no details page. */
    public static MapMarker start(ReferenceLocation location) {
        return new MapMarker(START, START, "Starting point", location.getInputText(),
                location.getCoordinate().getLatitude(), location.getCoordinate().getLongitude(), null);
    }

    /** The markers as a JSON array string, for {@code th:attr="data-markers=${markersJson}"}. */
    public static String toJson(JsonMapper jsonMapper, List<MapMarker> markers) {
        return jsonMapper.writeValueAsString(markers);
    }

    private static String kind(FacilityType type) {
        return type == FacilityType.TUITION_CENTRE ? TUITION_CENTRE : LIBRARY;
    }

    private static String path(String template, String id) {
        return UriComponentsBuilder.fromPath(template).buildAndExpand(id).encode().toUriString();
    }
}

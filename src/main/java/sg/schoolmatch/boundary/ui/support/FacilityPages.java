package sg.schoolmatch.boundary.ui.support;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.util.UriComponentsBuilder;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityType;

/**
 * What the facility pages share (NearbyFacilitiesUI, FacilityMapUI, FacilityDetailsUI, DirectionsUI, map
 * markers): the type labels, reading the {@code type} parameter, and the Facility Details URL (DC-08, DC-61).
 */
public final class FacilityPages {

    /** Request parameter and errors-map key of the facility types. */
    public static final String TYPE = "type";

    /** Plural labels for filters and badges. (Not an EnumMap: SpEL cannot index one.) */
    public static final Map<FacilityType, String> PLURAL = Map.of(
            FacilityType.LIBRARY, "Libraries", FacilityType.TUITION_CENTRE, "Tuition centres");
    /** Singular labels for one facility. */
    public static final Map<FacilityType, String> SINGULAR = Map.of(
            FacilityType.LIBRARY, "Library", FacilityType.TUITION_CENTRE, "Tuition centre");

    private FacilityPages() {
    }

    /**
     * {@code /facilities/{placeId}}, plus {@code ?from={fromSchoolCode}} when a school is given, so the details
     * page can lead back to that school's facilities. Both values are percent-encoded.
     */
    public static String detailsUrl(String placeId, String fromSchoolCode) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath("/facilities/{placeId}");
        if (fromSchoolCode != null) {
            url.queryParam("from", "{from}");
        }
        return url.encode().buildAndExpand(placeId, fromSchoolCode).toUriString();
    }

    /** The third-party data credits of a list of facilities, each once, for the Google Maps credit (DC-93). */
    public static List<Facility.Attribution> attributions(List<Facility> facilities) {
        return facilities.stream().flatMap(facility -> facility.getAttributions().stream()).distinct().toList();
    }

    /**
     * The known types among {@code values} (ignoring case and blanks). An unknown value puts {@link #TYPE} →
     * {@code message} into {@code errors} and is ignored. Empty = no type chosen.
     */
    public static Set<FacilityType> parseTypes(List<String> values, String message, Map<String, String> errors) {
        Set<FacilityType> types = EnumSet.noneOf(FacilityType.class);
        for (String value : values == null ? List.<String>of() : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                types.add(FacilityType.valueOf(value.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                errors.put(TYPE, message);
            }
        }
        return types;
    }
}

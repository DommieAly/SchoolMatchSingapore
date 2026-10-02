package sg.schoolmatch.dataset;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.school.District;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Planning areas for the importer (DC-04, DC-12): reads the URA planning-area GeoJSON and finds the area that
 * contains a school ({@link District#contains}, JTS). Works on the full-detail boundaries as downloaded; the
 * snapshot stores simplified ones (see {@link SnapshotWriter#districtsGeoJson}).
 */
public class DistrictLocator {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<District> districts;

    public DistrictLocator(List<District> districts) {
        this.districts = List.copyOf(districts);
    }

    /**
     * Each Feature of a FeatureCollection → District. Reads the raw data.gov.sg properties ({@code PLN_AREA_C},
     * {@code PLN_AREA_N}) or a snapshot's own ({@code planningAreaCode}, {@code planningAreaName}).
     * The geometry is kept as a JSON string.
     *
     * @throws IllegalStateException when the text is not a FeatureCollection with a code and name on every feature
     */
    public static List<District> parseFeatureCollection(String geoJson) {
        JsonNode root;
        try {
            root = JSON.readTree(geoJson);
        } catch (JacksonException e) {
            throw new IllegalStateException("Planning-area GeoJSON is not valid JSON: " + e.getOriginalMessage(), e);
        }
        JsonNode features = root == null ? null : root.get("features");
        if (features == null || !features.isArray() || features.isEmpty()) {
            throw new IllegalStateException("Planning-area GeoJSON has no features");
        }
        List<District> result = new ArrayList<>();
        int index = 0;
        for (JsonNode feature : features.values()) {
            index++;
            JsonNode properties = feature.path("properties");
            String code = text(properties, "PLN_AREA_C", "planningAreaCode");
            String name = text(properties, "PLN_AREA_N", "planningAreaName");
            if (code == null || name == null) {
                throw new IllegalStateException("Planning-area feature #" + index
                        + " has no PLN_AREA_C / PLN_AREA_N (or planningAreaCode / planningAreaName)");
            }
            JsonNode geometry = feature.get("geometry");
            result.add(new District(code, name,
                    geometry == null || geometry.isNull() ? null : JSON.writeValueAsString(geometry)));
        }
        return result;
    }

    /** The first planning area whose boundary contains {@code point}; empty when none does (e.g. at sea). */
    public Optional<District> locate(Coordinate point) {
        if (point == null) {
            return Optional.empty();
        }
        return districts.stream().filter(d -> d.contains(point)).findFirst();
    }

    /** The planning area with this name, compared on letters only (data.gov.sg's dgp_code says "SENG KANG"). */
    public Optional<District> byName(String name) {
        return districts.stream().filter(d -> sameArea(d.getPlanningAreaName(), name)).findFirst();
    }

    /** True when both names have the same letters and digits, ignoring case, spaces and punctuation. */
    public static boolean sameArea(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return lettersOnly(a).equals(lettersOnly(b)) && !lettersOnly(a).isEmpty();
    }

    public List<District> getDistricts() {
        return districts;
    }

    private static String lettersOnly(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String text(JsonNode properties, String... names) {
        for (String name : names) {
            JsonNode value = properties.get(name);
            if (value != null && !value.isNull() && !value.asString().isBlank()) {
                return value.asString().trim();
            }
        }
        return null;
    }
}

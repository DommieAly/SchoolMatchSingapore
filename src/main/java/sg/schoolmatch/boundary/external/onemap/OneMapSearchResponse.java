package sg.schoolmatch.boundary.external.onemap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import sg.schoolmatch.boundary.external.OneMapHit;

/**
 * JSON body of OneMap {@code /api/common/elastic/search} (also the format of the recorded files in
 * {@code src/main/resources/stub/onemap/}). Coordinates arrive as strings; "NIL" means no value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OneMapSearchResponse(Integer found, Integer totalNumPages, Integer pageNum, List<Result> results) {

    /** One row of {@code results}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            @JsonProperty("SEARCHVAL") String searchVal,
            @JsonProperty("BUILDING") String building,
            @JsonProperty("ADDRESS") String address,
            @JsonProperty("POSTAL") String postal,
            @JsonProperty("LATITUDE") String latitude,
            @JsonProperty("LONGITUDE") String longitude) {

        /** Null when the coordinate is missing or not a number. */
        OneMapHit toHit() {
            try {
                return new OneMapHit(clean(searchVal), clean(building), clean(address), clean(postal),
                        Double.parseDouble(latitude), Double.parseDouble(longitude));
            } catch (NullPointerException | NumberFormatException e) {
                return null;
            }
        }
    }

    /** Hits in OneMap's order, skipping rows without a usable coordinate. */
    public List<OneMapHit> toHits() {
        if (results == null) {
            return List.of();
        }
        return results.stream().map(Result::toHit).filter(Objects::nonNull).toList();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() || "NIL".equalsIgnoreCase(value.trim()) ? null : value.trim();
    }
}

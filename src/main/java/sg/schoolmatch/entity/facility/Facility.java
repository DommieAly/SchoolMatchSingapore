package sg.schoolmatch.entity.facility;

import java.util.List;
import java.util.Objects;
import sg.schoolmatch.entity.common.Place;

/**
 * Design class «entity» Facility — a library or tuition centre near a school, from Google Places
 * (FR-FACILITY-04, FR-FACDETAIL-02). DC-17: kept only in memory (FacilityDataCache), never in the database.
 */
public class Facility extends Place {

    private final String placeId;
    private final FacilityType facilityType;
    private String openingHours;   // null when unknown
    private List<Attribution> attributions = List.of();

    /** A third-party data credit that Google gave with this place and that must be shown with it (DC-93). */
    public record Attribution(String provider, String providerUri) {

        /** Only http(s) URLs become links, so data can never inject a javascript: link. */
        public boolean hasWebLink() {
            return providerUri != null && (providerUri.startsWith("https://") || providerUri.startsWith("http://"));
        }
    }

    public Facility(String placeId, String name, FacilityType facilityType) {
        super(name);
        this.placeId = placeId;
        this.facilityType = facilityType;
    }

    public String getPlaceId() {
        return placeId;
    }

    public FacilityType getFacilityType() {
        return facilityType;
    }

    public String getOpeningHours() {
        return openingHours;
    }

    public void setOpeningHours(String openingHours) {
        this.openingHours = openingHours;
    }

    public List<Attribution> getAttributions() {
        return attributions;
    }

    public void setAttributions(List<Attribution> attributions) {
        this.attributions = List.copyOf(attributions);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Facility other && Objects.equals(placeId, other.placeId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(placeId);
    }

    @Override
    public String toString() {
        return "Facility{" + placeId + ", " + getName() + ", " + facilityType + "}";
    }
}

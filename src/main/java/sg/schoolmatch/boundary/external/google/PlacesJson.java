package sg.schoolmatch.boundary.external.google;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityType;

/**
 * JSON bodies of the Google Places API (New): searchNearby, searchText and place details, only the fields we
 * ask for in the field masks, and their mapping to {@link Facility}. Shapes checked against the REST
 * reference on 2026-10-02.
 */
final class PlacesJson {

    private PlacesJson() {
    }

    // ---- request -----------------------------------------------------------------------------------------

    record LatLng(double latitude, double longitude) {

        static LatLng of(Coordinate c) {
            return new LatLng(c.getLatitude(), c.getLongitude());
        }
    }

    record Circle(LatLng center, double radius) {
    }

    record Area(Circle circle) {
    }

    /** Body of {@code POST /v1/places:searchNearby} (libraries). */
    record SearchNearbyRequest(List<String> includedTypes, int maxResultCount, Area locationRestriction) {
    }

    /**
     * Body of {@code POST /v1/places:searchText} (tuition centres: there is no place type for them).
     * {@code pageSize} replaces the deprecated {@code maxResultCount} of Text Search.
     */
    record SearchTextRequest(String textQuery, int pageSize, Area locationBias) {
    }

    // ---- responses ---------------------------------------------------------------------------------------

    /** searchNearby / searchText response; {@code {}} (no {@code places}) when nothing was found. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<Place> places) {

        List<Facility> toFacilities(FacilityType type) {
            if (places == null) {
                return List.of();
            }
            return places.stream().filter(Objects::nonNull).map(p -> p.toFacility(type))
                    .filter(Objects::nonNull).toList();
        }
    }

    /** One place (search result or details). Fields that were not asked for, or are unknown, stay null. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Place(String id, LocalizedText displayName, String formattedAddress, LatLngJson location,
                 String nationalPhoneNumber, String websiteUri, OpeningHours regularOpeningHours) {

        /** Null when the place has no id (cannot be linked to). {@code type} may be null (details only). */
        Facility toFacility(FacilityType type) {
            if (id == null || id.isBlank()) {
                return null;
            }
            String name = displayName == null ? null : displayName.text();
            Facility facility = new Facility(id, name, type);
            facility.setAddress(formattedAddress);
            facility.setCoordinate(location == null ? null : location.toCoordinate());
            facility.setTelephone(nationalPhoneNumber);
            facility.setWebsite(websiteUri);
            facility.setOpeningHours(regularOpeningHours == null ? null : regularOpeningHours.joined());
            return facility;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LocalizedText(String text, String languageCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LatLngJson(Double latitude, Double longitude) {

        /** Null when a value is missing or out of range. */
        Coordinate toCoordinate() {
            if (latitude == null || longitude == null) {
                return null;
            }
            try {
                return new Coordinate(latitude, longitude);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpeningHours(List<String> weekdayDescriptions) {

        /** "Monday: 10:00 AM – 9:00 PM; Tuesday: …", or null when Google gave none. */
        String joined() {
            if (weekdayDescriptions == null || weekdayDescriptions.isEmpty()) {
                return null;
            }
            return String.join("; ", weekdayDescriptions);
        }
    }
}

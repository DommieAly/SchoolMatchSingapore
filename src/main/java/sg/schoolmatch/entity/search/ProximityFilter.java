package sg.schoolmatch.entity.search;

import java.util.Set;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.school.School;

/**
 * Design class «entity» ProximityFilter — keeps schools within a straight-line radius of the
 * reference location (FR-FILTER-04, FR-FILTER-05). Valid: resolved location and radius 1, 3 or 5 km.
 * Only schools with a valid coordinate can match (NFR-DATA-02).
 */
public class ProximityFilter extends Filter {

    public static final Set<Integer> RADIUS_OPTIONS_KM = Set.of(1, 3, 5);   // DC-36 helper

    /** 1 mm, so rounding in the distance formula cannot drop a school that lies exactly on the radius. */
    private static final double TOLERANCE_KM = 1e-6;

    private final ReferenceLocation referenceLocation;
    private final int radiusKm;

    public ProximityFilter(ReferenceLocation referenceLocation, int radiusKm) {
        this.referenceLocation = referenceLocation;
        this.radiusKm = radiusKm;
    }

    public ReferenceLocation getReferenceLocation() {
        return referenceLocation;
    }

    public int getRadiusKm() {
        return radiusKm;
    }

    /** Straight-line distance ≤ radius; a school exactly on the radius is kept (FR-FILTER-05). */
    @Override
    public boolean matches(School school) {
        if (referenceLocation == null || !referenceLocation.isResolved() || !school.hasValidCoordinate()) {
            return false;
        }
        return school.distanceTo(referenceLocation.getCoordinate()) <= radiusKm + TOLERANCE_KM;
    }

    @Override
    public boolean isValid() {
        return referenceLocation != null && referenceLocation.isResolved() && RADIUS_OPTIONS_KM.contains(radiusKm);
    }

    /** e.g. "Within 3 km of BISHAN MRT"; "… of your location" for the device position (FR-FILTER-08). */
    @Override
    public String describe() {
        return "Within " + radiusKm + " km of " + placeName();
    }

    private String placeName() {
        if (referenceLocation == null) {
            return "the starting point";
        }
        String typed = referenceLocation.getInputText();
        if (typed != null && !typed.isBlank()) {
            return typed.strip();
        }
        return referenceLocation.getSource() == LocationSource.DEVICE_LOCATION ? "your location" : "the starting point";
    }
}

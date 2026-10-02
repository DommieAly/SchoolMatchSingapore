package sg.schoolmatch.entity.facility;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import sg.schoolmatch.entity.common.Coordinate;

/**
 * Design class «entity» FacilityFilterCriteria — facility types and radius chosen on the nearby
 * facilities page (FR-FACFILTER-01, FR-FACFILTER-02). DC-24: radius is 1, 2 or 3 km.
 * No selected type = every type.
 * The two constants below are the only source of the radius options and default (NearbyFacilitiesUI and
 * FacilityController read them). They are not settings, because an entity cannot read {@code AppProperties}.
 */
public class FacilityFilterCriteria {

    /** DC-24, DC-36: the radius options in km, in display order. */
    public static final List<Integer> RADIUS_OPTIONS_KM = List.of(1, 2, 3);

    /** DC-24: the radius used on the first visit ("nearby" = within 3 km). */
    public static final int DEFAULT_RADIUS_KM = 3;

    private final Set<FacilityType> selectedTypes;
    private final int radiusKm;

    public FacilityFilterCriteria(Collection<FacilityType> selectedTypes, int radiusKm) {
        this.selectedTypes = (selectedTypes == null || selectedTypes.isEmpty())
                ? EnumSet.noneOf(FacilityType.class) : EnumSet.copyOf(selectedTypes);
        this.radiusKm = radiusKm;
    }

    /**
     * True when the facility has a selected type (or no type is selected) AND has a valid coordinate
     * within {@code radiusKm} of {@code origin} (FR-FACFILTER-02: AND).
     */
    public boolean matches(Facility facility, Coordinate origin) {
        boolean typeOk = selectedTypes.isEmpty() || selectedTypes.contains(facility.getFacilityType());
        return typeOk && origin != null && facility.hasValidCoordinate() && facility.distanceTo(origin) <= radiusKm;
    }

    /** True when no facility type is selected. */
    public boolean isEmpty() {
        return selectedTypes.isEmpty();
    }

    public boolean isValid() {
        return RADIUS_OPTIONS_KM.contains(radiusKm);
    }

    public Set<FacilityType> getSelectedTypes() {
        return Collections.unmodifiableSet(selectedTypes);
    }

    public int getRadiusKm() {
        return radiusKm;
    }
}

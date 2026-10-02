package sg.schoolmatch.entity.search;

import java.util.Map;
import java.util.Set;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.School;

/**
 * Design class «entity» TransportationFilter — keeps schools reachable within a maximum commute time
 * (FR-FILTER-06). Valid: resolved location, a travel mode, and 15, 30, 45 or 60 minutes.
 * FilterController computes the commute times first (via DirectionsController) and sets
 * {@link #setCommuteMinutesBySchool}; {@link #matches} only reads that map.
 */
public class TransportationFilter extends Filter {

    public static final Set<Integer> DURATION_OPTIONS_MIN = Set.of(15, 30, 45, 60);   // DC-36 helper

    private final ReferenceLocation referenceLocation;
    private final TravelMode travelMode;
    private final int maxDurationMin;
    private Map<String, Integer> commuteMinutesBySchool = Map.of();   // DC-32: schoolCode → minutes

    public TransportationFilter(ReferenceLocation referenceLocation, TravelMode travelMode, int maxDurationMin) {
        this.referenceLocation = referenceLocation;
        this.travelMode = travelMode;
        this.maxDurationMin = maxDurationMin;
    }

    public ReferenceLocation getReferenceLocation() {
        return referenceLocation;
    }

    public TravelMode getTravelMode() {
        return travelMode;
    }

    public int getMaxDurationMin() {
        return maxDurationMin;
    }

    public Map<String, Integer> getCommuteMinutesBySchool() {
        return commuteMinutesBySchool;
    }

    public void setCommuteMinutesBySchool(Map<String, Integer> commuteMinutesBySchool) {
        this.commuteMinutesBySchool = commuteMinutesBySchool == null ? Map.of() : Map.copyOf(commuteMinutesBySchool);
    }

    /** Kept when its commute time is known and ≤ the maximum. No time (no route, or not asked) → left out. */
    @Override
    public boolean matches(School school) {
        Integer minutes = commuteMinutesBySchool.get(school.getSchoolCode());
        return minutes != null && minutes <= maxDurationMin;
    }

    @Override
    public boolean isValid() {
        return referenceLocation != null && referenceLocation.isResolved()
                && travelMode != null && DURATION_OPTIONS_MIN.contains(maxDurationMin);
    }

    /** e.g. "Within 30 min by public transport", "Within 15 min walking" (FR-FILTER-08). */
    @Override
    public String describe() {
        return "Within " + maxDurationMin + " min " + modePhrase(travelMode);
    }

    /** "walking", "by car" or "by public transport" (TRANSIT, DC-01); used on pages after a number of minutes. */
    public static String modePhrase(TravelMode mode) {   // DC-36 helper
        if (mode == null) {
            return "";
        }
        return switch (mode) {
            case WALK -> "walking";
            case DRIVE -> "by car";
            case TRANSIT -> "by public transport";
        };
    }
}

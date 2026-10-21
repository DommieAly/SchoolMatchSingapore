package sg.schoolmatch.error;

/**
 * The input messages (NFR-USE-03) that several controls and UI classes show for the same field rule. Each class
 * keeps its own constant name as an alias of these, so the texts are written once.
 */
public final class InputMessages {

    /** PSLE AL score outside 4–32 or not a whole number. */
    public static final String PSLE = "Enter a whole number from 4 to 32";
    /** Posting group other than 1, 2 or 3. */
    public static final String PG = "Choose posting group 1, 2 or 3";
    /** School search distance other than 1, 3 or 5 km (ProximityFilter). */
    public static final String RADIUS = "Choose 1, 3 or 5 km";
    /** Travel time other than 15, 30, 45 or 60 minutes. */
    public static final String DURATION = "Choose 15, 30, 45 or 60 minutes";
    /** Travel mode other than WALK, DRIVE or TRANSIT. */
    public static final String MODE = "Choose walk, drive or public transport";
    /** Distance or travel-time filter without a starting point. */
    public static final String LOCATION = "Set a starting point to use distance or travel-time filters";
    /** Facility search radius other than 1, 2 or 3 km (FacilityFilterCriteria). */
    public static final String FACILITY_RADIUS = "Choose 1, 2 or 3 km";

    private InputMessages() {
    }
}

package sg.schoolmatch.dataset;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import sg.schoolmatch.boundary.external.OneMapHit;
import sg.schoolmatch.boundary.external.OneMapInterface;
import sg.schoolmatch.dataset.CuratedCsvReader.GeocodeOverride;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.error.ExternalServiceUnavailableException;

/**
 * Finds a school's coordinate from its postal code with OneMap (DC-11, DC-12, FR-DATA-06). It never guesses:
 * <ol>
 *   <li>A row in {@code geocode-overrides.csv} for the postal code or school code wins ({@link Outcome#OVERRIDE}).</li>
 *   <li>Otherwise OneMap is searched for the postal code (5 digits get their leading 0 back; an error is retried
 *       once). Hits outside Singapore are ignored.</li>
 *   <li>The hit whose BUILDING is the school's name ({@link Outcome#MATCHED}); "ST." and "SAINT", "GOVT" and
 *       "GOVERNMENT" count as the same,
 *       and a BUILDING that contains the name (or the other way round) also matches.</li>
 *   <li>Else the only hit ({@link Outcome#SINGLE_HIT}), or the first of several hits that all lie within
 *       {@value #SAME_PLACE_METRES} m of each other ({@link Outcome#SAME_PLACE}).</li>
 *   <li>Else no coordinate: {@link Outcome#AMBIGUOUS} (hits far apart) or {@link Outcome#FAILED} (no hit,
 *       OneMap error, no postal code). The validator then fails the snapshot until an override row is added.</li>
 * </ol>
 */
public class SchoolGeocoder {

    static final int SAME_PLACE_METRES = 100;

    private final OneMapInterface oneMap;
    private final List<GeocodeOverride> overrides;

    public SchoolGeocoder(OneMapInterface oneMap, List<GeocodeOverride> overrides) {
        this.oneMap = oneMap;
        this.overrides = List.copyOf(overrides);
    }

    /** How a coordinate was found. */
    public enum Outcome { OVERRIDE, MATCHED, SINGLE_HIT, SAME_PLACE, AMBIGUOUS, FAILED }

    /**
     * @param coordinate null for AMBIGUOUS and FAILED
     * @param detail     a short explanation for the import log
     */
    public record GeocodeResult(Coordinate coordinate, Outcome outcome, String detail) {

        public boolean isLocated() {
            return coordinate != null;
        }
    }

    /** The coordinate of one school (see class comment). */
    public GeocodeResult locate(String schoolCode, String schoolName, String rawPostalCode) {
        String postal = normalisePostalCode(rawPostalCode);
        Optional<GeocodeOverride> override = overrides.stream()
                .filter(o -> (postal != null && postal.equals(normalisePostalCode(o.postalCode())))
                        || (schoolCode != null && schoolCode.equals(o.schoolCode())))
                .findFirst();
        if (override.isPresent()) {
            GeocodeOverride o = override.get();
            return new GeocodeResult(new Coordinate(o.latitude(), o.longitude()), Outcome.OVERRIDE,
                    "from " + CuratedCsvReader.GEOCODE_OVERRIDES + (o.reason() == null ? "" : " (" + o.reason() + ")"));
        }
        if (postal == null) {
            return failed("no postal code");
        }
        List<OneMapHit> hits;
        try {
            hits = searchWithOneRetry(postal).stream()
                    .filter(h -> isInSingapore(h.latitude(), h.longitude()))
                    .toList();
        } catch (ExternalServiceUnavailableException e) {
            return failed("OneMap search for " + postal + " failed twice: " + e.getMessage());
        }
        if (hits.isEmpty()) {
            return failed("OneMap has no hit in Singapore for postal code " + postal);
        }

        String key = matchKey(schoolName);
        Optional<OneMapHit> named = hits.stream().filter(h -> key != null && key.equals(matchKey(h.building())))
                .findFirst()
                .or(() -> hits.stream().filter(h -> roughlySameName(key, matchKey(h.building()))).findFirst());
        if (named.isPresent()) {
            return located(named.get(), Outcome.MATCHED, "BUILDING '" + named.get().building() + "'");
        }
        OneMapHit first = hits.getFirst();
        if (hits.size() == 1) {
            return located(first, Outcome.SINGLE_HIT, "the only hit for " + postal + " is '" + label(first)
                    + "', not named like the school");
        }
        Coordinate firstPoint = new Coordinate(first.latitude(), first.longitude());
        boolean samePlace = hits.stream().allMatch(h ->
                firstPoint.distanceTo(new Coordinate(h.latitude(), h.longitude())) * 1000 <= SAME_PLACE_METRES);
        if (samePlace) {
            return located(first, Outcome.SAME_PLACE, hits.size() + " hits for " + postal
                    + " within " + SAME_PLACE_METRES + " m, none named like the school; used '" + label(first) + "'");
        }
        return new GeocodeResult(null, Outcome.AMBIGUOUS, hits.size() + " hits for " + postal
                + " far apart and none named like the school; add a row to " + CuratedCsvReader.GEOCODE_OVERRIDES);
    }

    /** A OneMap error (timeout, HTTP 5xx or 429) is tried once more; the client keeps calls 1 s apart. */
    private List<OneMapHit> searchWithOneRetry(String postal) {
        try {
            return oneMap.search(postal);
        } catch (ExternalServiceUnavailableException firstFailure) {
            return oneMap.search(postal);
        }
    }

    /** Singapore postal codes have 6 digits; data.gov.sg drops a leading 0 ("99138" → "099138"). Null if unusable. */
    public static String normalisePostalCode(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.trim();
        if (!digits.matches("\\d{1,6}")) {
            return null;
        }
        return "0".repeat(6 - digits.length()) + digits;
    }

    /** Upper case, "ST." / "ST " → "SAINT ", "GOVT" → "GOVERNMENT", punctuation dropped, single spaces. Null for null. */
    static String matchKey(String name) {
        String normal = NameNormaliser.normalise(name);
        if (normal == null) {
            return null;
        }
        String key = normal.replaceAll("\\bST\\.?\\s", "SAINT ")
                .replaceAll("\\bGOVT\\b\\.?", "GOVERNMENT")
                .replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    /** One name contains the other, and the shorter one has at least 2 words (so "SCHOOL" alone never matches). */
    private static boolean roughlySameName(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        boolean aIsShorter = a.length() <= b.length();
        String shorter = aIsShorter ? a : b;
        String longer = aIsShorter ? b : a;
        return shorter.split(" ").length >= 2 && (" " + longer + " ").contains(" " + shorter + " ");
    }

    private static boolean isInSingapore(double latitude, double longitude) {
        try {
            return new Coordinate(latitude, longitude).isWithinSingapore();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static GeocodeResult located(OneMapHit hit, Outcome outcome, String detail) {
        return new GeocodeResult(new Coordinate(hit.latitude(), hit.longitude()), outcome, detail);
    }

    private static GeocodeResult failed(String detail) {
        return new GeocodeResult(null, Outcome.FAILED, detail);
    }

    private static String label(OneMapHit hit) {
        return hit.building() != null ? hit.building() : hit.address();
    }
}

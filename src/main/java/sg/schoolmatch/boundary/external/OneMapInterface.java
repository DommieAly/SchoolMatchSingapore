package sg.schoolmatch.boundary.external;

import java.util.List;

/**
 * Design class «boundary» OneMapInterface — address and postal-code search via OneMap (DC-11).
 * Used by LocationController (manual start location) and by the dataset importer (geocoding).
 * Implementations: StubOneMap (app.external.onemap.mode=stub) and OneMapClient (live, ≤1 request/second).
 */
public interface OneMapInterface {

    /** Matching addresses, best first; empty when nothing matches. */
    List<OneMapHit> search(String text);
}

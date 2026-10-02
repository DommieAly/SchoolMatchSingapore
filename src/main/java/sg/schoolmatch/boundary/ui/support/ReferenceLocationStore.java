package sg.schoolmatch.boundary.ui.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import sg.schoolmatch.entity.location.ReferenceLocation;

/**
 * Keeps the user's starting point (reference location) in the HTTP session, for the filter, map, directions
 * and recommendation pages (FR-FILTER-04, FR-ROUTE-02, DC-23). Page state only: login uses its own cookie.
 * <ul>
 *   <li>{@link #CURRENT} — the chosen {@link ReferenceLocation}</li>
 *   <li>{@link #CANDIDATES} — up to 5 address matches waiting for the user to pick one (DC-11)</li>
 * </ul>
 * The {@code HttpSession} methods accept null (no session yet). The {@code HttpServletRequest} overloads only
 * read, so they never create a session.
 */
@Component
public class ReferenceLocationStore {   // DC-42

    public static final String CURRENT = "sm.referenceLocation";
    public static final String CANDIDATES = "sm.locationCandidates";

    /** The stored starting point, if any. */
    public Optional<ReferenceLocation> get(HttpSession session) {
        if (session == null) {
            return Optional.empty();
        }
        return session.getAttribute(CURRENT) instanceof ReferenceLocation location
                ? Optional.of(location) : Optional.empty();
    }

    /** Same as {@link #get(HttpSession)}, without creating a session. */
    public Optional<ReferenceLocation> get(HttpServletRequest request) {
        return get(request.getSession(false));
    }

    /** Stores the starting point and drops any candidate list (the choice is made). */
    public void set(HttpSession session, ReferenceLocation location) {
        if (location == null) {
            clear(session);
            return;
        }
        session.setAttribute(CURRENT, location);
        session.removeAttribute(CANDIDATES);
    }

    /** Forgets the starting point (and any candidate list). */
    public void clear(HttpSession session) {
        if (session != null) {
            session.removeAttribute(CURRENT);
            session.removeAttribute(CANDIDATES);
        }
    }

    /** The address matches waiting to be chosen; empty when none. Read-only copy. */
    public List<ReferenceLocation> getCandidates(HttpSession session) {
        if (session == null || !(session.getAttribute(CANDIDATES) instanceof List<?> stored)) {
            return List.of();
        }
        List<ReferenceLocation> candidates = new ArrayList<>();
        for (Object item : stored) {
            if (item instanceof ReferenceLocation location) {
                candidates.add(location);
            }
        }
        return List.copyOf(candidates);
    }

    /** Same as {@link #getCandidates(HttpSession)}, without creating a session. */
    public List<ReferenceLocation> getCandidates(HttpServletRequest request) {
        return getCandidates(request.getSession(false));
    }

    /** Stores the address matches for the picker; an empty or null list clears them. */
    public void setCandidates(HttpSession session, List<ReferenceLocation> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            clearCandidates(session);
            return;
        }
        session.setAttribute(CANDIDATES, new ArrayList<>(candidates));   // ArrayList: Serializable
    }

    public void clearCandidates(HttpSession session) {
        if (session != null) {
            session.removeAttribute(CANDIDATES);
        }
    }
}

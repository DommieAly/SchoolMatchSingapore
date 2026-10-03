package sg.schoolmatch.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.search.TransportationFilter;
import sg.schoolmatch.error.ExternalFailureLog;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.persistence.UserProfileRepository;

/**
 * Design class «control» ProfileController — view and save the member's profile (use case Manage Profile;
 * FR-PROFILE-01, proposed id). DC-06/DC-27: account-scoped methods take the sessionId, and the account always
 * comes from that session, never from the request (NFR-SEC-05).
 * DC-14: depends on AuthController and LocationController. Called by UserProfileUI, RecommendationUI,
 * ChoicePlanController.
 */
@Service
public class ProfileController {

    public static final String DISPLAY_NAME_MESSAGE = "Use at most 50 characters";
    public static final String PSLE_MESSAGE = "Enter a whole number from 4 to 32";
    public static final String POSTING_GROUP_MESSAGE = "Choose posting group 1, 2 or 3";
    public static final String PRIMARY_SCHOOL_MESSAGE = "Use at most 100 characters";
    public static final String HOME_ADDRESS_LENGTH_MESSAGE = "Use at most 200 characters";
    public static final String ADDRESS_NOT_FOUND_MESSAGE = "Address not found in Singapore";
    public static final String CHOOSE_ADDRESS_MESSAGE = "Several addresses match. Choose yours from the list.";
    public static final String ADDRESS_UNAVAILABLE_MESSAGE =
            "Address search is temporarily unavailable. Try again later, or leave the address blank.";
    public static final String COMMUTE_MESSAGE = "Choose 15, 30, 45 or 60 minutes";
    public static final String TRAVEL_MODE_MESSAGE = "Choose walk, drive or public transport";

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    private static final int DISPLAY_NAME_MAX = 50;
    private static final int PRIMARY_SCHOOL_MAX = 100;
    private static final int HOME_ADDRESS_MAX = 200;

    private final AuthController authController;
    private final LocationController locationController;
    private final UserProfileRepository profileRepository;

    public ProfileController(AuthController authController, LocationController locationController,
                             UserProfileRepository profileRepository) {
        this.authController = authController;
        this.locationController = locationController;
        this.profileRepository = profileRepository;
    }

    /**
     * The member's profile; an empty, unsaved one before the first save (create on first save).
     *
     * @throws sg.schoolmatch.error.NotAuthenticatedException when the session has ended
     */
    @Transactional(readOnly = true)
    public UserProfile getProfile(String sessionId) {
        Account account = authController.getAccount(sessionId);
        return profileRepository.findById(account.getAccountId()).orElseGet(() -> new UserProfile(account));
    }

    /**
     * Validates {@code edited} and copies its values into the session account's profile (AF-2: nothing is saved
     * when a value is invalid; all errors are reported at once). Text is trimmed; blank means "not given" (null).
     * Home location:
     * <ul>
     *   <li>blank address → no home location;</li>
     *   <li>{@code edited.getHomeLocation()} set (the member chose one of the candidates) → kept;</li>
     *   <li>same address as saved → the saved location is kept, no new search;</li>
     *   <li>otherwise {@link #resolveHomeLocation}: no match → field error; several → {@link AmbiguousAddressException}.</li>
     * </ul>
     * Only the account of {@code sessionId} is changed, whichever account {@code edited} was made for (NFR-SEC-05).
     *
     * @throws InvalidInputException field → message
     * @throws AmbiguousAddressException the address has several matches; the member must choose one
     * @throws sg.schoolmatch.error.NotAuthenticatedException when the session has ended
     */
    @Transactional
    public void saveProfile(String sessionId, UserProfile edited) {
        Account account = authController.getAccount(sessionId);
        UserProfile profile = profileRepository.findById(account.getAccountId())
                .orElseGet(() -> new UserProfile(account));

        Map<String, String> errors = validate(edited);
        String address = clean(edited.getHomeAddress());
        Coordinate home = null;
        List<ReferenceLocation> candidates = List.of();
        if (address != null && !errors.containsKey("homeAddress")) {
            try {
                home = chooseHomeLocation(address, edited.getHomeLocation(), profile);
            } catch (AmbiguousAddressException e) {
                candidates = e.getCandidates();
                errors.put("homeAddress", CHOOSE_ADDRESS_MESSAGE);
            } catch (InvalidInputException e) {
                errors.put("homeAddress", e.getFieldErrors().values().iterator().next());
            }
        }
        if (!candidates.isEmpty()) {
            throw new AmbiguousAddressException(errors, candidates);
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }

        profile.setDisplayName(clean(edited.getDisplayName()));
        profile.setPsleScore(edited.getPsleScore());
        profile.setPostingGroup(edited.getPostingGroup());
        profile.setPrimarySchool(clean(edited.getPrimarySchool()));
        profile.setHomeAddress(address);
        profile.setHomeLocation(home);
        profile.setPreferredCCAs(edited.getPreferredCCAs());
        profile.setPreferredProgrammes(edited.getPreferredProgrammes());
        profile.setMaxCommuteMin(edited.getMaxCommuteMin());
        profile.setTravelMode(edited.getTravelMode());
        profileRepository.saveAndFlush(profile);   // flush now: a database error reaches UserProfileUI (EX-2)
    }

    /** The home location for a non-blank address (see {@link #saveProfile}). */
    private Coordinate chooseHomeLocation(String address, Coordinate chosen, UserProfile saved) {
        if (chosen != null) {
            if (!chosen.isWithinSingapore()) {
                throw new InvalidInputException("homeAddress", ADDRESS_NOT_FOUND_MESSAGE);
            }
            return chosen;
        }
        if (saved.getHomeLocation() != null && sameAddress(address, saved.getHomeAddress())) {
            return saved.getHomeLocation();
        }
        return resolveHomeLocation(address);
    }

    /**
     * Looks the address up with LocationController (OneMap, DC-11).
     *
     * @throws InvalidInputException no match, or the address search is unavailable
     * @throws AmbiguousAddressException more than one match
     */
    private Coordinate resolveHomeLocation(String address) {
        List<ReferenceLocation> candidates;
        try {
            candidates = locationController.findCandidates(address);
        } catch (ExternalServiceUnavailableException e) {
            ExternalFailureLog.warn(log, "Home address search", e);
            throw new InvalidInputException("homeAddress", ADDRESS_UNAVAILABLE_MESSAGE);
        } catch (InvalidInputException e) {
            throw new InvalidInputException("homeAddress", e.getFieldErrors().values().iterator().next());
        }
        if (candidates.isEmpty()) {
            throw new InvalidInputException("homeAddress", ADDRESS_NOT_FOUND_MESSAGE);
        }
        if (candidates.size() > 1) {
            throw new AmbiguousAddressException(Map.of("homeAddress", CHOOSE_ADDRESS_MESSAGE), candidates);
        }
        return candidates.getFirst().getCoordinate();
    }

    /**
     * Checks the profile values without saving or looking up the address: field → message for every value that
     * breaks a rule (empty when all are valid). UserProfileUI calls it when the form itself already found a
     * problem, so all messages show in one answer (Manage Profile AF-2, NFR-USE-03).
     */
    public Map<String, String> validateProfile(UserProfile edited) {
        return validate(edited);
    }

    /** Field → message for every invalid value (FR-PROFILE-01 limits; data dictionary PSLE rule). */
    private static Map<String, String> validate(UserProfile p) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (tooLong(p.getDisplayName(), DISPLAY_NAME_MAX)) {
            errors.put("displayName", DISPLAY_NAME_MESSAGE);
        }
        if (p.getPsleScore() != null && (p.getPsleScore() < 4 || p.getPsleScore() > 32)) {
            errors.put("psleScore", PSLE_MESSAGE);
        }
        if (p.getPostingGroup() != null && (p.getPostingGroup() < 1 || p.getPostingGroup() > 3)) {
            errors.put("postingGroup", POSTING_GROUP_MESSAGE);
        }
        if (tooLong(p.getPrimarySchool(), PRIMARY_SCHOOL_MAX)) {
            errors.put("primarySchool", PRIMARY_SCHOOL_MESSAGE);
        }
        if (tooLong(p.getHomeAddress(), HOME_ADDRESS_MAX)) {
            errors.put("homeAddress", HOME_ADDRESS_LENGTH_MESSAGE);
        }
        if (p.getMaxCommuteMin() != null && !TransportationFilter.DURATION_OPTIONS_MIN.contains(p.getMaxCommuteMin())) {
            errors.put("maxCommuteMin", COMMUTE_MESSAGE);
        }
        return errors;
    }

    private static boolean tooLong(String text, int max) {
        String value = clean(text);
        return value != null && value.length() > max;
    }

    private static boolean sameAddress(String a, String b) {
        return b != null && a.toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }

    /** Trimmed text, or null when blank (missing values are null, never ""). */
    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The home address matches several places: the page shows {@link #getCandidates()} so the member can choose
     * one; the chosen one comes back as {@code UserProfile.homeLocation}. Carries the other field errors too.
     */
    public static class AmbiguousAddressException extends InvalidInputException {   // DC-47

        private final transient List<ReferenceLocation> candidates;

        public AmbiguousAddressException(Map<String, String> fieldErrors, List<ReferenceLocation> candidates) {
            super(fieldErrors);
            this.candidates = List.copyOf(candidates);
        }

        /** Up to 5 matches from LocationController.findCandidates, in its order. */
        public List<ReferenceLocation> getCandidates() {
            return candidates;
        }
    }
}

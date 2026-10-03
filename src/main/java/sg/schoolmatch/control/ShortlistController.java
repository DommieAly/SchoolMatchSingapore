package sg.schoolmatch.control;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.shortlist.Shortlist;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.persistence.ShortlistRepository;

/**
 * Design class «control» ShortlistController — add, view, remove and compare shortlisted schools
 * (use cases Add School to Shortlist, Remove School from Shortlist,
 * View Shortlisted Schools, Compare Schools; FR-SHORTLIST-01..07, FR-COMPARE-01, NFR-SEC-05).
 * DC-14: depends on SchoolDataController to resolve codes into schools (DC-21).
 * Called by ShortlistUI (also the details page's POST /schools/{code}/shortlist, DC-38), ComparisonUI,
 * ChoicePlanUI, ChoicePlanController, LoginUI (pending add after login, DC-08).
 * <p>
 * NFR-SEC-05: every method takes the session id and works on that session's own account
 * ({@link AuthController#getAccount}); no method accepts an account id from the request.
 */
@Service
public class ShortlistController {

    /** FR-COMPARE-01: compare 2 to 4 schools at a time. */
    public static final int MIN_COMPARE = 2;   // DC-66
    public static final int MAX_COMPARE = 4;

    public static final String COMPARE_COUNT_MESSAGE =
            "Choose " + MIN_COMPARE + " to " + MAX_COMPARE + " schools from your shortlist to compare.";
    public static final String COMPARE_NOT_SHORTLISTED_MESSAGE = "You can only compare schools on your shortlist.";

    private final AuthController authController;
    private final SchoolDataController schoolDataController;
    private final ShortlistRepository shortlistRepository;
    private final Clock clock;

    public ShortlistController(AuthController authController, SchoolDataController schoolDataController,
                               ShortlistRepository shortlistRepository, Clock clock) {
        this.authController = authController;
        this.schoolDataController = schoolDataController;
        this.shortlistRepository = shortlistRepository;
        this.clock = clock;
    }

    /**
     * Adds the school to the member's shortlist and saves it (FR-SHORTLIST-01, FR-SHORTLIST-03).
     * The shortlist row is created here if registration did not create one.
     *
     * @return false when the school was already shortlisted (FR-SHORTLIST-02, AF-1)
     * @throws sg.schoolmatch.error.NotFoundException          for a school code that is not in the dataset
     * @throws sg.schoolmatch.error.NotAuthenticatedException when the session is not valid
     */
    public boolean addSchool(String sessionId, String schoolCode) {
        Account account = authController.getAccount(sessionId);
        School school = schoolDataController.getSchool(schoolCode);
        Shortlist shortlist = load(account);
        if (!shortlist.addSchool(school)) {
            return false;
        }
        shortlist.setUpdatedAt(clock.instant());
        try {
            shortlistRepository.save(shortlist);
        } catch (DataIntegrityViolationException doubleClick) {
            return false;   // a second click added the same school at the same moment (unique key)
        }
        return true;
    }

    /**
     * The last-known names of saved codes that left the dataset (open decision 3, DC-86), for the shortlist and plan
     * pages: code → name. Codes still in the dataset are left out; an empty list asks nothing.
     */
    public Map<String, String> getLastKnownNames(List<String> schoolCodes) {
        return schoolCodes.isEmpty() ? Map.of() : schoolDataController.getLastKnownSchoolNames(schoolCodes);
    }

    /**
     * Removes the school from the member's shortlist, and from the choice plan (FR-SHORTLIST-06).
     * A school that is not shortlisted is ignored.
     */
    public void removeSchool(String sessionId, String schoolCode) {
        Account account = authController.getAccount(sessionId);
        Shortlist shortlist = shortlistRepository.findById(account.getAccountId()).orElse(null);
        if (shortlist == null || !shortlist.contains(schoolCode)) {
            return;
        }
        shortlist.removeSchool(schoolCode);
        shortlist.setUpdatedAt(clock.instant());
        if (shortlist.getChoicePlan() != null) {
            shortlist.getChoicePlan().setUpdatedAt(clock.instant());
        }
        shortlistRepository.save(shortlist);
    }

    /**
     * The member's shortlisted schools in saved order (FR-SHORTLIST-04, FR-SHORTLIST-05; DC-35: by school code
     * after loading). Codes that are no longer in the dataset are left out; {@link #getShortlist} reports them.
     */
    public List<School> getShortlistedSchools(String sessionId) {
        return getShortlist(sessionId).getSchools();
    }

    /**
     * DC-07/DC-28: the schools to compare, in the order given. 2–4 different codes, all on the member's own
     * shortlist and still in the dataset (NFR-SEC-05).
     *
     * @throws InvalidInputException (field {@code codes}) otherwise (AF-1)
     */
    public List<School> compareSchools(String sessionId, Set<String> schoolCodes) {
        Shortlist shortlist = getShortlist(sessionId);
        Set<String> codes = schoolCodes == null ? Set.of() : new LinkedHashSet<>(schoolCodes);
        if (codes.size() < MIN_COMPARE || codes.size() > MAX_COMPARE) {
            throw new InvalidInputException("codes", COMPARE_COUNT_MESSAGE);
        }
        Map<String, School> resolved = byCode(shortlist.getSchools());
        List<School> schools = new ArrayList<>();
        for (String code : codes) {
            if (!shortlist.contains(code)) {
                throw new InvalidInputException("codes", COMPARE_NOT_SHORTLISTED_MESSAGE);
            }
            School school = resolved.get(code);
            if (school == null) {
                throw new InvalidInputException("codes", code + " is no longer in the dataset, so it cannot be compared.");
            }
            schools.add(school);
        }
        return schools;
    }

    /**
     * DC-06: the member's shortlist with its schools (and plan choices) resolved from the active dataset.
     * A member without a shortlist row gets an empty one; it is not saved.
     */
    public Shortlist getShortlist(String sessionId) {
        return load(authController.getAccount(sessionId));
    }

    /** The account's saved shortlist (or a new empty one), with schools resolved (DC-21). */
    private Shortlist load(Account account) {
        Shortlist shortlist = shortlistRepository.findById(account.getAccountId())
                .orElseGet(() -> new Shortlist(account));
        shortlist.resolveSchools(byCode(schoolDataController.getSchools()));
        return shortlist;
    }

    private static Map<String, School> byCode(List<School> schools) {
        return schools.stream()
                .collect(Collectors.toMap(School::getSchoolCode, Function.identity(), (first, second) -> first));
    }
}

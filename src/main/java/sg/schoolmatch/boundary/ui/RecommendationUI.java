package sg.schoolmatch.boundary.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.WebUtils;
import sg.schoolmatch.boundary.ui.support.AuthInterceptor;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.PsleAvailability;
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.FilterController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.control.RecommendationController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.recommend.MatchCriteria;
import sg.schoolmatch.entity.recommend.Recommendation;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.search.AttributeCategory;
import sg.schoolmatch.entity.search.TransportationFilter;
import sg.schoolmatch.error.ExternalFailureLog;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Design class «boundary» RecommendationUI — criteria form and ranked results (DM-22 RecommendationCriteria,
 * DM-23 RecommendationLoading, DM-24 Recommendation; use case Get School Recommendations, FR-REC-01).
 * <ul>
 *   <li>Login required (DC-07). The form is prefilled from {@code ProfileController.getProfile(sessionId)
 *       .toMatchCriteria()}; the starting point is the one chosen with the location picker (DC-23), else home.
 *       An incomplete profile shows what is missing and a link to the profile (AF-1).</li>
 *   <li>POST /recommendations runs {@code RecommendationController.recommend}, keeps the results in the HTTP
 *       session under a random id and redirects to GET /recommendations/results/{id}, so Refresh never repeats the
 *       commute-time calls. The last {@value #MAX_STORED_RESULTS} results are kept; only the login session that
 *       asked can open them (another account in the same browser gets the form again).</li>
 *   <li>DM-23: the form has {@code data-loading}, so loading.js shows the overlay while the POST runs.</li>
 *   <li>DC-74: when the dataset has no PSLE ranges, the PSLE score and posting group are optional and are not
 *       listed as missing from the profile; the pages say PSLE fit is not used ({@code psleDataAvailable}).</li>
 * </ul>
 */
@Controller
public class RecommendationUI {

    static final String RESULTS_ATTRIBUTE = "sm.recommendations";   // DC-54
    static final int MAX_STORED_RESULTS = 5;
    static final String CRITERIA_PATH = "/recommendations";

    private static final Logger log = LoggerFactory.getLogger(RecommendationUI.class);

    private final RecommendationController recommendationController;
    private final ProfileController profileController;
    private final FilterController filterController;   // DC-37: CCA and programme option lists
    private final SchoolDataController schoolDataController;   // DC-74: are there PSLE ranges at all?
    private final ReferenceLocationStore referenceLocationStore;
    private final AppProperties props;

    public RecommendationUI(RecommendationController recommendationController, ProfileController profileController,
                            FilterController filterController, SchoolDataController schoolDataController,
                            ReferenceLocationStore referenceLocationStore, AppProperties props) {
        this.recommendationController = recommendationController;
        this.profileController = profileController;
        this.filterController = filterController;
        this.schoolDataController = schoolDataController;
        this.referenceLocationStore = referenceLocationStore;
        this.props = props;
    }

    /**
     * GET /recommendations — the criteria form, prefilled from the profile (DC-07). Criteria in the query string
     * (the form's own field names) are an unsent draft and win over the profile: the location picker comes back
     * here with them, so setting a starting point does not empty the form.
     */
    @GetMapping(CRITERIA_PATH)
    public String displayCriteriaForm(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                                      @RequestParam(required = false) String psleScore,
                                      @RequestParam(required = false) String postingGroup,
                                      @RequestParam(required = false) String travelMode,
                                      @RequestParam(required = false) String maxCommuteMin,
                                      @RequestParam(required = false) List<String> preferredCCAs,
                                      @RequestParam(required = false) List<String> preferredProgrammes,
                                      HttpServletRequest request, Model model) {
        UserProfile profile = profileController.getProfile(sessionId);
        MatchCriteria criteria = profile.toMatchCriteria();
        referenceLocationStore.get(request).ifPresent(criteria::setStartLocation);
        boolean draft = psleScore != null || postingGroup != null || travelMode != null || maxCommuteMin != null
                || preferredCCAs != null || preferredProgrammes != null;
        if (draft) {
            criteria.setPsleScore(wholeNumber(psleScore));
            criteria.setPostingGroup(wholeNumber(postingGroup));
            criteria.setTravelMode(travelMode(travelMode));
            criteria.setMaxCommuteMin(wholeNumber(maxCommuteMin));
            criteria.setPreferredCCAs(preferredCCAs);
            criteria.setPreferredProgrammes(preferredProgrammes);
        }
        List<String> missing = missingProfileFields(profile, PsleAvailability.available(schoolDataController));
        if (!missing.isEmpty()) {
            model.addAttribute("missingProfileFields", missing);
        }
        addFormModel(model, criteria, draft);
        return "recommendation-criteria";
    }

    /**
     * POST /recommendations — {@code submitCriteria}: ranks the schools and redirects to the results.
     * Invalid input (AF-1) or a school data error (EX-1) shows the form again with the messages.
     */
    @PostMapping(CRITERIA_PATH)
    public String submitCriteria(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                                 @RequestParam(required = false) String psleScore,
                                 @RequestParam(required = false) String postingGroup,
                                 @RequestParam(required = false) String travelMode,
                                 @RequestParam(required = false) String maxCommuteMin,
                                 @RequestParam(required = false) List<String> preferredCCAs,
                                 @RequestParam(required = false) List<String> preferredProgrammes,
                                 HttpServletRequest request, Model model) {
        UserProfile profile = profileController.getProfile(sessionId);
        MatchCriteria criteria = new MatchCriteria();
        criteria.setPsleScore(wholeNumber(psleScore));      // not a number → null → the control's field message
        criteria.setPostingGroup(wholeNumber(postingGroup));
        criteria.setTravelMode(travelMode(travelMode));
        criteria.setMaxCommuteMin(wholeNumber(maxCommuteMin));
        criteria.setPreferredCCAs(preferredCCAs);
        criteria.setPreferredProgrammes(preferredProgrammes);
        criteria.setPrimarySchool(profile.getPrimarySchool());
        criteria.setStartLocation(referenceLocationStore.get(request)
                .orElse(profile.toMatchCriteria().getStartLocation()));
        criteria.setWeights(props.recommendation().weights());
        try {
            List<Recommendation> recommendations = recommendationController.recommend(criteria);
            String id = store(request.getSession(), new StoredRecommendations(sessionId, recommendations, criteria));
            return "redirect:" + CRITERIA_PATH + "/results/" + id;
        } catch (InvalidInputException e) {
            model.addAttribute(PageMessages.FIELD_ERRORS, e.getFieldErrors());
        } catch (ExternalServiceUnavailableException e) {
            ExternalFailureLog.warn(log, "Recommendations", e);
            model.addAttribute(PageMessages.FLASH_ERROR,
                    "School data is temporarily unavailable. Please try again in a few minutes.");
        }
        addFormModel(model, criteria, true);
        return "recommendation-criteria";
    }

    /** GET /recommendations/results/{id} — {@code displayRecommendations}; an unknown id goes back to the form. */
    @GetMapping(CRITERIA_PATH + "/results/{id}")
    public String displayRecommendations(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                                         @PathVariable String id, HttpServletRequest request, Model model,
                                         RedirectAttributes redirect) {
        StoredRecommendations stored = find(request.getSession(false), id);
        if (stored == null || !stored.loginSessionId().equals(sessionId)) {   // another login in this browser
            redirect.addFlashAttribute(PageMessages.FLASH_MESSAGE,
                    "Those results are no longer available. Please get recommendations again.");
            return "redirect:" + CRITERIA_PATH;
        }
        MatchCriteria criteria = stored.criteria();
        model.addAttribute("recommendations", stored.recommendations());
        model.addAttribute("criteria", criteria);
        model.addAttribute("travelModeLabel", modeLabel(criteria.getTravelMode()));
        model.addAttribute("weights", props.recommendation().weights());
        model.addAttribute("psleFit", props.recommendation().psleFit());   // app.recommendation.psle-fit.*
        model.addAttribute("reachNearMargin", props.recommendation().reachNearMargin());
        model.addAttribute("topN", props.recommendation().topN());
        model.addAttribute("resultId", id);
        return "recommendation-results";
    }

    // ------------------------------------------------------------------ helpers

    /** The results of one POST, the criteria they were made for, and the login session that asked (NFR-SEC-05). */
    record StoredRecommendations(String loginSessionId, List<Recommendation> recommendations, MatchCriteria criteria) {
    }

    /** @param keepDraft true: the location picker comes back to this form with the criteria filled in */
    private void addFormModel(Model model, MatchCriteria criteria, boolean keepDraft) {
        if (!model.containsAttribute(PageMessages.FIELD_ERRORS)) {
            model.addAttribute(PageMessages.FIELD_ERRORS, Map.of());   // the template reads it without null checks
        }
        model.addAttribute("criteria", criteria);
        model.addAttribute("startLocation", criteria.getStartLocation());
        model.addAttribute("startFromProfile", true);   // the picker labels the profile home and hides Clear
        model.addAttribute("travelModes", TravelMode.values());
        model.addAttribute("durationOptions", TransportationFilter.DURATION_OPTIONS_MIN.stream().sorted().toList());
        model.addAttribute("ccaOptions", filterController.getFilterOptions(AttributeCategory.CCA));
        model.addAttribute("programmeOptions", filterController.getFilterOptions(AttributeCategory.PROGRAMME));
        model.addAttribute("weights", props.recommendation().weights());
        model.addAttribute("returnTo", keepDraft ? draftUrl(criteria) : CRITERIA_PATH);
    }

    /** GET /recommendations with the criteria as query parameters (the form's field names). */
    static String draftUrl(MatchCriteria criteria) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath(CRITERIA_PATH);
        if (criteria.getPsleScore() != null) {
            url.queryParam("psleScore", criteria.getPsleScore());
        }
        if (criteria.getPostingGroup() != null) {
            url.queryParam("postingGroup", criteria.getPostingGroup());
        }
        if (criteria.getTravelMode() != null) {
            url.queryParam("travelMode", criteria.getTravelMode().name());
        }
        if (criteria.getMaxCommuteMin() != null) {
            url.queryParam("maxCommuteMin", criteria.getMaxCommuteMin());
        }
        criteria.getPreferredCCAs().forEach(cca -> url.queryParam("preferredCCAs", cca));
        criteria.getPreferredProgrammes().forEach(p -> url.queryParam("preferredProgrammes", p));
        String encoded = url.encode().build().toUriString();
        return encoded.length() <= 1900 ? encoded : CRITERIA_PATH;   // AuthInterceptor accepts up to 2000
    }

    /**
     * AF-1: what the profile still needs before recommendations can use it (UserProfile.isComplete).
     * DC-74: without PSLE data the PSLE score and posting group are not needed.
     */
    private static List<String> missingProfileFields(UserProfile profile, boolean psleData) {
        List<String> missing = new ArrayList<>();
        if (psleData && profile.getPsleScore() == null) {
            missing.add("PSLE score");
        }
        if (psleData && profile.getPostingGroup() == null) {
            missing.add("posting group");
        }
        if (profile.getHomeLocation() == null) {
            missing.add("home address");
        }
        if (profile.getTravelMode() == null) {
            missing.add("travel mode");
        }
        return missing;
    }

    /** Keeps the results under a new random id; only the newest {@value #MAX_STORED_RESULTS} are kept. */
    @SuppressWarnings("unchecked")
    private static String store(HttpSession session, StoredRecommendations results) {
        String id = UUID.randomUUID().toString();
        synchronized (WebUtils.getSessionMutex(session)) {
            Map<String, StoredRecommendations> all =
                    (Map<String, StoredRecommendations>) session.getAttribute(RESULTS_ATTRIBUTE);
            Map<String, StoredRecommendations> copy = all == null ? new LinkedHashMap<>() : new LinkedHashMap<>(all);
            copy.put(id, results);
            while (copy.size() > MAX_STORED_RESULTS) {
                copy.remove(copy.keySet().iterator().next());
            }
            session.setAttribute(RESULTS_ATTRIBUTE, copy);
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private static StoredRecommendations find(HttpSession session, String id) {
        if (session == null) {
            return null;
        }
        Object all = session.getAttribute(RESULTS_ATTRIBUTE);
        return all instanceof Map<?, ?> map ? ((Map<String, StoredRecommendations>) map).get(id) : null;
    }

    private static Integer wholeNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static TravelMode travelMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TravelMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "Public transport", "Walk", "Drive" (DC-01: TRANSIT is shown as public transport). */
    static String modeLabel(TravelMode mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case WALK -> "Walk";
            case DRIVE -> "Drive";
            case TRANSIT -> "Public transport";
        };
    }
}

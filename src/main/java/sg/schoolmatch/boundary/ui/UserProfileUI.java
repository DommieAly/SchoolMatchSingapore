package sg.schoolmatch.boundary.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import sg.schoolmatch.boundary.ui.support.AuthInterceptor;
import sg.schoolmatch.boundary.ui.support.FilterParams;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.FilterController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.search.AttributeCategory;
import sg.schoolmatch.entity.search.TransportationFilter;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Design class «boundary» UserProfileUI — the member's profile and logout
 * (DM-05 UserProfile, DM-06 LogoutConfirmation; use cases Manage Profile, Log Out;
 * FR-PROFILE-01, FR-LOGOUT-01..04, DC-03). Login required (AuthInterceptor).
 * <p>
 * One form saves the whole profile ({@code POST /profile}). When the home address has several matches, the page
 * shows them as a choice list and keeps the other edits; the matches wait in the HTTP session
 * ({@link #HOME_CANDIDATES}) until the member picks one ({@code homeChoice}) and saves again.
 */
@Controller
public class UserProfileUI {

    /** HTTP-session key of the address matches waiting for the member's choice. */
    public static final String HOME_CANDIDATES = "sm.homeCandidates";
    public static final String SAVED_MESSAGE = "Profile saved.";
    public static final String SAVE_FAILED_MESSAGE =
            "Your profile could not be saved. Your previous profile is unchanged. Please try again.";
    public static final String LOGGED_OUT_MESSAGE = "You have logged out.";
    public static final String CCA_MESSAGE = "Choose CCAs from the list";
    public static final String PROGRAMME_MESSAGE = "Choose programmes from the list";
    public static final String CHOICE_MESSAGE = "Choose one of the addresses in the list";

    /** Messages for values that are not even the right type (e.g. "abc" for the PSLE score). */
    private static final Map<String, String> TYPE_MESSAGES = Map.of(
            "psleScore", ProfileController.PSLE_MESSAGE,
            "postingGroup", ProfileController.POSTING_GROUP_MESSAGE,
            "maxCommuteMin", ProfileController.COMMUTE_MESSAGE,
            "travelMode", ProfileController.TRAVEL_MODE_MESSAGE,
            "homeChoice", CHOICE_MESSAGE);

    private static final Logger log = LoggerFactory.getLogger(UserProfileUI.class);

    private final ProfileController profileController;
    private final AuthController authController;
    private final FilterController filterController;   // DC-37: CCA and programme option lists
    private final SessionCookie sessionCookie;

    public UserProfileUI(ProfileController profileController, AuthController authController,
                         FilterController filterController, SessionCookie sessionCookie) {
        this.profileController = profileController;
        this.authController = authController;
        this.filterController = filterController;
        this.sessionCookie = sessionCookie;
    }

    /** GET /profile — the saved profile, or an empty form before the first save. */
    @GetMapping("/profile")
    public String displayUserProfile(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                                     Model model) {
        UserProfile profile = profileController.getProfile(sessionId);
        model.addAttribute("form", ProfileForm.from(profile));
        return showProfile(SavedProfile.of(profile), Map.of(), model);
    }

    /**
     * POST /profile — saves the whole profile (T-14). Invalid values: the page again with the field errors and
     * the typed values, nothing saved (AF-2). Several address matches: the choice list (UC special requirement).
     * Database error: a message, the saved profile unchanged (EX-2).
     */
    @PostMapping("/profile")
    public String submitProfile(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                                @ModelAttribute("form") ProfileForm form, BindingResult binding,
                                HttpSession httpSession, HttpServletResponse response, Model model,
                                RedirectAttributes redirect) {
        UserProfile edited = profileController.getProfile(sessionId);
        SavedProfile saved = SavedProfile.of(edited);   // for the page, before the edits are copied in
        Map<String, String> errors = checkForm(form, binding);
        if (!errors.isEmpty()) {
            // Also show the control's messages for the other fields now, not after the next submit (nothing saved).
            form.copyTo(edited);
            profileController.validateProfile(edited).forEach(errors::putIfAbsent);
            return showProfile(saved, errors, model);
        }

        form.copyTo(edited);
        edited.setHomeLocation(null);   // ProfileController keeps or looks up the location
        ReferenceLocation chosen = chosenAddress(form, httpSession);
        if (chosen != null) {
            edited.setHomeAddress(chosen.getInputText());
            edited.setHomeLocation(chosen.getCoordinate());
        }
        try {
            profileController.saveProfile(sessionId, edited);
        } catch (ProfileController.AmbiguousAddressException e) {
            httpSession.setAttribute(HOME_CANDIDATES, new HomeCandidates(form.getHomeAddress(), e.getCandidates()));
            model.addAttribute("homeCandidates", e.getCandidates());
            return showProfile(saved, e.getFieldErrors(), model);
        } catch (InvalidInputException e) {
            return showProfile(saved, e.getFieldErrors(), model);
        } catch (DataAccessException e) {
            log.warn("Saving a profile failed", e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            model.addAttribute(PageMessages.FLASH_ERROR, SAVE_FAILED_MESSAGE);
            return showProfile(saved, Map.of(), model);
        }
        httpSession.removeAttribute(HOME_CANDIDATES);
        redirect.addFlashAttribute(PageMessages.FLASH_MESSAGE, SAVED_MESSAGE);
        return "redirect:/profile";
    }

    /** GET /logout — asks "Log out?" (selectLogOut). Cancel goes back to the profile (AF-1, FR-LOGOUT-04). */
    @GetMapping("/logout")
    public String selectLogOut() {
        return "logout-confirm";
    }

    /**
     * POST /logout — the user confirmed: end the session on the server, clear the cookie, go to the home page
     * with "You have logged out." (FR-LOGOUT-02, FR-LOGOUT-03, DC-03). An expired session never gets here:
     * AuthInterceptor clears its cookie and treats the user as a guest (Log Out EX-1).
     * DC-71: the browser's HTTP session is also ended, so the member's starting point (typed address or device
     * position), the address choice list and stored results are not left for the next person on this browser.
     * The flash message then goes into a new, empty HTTP session.
     */
    @PostMapping("/logout")
    public String promptLogoutConfirmation(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                                           HttpServletRequest request, HttpServletResponse response,
                                           RedirectAttributes redirect) {
        authController.logout(sessionId);
        sessionCookie.clear(response);
        HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            httpSession.invalidate();
        }
        redirect.addFlashAttribute(PageMessages.FLASH_MESSAGE, LOGGED_OUT_MESSAGE);
        return "redirect:/";
    }

    /** Errors the page itself can find: values of the wrong type, and CCAs/programmes not in the dataset. */
    private Map<String, String> checkForm(ProfileForm form, BindingResult binding) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : binding.getFieldErrors()) {
            errors.putIfAbsent(error.getField(), TYPE_MESSAGES.getOrDefault(error.getField(), "Check this value"));
        }
        if (!filterController.getFilterOptions(AttributeCategory.CCA).containsAll(form.getPreferredCCAs())) {
            errors.put("preferredCCAs", CCA_MESSAGE);
        }
        if (!filterController.getFilterOptions(AttributeCategory.PROGRAMME)
                .containsAll(form.getPreferredProgrammes())) {
            errors.put("preferredProgrammes", PROGRAMME_MESSAGE);
        }
        return errors;
    }

    /** The address the member picked from the list, if it belongs to the address now in the form. */
    private static ReferenceLocation chosenAddress(ProfileForm form, HttpSession httpSession) {
        if (form.getHomeChoice() == null
                || !(httpSession.getAttribute(HOME_CANDIDATES) instanceof HomeCandidates stored)) {
            return null;
        }
        return stored.pick(form.getHomeAddress(), form.getHomeChoice());
    }

    private String showProfile(SavedProfile saved, Map<String, String> errors, Model model) {
        model.addAttribute(PageMessages.FIELD_ERRORS, errors);
        model.addAttribute("account", saved.account());
        model.addAttribute("profileComplete", saved.complete());
        model.addAttribute("homeLocationSaved", saved.homeLocationSaved());
        model.addAttribute("travelModes", travelModeLabels());
        model.addAttribute("commuteOptions", TransportationFilter.DURATION_OPTIONS_MIN.stream().sorted().toList());
        model.addAttribute("ccaOptions", filterController.getFilterOptions(AttributeCategory.CCA));
        model.addAttribute("programmeOptions", filterController.getFilterOptions(AttributeCategory.PROGRAMME));
        return "profile";
    }

    /** WALK → "Walking", DRIVE → "Car", TRANSIT → "Public transport" (same words as the filter page). */
    private static Map<TravelMode, String> travelModeLabels() {
        Map<TravelMode, String> labels = new EnumMap<>(TravelMode.class);
        for (TravelMode mode : TravelMode.values()) {
            String label = FilterParams.modeLabel(mode);
            labels.put(mode, label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1));
        }
        return labels;
    }

    /** What the page says about the stored profile (not about the values being edited). */
    private record SavedProfile(Account account, boolean complete, boolean homeLocationSaved) {

        static SavedProfile of(UserProfile profile) {
            return new SavedProfile(profile.getAccount(), profile.isComplete(), profile.getHomeLocation() != null);
        }
    }

    /** The address matches waiting for the member's choice, and the address text they belong to. */
    public record HomeCandidates(String address, List<ReferenceLocation> candidates) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        public HomeCandidates {
            address = address == null ? "" : address.strip();
            candidates = List.copyOf(candidates);
        }

        /** Candidate {@code index} when {@code formAddress} is still the searched address; otherwise null. */
        ReferenceLocation pick(String formAddress, int index) {
            boolean sameAddress = formAddress != null && formAddress.strip().equalsIgnoreCase(address);
            return sameAddress && index >= 0 && index < candidates.size() ? candidates.get(index) : null;
        }
    }

    /**
     * Form object for {@code profile.html} (UserProfile has no public no-arg constructor).
     * Ranges are checked by ProfileController: PSLE AL 4–32, posting group 1–3, commute 15/30/45/60 minutes.
     */
    public static class ProfileForm {

        private String displayName;
        private Integer psleScore;
        private Integer postingGroup;
        private String primarySchool;
        private String homeAddress;
        private Integer homeChoice;   // index into the address matches, only after "Several addresses match"
        private Set<String> preferredCCAs = new LinkedHashSet<>();
        private Set<String> preferredProgrammes = new LinkedHashSet<>();
        private Integer maxCommuteMin;
        private TravelMode travelMode;

        /** Copies a saved profile into the form. */
        public static ProfileForm from(UserProfile profile) {
            ProfileForm form = new ProfileForm();
            form.displayName = profile.getDisplayName();
            form.psleScore = profile.getPsleScore();
            form.postingGroup = profile.getPostingGroup();
            form.primarySchool = profile.getPrimarySchool();
            form.homeAddress = profile.getHomeAddress();
            form.preferredCCAs = new LinkedHashSet<>(profile.getPreferredCCAs());
            form.preferredProgrammes = new LinkedHashSet<>(profile.getPreferredProgrammes());
            form.maxCommuteMin = profile.getMaxCommuteMin();
            form.travelMode = profile.getTravelMode();
            return form;
        }

        /** Copies the form into {@code profile} (all fields except the home location). */
        void copyTo(UserProfile profile) {
            profile.setDisplayName(displayName);
            profile.setPsleScore(psleScore);
            profile.setPostingGroup(postingGroup);
            profile.setPrimarySchool(primarySchool);
            profile.setHomeAddress(homeAddress);
            profile.setPreferredCCAs(preferredCCAs);
            profile.setPreferredProgrammes(preferredProgrammes);
            profile.setMaxCommuteMin(maxCommuteMin);
            profile.setTravelMode(travelMode);
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public Integer getPsleScore() {
            return psleScore;
        }

        public void setPsleScore(Integer psleScore) {
            this.psleScore = psleScore;
        }

        public Integer getPostingGroup() {
            return postingGroup;
        }

        public void setPostingGroup(Integer postingGroup) {
            this.postingGroup = postingGroup;
        }

        public String getPrimarySchool() {
            return primarySchool;
        }

        public void setPrimarySchool(String primarySchool) {
            this.primarySchool = primarySchool;
        }

        public String getHomeAddress() {
            return homeAddress;
        }

        public void setHomeAddress(String homeAddress) {
            this.homeAddress = homeAddress;
        }

        public Integer getHomeChoice() {
            return homeChoice;
        }

        public void setHomeChoice(Integer homeChoice) {
            this.homeChoice = homeChoice;
        }

        public Set<String> getPreferredCCAs() {
            return preferredCCAs;
        }

        public void setPreferredCCAs(Set<String> preferredCCAs) {
            this.preferredCCAs = preferredCCAs == null ? new LinkedHashSet<>() : preferredCCAs;
        }

        public Set<String> getPreferredProgrammes() {
            return preferredProgrammes;
        }

        public void setPreferredProgrammes(Set<String> preferredProgrammes) {
            this.preferredProgrammes = preferredProgrammes == null ? new LinkedHashSet<>() : preferredProgrammes;
        }

        public Integer getMaxCommuteMin() {
            return maxCommuteMin;
        }

        public void setMaxCommuteMin(Integer maxCommuteMin) {
            this.maxCommuteMin = maxCommuteMin;
        }

        public TravelMode getTravelMode() {
            return travelMode;
        }

        public void setTravelMode(TravelMode travelMode) {
            this.travelMode = travelMode;
        }
    }
}

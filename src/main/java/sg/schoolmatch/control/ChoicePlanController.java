package sg.schoolmatch.control;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.shortlist.ChoicePlan;
import sg.schoolmatch.entity.shortlist.SchoolChoice;
import sg.schoolmatch.entity.shortlist.Shortlist;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.persistence.ShortlistRepository;

/**
 * Design class «control» ChoicePlanController — plan up to 6 ordered choices with SAFE/MATCH/REACH labels
 * (use case Plan School Choices; FR-PLAN-01, FR-PLAN-02, proposed ids). DC-06: methods take the sessionId.
 * DC-14: depends on ProfileController (score, affiliation) and SchoolDataController.
 * The plan is saved together with its Shortlist. Called by ChoicePlanUI.
 * <p>
 * The member's shortlist (and so the account) always comes from {@link ShortlistController#getShortlist}, so a
 * plan can only hold schools from the member's own shortlist (NFR-SEC-05).
 */
@Service
public class ChoicePlanController {

    public static final String NOT_SHORTLISTED_MESSAGE = "Add this school to your shortlist before adding it to your plan.";
    public static final String NOT_IN_DATASET_MESSAGE =
            "This school is no longer in the dataset, so it cannot be added to your plan.";
    public static final String PLAN_FULL_MESSAGE =
            "Your plan already has " + ChoicePlan.MAX_CHOICES + " choices. Remove one before adding another.";
    public static final String NO_CHOICES_MESSAGE = "Your plan has no choices to move.";
    public static final String NO_SCORE_MESSAGE = "Add your PSLE score in your profile first.";

    private final ShortlistController shortlistController;
    private final ProfileController profileController;
    private final SchoolDataController schoolDataController;
    private final ShortlistRepository shortlistRepository;
    private final Clock clock;

    public ChoicePlanController(ShortlistController shortlistController, ProfileController profileController,
                                SchoolDataController schoolDataController, ShortlistRepository shortlistRepository,
                                Clock clock) {
        this.shortlistController = shortlistController;
        this.profileController = profileController;
        this.schoolDataController = schoolDataController;
        this.shortlistRepository = shortlistRepository;
        this.clock = clock;
    }

    /**
     * The member's plan, with schools, affiliation (DC-21) and the current profile values filled in.
     * On first use an empty plan is created and saved with the profile's PSLE score and posting group.
     * A plan made before the member entered a score takes the profile's score now.
     */
    public ChoicePlan getPlan(String sessionId) {
        Shortlist shortlist = shortlistController.getShortlist(sessionId);
        UserProfile profile = profileController.getProfile(sessionId);
        ChoicePlan plan = shortlist.getChoicePlan();
        if (plan == null || (plan.getBasedOnScore() == null && scoreOf(profile) != null)) {
            if (plan == null) {
                plan = new ChoicePlan(null, null);
                shortlist.setChoicePlan(plan);
            }
            copyScoreFromProfile(plan, profile);
            plan = save(shortlist).getChoicePlan();
        }
        fillFromProfile(plan, profile);
        return plan;
    }

    /**
     * DC-06: inserts a school from the member's shortlist at {@code rank} (1..n+1); later choices move down.
     *
     * @throws InvalidInputException (field {@code code}) when the school is not shortlisted, no longer in the
     *                               dataset, already in the plan, or the plan has 6 choices; (field {@code rank})
     *                               when the rank is outside 1..n+1
     */
    public void addChoice(String sessionId, String schoolCode, int rank) {
        Shortlist shortlist = shortlistController.getShortlist(sessionId);
        if (!shortlist.contains(schoolCode)) {
            throw new InvalidInputException("code", NOT_SHORTLISTED_MESSAGE);
        }
        School school = shortlist.getSchools().stream()
                .filter(s -> s.getSchoolCode().equals(schoolCode))
                .findFirst()
                .orElseThrow(() -> new InvalidInputException("code", NOT_IN_DATASET_MESSAGE));
        ChoicePlan plan = shortlist.getChoicePlan();
        if (plan == null) {
            plan = new ChoicePlan(null, null);
            copyScoreFromProfile(plan, profileController.getProfile(sessionId));
            shortlist.setChoicePlan(plan);
        }
        if (plan.contains(schoolCode)) {
            throw new InvalidInputException("code", school.getName() + " is already in your plan.");
        }
        int size = plan.getChoices().size();
        if (size >= ChoicePlan.MAX_CHOICES) {
            throw new InvalidInputException("code", PLAN_FULL_MESSAGE);
        }
        if (rank < 1 || rank > size + 1) {
            throw new InvalidInputException("rank", positionMessage(size + 1));
        }
        plan.addChoice(school, rank);
        plan.setUpdatedAt(clock.instant());
        save(shortlist);
    }

    /**
     * Moves the choice at rank {@code from} to rank {@code to} (both 1..n).
     *
     * @throws InvalidInputException (field {@code rank}) when a rank is outside 1..n or there are no choices
     */
    public void reorderChoices(String sessionId, int from, int to) {
        Shortlist shortlist = shortlistController.getShortlist(sessionId);
        ChoicePlan plan = shortlist.getChoicePlan();
        int size = plan == null ? 0 : plan.getChoices().size();
        if (size == 0) {
            throw new InvalidInputException("rank", NO_CHOICES_MESSAGE);
        }
        if (from < 1 || from > size || to < 1 || to > size) {
            throw new InvalidInputException("rank", positionMessage(size));
        }
        if (from == to) {
            return;
        }
        plan.reorder(from, to);
        plan.setUpdatedAt(clock.instant());
        save(shortlist);
    }

    /** DC-06: removes the school from the plan (it stays on the shortlist). A school not in the plan is ignored. */
    public void removeChoice(String sessionId, String schoolCode) {
        Shortlist shortlist = shortlistController.getShortlist(sessionId);
        ChoicePlan plan = shortlist.getChoicePlan();
        if (plan == null || !plan.contains(schoolCode)) {
            return;
        }
        plan.removeChoice(schoolCode);
        plan.setUpdatedAt(clock.instant());
        save(shortlist);
    }

    /**
     * Risk warnings for a plan from {@link #getPlan} (FR-PLAN-02; rules in docs/recommendation-scoring.md §4):
     * the plan's own warnings, plus one when the profile's score or posting group changed after the plan was made.
     */
    public List<String> assessPlan(ChoicePlan plan) {
        List<String> warnings = new ArrayList<>(plan.getRiskWarnings());
        if (plan.isOutdated()) {
            warnings.add(profileChangedWarning(plan));
        }
        return warnings;
    }

    /**
     * Makes the plan use the profile's current PSLE score and posting group (the "Use my current score" button
     * shown when {@link ChoicePlan#isOutdated()}).
     *
     * @throws InvalidInputException (field {@code score}) when the profile has no PSLE score
     */
    public void useProfileScore(String sessionId) {   // DC-65
        Shortlist shortlist = shortlistController.getShortlist(sessionId);
        UserProfile profile = profileController.getProfile(sessionId);
        if (scoreOf(profile) == null) {
            throw new InvalidInputException("score", NO_SCORE_MESSAGE);
        }
        ChoicePlan plan = shortlist.getChoicePlan();
        if (plan == null) {
            plan = new ChoicePlan(null, null);
            shortlist.setChoicePlan(plan);
        }
        copyScoreFromProfile(plan, profile);
        save(shortlist);
    }

    /** "This plan was made for a score of 10, but your profile now says 14." and the like. */
    private static String profileChangedWarning(ChoicePlan plan) {
        Integer profileScore = plan.getCurrentProfileScore();
        if (profileScore == null) {
            return "This plan was made for a score of " + plan.getBasedOnScore()
                    + ", but your profile has no PSLE score now.";
        }
        if (!profileScore.equals(plan.getBasedOnScore())) {
            return "This plan was made for a score of " + plan.getBasedOnScore()
                    + ", but your profile now says " + profileScore + ".";
        }
        return "This plan was made for posting group " + plan.getPostingGroup()
                + ", but your profile now says posting group " + plan.getCurrentProfilePostingGroup() + ".";
    }

    /** Sets the plan's score and posting group from the profile; a score without a PG uses the default PG3. */
    private void copyScoreFromProfile(ChoicePlan plan, UserProfile profile) {
        Integer score = scoreOf(profile);
        Integer postingGroup = profile == null ? null : profile.getPostingGroup();
        if (score != null && postingGroup == null) {
            postingGroup = ChoicePlan.DEFAULT_POSTING_GROUP;
        }
        plan.setBasedOnScore(score);
        plan.setPostingGroup(postingGroup);
        plan.setUpdatedAt(clock.instant());
    }

    /** DC-21: each choice's affiliation, and the profile's current score and posting group. */
    private static void fillFromProfile(ChoicePlan plan, UserProfile profile) {
        String primarySchool = profile == null ? null : profile.getPrimarySchool();
        for (SchoolChoice choice : plan.getChoices()) {
            choice.setAffiliated(choice.getSchool() != null && isAffiliated(choice.getSchool(), primarySchool));
        }
        plan.setCurrentProfile(scoreOf(profile), profile == null ? null : profile.getPostingGroup());
    }

    /**
     * DC-22: true when the member's primary school is one of the school's affiliated primary schools.
     * Names are compared ignoring case and extra spaces, because the profile field is free text.
     */
    private static boolean isAffiliated(School school, String primarySchool) {
        if (primarySchool == null || primarySchool.isBlank()) {
            return false;
        }
        String wanted = normalise(primarySchool);
        return school.getAffiliatedPrimarySchools().stream().anyMatch(name -> normalise(name).equals(wanted));
    }

    private static String normalise(String name) {
        return name.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static Integer scoreOf(UserProfile profile) {
        return profile == null ? null : profile.getPsleScore();
    }

    private static String positionMessage(int last) {
        return "Choose a position from 1 to " + last + ".";
    }

    /**
     * Saves the shortlist with its plan. Saving returns a new copy without the transient school data (DC-21),
     * so the copy is resolved again before it is used.
     */
    private Shortlist save(Shortlist shortlist) {
        Shortlist saved = shortlistRepository.save(shortlist);
        Map<String, School> byCode = schoolDataController.getSchools().stream()
                .collect(Collectors.toMap(School::getSchoolCode, Function.identity(), (first, second) -> first));
        saved.resolveSchools(byCode);
        return saved;
    }
}

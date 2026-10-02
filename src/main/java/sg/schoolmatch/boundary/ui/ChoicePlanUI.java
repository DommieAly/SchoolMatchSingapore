package sg.schoolmatch.boundary.ui;

import java.util.List;
import java.util.stream.IntStream;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import sg.schoolmatch.boundary.ui.support.AuthInterceptor;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.control.ChoicePlanController;
import sg.schoolmatch.control.ShortlistController;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.shortlist.AdmissionChance;
import sg.schoolmatch.entity.shortlist.ChoicePlan;
import sg.schoolmatch.entity.shortlist.SchoolChoice;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Design class «boundary» ChoicePlanUI — up to 6 ordered school choices with SAFE/MATCH/REACH and risk warnings
 * (DM-17 ChoicePlanner; use case Plan School Choices, FR-PLAN-01, FR-PLAN-02; DC-20). Login required.
 * <p>
 * Design display operations are {@code th:fragment} names in {@code choice-plan.html}: {@code displayPlan},
 * {@code displayAdmissionChance}, {@code displayRiskWarnings}. Every action is a POST that redirects back to
 * GET /plan with a message.
 * Model attributes: {@code plan}, {@code rows} ({@link ChoiceRow}), {@code warnings}, {@code addableSchools}
 * (shortlisted schools not in the plan), {@code positions} (1..n+1, empty when the plan is full),
 * {@code emptyRanks} (n+1..6, shown as empty slots), {@code shortlistEmpty}, {@code safeMargin}, {@code maxChoices}.
 * DC-37 style arrow: ChoicePlanUI → ShortlistController ({@code getShortlistedSchools}) for the "Add a school" list.
 */
@Controller
public class ChoicePlanUI {

    /**
     * One plan row: the choice, its school (null when it left the dataset), the applicable range and the
     * label (both null when not available).
     */
    public record ChoiceRow(int rank, String schoolCode, School school, IndicativePsleScoreRange range,
                            AdmissionChance chance, boolean first, boolean last) {
    }

    public static final String ADDED_MESSAGE = "Added to your plan.";
    public static final String REMOVED_MESSAGE = "Removed from your plan.";
    public static final String SCORE_UPDATED_MESSAGE =
            "Your plan now uses the PSLE score and posting group from your profile.";
    public static final String POSITION_MESSAGE = "Choose a position from the list.";
    public static final String CHOOSE_SCHOOL_MESSAGE = "Choose a school from your shortlist.";
    public static final String NOT_IN_PLAN_MESSAGE = "That school is not in your plan.";
    public static final String UNAVAILABLE_MESSAGE =
            "Your plan is temporarily unavailable. Please try again in a few minutes.";
    public static final String SAVE_FAILED_MESSAGE = "Your plan could not be saved. Please try again.";

    private final ChoicePlanController choicePlanController;
    private final ShortlistController shortlistController;

    public ChoicePlanUI(ChoicePlanController choicePlanController, ShortlistController shortlistController) {
        this.choicePlanController = choicePlanController;
        this.shortlistController = shortlistController;
    }

    /** GET /plan — the plan with labels (displayAdmissionChance) and warnings (displayRiskWarnings). */
    @GetMapping("/plan")
    public String displayPlan(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                              Model model) {
        model.addAttribute("safeMargin", SchoolChoice.SAFE_MARGIN);
        model.addAttribute("maxChoices", ChoicePlan.MAX_CHOICES);
        try {
            ChoicePlan plan = choicePlanController.getPlan(sessionId);
            List<School> shortlisted = shortlistController.getShortlistedSchools(sessionId);
            int size = plan.getChoices().size();
            model.addAttribute("plan", plan);
            model.addAttribute("rows", rows(plan));
            model.addAttribute("warnings", choicePlanController.assessPlan(plan));
            model.addAttribute("addableSchools",
                    shortlisted.stream().filter(s -> !plan.contains(s.getSchoolCode())).toList());
            model.addAttribute("shortlistEmpty", shortlisted.isEmpty());
            model.addAttribute("positions", size >= ChoicePlan.MAX_CHOICES ? List.of()
                    : IntStream.rangeClosed(1, size + 1).boxed().toList());
            model.addAttribute("emptyRanks", IntStream.rangeClosed(size + 1, ChoicePlan.MAX_CHOICES).boxed().toList());
        } catch (DataAccessException e) {
            model.addAttribute("unavailable", true);
            model.addAttribute(PageMessages.FLASH_ERROR, UNAVAILABLE_MESSAGE);
        }
        return "choice-plan";
    }

    /** POST /plan/choices (fields {@code code}, {@code rank}) — DC-06 addChoice. */
    @PostMapping("/plan/choices")
    public String addChoice(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                            @RequestParam(name = "code", required = false) String code,
                            @RequestParam(name = "rank", required = false) String rank,
                            RedirectAttributes redirect) {
        if (code == null || code.isBlank()) {
            return backToPlan(redirect, PageMessages.FLASH_ERROR, CHOOSE_SCHOOL_MESSAGE);
        }
        int position;
        try {
            position = Integer.parseInt(rank == null ? "" : rank.strip());
        } catch (NumberFormatException e) {
            return backToPlan(redirect, PageMessages.FLASH_ERROR, POSITION_MESSAGE);
        }
        try {
            choicePlanController.addChoice(sessionId, code.strip(), position);
            return backToPlan(redirect, PageMessages.FLASH_MESSAGE, ADDED_MESSAGE);
        } catch (InvalidInputException e) {
            return backToPlan(redirect, PageMessages.FLASH_ERROR, String.join(" ", e.getFieldErrors().values()));
        } catch (DataAccessException e) {
            return backToPlan(redirect, PageMessages.FLASH_ERROR, SAVE_FAILED_MESSAGE);
        }
    }

    /** POST /plan/choices/{code}/move?dir=up|down — moves the choice one place; at the top/bottom nothing moves. */
    @PostMapping("/plan/choices/{code}/move")
    public String reorderChoice(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                                @PathVariable("code") String code,
                                @RequestParam(name = "dir", defaultValue = "") String dir,
                                RedirectAttributes redirect) {
        try {
            ChoicePlan plan = choicePlanController.getPlan(sessionId);
            SchoolChoice choice = plan.getChoices().stream()
                    .filter(c -> c.getSchoolCode().equals(code)).findFirst().orElse(null);
            if (choice == null) {
                return backToPlan(redirect, PageMessages.FLASH_ERROR, NOT_IN_PLAN_MESSAGE);
            }
            int from = choice.getRank();
            int to = switch (dir) {
                case "up" -> from - 1;
                case "down" -> from + 1;
                default -> from;
            };
            if (to != from && to >= 1 && to <= plan.getChoices().size()) {
                choicePlanController.reorderChoices(sessionId, from, to);
            }
            return "redirect:/plan";
        } catch (InvalidInputException e) {
            return backToPlan(redirect, PageMessages.FLASH_ERROR, String.join(" ", e.getFieldErrors().values()));
        } catch (DataAccessException e) {
            return backToPlan(redirect, PageMessages.FLASH_ERROR, SAVE_FAILED_MESSAGE);
        }
    }

    /** POST /plan/choices/{code}/remove — DC-06 removeChoice; the school stays on the shortlist. */
    @PostMapping("/plan/choices/{code}/remove")
    public String removeChoice(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                               @PathVariable("code") String code, RedirectAttributes redirect) {
        try {
            choicePlanController.removeChoice(sessionId, code);
            return backToPlan(redirect, PageMessages.FLASH_MESSAGE, REMOVED_MESSAGE);
        } catch (DataAccessException e) {
            return backToPlan(redirect, PageMessages.FLASH_ERROR, SAVE_FAILED_MESSAGE);
        }
    }

    /** POST /plan/score — "Use my current score": the plan takes the profile's score and posting group. */
    @PostMapping("/plan/score")
    public String useProfileScore(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                                  RedirectAttributes redirect) {
        try {
            choicePlanController.useProfileScore(sessionId);
            return backToPlan(redirect, PageMessages.FLASH_MESSAGE, SCORE_UPDATED_MESSAGE);
        } catch (InvalidInputException e) {
            return backToPlan(redirect, PageMessages.FLASH_ERROR, String.join(" ", e.getFieldErrors().values()));
        } catch (DataAccessException e) {
            return backToPlan(redirect, PageMessages.FLASH_ERROR, SAVE_FAILED_MESSAGE);
        }
    }

    /** One row per choice: range and label for the plan's score and posting group (DC-20, DC-22). */
    private static List<ChoiceRow> rows(ChoicePlan plan) {
        List<SchoolChoice> choices = plan.getChoices();
        Integer postingGroup = plan.getPostingGroup();
        return choices.stream()
                .map(c -> new ChoiceRow(c.getRank(), c.getSchoolCode(), c.getSchool(),
                        postingGroup == null ? null : c.getApplicableRange(postingGroup).orElse(null),
                        plan.getAdmissionChance(c),
                        c.getRank() == 1, c.getRank() == choices.size()))
                .toList();
    }

    private static String backToPlan(RedirectAttributes redirect, String kind, String message) {
        redirect.addFlashAttribute(kind, message);
        return "redirect:/plan";
    }
}

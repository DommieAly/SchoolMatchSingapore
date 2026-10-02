package sg.schoolmatch.boundary.ui;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import sg.schoolmatch.boundary.ui.support.AuthInterceptor;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.control.ShortlistController;
import sg.schoolmatch.entity.shortlist.Shortlist;

/**
 * Design class «boundary» ShortlistUI — the member's shortlist (DM-16 Shortlist; use cases Add School to Shortlist,
 * Remove School from Shortlist, View Shortlisted Schools;
 * FR-SHORTLIST-01..07, NFR-SEC-05). Login required (AuthInterceptor; POST /schools/{code}/shortlist only).
 * <p>
 * Design display operations are {@code th:fragment} names in {@code shortlist.html}: {@code displayShortlist},
 * {@code displayEmptyShortlist}, {@code displayServiceUnavailable}. The links {@code selectCompare}
 * (GET /compare?codes=…) and {@code selectPlanChoices} (GET /plan) are on the page.
 * Model attributes: {@code schools} (List&lt;School&gt;), {@code missingCodes} (codes no longer in the dataset),
 * or {@code unavailable} = true.
 */
@Controller
public class ShortlistUI {

    public static final String ADDED_MESSAGE = "Added to your shortlist.";
    public static final String ALREADY_MESSAGE = "Already in your shortlist.";
    public static final String REMOVED_MESSAGE = "Removed from your shortlist.";
    public static final String UNAVAILABLE_MESSAGE =
            "Your shortlist is temporarily unavailable. Please try again in a few minutes.";
    public static final String SAVE_FAILED_MESSAGE = "Your shortlist could not be saved. Please try again.";

    private final ShortlistController shortlistController;

    public ShortlistUI(ShortlistController shortlistController) {
        this.shortlistController = shortlistController;
    }

    /**
     * GET /shortlist (UC #13, FR-SHORTLIST-04/05/07). One read gives both the schools (the same list as
     * {@code getShortlistedSchools}) and the codes that left the dataset.
     */
    @GetMapping("/shortlist")
    public String displayShortlist(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                                   Model model) {
        try {
            Shortlist shortlist = shortlistController.getShortlist(sessionId);
            model.addAttribute("schools", shortlist.getSchools());
            model.addAttribute("missingCodes", shortlist.getUnresolvedSchoolCodes());
        } catch (DataAccessException e) {
            model.addAttribute("unavailable", true);     // displayServiceUnavailable
            model.addAttribute(PageMessages.FLASH_ERROR, UNAVAILABLE_MESSAGE);
        }
        return "shortlist";
    }

    /**
     * POST /schools/{code}/shortlist — from the School Details page (UC #11; DC-38: the handler lives here, not in
     * SchoolDetailsUI). A guest is sent to login first (DC-08). An unknown code answers 404 (NotFoundException).
     */
    @PostMapping("/schools/{code}/shortlist")
    public String selectAddToShortlist(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                                       @PathVariable("code") String code, RedirectAttributes redirect) {
        try {
            boolean added = shortlistController.addSchool(sessionId, code);
            // false → displayAlreadyShortlisted (FR-SHORTLIST-02, AF-1)
            redirect.addFlashAttribute(PageMessages.FLASH_MESSAGE, added ? ADDED_MESSAGE : ALREADY_MESSAGE);
        } catch (DataAccessException e) {
            redirect.addFlashAttribute(PageMessages.FLASH_ERROR, SAVE_FAILED_MESSAGE);
        }
        return "redirect:/schools/{code}";
    }

    /**
     * POST /shortlist/{code}/remove (UC #12, FR-SHORTLIST-06). The button asks for confirmation in the browser;
     * Cancel sends nothing (AF-1).
     */
    @PostMapping("/shortlist/{code}/remove")
    public String selectRemoveSchool(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                                     @PathVariable("code") String code, RedirectAttributes redirect) {
        try {
            shortlistController.removeSchool(sessionId, code);
            redirect.addFlashAttribute(PageMessages.FLASH_MESSAGE, REMOVED_MESSAGE);
        } catch (DataAccessException e) {
            redirect.addFlashAttribute(PageMessages.FLASH_ERROR, SAVE_FAILED_MESSAGE);
        }
        return "redirect:/shortlist";
    }
}

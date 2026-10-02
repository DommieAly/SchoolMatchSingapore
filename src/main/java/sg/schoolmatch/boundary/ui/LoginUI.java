package sg.schoolmatch.boundary.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;
import sg.schoolmatch.boundary.ui.support.AuthInterceptor;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.ShortlistController;
import sg.schoolmatch.entity.account.AuthenticatedSession;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotFoundException;

/**
 * Design class «boundary» LoginUI — the login form (DM-02 LoginForm; use case Log In, FR-LOGIN-01..05).
 * {@code next} = local page to return to (T-77); {@code add} = school code to shortlist after login (DC-08, T-76).
 * A failed login goes back to {@code GET /login} with the errors (Post/Redirect/Get), keeping next and add.
 */
@Controller
public class LoginUI {

    public static final String UNAVAILABLE_MESSAGE = "Login is temporarily unavailable. Please try again later.";
    public static final String ADDED_MESSAGE = "Added to shortlist";
    public static final String ALREADY_SHORTLISTED_MESSAGE = "Already in your shortlist";

    /** After login never go back to the logout page: that session had already ended (Log Out EX-1). */
    private static final String LOGOUT_PATH = "/logout";

    private static final Logger log = LoggerFactory.getLogger(LoginUI.class);

    private final AuthController authController;
    private final ShortlistController shortlistController;
    private final SessionCookie sessionCookie;

    public LoginUI(AuthController authController, ShortlistController shortlistController,
                   SessionCookie sessionCookie) {
        this.authController = authController;
        this.shortlistController = shortlistController;
        this.sessionCookie = sessionCookie;
    }

    /**
     * GET /login — {@code next} is kept only when it is a safe local path. Errors and the typed identifier
     * arrive as flash attributes from a failed {@link #submitLogin}.
     */
    @GetMapping("/login")
    public String displayLoginForm(@RequestParam(required = false) String next,
                                   @RequestParam(required = false) String add, Model model) {
        model.addAttribute("next", safeNext(next));
        model.addAttribute("add", AuthInterceptor.safeSchoolCode(add));
        model.addAttribute("sessionEnded", LOGOUT_PATH.equals(next));
        if (!model.containsAttribute(PageMessages.FIELD_ERRORS)) {
            model.addAttribute(PageMessages.FIELD_ERRORS, Map.of());
        }
        return "login";
    }

    /**
     * POST /login (identifier, password, next, add). Success: new session + cookie (FR-LOGIN-04), an old session
     * on this browser is ended, then {@code add} is shortlisted (DC-08) or the user goes to {@code next} or "/".
     * Failure: back to the form with field errors or the one generic message (AF-1, AF-2, FR-LOGIN-05).
     */
    @PostMapping("/login")
    public RedirectView submitLogin(@RequestParam(required = false) String identifier,
                                    @RequestParam(required = false) String password,
                                    @RequestParam(required = false) String next,
                                    @RequestParam(required = false) String add,
                                    HttpServletRequest request, HttpServletResponse response,
                                    RedirectAttributes redirect) {
        String safeNext = safeNext(next);
        String safeAdd = AuthInterceptor.safeSchoolCode(add);
        AuthenticatedSession session;
        try {
            session = authController.login(identifier, password);
        } catch (InvalidInputException e) {
            redirect.addFlashAttribute(PageMessages.FIELD_ERRORS, e.getFieldErrors());   // showLoginError
            redirect.addFlashAttribute("identifier", identifier == null ? null : identifier.trim());
            return redirectTo(AuthInterceptor.loginUrl(safeNext, safeAdd));
        } catch (DataAccessException e) {
            log.warn("Login failed: database unavailable", e);                         // EX-1
            redirect.addFlashAttribute(PageMessages.FLASH_ERROR, UNAVAILABLE_MESSAGE);
            return redirectTo(AuthInterceptor.loginUrl(safeNext, safeAdd));
        }

        sessionCookie.read(request).ifPresent(authController::logout);   // a previous login on this browser
        sessionCookie.create(response, session.getSessionId());
        if (request.getSession(false) != null) {
            request.changeSessionId();   // DC-71: a new HTTP session id after login (the guest's starting point stays)
        }

        if (safeAdd != null) {
            return finishAddToShortlist(session.getSessionId(), safeAdd, redirect);
        }
        return redirectTo(safeNext != null ? safeNext : "/");
    }

    /** DC-08: the guest pressed "Add to shortlist" before logging in; add it now and show the school again. */
    private RedirectView finishAddToShortlist(String sessionId, String schoolCode, RedirectAttributes redirect) {
        try {
            boolean added = shortlistController.addSchool(sessionId, schoolCode);
            redirect.addFlashAttribute(PageMessages.FLASH_MESSAGE, added ? ADDED_MESSAGE : ALREADY_SHORTLISTED_MESSAGE);
            return redirectTo("/schools/" + schoolCode);
        } catch (NotFoundException e) {
            redirect.addFlashAttribute(PageMessages.FLASH_ERROR,
                    "You are logged in, but that school is no longer in the dataset.");
            return redirectTo("/schools");
        } catch (RuntimeException e) {
            log.warn("Login succeeded but adding {} to the shortlist failed", schoolCode, e);
            redirect.addFlashAttribute(PageMessages.FLASH_ERROR,
                    "You are logged in, but the school could not be added to your shortlist. Please try again.");
            return redirectTo("/schools/" + schoolCode);
        }
    }

    /** A safe local {@code next}, never the logout page. */
    private static String safeNext(String next) {
        String safe = AuthInterceptor.safeLocalPath(next);
        return LOGOUT_PATH.equals(safe) ? null : safe;
    }

    /** Redirect to a local URL exactly as given: no URI-template expansion of "{…}", no model attributes added. */
    private static RedirectView redirectTo(String url) {
        RedirectView view = new RedirectView(url, true);
        view.setExpandUriTemplateVariables(false);
        view.setExposeModelAttributes(false);
        return view;
    }
}

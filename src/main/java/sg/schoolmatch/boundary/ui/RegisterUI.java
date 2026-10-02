package sg.schoolmatch.boundary.ui;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.control.AccountController;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Design class «boundary» RegisterUI — the registration form (DM-03 RegistrationForm; use case Register,
 * FR-REG-01..05, NFR-SEC-04). It checks that the confirmation matches (AF-3) and shows every field error at
 * once (AF-1, {@code highlightInvalidFields}); AccountController checks the rest. Success → the login page
 * with "Account created. Please log in." and no session (FR-REG-05).
 */
@Controller
public class RegisterUI {

    public static final String CONFIRM_MESSAGE = "The passwords do not match";
    public static final String CONFIRM_MISSING_MESSAGE = "Enter the password again to confirm it";
    public static final String SUCCESS_MESSAGE = "Account created. Please log in.";
    public static final String UNAVAILABLE_MESSAGE =
            "Your account cannot be created at this time. Please try again later.";

    private static final Logger log = LoggerFactory.getLogger(RegisterUI.class);

    private final AccountController accountController;

    public RegisterUI(AccountController accountController) {
        this.accountController = accountController;
    }

    @GetMapping("/register")
    public String displayRegistrationForm(Model model) {
        model.addAttribute("form", new RegistrationForm());
        model.addAttribute(PageMessages.FIELD_ERRORS, Map.of());
        return "register";
    }

    /** POST /register (username, email, password, confirm). */
    @PostMapping("/register")
    public String submitRegistration(@ModelAttribute("form") RegistrationForm form, Model model,
                                     HttpServletResponse response, RedirectAttributes redirect) {
        Map<String, String> errors = new LinkedHashMap<>(
                accountController.validateRegistration(form.getUsername(), form.getEmail(), form.getPassword()));
        if (form.getConfirm() == null || form.getConfirm().isEmpty()) {
            errors.put("confirm", CONFIRM_MISSING_MESSAGE);
        } else if (!form.getConfirm().equals(form.getPassword())) {
            errors.put("confirm", CONFIRM_MESSAGE);
        }
        if (!errors.isEmpty()) {
            return highlightInvalidFields(form, errors, model);
        }
        try {
            accountController.register(form.getUsername(), form.getEmail(), form.getPassword());
        } catch (InvalidInputException e) {
            return highlightInvalidFields(form, e.getFieldErrors(), model);   // e.g. registered meanwhile (AF-2)
        } catch (DataAccessException e) {
            log.warn("Registration failed: database unavailable", e);         // EX-1
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            model.addAttribute(PageMessages.FLASH_ERROR, UNAVAILABLE_MESSAGE);
            return highlightInvalidFields(form, Map.of(), model);
        }
        redirect.addFlashAttribute(PageMessages.FLASH_MESSAGE, SUCCESS_MESSAGE);
        redirect.addFlashAttribute("identifier", form.getUsername().trim());   // prefills the login form
        return "redirect:/login";
    }

    /** Shows the form again with the errors; the passwords are never sent back to the page (NFR-SEC-04). */
    private static String highlightInvalidFields(RegistrationForm form, Map<String, String> errors, Model model) {
        form.setPassword(null);
        form.setConfirm(null);
        model.addAttribute(PageMessages.FIELD_ERRORS, errors);
        return "register";
    }

    /** Form object for {@code register.html}. */
    public static class RegistrationForm {

        private String username;
        private String email;
        private String password;
        private String confirm;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getConfirm() {
            return confirm;
        }

        public void setConfirm(String confirm) {
            this.confirm = confirm;
        }
    }
}

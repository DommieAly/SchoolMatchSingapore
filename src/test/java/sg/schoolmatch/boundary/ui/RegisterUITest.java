package sg.schoolmatch.boundary.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AccountController;
import sg.schoolmatch.control.AccountTestCases;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Web test of the boundary class RegisterUI (DM-03 RegistrationForm; use case Register, FR-REG-01..05,
 * NFR-SEC-04). AccountController is a mock: the format and duplicate rules are tested in AccountControllerTest;
 * this test covers the page, the confirmation check (FR-REG-03, the TC-REGISTER.csv rows for it) and the redirect.
 */
@WebMvcTest(RegisterUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import(SessionCookie.class)
@ActiveProfiles("test")
class RegisterUITest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccountController accountController;

    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    @BeforeEach
    void setUp() {
        // The mock's only format rule: a 2-character username is invalid (enough for TC-REG-03-03).
        when(accountController.validateRegistration(any(), any(), any())).thenAnswer(call -> {
            Map<String, String> errors = new HashMap<>();
            if ("ab".equals(call.getArgument(0))) {
                errors.put("username", AccountController.USERNAME_MESSAGE);
            }
            return errors;
        });
    }

    static List<AccountTestCases.Row> confirmRows() {
        return AccountTestCases.rows("TC-REGISTER.csv", row -> row.requirement().equals("FR-REG-03"));
    }

    @Test
    @Tag("FR-REG-01")
    @Tag("NFR-SEC-04")
    @DisplayName("TC-REG-01-02: the form shows the four fields, masked passwords and the password rules")
    void displayRegistrationForm() throws Exception {
        mvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(containsString("type=\"password\" name=\"password\"")))
                .andExpect(content().string(containsString("type=\"password\" name=\"confirm\"")))
                .andExpect(content().string(containsString("an upper-case letter, a lower-case letter and a digit")))
                .andExpect(content().string(not(containsString("Not built yet"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("confirmRows")
    @Tag("FR-REG-03")
    @DisplayName("TC-REG-03-xx: confirmation cases from TC-REGISTER.csv; no account is created")
    void submitRegistration_confirmation(AccountTestCases.Row row) throws Exception {
        MvcResult result = mvc.perform(register(row.registration()))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) result.getModelAndView().getModel()
                .get(PageMessages.FIELD_ERRORS);
        assertThat(errors.keySet()).containsExactlyInAnyOrderElementsOf(row.errorFields());
        verify(accountController, never()).register(anyString(), anyString(), anyString());
    }

    @Test
    @Tag("FR-REG-03")
    @DisplayName("TC-REG-03-04: a mismatch shows \"The passwords do not match\" next to the confirmation field")
    void submitRegistration_mismatchMessage() throws Exception {
        Map<String, String> form = new HashMap<>(AccountTestCases.VALID_REGISTRATION);
        form.put("confirm", "Other1234");

        mvc.perform(register(form))
                .andExpect(model().attribute(PageMessages.FIELD_ERRORS, Map.of("confirm", "The passwords do not match")))
                .andExpect(content().string(containsString("The passwords do not match")))
                .andExpect(content().string(containsString("is-invalid")));
    }

    @Test
    @Tag("FR-REG-05")
    @DisplayName("TC-REG-05-03: success creates the account and goes to the login page with \"Account created. Please log in.\"")
    void submitRegistration_success() throws Exception {
        Map<String, String> form = new HashMap<>(AccountTestCases.VALID_REGISTRATION);
        form.put("confirm", form.get("password"));

        mvc.perform(register(form))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, "Account created. Please log in."))
                .andExpect(flash().attribute("identifier", "alice_01"));
        verify(accountController).register("alice_01", "alice@example.com", "Passw0rd");
    }

    @Test
    @Tag("FR-REG-04")
    @Tag("NFR-SEC-04")
    @DisplayName("TC-REG-04-07: a duplicate is shown on the form; the typed username stays, the passwords are not sent back")
    void submitRegistration_duplicate_keepsInputButNotPasswords() throws Exception {
        when(accountController.register(anyString(), anyString(), anyString()))
                .thenThrow(new InvalidInputException("username", AccountController.USERNAME_TAKEN_MESSAGE));
        Map<String, String> form = new HashMap<>(AccountTestCases.VALID_REGISTRATION);
        form.put("password", "Secret-Passw0rd");
        form.put("confirm", "Secret-Passw0rd");

        mvc.perform(register(form))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(content().string(containsString(AccountController.USERNAME_TAKEN_MESSAGE)))
                .andExpect(content().string(containsString("value=\"alice_01\"")))
                .andExpect(content().string(not(containsString("Secret-Passw0rd"))));
    }

    @Test
    @Tag("FR-REG-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-REG-01-03: the database being down shows \"cannot be created at this time\" (EX-1)")
    void submitRegistration_serviceUnavailable() throws Exception {
        when(accountController.register(anyString(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("database down"));
        Map<String, String> form = new HashMap<>(AccountTestCases.VALID_REGISTRATION);
        form.put("confirm", form.get("password"));

        mvc.perform(register(form))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("register"))
                .andExpect(content().string(containsString("Your account cannot be created at this time")));
    }

    private static MockHttpServletRequestBuilder register(Map<String, String> form) {
        MockHttpServletRequestBuilder request = post("/register");
        form.forEach(request::param);
        return request;
    }
}

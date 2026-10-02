package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.persistence.AccountRepository;
import sg.schoolmatch.persistence.ShortlistRepository;
import sg.schoolmatch.persistence.UserProfileRepository;

/**
 * Flow test of the account use cases (Register → Log In → Manage Profile → Log Out, and a guest's
 * add-to-shortlist that finishes after login, DC-08). The whole application with the {@code test} profile:
 * in-memory H2, stub OneMap (it knows postal code 579767), fixture snapshot. Each test uses its own usernames,
 * because the database is shared with the other flow tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountFlowTest {

    private static final AtomicInteger NEXT_USER = new AtomicInteger();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppProperties props;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserProfileRepository profileRepository;

    @Autowired
    private ShortlistRepository shortlistRepository;

    @Autowired
    private SchoolDataController schoolDataController;

    @Test
    @Tag("FR-REG-05")
    @Tag("FR-LOGIN-04")
    @Tag("FR-PROFILE-01")
    @Tag("FR-LOGOUT-02")
    @DisplayName("TC-AccountFlow-01: register → log in → save profile → log out; afterwards the cookie no longer works")
    void registerLoginProfileLogout() throws Exception {
        String username = newUsername();

        // Register: no session is created, the login page says so (FR-REG-05)
        MvcResult registered = mvc.perform(post("/register").param("username", username)
                        .param("email", username + "@Example.com").param("password", "Passw0rd")
                        .param("confirm", "Passw0rd"))
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, "Account created. Please log in."))
                .andReturn();
        assertThat(registered.getResponse().getCookie(cookieName())).isNull();
        Account account = accountRepository.findByUsernameIgnoreCase(username).orElseThrow();
        assertThat(account.getEmail()).isEqualTo(username.toLowerCase() + "@example.com");
        assertThat(shortlistRepository.findById(account.getAccountId())).as("empty shortlist created").isPresent();

        // Log in with the email in another case
        Cookie session = logIn(username.toUpperCase() + "@EXAMPLE.COM", "Passw0rd");
        mvc.perform(get("/profile").cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hi, " + username)));

        // Save the profile (no address: that part is in TC-AccountFlow-02)
        mvc.perform(post("/profile").cookie(session).param("displayName", "Flow Tester").param("psleScore", "12")
                        .param("postingGroup", "3").param("travelMode", "TRANSIT").param("maxCommuteMin", "45"))
                .andExpect(redirectedUrl("/profile"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, "Profile saved."));
        UserProfile profile = profileRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(profile.getPsleScore()).isEqualTo(12);
        assertThat(profile.getTravelMode()).isEqualTo(TravelMode.TRANSIT);
        mvc.perform(get("/profile").cookie(session))
                .andExpect(content().string(containsString("Hi, Flow Tester")))
                .andExpect(content().string(containsString("value=\"12\"")));

        // A second save updates the same row; a CCA from the fixture dataset is accepted
        String cca = schoolDataController.getSchools().stream().flatMap(s -> s.getCcas().stream())
                .findFirst().orElseThrow();
        mvc.perform(post("/profile").cookie(session).param("displayName", "Flow Tester").param("psleScore", "14")
                        .param("postingGroup", "3").param("preferredCCAs", cca))
                .andExpect(redirectedUrl("/profile"));
        profile = profileRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(profile.getPsleScore()).isEqualTo(14);
        assertThat(profile.getPreferredCCAs()).containsExactly(cca);
        assertThat(profile.getTravelMode()).as("not sent = cleared").isNull();

        // Log out: home page with the message, the cookie is cleared and the old session id no longer works
        mvc.perform(post("/logout").cookie(session))
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, "You have logged out."));
        mvc.perform(get("/profile").cookie(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?next=/profile"));
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-AccountFlow-02: a home postal code is looked up (stub OneMap) and saved as the home location")
    void profileHomeAddress() throws Exception {
        String username = newUsername();
        Cookie session = registerAndLogIn(username);

        mvc.perform(post("/profile").cookie(session).param("homeAddress", "579767"))
                .andExpect(redirectedUrl("/profile"));

        Account account = accountRepository.findByUsernameIgnoreCase(username).orElseThrow();
        UserProfile profile = profileRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(profile.getHomeLocation()).isNotNull();
        assertThat(profile.getHomeLocation().isWithinSingapore()).isTrue();

        mvc.perform(post("/profile").cookie(session).param("homeAddress", "no such place 123"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Address not found in Singapore")));
        assertThat(profileRepository.findById(account.getAccountId()).orElseThrow().getHomeLocation())
                .as("previous location kept").isEqualTo(profile.getHomeLocation());
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-AccountFlow-08: an address with two matches shows a choice list; the chosen one is saved with the other edits")
    void profileHomeAddress_choiceList() throws Exception {
        String username = newUsername();
        Cookie session = registerAndLogIn(username);
        MockHttpSession httpSession = new MockHttpSession();

        // stub OneMap has two hits for "catholic high school" (the school and a tuition centre in the same building)
        mvc.perform(post("/profile").cookie(session).session(httpSession)
                        .param("displayName", "Chooser").param("homeAddress", "Catholic High School"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Which address is yours?")))
                .andExpect(content().string(containsString("name=\"homeChoice\" value=\"1\"")))
                .andExpect(content().string(containsString("value=\"Chooser\"")));
        Account account = accountRepository.findByUsernameIgnoreCase(username).orElseThrow();
        assertThat(profileRepository.findById(account.getAccountId())).as("nothing saved yet").isEmpty();

        mvc.perform(post("/profile").cookie(session).session(httpSession).param("displayName", "Chooser")
                        .param("homeAddress", "Catholic High School").param("homeChoice", "1"))
                .andExpect(redirectedUrl("/profile"));

        UserProfile profile = profileRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(profile.getDisplayName()).isEqualTo("Chooser");
        assertThat(profile.getHomeLocation().getLatitude()).isCloseTo(1.354388, within(1e-5));
        assertThat(profile.getHomeAddress()).isNotEqualTo("Catholic High School");   // the chosen address text
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AccountFlow-03: a guest's add-to-shortlist goes to login and is finished after login (DC-08)")
    void guestAddToShortlist_finishedAfterLogin() throws Exception {
        String username = newUsername();
        register(username);
        String code = schoolDataController.getSchools().getFirst().getSchoolCode();

        mvc.perform(post("/schools/" + code + "/shortlist"))
                .andExpect(redirectedUrl("/login?next=/schools/" + code + "&add=" + code));
        mvc.perform(get("/login").param("next", "/schools/" + code).param("add", code))
                .andExpect(content().string(containsString("name=\"add\" value=\"" + code + "\"")));

        mvc.perform(post("/login").param("identifier", username).param("password", "Passw0rd")
                        .param("next", "/schools/" + code).param("add", code))
                .andExpect(redirectedUrl("/schools/" + code))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, "Added to shortlist"));

        Account account = accountRepository.findByUsernameIgnoreCase(username).orElseThrow();
        assertThat(shortlistRepository.findById(account.getAccountId()).orElseThrow().getSchoolCodes())
                .containsExactly(code);
    }

    @Test
    @Tag("FR-REG-04")
    @DisplayName("TC-AccountFlow-04: registering the same username twice (another case) keeps one account and shows AF-2")
    void registerTwice_secondIsDuplicate() throws Exception {
        String username = newUsername();
        register(username);

        mvc.perform(post("/register").param("username", username.toUpperCase())
                        .param("email", "other-" + username + "@example.com").param("password", "Passw0rd")
                        .param("confirm", "Passw0rd"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("An account with this username already exists")));
        assertThat(accountRepository.findAll()).filteredOn(a -> a.getUsername().equalsIgnoreCase(username)).hasSize(1);
    }

    @Test
    @Tag("FR-LOGIN-05")
    @DisplayName("TC-AccountFlow-05: a wrong password shows the generic message on the login page and sets no cookie")
    void wrongPassword_genericMessage() throws Exception {
        String username = newUsername();
        register(username);

        MvcResult failed = mvc.perform(post("/login").param("identifier", username).param("password", "Wrong-pass1"))
                .andExpect(redirectedUrl("/login"))
                .andReturn();
        assertThat(failed.getResponse().getCookie(cookieName())).isNull();

        mvc.perform(get("/login").flashAttrs(failed.getFlashMap()))
                .andExpect(content().string(containsString("Incorrect username/email or password")))
                .andExpect(content().string(containsString("value=\"" + username + "\"")))
                .andExpect(content().string(not(containsString("Wrong-pass1"))));
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AccountFlow-06: a guest sent to login from a protected page returns to it after logging in")
    void loginReturnsToNext() throws Exception {
        String username = newUsername();
        register(username);

        mvc.perform(get("/profile")).andExpect(redirectedUrl("/login?next=/profile"));
        mvc.perform(post("/login").param("identifier", username).param("password", "Passw0rd")
                        .param("next", "/profile"))
                .andExpect(redirectedUrl("/profile"));
    }

    @Test
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-AccountFlow-07: the navbar shows Profile and Log out (not Log in) while logged in")
    void navbarWhileLoggedIn() throws Exception {
        Cookie session = registerAndLogIn(newUsername());

        mvc.perform(get("/").cookie(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("loggedIn", true))
                .andExpect(content().string(containsString("href=\"/logout\"")))
                .andExpect(content().string(not(containsString("href=\"/login\""))));
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static String newUsername() {
        return "flow_" + System.nanoTime() % 100_000 + "_" + NEXT_USER.incrementAndGet();
    }

    private String cookieName() {
        return props.session().cookieName();
    }

    private void register(String username) throws Exception {
        mvc.perform(post("/register").param("username", username).param("email", username + "@example.com")
                        .param("password", "Passw0rd").param("confirm", "Passw0rd"))
                .andExpect(redirectedUrl("/login"));
    }

    private Cookie logIn(String identifier, String password) throws Exception {
        MvcResult result = mvc.perform(post("/login").param("identifier", identifier).param("password", password))
                .andExpect(redirectedUrl("/"))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie(cookieName());
        assertThat(cookie).as("session cookie").isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        return new Cookie(cookie.getName(), cookie.getValue());
    }

    private Cookie registerAndLogIn(String username) throws Exception {
        register(username);
        return logIn(username, "Passw0rd");
    }
}

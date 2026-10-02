package sg.schoolmatch.boundary.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.FilterController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.search.AttributeCategory;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Web test of the boundary class UserProfileUI (DM-05 UserProfile, DM-06 LogoutConfirmation; use cases
 * Manage Profile and Log Out; FR-PROFILE-01, FR-LOGOUT-01..04). The member is logged in with cookie
 * {@code SM_SESSION=s1}; ProfileController, AuthController and FilterController are mocks.
 */
@WebMvcTest(UserProfileUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import(SessionCookie.class)
@ActiveProfiles("test")
class UserProfileUITest {

    private static final Cookie LOGGED_IN = new Cookie("SM_SESSION", "s1");
    private static final Coordinate BISHAN = new Coordinate(1.3510, 103.8484);
    private static final Coordinate TAMPINES = new Coordinate(1.3543, 103.9453);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProfileController profileController;

    @MockitoBean
    private FilterController filterController;

    @MockitoBean
    private AuthController authController;

    @MockitoBean
    private SchoolDataController schoolDataController;

    private final Account alice = new Account("alice_01", "alice@example.com", "hash", Instant.EPOCH);
    private UserProfile saved;

    @BeforeEach
    void setUp() {
        when(authController.verifySession("s1")).thenReturn(true);
        saved = new UserProfile(alice);
        saved.setDisplayName("Alice Tan");
        saved.setPsleScore(12);
        saved.setPostingGroup(3);
        saved.setHomeAddress("579767");
        saved.setHomeLocation(BISHAN);
        saved.setPreferredCCAs(Set.of("BADMINTON"));
        saved.setTravelMode(TravelMode.TRANSIT);
        saved.setMaxCommuteMin(45);
        when(profileController.getProfile("s1")).thenAnswer(call -> copyOf(saved));
        when(filterController.getFilterOptions(AttributeCategory.CCA))
                .thenReturn(new TreeSet<>(Set.of("BADMINTON", "CHOIR")));
        when(filterController.getFilterOptions(AttributeCategory.PROGRAMME))
                .thenReturn(new TreeSet<>(Set.of("Applied Learning Programme")));
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-PROFILE-01-20: the profile page shows the saved values and links to shortlist, plan and recommendations")
    void displayUserProfile_showsSavedProfile() throws Exception {
        mvc.perform(get("/profile").cookie(LOGGED_IN))
                .andExpect(status().isOk())
                .andExpect(view().name("profile"))
                .andExpect(content().string(containsString("value=\"Alice Tan\"")))
                .andExpect(content().string(containsString("value=\"12\"")))
                .andExpect(content().string(containsString("value=\"579767\"")))
                .andExpect(content().string(containsString("href=\"/shortlist\"")))
                .andExpect(content().string(containsString("href=\"/plan\"")))
                .andExpect(content().string(containsString("href=\"/recommendations\"")))
                .andExpect(content().string(containsString("Public transport")))
                .andExpect(content().string(not(containsString("Not built yet"))));
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-PROFILE-01-21: the navbar greets the member by display name")
    void navbar_greetsMember() throws Exception {
        mvc.perform(get("/profile").cookie(LOGGED_IN))
                .andExpect(content().string(containsString("Hi, Alice Tan")));
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-PROFILE-01-22: saving passes the edited values and the session id to saveProfile, then shows \"Profile saved.\"")
    void submitProfile_valid_saves() throws Exception {
        mvc.perform(post("/profile").cookie(LOGGED_IN)
                        .param("displayName", "Ally")
                        .param("psleScore", "14")
                        .param("postingGroup", "2")
                        .param("primarySchool", "Ai Tong School")
                        .param("homeAddress", "579767")
                        .param("preferredCCAs", "CHOIR")
                        .param("maxCommuteMin", "30")
                        .param("travelMode", "WALK"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/profile"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, "Profile saved."));

        UserProfile edited = savedArgument();
        assertThat(edited.getDisplayName()).isEqualTo("Ally");
        assertThat(edited.getPsleScore()).isEqualTo(14);
        assertThat(edited.getPostingGroup()).isEqualTo(2);
        assertThat(edited.getPrimarySchool()).isEqualTo("Ai Tong School");
        assertThat(edited.getHomeAddress()).isEqualTo("579767");
        assertThat(edited.getHomeLocation()).as("the control decides the location").isNull();
        assertThat(edited.getPreferredCCAs()).containsExactly("CHOIR");
        assertThat(edited.getPreferredProgrammes()).isEmpty();
        assertThat(edited.getMaxCommuteMin()).isEqualTo(30);
        assertThat(edited.getTravelMode()).isEqualTo(TravelMode.WALK);
    }

    @Test
    @Tag("FR-PROFILE-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-PROFILE-01-23: a PSLE score that is not a number is a field error and nothing is saved")
    void submitProfile_psleNotANumber() throws Exception {
        mvc.perform(post("/profile").cookie(LOGGED_IN).param("psleScore", "abc").param("displayName", "Ally"))
                .andExpect(status().isOk())
                .andExpect(view().name("profile"))
                .andExpect(model().attributeExists(PageMessages.FIELD_ERRORS))
                .andExpect(content().string(containsString("Enter a whole number from 4 to 32")))
                .andExpect(content().string(containsString("value=\"Ally\"")));
        verify(profileController, never()).saveProfile(anyString(), any());
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-PROFILE-01-24: a CCA that is not in the dataset is rejected (data dictionary: values from the active dataset)")
    void submitProfile_unknownCca() throws Exception {
        mvc.perform(post("/profile").cookie(LOGGED_IN).param("preferredCCAs", "QUIDDITCH"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Choose CCAs from the list")));
        verify(profileController, never()).saveProfile(anyString(), any());
    }

    @Test
    @Tag("FR-PROFILE-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-PROFILE-01-31: form errors and the control's rule errors are shown together in one answer (AF-2)")
    void submitProfile_allErrorsAtOnce() throws Exception {
        when(profileController.validateProfile(any())).thenReturn(java.util.Map.of(
                "postingGroup", "Choose posting group 1, 2 or 3",
                "maxCommuteMin", "Choose 15, 30, 45 or 60 minutes"));

        mvc.perform(post("/profile").cookie(LOGGED_IN).param("preferredCCAs", "QUIDDITCH")
                        .param("postingGroup", "4").param("maxCommuteMin", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Choose CCAs from the list")))
                .andExpect(content().string(containsString("Choose posting group 1, 2 or 3")))
                .andExpect(content().string(containsString("Choose 15, 30, 45 or 60 minutes")));
        verify(profileController, never()).saveProfile(anyString(), any());
    }

    @Test
    @Tag("FR-PROFILE-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-PROFILE-01-25: errors from the control are shown and the typed values stay in the form (AF-2)")
    void submitProfile_invalid_showsErrors() throws Exception {
        doThrow(new InvalidInputException("maxCommuteMin", "Choose 15, 30, 45 or 60 minutes"))
                .when(profileController).saveProfile(eq("s1"), any());

        mvc.perform(post("/profile").cookie(LOGGED_IN).param("displayName", "Ally").param("maxCommuteMin", "20"))
                .andExpect(status().isOk())
                .andExpect(view().name("profile"))
                .andExpect(content().string(containsString("Choose 15, 30, 45 or 60 minutes")))
                .andExpect(content().string(containsString("value=\"Ally\"")));
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-PROFILE-01-26: several address matches show a choice list; choosing one saves its location with the other edits")
    void submitProfile_severalAddresses_thenChoose() throws Exception {
        List<ReferenceLocation> candidates = List.of(
                new ReferenceLocation(BISHAN, LocationSource.MANUAL_ENTRY, "9 BISHAN STREET 22"),
                new ReferenceLocation(TAMPINES, LocationSource.MANUAL_ENTRY, "1 TAMPINES STREET 11"));
        doThrow(new ProfileController.AmbiguousAddressException(
                Map.of("homeAddress", ProfileController.CHOOSE_ADDRESS_MESSAGE), candidates))
                .when(profileController).saveProfile(eq("s1"), any());
        MockHttpSession httpSession = new MockHttpSession();

        mvc.perform(post("/profile").cookie(LOGGED_IN).session(httpSession)
                        .param("displayName", "Ally").param("homeAddress", "street"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("9 BISHAN STREET 22")))
                .andExpect(content().string(containsString("1 TAMPINES STREET 11")))
                .andExpect(content().string(containsString("name=\"homeChoice\"")))
                .andExpect(content().string(containsString("value=\"Ally\"")));

        reset(profileController);
        when(profileController.getProfile("s1")).thenAnswer(call -> copyOf(saved));
        mvc.perform(post("/profile").cookie(LOGGED_IN).session(httpSession)
                        .param("displayName", "Ally").param("homeAddress", "street").param("homeChoice", "1"))
                .andExpect(redirectedUrl("/profile"));

        UserProfile edited = savedArgument();
        assertThat(edited.getDisplayName()).isEqualTo("Ally");
        assertThat(edited.getHomeAddress()).isEqualTo("1 TAMPINES STREET 11");
        assertThat(edited.getHomeLocation()).isEqualTo(TAMPINES);
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-PROFILE-01-27: a choice for a different address than the one searched is ignored")
    void submitProfile_staleChoiceIgnored() throws Exception {
        MockHttpSession httpSession = new MockHttpSession();
        httpSession.setAttribute(UserProfileUI.HOME_CANDIDATES, new UserProfileUI.HomeCandidates("street",
                List.of(new ReferenceLocation(BISHAN, LocationSource.MANUAL_ENTRY, "9 BISHAN STREET 22"))));

        mvc.perform(post("/profile").cookie(LOGGED_IN).session(httpSession)
                        .param("homeAddress", "tampines").param("homeChoice", "0"))
                .andExpect(redirectedUrl("/profile"));

        UserProfile edited = savedArgument();
        assertThat(edited.getHomeAddress()).isEqualTo("tampines");
        assertThat(edited.getHomeLocation()).isNull();
    }

    @Test
    @Tag("FR-PROFILE-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-PROFILE-01-28: a save failure shows a message and keeps the typed values (EX-2)")
    void submitProfile_saveFails() throws Exception {
        doThrow(new DataAccessResourceFailureException("disk full"))
                .when(profileController).saveProfile(eq("s1"), any());

        mvc.perform(post("/profile").cookie(LOGGED_IN).param("displayName", "Ally"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("Your profile could not be saved")))
                .andExpect(content().string(containsString("value=\"Ally\"")));
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-PROFILE-01-29: Cancel discards the edits by reloading the saved profile (AF-1)")
    void profilePage_cancelLink() throws Exception {
        mvc.perform(get("/profile").cookie(LOGGED_IN))
                .andExpect(content().string(containsString("href=\"/profile\"")))
                .andExpect(content().string(containsString(">Cancel<")));
    }

    @Test
    @Tag("FR-LOGOUT-01")
    @Tag("FR-LOGOUT-04")
    @DisplayName("TC-LOGOUT-01-01: GET /logout asks for confirmation; Cancel goes back to the profile")
    void selectLogOut_confirmPage() throws Exception {
        mvc.perform(get("/logout").cookie(LOGGED_IN))
                .andExpect(status().isOk())
                .andExpect(view().name("logout-confirm"))
                .andExpect(content().string(containsString("Log out?")))
                .andExpect(content().string(containsString("href=\"/profile\"")))
                .andExpect(content().string(not(containsString("Not built yet"))));
        verify(authController, never()).logout(anyString());
    }

    @Test
    @Tag("FR-LOGOUT-02")
    @Tag("FR-LOGOUT-03")
    @DisplayName("TC-LOGOUT-02-01: confirming ends the session, clears the cookie and goes home with \"You have logged out.\"")
    void promptLogoutConfirmation_logsOut() throws Exception {
        mvc.perform(post("/logout").cookie(LOGGED_IN))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(cookie().maxAge("SM_SESSION", 0))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, "You have logged out."));
        verify(authController).logout("s1");
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-PROFILE-01-30: a guest cannot open or post the profile; nothing reaches ProfileController")
    void guest_redirectedToLogin() throws Exception {
        mvc.perform(get("/profile")).andExpect(redirectedUrl("/login?next=/profile"));
        mvc.perform(post("/profile").param("displayName", "x")).andExpect(status().is3xxRedirection());
        verify(profileController, never()).saveProfile(any(), any());
    }

    private UserProfile savedArgument() {
        ArgumentCaptor<UserProfile> edited = ArgumentCaptor.forClass(UserProfile.class);
        verify(profileController).saveProfile(eq("s1"), edited.capture());
        return edited.getValue();
    }

    /** A fresh copy each call, as a new request would load it. */
    private UserProfile copyOf(UserProfile source) {
        UserProfile copy = new UserProfile(source.getAccount());
        copy.setDisplayName(source.getDisplayName());
        copy.setPsleScore(source.getPsleScore());
        copy.setPostingGroup(source.getPostingGroup());
        copy.setPrimarySchool(source.getPrimarySchool());
        copy.setHomeAddress(source.getHomeAddress());
        copy.setHomeLocation(source.getHomeLocation());
        copy.setPreferredCCAs(source.getPreferredCCAs());
        copy.setPreferredProgrammes(source.getPreferredProgrammes());
        copy.setMaxCommuteMin(source.getMaxCommuteMin());
        copy.setTravelMode(source.getTravelMode());
        return copy;
    }
}

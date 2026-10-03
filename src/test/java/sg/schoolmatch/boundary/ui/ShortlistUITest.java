package sg.schoolmatch.boundary.ui;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.control.ShortlistController;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.shortlist.Shortlist;
import sg.schoolmatch.error.NotFoundException;
import sg.schoolmatch.support.FixedClock;
import sg.schoolmatch.support.TestSchools;

/**
 * Web test of ShortlistUI (DM-16 Shortlist; use cases Add School to Shortlist, Remove School from Shortlist,
 * View Shortlisted Schools). The control is a mock; a logged-in member is simulated with a session cookie
 * that the mocked AuthController accepts.
 */
@WebMvcTest(ShortlistUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import(SessionCookie.class)
@ActiveProfiles("test")
class ShortlistUITest {

    private static final String SESSION = "session-alice";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppProperties props;

    @MockitoBean
    private ShortlistController shortlistController;

    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    private final Account alice = new Account("alice", "alice@example.com", "hash", FixedClock.DEFAULT_INSTANT);
    private final School catholic = TestSchools.school("catholic-high-school")
            .name("CATHOLIC HIGH SCHOOL").planningArea("BISHAN").build();
    private final School tampines = TestSchools.school("tampines-secondary-school")
            .name("TAMPINES SECONDARY SCHOOL").planningArea("TAMPINES").build();

    @BeforeEach
    void logIn() {
        when(authController.verifySession(SESSION)).thenReturn(true);
    }

    @Test
    @Tag("FR-SHORTLIST-04")
    @Tag("FR-SHORTLIST-05")
    @DisplayName("TC-ShortlistUI-01: the shortlist shows a summary card per school with Remove and a Compare checkbox")
    void displayShortlist_showsCards() throws Exception {
        when(shortlistController.getShortlist(SESSION)).thenReturn(shortlistOf(catholic, tampines));

        mvc.perform(get("/shortlist").cookie(member()))
                .andExpect(status().isOk())
                .andExpect(view().name("shortlist"))
                .andExpect(model().attribute("schools", List.of(catholic, tampines)))
                .andExpect(content().string(containsString("CATHOLIC HIGH SCHOOL")))
                .andExpect(content().string(containsString("href=\"/schools/tampines-secondary-school\"")))
                .andExpect(content().string(containsString("action=\"/shortlist/catholic-high-school/remove\"")))
                .andExpect(content().string(containsString("return confirm(")))
                .andExpect(content().string(containsString("name=\"codes\" value=\"tampines-secondary-school\"")))
                .andExpect(content().string(containsString("action=\"/compare\"")))
                .andExpect(content().string(containsString("href=\"/plan\"")))
                .andExpect(content().string(not(containsString("Not built yet"))));
    }

    @Test
    @Tag("FR-SHORTLIST-07")
    @DisplayName("TC-ShortlistUI-02: an empty shortlist says so and links to the search (AF-1)")
    void displayShortlist_empty() throws Exception {
        when(shortlistController.getShortlist(SESSION)).thenReturn(shortlistOf());

        mvc.perform(get("/shortlist").cookie(member()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You have not shortlisted any schools yet")))
                .andExpect(content().string(containsString("href=\"/schools\"")))
                .andExpect(content().string(not(containsString("action=\"/compare\""))));
    }

    @Test
    @Tag("FR-SHORTLIST-05")
    @DisplayName("TC-ShortlistUI-03: a shortlisted code that left the dataset is listed with a Remove button")
    void displayShortlist_schoolLeftDataset() throws Exception {
        Shortlist shortlist = shortlistOf(catholic, TestSchools.named("closed-secondary-school", "CLOSED"));
        shortlist.resolveSchools(Map.of("catholic-high-school", catholic));
        when(shortlistController.getShortlist(SESSION)).thenReturn(shortlist);

        mvc.perform(get("/shortlist").cookie(member()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("missingCodes", List.of("closed-secondary-school")))
                .andExpect(content().string(containsString("School no longer in the dataset")))
                .andExpect(content().string(containsString("action=\"/shortlist/closed-secondary-school/remove\"")));
    }

    @Test
    @Tag("FR-SHORTLIST-05")
    @Tag("FR-DATA-05")
    @DisplayName("TC-ShortlistUI-11: a school that left the dataset is shown with its last-known name and its code (open decision 3)")
    void displayShortlist_schoolLeftDatasetShowsLastKnownName() throws Exception {
        Shortlist shortlist = shortlistOf(catholic, TestSchools.named("closed-secondary-school", "CLOSED"));
        shortlist.resolveSchools(Map.of("catholic-high-school", catholic));
        when(shortlistController.getShortlist(SESSION)).thenReturn(shortlist);
        when(shortlistController.getLastKnownNames(List.of("closed-secondary-school")))
                .thenReturn(Map.of("closed-secondary-school", "CLOSED SECONDARY SCHOOL"));

        mvc.perform(get("/shortlist").cookie(member()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("missingNames", Map.of("closed-secondary-school", "CLOSED SECONDARY SCHOOL")))
                .andExpect(content().string(containsString("<strong>CLOSED SECONDARY SCHOOL</strong>")))
                .andExpect(content().string(containsString("<code>closed-secondary-school</code>")))
                .andExpect(content().string(containsString("School no longer in the dataset")))
                .andExpect(content().string(containsString("action=\"/shortlist/closed-secondary-school/remove\"")));
    }

    @Test
    @Tag("FR-SHORTLIST-04")
    @Tag("NFR-USE-03")
    @DisplayName("TC-ShortlistUI-04: a database failure shows \"temporarily unavailable\" instead of an error page")
    void displayShortlist_serviceUnavailable() throws Exception {
        when(shortlistController.getShortlist(SESSION)).thenThrow(new DataAccessResourceFailureException("db down"));

        mvc.perform(get("/shortlist").cookie(member()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ShortlistUI.UNAVAILABLE_MESSAGE)))
                .andExpect(content().string(not(containsString("You have not shortlisted any schools yet"))));
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-ShortlistUI-05: Add to shortlist adds the school and returns to its details page")
    void selectAddToShortlist_added() throws Exception {
        when(shortlistController.addSchool(SESSION, "catholic-high-school")).thenReturn(true);

        mvc.perform(post("/schools/catholic-high-school/shortlist").cookie(member()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schools/catholic-high-school"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, ShortlistUI.ADDED_MESSAGE));
        verify(shortlistController).addSchool(SESSION, "catholic-high-school");
    }

    @Test
    @Tag("FR-SHORTLIST-02")
    @DisplayName("TC-ShortlistUI-06: adding a school twice says it is already in the shortlist (displayAlreadyShortlisted)")
    void selectAddToShortlist_alreadyShortlisted() throws Exception {
        when(shortlistController.addSchool(SESSION, "catholic-high-school")).thenReturn(false);

        mvc.perform(post("/schools/catholic-high-school/shortlist").cookie(member()))
                .andExpect(redirectedUrl("/schools/catholic-high-school"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, ShortlistUI.ALREADY_MESSAGE));
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-ShortlistUI-07: adding an unknown school code answers 404")
    void selectAddToShortlist_unknownSchool() throws Exception {
        when(shortlistController.addSchool(SESSION, "no-such-school")).thenThrow(new NotFoundException("No school"));

        mvc.perform(post("/schools/no-such-school/shortlist").cookie(member()))
                .andExpect(status().isNotFound());
    }

    @Test
    @Tag("FR-SHORTLIST-06")
    @DisplayName("TC-ShortlistUI-08: Remove removes the school and returns to the shortlist with \"Removed\"")
    void selectRemoveSchool() throws Exception {
        mvc.perform(post("/shortlist/catholic-high-school/remove").cookie(member()))
                .andExpect(redirectedUrl("/shortlist"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, ShortlistUI.REMOVED_MESSAGE));
        verify(shortlistController).removeSchool(SESSION, "catholic-high-school");
    }

    @Test
    @Tag("FR-SHORTLIST-06")
    @Tag("NFR-USE-03")
    @DisplayName("TC-ShortlistUI-09: a failed save while removing keeps the shortlist and shows an error")
    void selectRemoveSchool_saveFails() throws Exception {
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(shortlistController).removeSchool(SESSION, "catholic-high-school");

        mvc.perform(post("/shortlist/catholic-high-school/remove").cookie(member()))
                .andExpect(redirectedUrl("/shortlist"))
                .andExpect(flash().attribute(PageMessages.FLASH_ERROR, ShortlistUI.SAVE_FAILED_MESSAGE));
    }

    @Test
    @Tag("NFR-SEC-05")
    @Tag("FR-SHORTLIST-03")
    @DisplayName("TC-ShortlistUI-10: a guest never reaches the shortlist control")
    void guest_isSentToLogin() throws Exception {
        mvc.perform(get("/shortlist"))
                .andExpect(redirectedUrl("/login?next=/shortlist"));
        mvc.perform(post("/shortlist/catholic-high-school/remove"))
                .andExpect(redirectedUrl("/login?next=/shortlist"));
        mvc.perform(post("/schools/catholic-high-school/shortlist"))
                .andExpect(redirectedUrl("/login?next=/schools/catholic-high-school&add=catholic-high-school"));
        verifyNoInteractions(shortlistController);
    }

    private Cookie member() {
        return new Cookie(props.session().cookieName(), SESSION);
    }

    private Shortlist shortlistOf(School... schools) {
        Shortlist shortlist = new Shortlist(alice);
        for (School school : schools) {
            shortlist.addSchool(school);
        }
        return shortlist;
    }
}

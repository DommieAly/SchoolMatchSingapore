package sg.schoolmatch.boundary.ui;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AccountController;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.FacilityController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.control.ShortlistController;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.support.TestMembers;

/**
 * Every GET route of {@code docs/routes.md} renders against the fixture snapshot and the offline stubs:
 * a public page answers 200 for a guest, a login-only page sends a guest to {@code /login?next=<page>}, and a
 * logged-in member (real register + login, {@link TestMembers}) gets 200 on every login-only page.
 * Replaces the skeleton's PlaceholderPagesTest; each page's own behaviour is tested in its {@code XxxUITest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PagesSmokeTest {

    /** Stands for a real school code from the fixture snapshot. */
    private static final String CODE = "{code}";
    /** Stands for the place id of a stub library near that school. */
    private static final String PLACE = "{place}";

    /** Stub OneMap answers this postal code with exactly one hit (Catholic High School). */
    private static final String ONE_HIT_ADDRESS = "579767";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppProperties props;

    @Autowired
    private SchoolDataController schoolDataController;

    @Autowired
    private FacilityController facilityController;

    @Autowired
    private AccountController accountController;

    @Autowired
    private AuthController authController;

    @Autowired
    private ShortlistController shortlistController;

    private School school;
    private String placeId;

    @BeforeEach
    void firstSchoolAndOneOfItsFacilities() {
        school = schoolDataController.getSchools().getFirst();
        placeId = facilityController.getNearbyFacilities(school).getFirst().getPlaceId();
    }

    /** Public pages: DM id (or "–" for a page that is not a dialog-map state), route. */
    static Stream<Arguments> publicPages() {
        return Stream.of(
                arguments("01", "/"),
                arguments("02", "/login"),
                arguments("03", "/register"),
                arguments("07", "/schools"),
                arguments("08", "/schools?q=secondary&sort=NAME_ASC&page=1"),
                arguments("08", "/schools?type=GOVERNMENT SCHOOL&psle=12&pg=3"),
                arguments("09", "/schools/" + CODE),
                arguments("10", "/schools/filter?psle=12&pg=3"),
                arguments("11", "/schools/map"),
                arguments("12", "/schools/" + CODE + "/facilities"),
                arguments("13", "/schools/" + CODE + "/facilities?type=LIBRARY&radiusKm=2"),
                arguments("14", "/facilities/" + PLACE + "?from=" + CODE),
                arguments("15", "/schools/" + CODE + "/facilities/map"),
                arguments("19", "/directions?to=school:" + CODE),
                arguments("19", "/directions?to=facility:" + PLACE + "&from=/schools/" + CODE + "/facilities"),
                arguments("21", "/directions?to=school:" + CODE + "&mode=TRANSIT"),
                arguments("–", "/about/data"));
    }

    /** Login-only pages: DM id, route. */
    static Stream<Arguments> loginOnlyPages() {
        return Stream.of(
                arguments("05", "/profile"),
                arguments("06", "/logout"),
                arguments("16", "/shortlist"),
                arguments("17", "/plan"),
                arguments("18", "/compare?codes=a,b"),
                arguments("22", "/recommendations"),
                arguments("24", "/recommendations/results/some-id"));
    }

    @ParameterizedTest(name = "DM-{0} GET {1} → 200 for a guest")
    @MethodSource("publicPages")
    @Tag("NFR-MAIN-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-PagesSmoke-01: every public page renders for a guest")
    void publicPageRenders(String dm, String route) throws Exception {
        mvc.perform(get(fill(route)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("Not built yet"))));
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-PagesSmoke-02: GET /api/districts answers planning-area GeoJSON")
    void districtsJson() throws Exception {
        mvc.perform(get("/api/districts"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/geo+json"))
                .andExpect(content().string(containsString("\"FeatureCollection\"")));
    }

    @Test
    @Tag("FR-SCHOOL-01")
    @Tag("FR-FACDETAIL-01")
    @DisplayName("TC-PagesSmoke-03: an unknown school or facility answers 404")
    void unknownSchoolOrFacilityIsNotFound() throws Exception {
        mvc.perform(get("/schools/no-such-school")).andExpect(status().isNotFound());
        mvc.perform(get("/facilities/no-such-place")).andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "DM-{0} GET {1} → /login?next={1}")
    @MethodSource("loginOnlyPages")
    @Tag("NFR-SEC-05")
    @Tag("FR-SHORTLIST-03")
    @DisplayName("TC-PagesSmoke-04: a guest opening a login-only page is sent to /login?next=<page>")
    void guestIsSentToLogin(String dm, String route) throws Exception {
        MvcResult result = mvc.perform(get(route))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertLoginRedirect(result, route);
    }

    @Test
    @Tag("NFR-SEC-05")
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-PagesSmoke-05: a guest adding a school to the shortlist (POST) goes to login with next and add")
    void guestAddToShortlistIsSentToLogin() throws Exception {
        MvcResult result = mvc.perform(post(fill("/schools/" + CODE + "/shortlist")))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        UriComponents target = redirectTarget(result);
        assertThat(target.getPath()).isEqualTo("/login");
        assertThat(target.getQueryParams().getFirst("add")).isEqualTo(school.getSchoolCode());
    }

    @Test
    @Tag("NFR-SEC-05")
    @Tag("FR-LOGOUT-03")
    @DisplayName("TC-PagesSmoke-06: an unknown session cookie counts as a guest: redirect to login, cookie cleared")
    void unknownSessionCookieIsTreatedAsGuest() throws Exception {
        String cookieName = props.session().cookieName();

        MvcResult result = mvc.perform(get("/shortlist").cookie(new Cookie(cookieName, "not-a-real-session")))
                .andExpect(status().is3xxRedirection())
                .andExpect(cookie().maxAge(cookieName, 0))
                .andReturn();

        assertLoginRedirect(result, "/shortlist");
    }

    @Test
    @Tag("FR-LOGOUT-03")
    @DisplayName("TC-PagesSmoke-07: the home page shows the flash message it is redirected with (\"You have logged out.\")")
    void homeShowsFlashMessage() throws Exception {
        mvc.perform(get("/").flashAttr(PageMessages.FLASH_MESSAGE, "You have logged out."))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You have logged out.")));
    }

    @Test
    @Tag("NFR-MAIN-01")
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-PagesSmoke-08: a logged-in member gets 200 on every login-only page and the member home block")
    void memberPagesRender() throws Exception {
        String sessionId = TestMembers.registerAndLogIn(accountController, authController, "smoke");
        Cookie login = TestMembers.cookie(props, sessionId);
        MockHttpSession pageSession = new MockHttpSession();   // JSESSIONID: start location, stored recommendations
        String other = schoolDataController.getSchools().get(1).getSchoolCode();
        shortlistController.addSchool(sessionId, school.getSchoolCode());
        shortlistController.addSchool(sessionId, other);

        mvc.perform(get("/").cookie(login))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome back")))
                .andExpect(content().string(not(containsString("Not built yet"))));
        for (String route : new String[] {"/profile", "/logout", "/shortlist", "/plan",
                "/compare?codes=" + school.getSchoolCode() + "," + other, "/recommendations",
                "/schools?psle=12&pg=3", "/schools/map?psle=12&pg=3"}) {
            mvc.perform(get(route).cookie(login))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(not(containsString("Not built yet"))));
        }

        // DM-24: a starting point in the page session, then POST /recommendations → GET /recommendations/results/{id}
        mvc.perform(post("/location").param("address", ONE_HIT_ADDRESS).param("returnTo", "/recommendations")
                        .session(pageSession).cookie(login))
                .andExpect(status().is3xxRedirection());
        MvcResult posted = mvc.perform(post("/recommendations").param("psleScore", "12").param("postingGroup", "3")
                        .param("travelMode", "TRANSIT").param("maxCommuteMin", "60")
                        .session(pageSession).cookie(login))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String resultsUrl = posted.getResponse().getRedirectedUrl();
        assertThat(resultsUrl).startsWith("/recommendations/results/");
        mvc.perform(get(resultsUrl).session(pageSession).cookie(login))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Refine criteria")));

        // DM-21 with a starting point: the route page
        mvc.perform(get(fill("/directions?to=school:" + CODE + "&mode=WALK")).session(pageSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("(stub)")));
    }

    private String fill(String route) {
        return route.replace(CODE, school.getSchoolCode()).replace(PLACE, placeId);
    }

    private static UriComponents redirectTarget(MvcResult result) {
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).as("redirect location").isNotNull();
        return UriComponentsBuilder.fromUriString(location).build();
    }

    /** The redirect goes to /login with {@code next} = the page the guest asked for (encoded or not). */
    private static void assertLoginRedirect(MvcResult result, String expectedNext) {
        UriComponents target = redirectTarget(result);
        String next = target.getQueryParams().getFirst("next");

        assertThat(target.getPath()).isEqualTo("/login");
        assertThat(next).as("next parameter").isNotNull();
        assertThat(URLDecoder.decode(next, UTF_8)).isEqualTo(expectedNext);
    }
}

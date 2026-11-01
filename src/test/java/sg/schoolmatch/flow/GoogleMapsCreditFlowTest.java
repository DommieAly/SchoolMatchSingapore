package sg.schoolmatch.flow;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import sg.schoolmatch.boundary.external.GoogleMapsPlatformInterface;
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AccountController;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.MapController;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.RouteStep;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.support.TestMembers;

/**
 * The Google Maps credit on the pages that show Google content without a Google map (NFR-ATTR-01, DC-88), and the
 * third-party data credits Google gives with a place (DC-93), with the whole application in live Google mode. No
 * request is sent: {@link GoogleMapsPlatformInterface} is a mock. {@link MapController} really decides there is no map
 * (the test profile has no browser key) unless a test says otherwise. Stub mode, where the content is made up and has
 * no credit, is in {@code FacilityFlowTest}.
 */
@SpringBootTest(properties = {"app.external.google.mode=live", "app.google.server-key=test-key"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoogleMapsCreditFlowTest {

    private static final String CREDIT = "translate=\"no\">Google Maps</span>";
    private static final String MAP = "id=\"map\" class=\"map-canvas\"";
    private static final String BOX = "google-content-box";
    private static final String CODE = "catholic-high-school";
    /** Far from the one fake library, which is near {@link #CODE}; the 3 km limit leaves this school no facility. */
    private static final String NO_FACILITIES_CODE = "westwood-secondary-school";
    /** Used by one test only: FacilityController keeps each school's facilities in memory, and it changes what they are. */
    private static final String CREDITS_CODE = "kuo-chuan-presbyterian-secondary-school";
    private static final String PLACE_ID = "place-1";
    private static final String PLACE_WITH_CREDIT_ID = "place-2";
    private static final String PLACE_WITH_BAD_LINK_ID = "place-3";
    private static final String PLACE_WITH_HOURS_ID = "place-4";
    private static final String THIRD_PARTY_CREDIT = "<a href=\"https://data.example/\" target=\"_blank\" "
            + "rel=\"noopener\">Test Data Co</a>";

    private static final ReferenceLocation BISHAN_MRT =
            new ReferenceLocation(new Coordinate(1.3510, 103.8484), LocationSource.MANUAL_ENTRY, "BISHAN MRT");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppProperties props;

    @Autowired
    private AccountController accountController;

    @Autowired
    private AuthController authController;

    @MockitoBean
    private GoogleMapsPlatformInterface google;

    @MockitoSpyBean
    private MapController mapController;

    @BeforeEach
    void fakeGoogle() {
        Facility library = library();
        when(google.searchPlaces(any(), any())).thenReturn(List.of());
        when(google.searchPlaces(eq(FacilityType.LIBRARY), any())).thenReturn(List.of(library));
        when(google.getPlaceDetails(anyString())).thenReturn(Optional.empty());
        when(google.getPlaceDetails(PLACE_ID)).thenReturn(Optional.of(library));
        when(google.getPlaceDetails(PLACE_WITH_CREDIT_ID)).thenReturn(Optional.of(
                libraryWithCredit(PLACE_WITH_CREDIT_ID, "https://data.example/")));
        when(google.getPlaceDetails(PLACE_WITH_BAD_LINK_ID)).thenReturn(Optional.of(
                libraryWithCredit(PLACE_WITH_BAD_LINK_ID, "javascript:alert(1)")));
        when(google.getPlaceDetails(PLACE_WITH_HOURS_ID)).thenReturn(Optional.of(libraryWithHours()));
        when(google.computeRoute(any(), any(), any())).thenReturn(new Route(TravelMode.WALK, 1000, 600, null,
                List.of(new RouteStep(1, "Walk north", 1000))));
        tenMinutesToEverySchool();
    }

    @Test
    @Tag("NFR-ATTR-01")
    @DisplayName("TC-GoogleCredit-01: nearby facilities have the credit in the same card as the list")
    void nearbyFacilities() throws Exception {
        String page = rendered("/schools/" + CODE + "/facilities");

        assertThat(page).contains("Test Library");
        assertThat(page).contains("Places:</span> <span class=\"google-maps-credit-name\" " + CREDIT);
        assertThat(page).contains("</ol> <p class=\"google-maps-credit\">");
    }

    @Test
    @Tag("NFR-ATTR-01")
    @DisplayName("TC-GoogleCredit-02: no facilities, no credit")
    void noFacilitiesNoCredit() throws Exception {
        assertThat(rendered("/schools/" + NO_FACILITIES_CODE + "/facilities")).doesNotContain(CREDIT);
    }

    @Test
    @Tag("NFR-ATTR-01")
    @DisplayName("TC-GoogleCredit-03: facility details have the credit on both cards; with no opening hours, only on the details card")
    void facilityDetails() throws Exception {
        String withHours = rendered("/facilities/" + PLACE_WITH_HOURS_ID + "?from=" + CODE);
        assertThat(withHours).contains("Place details:</span>", "Opening hours:</span>");
        assertThat(withHours.split(CREDIT, -1)).hasSize(3);

        String withoutHours = rendered("/facilities/" + PLACE_ID + "?from=" + CODE);
        assertThat(withoutHours).contains("Place details:</span>").doesNotContain("Opening hours:</span>");
        assertThat(withoutHours).containsOnlyOnce(CREDIT);
    }

    @Test
    @Tag("NFR-ATTR-01")
    @DisplayName("TC-GoogleCredit-04: recommendation results with commute times have the credit, once")
    void recommendations() throws Exception {
        String page = recommendationResults();

        assertThat(page).contains("Commute times:</span>", "10 min");
        assertThat(page).containsOnlyOnce(CREDIT);
        assertThat(page).containsOnlyOnce(BOX);
        String oneLine = page.replaceAll("\\s+", " ");
        assertThat(oneLine).containsSubsequence(BOX, CREDIT, "10 min", "</ol> </div> </div> <aside");
    }

    @Test
    @Tag("NFR-ATTR-01")
    @DisplayName("TC-GoogleCredit-05: recommendation results without a commute time have no credit")
    void recommendationsWithoutCommuteTimes() throws Exception {
        when(google.computeRouteMatrix(any(), anyList(), any())).thenAnswer(call -> {
            List<Coordinate> destinations = call.getArgument(1);
            TravelMode mode = call.getArgument(2);
            return destinations.stream().map(destination -> Route.unavailable(mode)).toList();
        });

        String page = recommendationResults();

        assertThat(page).contains("Commute: Not available");
        assertThat(page).doesNotContain(CREDIT, BOX);
    }

    @Test
    @Tag("NFR-ATTR-01")
    @Tag("FR-FILTER-06")
    @DisplayName("TC-GoogleCredit-06: search results have the credit only when travel times are shown")
    void searchResults() throws Exception {
        MockHttpSession session = sessionWithStart();

        mvc.perform(get("/schools").param("travel", "1").param("mode", "TRANSIT").param("maxMin", "45")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Travel times:</span>")))
                .andExpect(content().string(containsString(CREDIT)))
                .andExpect(content().string(containsString(BOX)));

        mvc.perform(get("/schools").param("q", "catholic").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(CREDIT))))
                .andExpect(content().string(not(containsString(BOX))));
    }

    @Test
    @Tag("NFR-ATTR-01")
    @DisplayName("TC-GoogleCredit-07: the credit follows Google's text rules: weight 400, 12–16 px, one line, not translated")
    void creditFollowsGoogleTextRules() throws Exception {
        assertThat(rendered("/schools/" + CODE + "/facilities")).contains(CREDIT).doesNotContain("<strong " + CREDIT);

        String css = new ClassPathResource("static/css/app.css").getContentAsString(UTF_8);
        String rule = css.substring(css.indexOf(".google-maps-credit {"));
        rule = rule.substring(0, rule.indexOf('}'));
        assertThat(rule).contains("font-weight: 400", "font-style: normal", "letter-spacing: normal",
                "font-size: 0.875rem", "color: #5e5e5e");
        assertThat(css).contains(".google-maps-credit-name { white-space: nowrap; }");
    }

    @Test
    @Tag("NFR-ATTR-01")
    @DisplayName("TC-GoogleCredit-08: the list beside the facility map has the credit in its card, with a map and without one")
    void facilityMap() throws Exception {
        String withoutMap = rendered("/schools/" + CODE + "/facilities/map");
        assertThat(withoutMap).contains("Test Library").doesNotContain(MAP);
        assertThat(withoutMap).contains("</ol> <p class=\"google-maps-credit\"><span>Places:</span>");

        doReturn(true).when(mapController).displayInteractiveMap(anyList());
        String withMap = rendered("/schools/" + CODE + "/facilities/map");
        assertThat(withMap).contains(MAP);
        assertThat(withMap).contains("</ol> <p class=\"google-maps-credit\"><span>Places:</span>");
    }

    @Test
    @Tag("NFR-ATTR-01")
    @DisplayName("TC-GoogleCredit-09: a facility map with no facility has no credit")
    void facilityMapWithoutFacilities() throws Exception {
        assertThat(rendered("/schools/" + NO_FACILITIES_CODE + "/facilities/map")).doesNotContain(CREDIT);
    }

    @Test
    @Tag("NFR-ATTR-01")
    @Tag("FR-ROUTE-07")
    @DisplayName("TC-GoogleCredit-10: the directions page has the credit in the card with the time and steps, with a map and without one")
    void directions() throws Exception {
        MockHttpSession session = sessionWithStart();

        String withoutMap = renderedWith("/directions?mode=WALK&to=school:" + CODE, session);
        assertThat(withoutMap).contains("Walk north").doesNotContain(MAP);
        assertThat(withoutMap).contains("<span>Route:</span> <span class=\"google-maps-credit-name\" " + CREDIT
                + "</p> </div> </section>");
        assertThat(withoutMap).doesNotContain("Estimated by Google Maps Platform");

        doReturn(true).when(mapController).displayInteractiveMap(anyList());
        String withMap = renderedWith("/directions?mode=WALK&to=school:" + CODE, session);
        assertThat(withMap).contains(MAP);
        assertThat(withMap).contains("<span>Route:</span> <span class=\"google-maps-credit-name\" " + CREDIT);
    }

    @Test
    @Tag("NFR-ATTR-01")
    @DisplayName("TC-GoogleCredit-11: the directions input page has the credit when the destination is a facility, whose name and address are Google's, and not for a school")
    void directionsInput() throws Exception {
        String facility = rendered("/directions?to=facility:" + PLACE_ID);
        assertThat(facility).contains("Test Library", "1 TEST ROAD");
        assertThat(facility).contains("<span>Place details:</span> <span class=\"google-maps-credit-name\" " + CREDIT);

        assertThat(rendered("/directions?to=school:" + CODE)).doesNotContain(CREDIT);
    }

    @Test
    @Tag("NFR-ATTR-01")
    @DisplayName("TC-GoogleCredit-12: a third-party data credit from Google follows \"Google Maps\", on both cards of facility details, on directions input and on the route page")
    void thirdPartyCreditOnOnePlace() throws Exception {
        String details = rendered("/facilities/" + PLACE_WITH_CREDIT_ID);
        assertThat(details).contains("Place details:</span> <span class=\"google-maps-credit-name\" " + CREDIT + ", "
                + THIRD_PARTY_CREDIT + "</p>");
        assertThat(details).contains("Opening hours:</span> <span class=\"google-maps-credit-name\" " + CREDIT + ", "
                + THIRD_PARTY_CREDIT + "</p>");

        assertThat(rendered("/directions?to=facility:" + PLACE_WITH_CREDIT_ID)).contains(CREDIT + ", " + THIRD_PARTY_CREDIT);

        String route = renderedWith("/directions?mode=WALK&to=facility:" + PLACE_WITH_CREDIT_ID, sessionWithStart());
        assertThat(route).contains("Walk north");
        assertThat(route).contains("<span>Route:</span> <span class=\"google-maps-credit-name\" " + CREDIT + ", "
                + THIRD_PARTY_CREDIT + "</p>");
    }

    @Test
    @Tag("NFR-ATTR-01")
    @DisplayName("TC-GoogleCredit-13: on nearby facilities and the facility map each third-party credit shows once, however many places have it")
    void thirdPartyCreditOnLists() throws Exception {
        when(google.searchPlaces(eq(FacilityType.TUITION_CENTRE), any())).thenReturn(List.of(
                libraryWithCredit("place-near-1", "https://data.example/"),
                libraryWithCredit("place-near-2", "https://data.example/")));

        assertThat(rendered("/schools/" + CREDITS_CODE + "/facilities")).containsOnlyOnce(THIRD_PARTY_CREDIT);
        assertThat(rendered("/schools/" + CREDITS_CODE + "/facilities/map")).containsOnlyOnce(THIRD_PARTY_CREDIT);
    }

    @Test
    @Tag("NFR-ATTR-01")
    @DisplayName("TC-GoogleCredit-14: a third-party credit whose address is not http(s) shows as plain text, never as a link")
    void thirdPartyCreditWithBadLink() throws Exception {
        String page = rendered("/facilities/" + PLACE_WITH_BAD_LINK_ID);

        assertThat(page).contains(CREDIT + ", <span>Test Data Co</span></p>");
        assertThat(page).doesNotContain("javascript:alert");
    }

    private void tenMinutesToEverySchool() {
        when(google.computeRouteMatrix(any(), anyList(), any())).thenAnswer(call -> {
            List<Coordinate> destinations = call.getArgument(1);
            TravelMode mode = call.getArgument(2);
            return destinations.stream().map(destination -> new Route(mode, 1000, 600, null, List.of())).toList();
        });
    }

    /** Asks for recommendations as a new member and returns the results page. */
    private String recommendationResults() throws Exception {
        Cookie member = TestMembers.cookie(props,
                TestMembers.registerAndLogIn(accountController, authController, "credit"));
        MockHttpSession session = sessionWithStart();
        MvcResult submitted = mvc.perform(post("/recommendations").cookie(member).session(session)
                        .param("psleScore", "12").param("postingGroup", "3").param("travelMode", "TRANSIT")
                        .param("maxCommuteMin", "45"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        return mvc.perform(get(submitted.getResponse().getRedirectedUrl()).cookie(member).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(UTF_8);
    }

    private String rendered(String path) throws Exception {
        return renderedWith(path, new MockHttpSession());
    }

    private String renderedWith(String path, MockHttpSession session) throws Exception {
        return mvc.perform(get(path).session(session)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(UTF_8).replaceAll("\\s+", " ");
    }

    private static MockHttpSession sessionWithStart() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ReferenceLocationStore.CURRENT, BISHAN_MRT);
        return session;
    }

    private static Facility library() {
        Facility facility = new Facility(PLACE_ID, "Test Library", FacilityType.LIBRARY);
        facility.setAddress("1 TEST ROAD");
        facility.setCoordinate(new Coordinate(1.3520, 103.8490));
        return facility;
    }

    private static Facility libraryWithHours() {
        Facility facility = new Facility(PLACE_WITH_HOURS_ID, "Test Library With Hours", FacilityType.LIBRARY);
        facility.setOpeningHours("Monday: 10:00 AM – 9:00 PM");
        return facility;
    }

    /** A place near {@link #CREDITS_CODE} and {@link #CODE} that Google gave one third-party data credit with. */
    private static Facility libraryWithCredit(String placeId, String providerUri) {
        Facility facility = new Facility(placeId, "Credited Library " + placeId, FacilityType.LIBRARY);
        facility.setAddress("2 TEST ROAD");
        facility.setCoordinate(new Coordinate(1.3520, 103.8490));
        facility.setOpeningHours("Monday: 10:00 AM – 9:00 PM");
        facility.setAttributions(List.of(new Facility.Attribution("Test Data Co", providerUri)));
        return facility;
    }
}

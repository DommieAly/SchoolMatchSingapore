package sg.schoolmatch.boundary.ui;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.boundary.ui.support.FilterParams;
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.DirectionsController;
import sg.schoolmatch.control.FacilityController;
import sg.schoolmatch.control.LocationController;
import sg.schoolmatch.control.MapController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.RouteStep;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotFoundException;
import sg.schoolmatch.support.TestSchools;

/**
 * Web test of DirectionsUI: the directions pages (DM-19..21; UC Get Directions, FR-ROUTE-01..04/07/08) and the
 * starting-point forms {@code POST /location*} (DC-11, DC-23, NFR-SEC-06). All controls are mocks.
 */
@WebMvcTest(DirectionsUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import({SessionCookie.class, ReferenceLocationStore.class})
@ActiveProfiles("test")
class DirectionsUITest {

    private static final String SCHOOL_CODE = "catholic-high-school";
    private static final String TO_SCHOOL = "school:" + SCHOOL_CODE;
    /** The input page URL for TO_SCHOOL, as DirectionsUI writes it (every reserved character encoded). */
    private static final String INPUT_URL = "/directions?to=school%3Acatholic-high-school";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SchoolController schoolController;

    @MockitoBean
    private FacilityController facilityController;

    @MockitoBean
    private DirectionsController directionsController;

    @MockitoBean
    private LocationController locationController;

    @MockitoBean
    private MapController mapController;

    // Shared layout (LayoutModelAdvice, AuthInterceptor); ProfileController in case the layout greets the user.
    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    @MockitoBean
    private ProfileController profileController;

    private final School school = TestSchools.school(SCHOOL_CODE).name("CATHOLIC HIGH SCHOOL")
            .address("9 BISHAN STREET 22").build();
    private final ReferenceLocation home = new ReferenceLocation(new Coordinate(1.3500, 103.8400),
            LocationSource.MANUAL_ENTRY, "200 BISHAN ROAD");

    // ---- GET /directions?to= (DM-19) ------------------------------------------------------------------------

    @Test
    @Tag("FR-ROUTE-01")
    @Tag("FR-ROUTE-04")
    @DisplayName("TC-DirectionsUI-01: the input page shows the school, the three travel modes and Cancel back to the school")
    void inputPage_school() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);

        mvc.perform(get("/directions").param("to", TO_SCHOOL))
                .andExpect(status().isOk())
                .andExpect(view().name("directions-input"))
                .andExpect(model().attribute("destination", school))
                .andExpect(model().attribute("cancelUrl", "/schools/" + SCHOOL_CODE))
                .andExpect(content().string(containsString("CATHOLIC HIGH SCHOOL")))
                .andExpect(content().string(stringContainsInOrder("Walk", "Drive", "Public transport")))
                .andExpect(content().string(containsString("href=\"/schools/" + SCHOOL_CODE + "\"")))
                .andExpect(content().string(containsString("action=\"/location\"")))
                .andExpect(content().string(not(containsString("Not built yet"))));
    }

    @Test
    @Tag("FR-ROUTE-03")
    @DisplayName("TC-DirectionsUI-02: a bare school code works as the destination too")
    void inputPage_bareSchoolCode() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);

        mvc.perform(get("/directions").param("to", SCHOOL_CODE))
                .andExpect(status().isOk())
                .andExpect(model().attribute("destination", school));
    }

    @Test
    @Tag("FR-ROUTE-03")
    @DisplayName("TC-DirectionsUI-03: a facility destination is loaded by place id; Cancel goes back to the facility")
    void inputPage_facility() throws Exception {
        Facility library = new Facility("lib-1", "BISHAN PUBLIC LIBRARY", FacilityType.LIBRARY);
        library.setCoordinate(TestSchools.BISHAN);
        when(facilityController.getFacilityDetails("lib-1")).thenReturn(library);

        mvc.perform(get("/directions").param("to", "facility:lib-1"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("destination", library))
                .andExpect(model().attribute("cancelUrl", "/facilities/lib-1"))
                .andExpect(content().string(containsString("BISHAN PUBLIC LIBRARY")));
    }

    @Test
    @Tag("FR-ROUTE-03")
    @Tag("FR-FACILITY-02")
    @DisplayName("TC-DirectionsUI-29: the facility's details link keeps the school it was found near (?from=code)")
    void facilityDetailsLinkKeepsSchool() throws Exception {
        Facility library = new Facility("lib-1", "BISHAN PUBLIC LIBRARY", FacilityType.LIBRARY);
        library.setCoordinate(TestSchools.BISHAN);
        when(facilityController.getFacilityDetails("lib-1")).thenReturn(library);

        mvc.perform(get("/directions").param("to", "facility:lib-1")
                        .param("from", "/schools/catholic-high-school/facilities"))
                .andExpect(model().attribute("destinationUrl", "/facilities/lib-1?from=catholic-high-school"));
        mvc.perform(get("/directions").param("to", "facility:lib-1")
                        .param("from", "/facilities/lib-1?from=catholic-high-school"))
                .andExpect(model().attribute("destinationUrl", "/facilities/lib-1?from=catholic-high-school"));
        mvc.perform(get("/directions").param("to", "facility:lib-1").param("from", "/schools"))
                .andExpect(model().attribute("destinationUrl", "/facilities/lib-1"));
    }

    @Test
    @Tag("FR-ROUTE-01")
    @DisplayName("TC-DirectionsUI-04: Cancel uses a local 'from' page, never a foreign one")
    void inputPage_fromParameter() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);

        mvc.perform(get("/directions").param("to", TO_SCHOOL).param("from", "/schools/" + SCHOOL_CODE + "/facilities"))
                .andExpect(model().attribute("cancelUrl", "/schools/" + SCHOOL_CODE + "/facilities"));
        mvc.perform(get("/directions").param("to", TO_SCHOOL).param("from", "//evil.com"))
                .andExpect(model().attribute("cancelUrl", "/schools/" + SCHOOL_CODE));
    }

    @Test
    @Tag("FR-ROUTE-03")
    @DisplayName("TC-DirectionsUI-05: an unknown school is a 404 page")
    void inputPage_unknownSchool() throws Exception {
        when(schoolController.getSchoolDetails("nope")).thenThrow(new NotFoundException("No school with code 'nope'"));

        mvc.perform(get("/directions").param("to", "school:nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Tag("FR-ROUTE-01")
    @DisplayName("TC-DirectionsUI-06: without a destination the page says so and offers no route button")
    void inputPage_noDestination() throws Exception {
        mvc.perform(get("/directions"))
                .andExpect(status().isOk())
                .andExpect(view().name("directions-input"))
                .andExpect(content().string(containsString("No destination chosen")))
                .andExpect(content().string(not(containsString("Show route"))));
    }

    // ---- GET /directions?to=&mode= (DM-20/21) ----------------------------------------------------------------

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-DirectionsUI-07: no starting point → back to the input page with 'Set a starting point first' (AF-1)")
    void route_noStartingPoint() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);

        mvc.perform(get("/directions").param("to", TO_SCHOOL).param("mode", "WALK"))
                .andExpect(status().isOk())
                .andExpect(view().name("directions-input"))
                .andExpect(model().attribute("mode", TravelMode.WALK))
                .andExpect(content().string(containsString("Set a starting point first")));
        verifyNoInteractions(directionsController);
    }

    @Test
    @Tag("FR-ROUTE-04")
    @Tag("NFR-USE-03")
    @DisplayName("TC-DirectionsUI-08: an unknown travel mode is a field error, no route is asked for")
    void route_unknownMode() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);

        mvc.perform(get("/directions").param("to", TO_SCHOOL).param("mode", "FLY").session(sessionWith(home)))
                .andExpect(status().isOk())
                .andExpect(view().name("directions-input"))
                .andExpect(content().string(containsString(FilterParams.MODE_MESSAGE)));
        verifyNoInteractions(directionsController);
    }

    @Test
    @Tag("FR-ROUTE-07")
    @DisplayName("TC-DirectionsUI-09: a route shows distance (km, 1 dp), travel time (min) and the numbered steps in order")
    void route_available() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);
        Route route = new Route(TravelMode.TRANSIT, 2449, 700, "_p~iF~ps|U", List.of(
                new RouteStep(2, "Take bus 52 towards Bishan", 2000),
                new RouteStep(1, "Walk to Bishan Int", 350),
                new RouteStep(3, "Walk to the school", 99)));
        when(directionsController.getDirections(home, school, TravelMode.TRANSIT)).thenReturn(route);

        mvc.perform(get("/directions").param("to", TO_SCHOOL).param("mode", "TRANSIT").session(sessionWith(home)))
                .andExpect(status().isOk())
                .andExpect(view().name("directions"))
                .andExpect(model().attribute("distanceKm", "2.4"))
                .andExpect(model().attribute("durationText", "12 min"))
                .andExpect(model().attribute("steps", hasSize(3)))
                .andExpect(content().string(containsString("2.4 km")))
                .andExpect(content().string(containsString("12 min")))
                .andExpect(content().string(stringContainsInOrder(
                        "Walk to Bishan Int", "350 m", "Take bus 52 towards Bishan", "2.0 km", "Walk to the school")))
                .andExpect(content().string(containsString("200 BISHAN ROAD")))
                .andExpect(content().string(not(containsString("Not built yet"))));
        verify(directionsController).getDirections(home, school, TravelMode.TRANSIT);
    }

    @Test
    @Tag("FR-ROUTE-07")
    @DisplayName("TC-DirectionsUI-10: a trip of an hour or more shows hours and minutes")
    void route_longDuration() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);
        when(directionsController.getDirections(home, school, TravelMode.WALK)).thenReturn(
                new Route(TravelMode.WALK, 5200, 3900, null, List.of(new RouteStep(1, "Walk", 5200))));

        mvc.perform(get("/directions").param("to", TO_SCHOOL).param("mode", "walk").session(sessionWith(home)))
                .andExpect(view().name("directions"))
                .andExpect(model().attribute("durationText", "1 h 5 min"));
    }

    @Test
    @Tag("FR-ROUTE-08")
    @DisplayName("TC-DirectionsUI-11: no route → the input page says 'No route found' and shows no partial route (AF-2)")
    void route_notFound() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);
        when(directionsController.getDirections(home, school, TravelMode.DRIVE))
                .thenReturn(Route.unavailable(TravelMode.DRIVE));

        mvc.perform(get("/directions").param("to", TO_SCHOOL).param("mode", "DRIVE").session(sessionWith(home)))
                .andExpect(status().isOk())
                .andExpect(view().name("directions-input"))
                .andExpect(model().attributeDoesNotExist("steps"))
                .andExpect(content().string(containsString("No route found")));
    }

    @Test
    @Tag("FR-ROUTE-08")
    @Tag("NFR-USE-03")
    @DisplayName("TC-DirectionsUI-12: the routing service failing → 'Directions are temporarily unavailable' (EX-1)")
    void route_serviceDown() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);
        when(directionsController.getDirections(home, school, TravelMode.WALK))
                .thenThrow(new ExternalServiceUnavailableException("Google Routes", null));

        mvc.perform(get("/directions").param("to", TO_SCHOOL).param("mode", "WALK").session(sessionWith(home)))
                .andExpect(status().isOk())
                .andExpect(view().name("directions-input"))
                .andExpect(content().string(containsString("Directions are temporarily unavailable")));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-DirectionsUI-13: locations the control refuses → input page with the reason (EX-2)")
    void route_invalidLocations() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);
        when(directionsController.getDirections(home, school, TravelMode.WALK))
                .thenThrow(new InvalidInputException("origin", "The starting point is not resolved"));

        mvc.perform(get("/directions").param("to", TO_SCHOOL).param("mode", "WALK").session(sessionWith(home)))
                .andExpect(view().name("directions-input"))
                .andExpect(content().string(containsString("The starting point is not resolved")))
                .andExpect(content().string(containsString("Directions cannot be calculated")));
    }

    @Test
    @Tag("FR-ROUTE-08")
    @Tag("NFR-DATA-02")
    @DisplayName("TC-DirectionsUI-14: a destination without a map location gets a message and no route request")
    void route_destinationWithoutCoordinate() throws Exception {
        School noLocation = TestSchools.school("no-location-school").noCoordinate().build();
        when(schoolController.getSchoolDetails("no-location-school")).thenReturn(noLocation);

        mvc.perform(get("/directions").param("to", "school:no-location-school"))
                .andExpect(content().string(containsString("has no map location")));
        mvc.perform(get("/directions").param("to", "school:no-location-school").param("mode", "WALK")
                        .session(sessionWith(home)))
                .andExpect(view().name("directions-input"))
                .andExpect(content().string(containsString("has no map location")));
        verifyNoInteractions(directionsController);
    }

    @Test
    @Tag("FR-ROUTE-07")
    @Tag("FR-MAP-02")
    @DisplayName("TC-DirectionsUI-15: with an interactive map the page embeds the route line and the start and end markers")
    void route_mapWithRouteLine() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);
        when(directionsController.getDirections(home, school, TravelMode.WALK)).thenReturn(
                new Route(TravelMode.WALK, 1200, 900, "abc~DEF", List.of(new RouteStep(1, "Walk north", 1200))));
        when(mapController.displayInteractiveMap(List.of(school))).thenReturn(true);

        mvc.perform(get("/directions").param("to", TO_SCHOOL).param("mode", "WALK").session(sessionWith(home)))
                .andExpect(view().name("directions"))
                .andExpect(model().attribute("interactive", true))
                .andExpect(content().string(containsString("data-route=\"abc~DEF\"")))
                .andExpect(content().string(allOf(containsString("&quot;kind&quot;:&quot;start&quot;"),
                        containsString("&quot;id&quot;:&quot;" + SCHOOL_CODE + "&quot;"))));
    }

    @Test
    @Tag("FR-ROUTE-07")
    @Tag("NFR-PERF-03")
    @DisplayName("TC-DirectionsUI-16: the travel-mode links on the route page show the loading overlay")
    void route_modeLinksShowLoading() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);
        when(directionsController.getDirections(home, school, TravelMode.WALK)).thenReturn(
                new Route(TravelMode.WALK, 1200, 900, null, List.of(new RouteStep(1, "Walk north", 1200))));

        mvc.perform(get("/directions").param("to", TO_SCHOOL).param("mode", "WALK").session(sessionWith(home)))
                .andExpect(content().string(containsString("href=\"" + INPUT_URL + "&amp;mode=DRIVE\"")))
                .andExpect(content().string(containsString("data-loading")))
                .andExpect(content().string(containsString("loading-overlay")));
    }

    // ---- POST /location (address) ---------------------------------------------------------------------------

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("FR-FILTER-04")
    @DisplayName("TC-DirectionsUI-17: one match sets the starting point and goes back to returnTo")
    void enterAddress_oneMatch() throws Exception {
        when(locationController.findCandidates("579767")).thenReturn(List.of(home));

        mvc.perform(post("/location").param("address", "579767").param("returnTo", INPUT_URL))
                .andExpect(redirectedUrl(INPUT_URL))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CURRENT, sameInstance(home)))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CANDIDATES, nullValue()))
                .andExpect(flash().attribute("flashMessage", "Starting point set: 200 BISHAN ROAD"));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-DirectionsUI-18: several matches are kept as candidates for the picker; the current point stays")
    void enterAddress_severalMatches() throws Exception {
        ReferenceLocation a = manual("A ROAD", 1.35, 103.84);
        ReferenceLocation b = manual("B ROAD", 1.36, 103.85);
        when(locationController.findCandidates("bishan")).thenReturn(List.of(a, b));
        MockHttpSession session = sessionWith(home);

        mvc.perform(post("/location").param("address", "bishan").param("returnTo", INPUT_URL).session(session))
                .andExpect(redirectedUrl(INPUT_URL))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CURRENT, sameInstance(home)))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CANDIDATES, hasSize(2)))
                .andExpect(flash().attribute("flashMessage", DirectionsUI.SEVERAL_FOUND_MESSAGE));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-DirectionsUI-19: no match → error message, the typed text is kept, nothing is stored")
    void enterAddress_noMatch() throws Exception {
        when(locationController.findCandidates("zzz")).thenReturn(List.of());

        mvc.perform(post("/location").param("address", "zzz").param("returnTo", INPUT_URL))
                .andExpect(redirectedUrl(INPUT_URL))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CURRENT, nullValue()))
                .andExpect(flash().attribute("flashError", DirectionsUI.ADDRESS_NOT_FOUND_MESSAGE))
                .andExpect(flash().attribute("locationAddress", "zzz"));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-DirectionsUI-20: an invalid address shows the control's message")
    void enterAddress_invalid() throws Exception {
        when(locationController.findCandidates(" ")).thenThrow(
                new InvalidInputException(LocationController.ADDRESS, "Enter an address or postal code (1–200 characters)"));

        mvc.perform(post("/location").param("address", " ").param("returnTo", INPUT_URL))
                .andExpect(redirectedUrl(INPUT_URL))
                .andExpect(flash().attribute("flashError", "Enter an address or postal code (1–200 characters)"));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-DirectionsUI-21: OneMap down → 'Address search is temporarily unavailable'")
    void enterAddress_oneMapDown() throws Exception {
        when(locationController.findCandidates("bishan"))
                .thenThrow(new ExternalServiceUnavailableException("OneMap", null));

        mvc.perform(post("/location").param("address", "bishan").param("returnTo", INPUT_URL))
                .andExpect(redirectedUrl(INPUT_URL))
                .andExpect(flash().attribute("flashError", containsString("Address search is temporarily unavailable")));
    }

    // ---- POST /location/device ------------------------------------------------------------------------------

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-SEC-06")
    @DisplayName("TC-DirectionsUI-22: the browser's position becomes the starting point")
    void devicePosition_inside() throws Exception {
        ReferenceLocation device = new ReferenceLocation(new Coordinate(1.35, 103.85), LocationSource.DEVICE_LOCATION,
                LocationController.DEVICE_LABEL);
        when(locationController.getDeviceLocation(new Coordinate(1.35, 103.85))).thenReturn(device);

        mvc.perform(post("/location/device").param("latitude", "1.35").param("longitude", "103.85")
                        .param("returnTo", INPUT_URL))
                .andExpect(redirectedUrl(INPUT_URL))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CURRENT, sameInstance(device)));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-DirectionsUI-23: a position outside Singapore is refused with the control's message")
    void devicePosition_outside() throws Exception {
        when(locationController.getDeviceLocation(new Coordinate(40.0, 100.0)))
                .thenThrow(new InvalidInputException(LocationController.LOCATION, "That location is outside Singapore"));

        mvc.perform(post("/location/device").param("latitude", "40").param("longitude", "100")
                        .param("returnTo", INPUT_URL))
                .andExpect(redirectedUrl(INPUT_URL))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CURRENT, nullValue()))
                .andExpect(flash().attribute("flashError", "That location is outside Singapore"));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-DirectionsUI-24: a missing or unreadable position is refused before the control is called")
    void devicePosition_unreadable() throws Exception {
        mvc.perform(post("/location/device").param("returnTo", INPUT_URL))
                .andExpect(flash().attribute("flashError", DirectionsUI.DEVICE_UNREADABLE_MESSAGE));
        mvc.perform(post("/location/device").param("latitude", "abc").param("longitude", "103.8")
                        .param("returnTo", INPUT_URL))
                .andExpect(flash().attribute("flashError", DirectionsUI.DEVICE_UNREADABLE_MESSAGE));
        mvc.perform(post("/location/device").param("latitude", "95").param("longitude", "103.8")
                        .param("returnTo", INPUT_URL))
                .andExpect(flash().attribute("flashError", DirectionsUI.DEVICE_UNREADABLE_MESSAGE));
        verify(locationController, never()).getDeviceLocation(any());
    }

    // ---- POST /location/choose, /location/clear -------------------------------------------------------------

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-DirectionsUI-25: choosing a candidate makes it the starting point and drops the list")
    void chooseCandidate() throws Exception {
        ReferenceLocation a = manual("A ROAD", 1.35, 103.84);
        ReferenceLocation b = manual("B ROAD", 1.36, 103.85);
        MockHttpSession session = sessionWithCandidates(a, b);

        mvc.perform(post("/location/choose").param("index", "1").param("returnTo", INPUT_URL).session(session))
                .andExpect(redirectedUrl(INPUT_URL))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CURRENT, sameInstance(b)))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CANDIDATES, nullValue()))
                .andExpect(flash().attribute("flashMessage", "Starting point set: B ROAD"));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-DirectionsUI-26: an index that is not in the list (or no list) changes nothing")
    void chooseCandidate_badIndex() throws Exception {
        MockHttpSession session = sessionWithCandidates(manual("A ROAD", 1.35, 103.84));

        for (String index : new String[] {"1", "-1", "abc", ""}) {
            mvc.perform(post("/location/choose").param("index", index).param("returnTo", INPUT_URL).session(session))
                    .andExpect(redirectedUrl(INPUT_URL))
                    .andExpect(request().sessionAttribute(ReferenceLocationStore.CURRENT, nullValue()))
                    .andExpect(flash().attribute("flashError", DirectionsUI.CHOICE_GONE_MESSAGE));
        }
        mvc.perform(post("/location/choose").param("index", "0").param("returnTo", INPUT_URL))
                .andExpect(flash().attribute("flashError", DirectionsUI.CHOICE_GONE_MESSAGE));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-DirectionsUI-27: Clear forgets the starting point and the candidate list")
    void clearLocation() throws Exception {
        MockHttpSession session = sessionWith(home);
        session.setAttribute(ReferenceLocationStore.CANDIDATES, new ArrayList<>(List.of(manual("A", 1.35, 103.84))));

        mvc.perform(post("/location/clear").param("returnTo", INPUT_URL).session(session))
                .andExpect(redirectedUrl(INPUT_URL))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CURRENT, nullValue()))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CANDIDATES, nullValue()))
                .andExpect(flash().attribute("flashMessage", "Starting point cleared."));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-DirectionsUI-28: 'None of these' drops only the candidate list")
    void clearCandidatesOnly() throws Exception {
        MockHttpSession session = sessionWith(home);
        session.setAttribute(ReferenceLocationStore.CANDIDATES, new ArrayList<>(List.of(manual("A", 1.35, 103.84))));

        mvc.perform(post("/location/clear").param("candidatesOnly", "true").param("returnTo", INPUT_URL)
                        .session(session))
                .andExpect(redirectedUrl(INPUT_URL))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CURRENT, sameInstance(home)))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CANDIDATES, nullValue()));
    }

    // ---- the location picker fragment ----------------------------------------------------------------------

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-DirectionsUI-29: the picker shows the current starting point with a Clear button")
    void picker_showsCurrentPoint() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);

        mvc.perform(get("/directions").param("to", TO_SCHOOL).session(sessionWith(home)))
                .andExpect(model().attribute("startLocation", home))
                .andExpect(content().string(stringContainsInOrder("Starting point", "200 BISHAN ROAD")))
                .andExpect(content().string(containsString("action=\"/location/clear\"")));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-DirectionsUI-30: the picker lists the candidates to choose from (DC-11)")
    void picker_listsCandidates() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);
        MockHttpSession session = sessionWithCandidates(manual("A ROAD", 1.35, 103.84), manual("B ROAD", 1.36, 103.85));

        mvc.perform(get("/directions").param("to", TO_SCHOOL).session(session))
                .andExpect(content().string(containsString("action=\"/location/choose\"")))
                .andExpect(content().string(stringContainsInOrder("A ROAD", "B ROAD", "Use this place")))
                .andExpect(content().string(containsString("name=\"index\" value=\"1\"")))
                .andExpect(content().string(containsString("None of these")));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-DirectionsUI-31: after a failed search the address field keeps the typed text")
    void picker_keepsTypedAddress() throws Exception {
        when(schoolController.getSchoolDetails(SCHOOL_CODE)).thenReturn(school);

        mvc.perform(get("/directions").param("to", TO_SCHOOL).flashAttr("locationAddress", "zzz street"))
                .andExpect(content().string(containsString("value=\"zzz street\"")));
    }

    private static ReferenceLocation manual(String text, double latitude, double longitude) {
        return new ReferenceLocation(new Coordinate(latitude, longitude), LocationSource.MANUAL_ENTRY, text);
    }

    private static MockHttpSession sessionWith(ReferenceLocation location) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ReferenceLocationStore.CURRENT, location);
        return session;
    }

    private static MockHttpSession sessionWithCandidates(ReferenceLocation... candidates) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ReferenceLocationStore.CANDIDATES, new ArrayList<>(List.of(candidates)));
        return session;
    }
}

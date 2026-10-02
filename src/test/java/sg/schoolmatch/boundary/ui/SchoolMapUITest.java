package sg.schoolmatch.boundary.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import sg.schoolmatch.control.FilterController;
import sg.schoolmatch.control.MapController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.search.AttributeCategory;
import sg.schoolmatch.entity.search.CurrentResultSet;
import sg.schoolmatch.entity.search.Filter;
import sg.schoolmatch.entity.search.ProximityFilter;
import sg.schoolmatch.entity.search.PsleScoreFilter;
import sg.schoolmatch.entity.search.SchoolAttributeFilter;
import sg.schoolmatch.entity.search.TransportationFilter;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotAuthenticatedException;
import sg.schoolmatch.support.TestSchools;

/**
 * Web test of SchoolMapUI: the school map page (DM-11; UC View Schools on Map and View School's Districts,
 * FR-MAP-01..07) and {@code GET /api/districts}. The search, filter and map controls are mocks; the real
 * MapController is unit-tested in {@code MapControllerTest}.
 */
@WebMvcTest(SchoolMapUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import({SessionCookie.class, ReferenceLocationStore.class})
@ActiveProfiles("test")
class SchoolMapUITest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SchoolController schoolController;

    @MockitoBean
    private FilterController filterController;

    @MockitoBean
    private MapController mapController;

    @MockitoBean
    private ProfileController profileController;

    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    private final School catholic = TestSchools.school("catholic-high-school").name("CATHOLIC HIGH SCHOOL")
            .type("GOVERNMENT-AIDED SCH").planningArea("BISHAN").build();
    private final School tampines = TestSchools.school("tampines-secondary-school").name("TAMPINES SECONDARY SCHOOL")
            .type("GOVERNMENT SCHOOL").planningArea("TAMPINES").at(TestSchools.TAMPINES).build();
    private final CurrentResultSet all = new CurrentResultSet(null, List.of(catholic, tampines));
    private final ReferenceLocation home = new ReferenceLocation(new Coordinate(1.3500, 103.8400),
            LocationSource.MANUAL_ENTRY, "200 BISHAN ROAD");

    // ---- GET /api/districts ---------------------------------------------------------------------------------

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-SchoolMapUI-01: /api/districts returns the district GeoJSON as application/geo+json")
    void apiDistricts() throws Exception {
        String geoJson = "{\"type\":\"FeatureCollection\",\"features\":[]}";
        when(mapController.toggleDistricts(true)).thenReturn(geoJson);

        mvc.perform(get("/api/districts"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/geo+json")))
                .andExpect(content().string(geoJson));
    }

    // ---- GET /schools/map -----------------------------------------------------------------------------------

    @Test
    @Tag("FR-MAP-01")
    @Tag("FR-MAP-05")
    @DisplayName("TC-SchoolMapUI-02: without filters every result becomes a marker that links to its details page")
    void map_noFilters() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(all);
        when(mapController.showSchoolsOnMap(all)).thenReturn(List.of(catholic, tampines));

        mvc.perform(get("/schools/map"))
                .andExpect(status().isOk())
                .andExpect(view().name("school-map"))
                .andExpect(model().attribute("schools", List.of(catholic, tampines)))
                .andExpect(model().attribute("resultsUrl", "/schools"))
                .andExpect(model().attribute("markersJson", containsString("\"href\":\"/schools/catholic-high-school\"")))
                .andExpect(content().string(containsString("TAMPINES SECONDARY SCHOOL")))
                .andExpect(content().string(not(containsString("Not built yet"))));
        verify(filterController, never()).applyFilters(any(), anyList());
    }

    @Test
    @Tag("FR-MAP-01")
    @Tag("FR-FILTER-07")
    @DisplayName("TC-SchoolMapUI-03: the markers follow the same filters as the result list; Back keeps the query")
    void map_withAttributeFilter() throws Exception {
        CurrentResultSet filtered = new CurrentResultSet(null, List.of(tampines));
        when(schoolController.searchSchools(null)).thenReturn(all);
        when(filterController.applyFilters(any(), anyList())).thenReturn(filtered);
        when(mapController.showSchoolsOnMap(filtered)).thenReturn(List.of(tampines));

        mvc.perform(get("/schools/map").param("type", "GOVERNMENT SCHOOL"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("schools", List.of(tampines)))
                .andExpect(model().attribute("resultsUrl", "/schools?type=GOVERNMENT%20SCHOOL"))
                .andExpect(model().attribute("filterUrl", "/schools/filter?type=GOVERNMENT%20SCHOOL"))
                .andExpect(content().string(containsString("Type: GOVERNMENT SCHOOL")));

        List<Filter> filters = capturedFilters(1).getFirst();
        assertThat(filters).singleElement().isInstanceOfSatisfying(SchoolAttributeFilter.class, f -> {
            assertThat(f.getCategory()).isEqualTo(AttributeCategory.SCHOOL_TYPE);
            assertThat(f.getSelectedValues()).containsExactly("GOVERNMENT SCHOOL");
        });
    }

    @Test
    @Tag("FR-FILTER-03")
    @Tag("NFR-USE-03")
    @DisplayName("TC-SchoolMapUI-04: an invalid PSLE score is shown as an error; the other filters still apply")
    void map_invalidPsle() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(all);
        when(filterController.applyFilters(any(), anyList())).thenReturn(all);
        when(mapController.showSchoolsOnMap(any())).thenReturn(List.of(catholic, tampines));

        mvc.perform(get("/schools/map").param("psle", "abc").param("cca", "BASKETBALL"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("fieldErrors", hasEntry(FilterParams.PSLE, FilterParams.PSLE_MESSAGE)))
                .andExpect(content().string(containsString(FilterParams.PSLE_MESSAGE)));

        assertThat(capturedFilters(1).getFirst()).singleElement().isInstanceOf(SchoolAttributeFilter.class);
    }

    @Test
    @Tag("FR-FILTER-04")
    @Tag("FR-FILTER-05")
    @DisplayName("TC-SchoolMapUI-05: a distance filter needs a starting point; with one it is applied")
    void map_distanceFilter() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(all);
        when(filterController.applyFilters(any(), anyList())).thenReturn(all);
        when(mapController.showSchoolsOnMap(any())).thenReturn(List.of(catholic, tampines));

        mvc.perform(get("/schools/map").param("radiusKm", "3"))
                .andExpect(content().string(containsString(FilterParams.LOCATION_MESSAGE)));
        verify(filterController, never()).applyFilters(any(), anyList());

        mvc.perform(get("/schools/map").param("radiusKm", "3").session(sessionWith(home)))
                .andExpect(content().string(not(containsString(FilterParams.LOCATION_MESSAGE))));
        assertThat(capturedFilters(1).getFirst()).singleElement().isInstanceOfSatisfying(ProximityFilter.class,
                f -> assertThat(f.getReferenceLocation()).isSameAs(home));
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-SchoolMapUI-06: the travel-time filter runs only after the explicit travel=1 click")
    void map_travelFilterOnlyWithTravel() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(all);
        when(filterController.applyFilters(any(), anyList())).thenReturn(all);
        when(mapController.showSchoolsOnMap(any())).thenReturn(List.of(catholic, tampines));

        mvc.perform(get("/schools/map").param("mode", "WALK").param("maxMin", "30").session(sessionWith(home)))
                .andExpect(status().isOk());
        verify(filterController, never()).applyFilters(any(), anyList());

        mvc.perform(get("/schools/map").param("mode", "WALK").param("maxMin", "30").param("travel", "1")
                        .session(sessionWith(home)))
                .andExpect(status().isOk());
        assertThat(capturedFilters(1).getFirst()).singleElement().isInstanceOf(TransportationFilter.class);
    }

    @Test
    @Tag("FR-FILTER-06")
    @Tag("NFR-USE-03")
    @DisplayName("TC-SchoolMapUI-07: routing down → the other filters still apply and the page says the travel filter is unavailable")
    void map_travelFilterUnavailable() throws Exception {
        CurrentResultSet filtered = new CurrentResultSet(null, List.of(catholic));
        when(schoolController.searchSchools(null)).thenReturn(all);
        when(filterController.applyFilters(any(), anyList()))
                .thenThrow(new ExternalServiceUnavailableException("Google route-matrix-elements", null))
                .thenReturn(filtered);
        when(mapController.showSchoolsOnMap(filtered)).thenReturn(List.of(catholic));

        mvc.perform(get("/schools/map").param("type", "GOVERNMENT-AIDED SCH").param("mode", "WALK")
                        .param("maxMin", "30").param("travel", "1").session(sessionWith(home)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("schools", List.of(catholic)))
                .andExpect(content().string(containsString("Travel-time filter is temporarily unavailable")));

        List<List<Filter>> calls = capturedFilters(2);
        assertThat(calls.get(0)).hasSize(2);
        assertThat(calls.get(1)).singleElement().isInstanceOf(SchoolAttributeFilter.class);
    }

    @Test
    @Tag("FR-FILTER-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-SchoolMapUI-08: a filter value the control rejects is shown as an error; the map uses the other filters")
    void map_rejectedFilterValue() throws Exception {
        CurrentResultSet filtered = new CurrentResultSet(null, List.of(tampines));
        when(schoolController.searchSchools(null)).thenReturn(all);
        when(filterController.applyFilters(any(), anyList()))
                .thenThrow(new InvalidInputException(FilterParams.CCA, "Unknown CCA: KITE FLYING"))
                .thenReturn(filtered);
        when(mapController.showSchoolsOnMap(filtered)).thenReturn(List.of(tampines));

        mvc.perform(get("/schools/map").param("cca", "KITE FLYING").param("type", "GOVERNMENT SCHOOL"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("schools", List.of(tampines)))
                .andExpect(content().string(containsString("Unknown CCA: KITE FLYING")));

        assertThat(capturedFilters(2).get(1)).singleElement().isInstanceOfSatisfying(SchoolAttributeFilter.class,
                f -> assertThat(f.getCategory()).isEqualTo(AttributeCategory.SCHOOL_TYPE));
    }

    @Test
    @Tag("FR-MAP-01")
    @DisplayName("TC-SchoolMapUI-09: no school to show → 'No schools to show on the map' and a way back (AF-1)")
    void map_noSchools() throws Exception {
        CurrentResultSet none = new CurrentResultSet("zzz", List.of());
        when(schoolController.searchSchools("zzz")).thenReturn(none);
        when(mapController.showSchoolsOnMap(none)).thenReturn(List.of());

        mvc.perform(get("/schools/map").param("q", "zzz"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No schools to show on the map")))
                .andExpect(content().string(containsString("href=\"/schools?q=zzz\"")));
    }

    @Test
    @Tag("FR-MAP-02")
    @DisplayName("TC-SchoolMapUI-10: map unavailable → 'Map unavailable', the list still works, the district switch is off (EX-1)")
    void map_unavailable() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(all);
        when(mapController.showSchoolsOnMap(all)).thenReturn(List.of(catholic, tampines));
        when(mapController.displayInteractiveMap(anyList())).thenReturn(false);

        mvc.perform(get("/schools/map"))
                .andExpect(model().attribute("interactive", false))
                .andExpect(content().string(containsString("Map unavailable")))
                .andExpect(content().string(containsString("href=\"/schools/catholic-high-school\"")))
                .andExpect(content().string(containsString("id=\"district-layer\" disabled")))
                .andExpect(content().string(not(containsString("id=\"map\""))));
    }

    @Test
    @Tag("FR-MAP-02")
    @Tag("FR-MAP-06")
    @Tag("FR-MAP-07")
    @DisplayName("TC-SchoolMapUI-11: interactive map → markers embedded and the 'Show planning areas' switch enabled")
    void map_interactive() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(all);
        when(mapController.showSchoolsOnMap(all)).thenReturn(List.of(catholic, tampines));
        when(mapController.displayInteractiveMap(List.of(catholic, tampines))).thenReturn(true);

        mvc.perform(get("/schools/map"))
                .andExpect(model().attribute("interactive", true))
                .andExpect(content().string(containsString("id=\"map\"")))
                .andExpect(content().string(containsString("data-markers=")))
                .andExpect(content().string(containsString("Show planning areas")))
                .andExpect(content().string(not(containsString("id=\"district-layer\" disabled"))));
    }

    @Test
    @Tag("FR-SEARCH-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-SchoolMapUI-12: a too-long search term shows the error and the map shows all schools")
    void map_invalidTerm() throws Exception {
        String tooLong = "a".repeat(101);
        when(schoolController.searchSchools(tooLong)).thenThrow(new InvalidInputException("q", "Enter 1–100 characters"));
        when(schoolController.searchSchools(null)).thenReturn(all);
        when(mapController.showSchoolsOnMap(all)).thenReturn(List.of(catholic, tampines));

        mvc.perform(get("/schools/map").param("q", tooLong))
                .andExpect(status().isOk())
                .andExpect(model().attribute("schools", List.of(catholic, tampines)))
                .andExpect(content().string(containsString("Enter 1–100 characters")));
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-SchoolMapUI-13: the PSLE filter uses the logged-in user's primary school (DC-22); a guest has none")
    void map_psleUsesPrimarySchool() throws Exception {
        UserProfile profile = mock(UserProfile.class);
        when(profile.getPrimarySchool()).thenReturn("CATHOLIC HIGH SCHOOL (PRIMARY)");
        when(profileController.getProfile("session-1")).thenReturn(profile);
        when(profileController.getProfile("expired")).thenThrow(new NotAuthenticatedException());
        when(schoolController.searchSchools(null)).thenReturn(all);
        when(filterController.applyFilters(any(), anyList())).thenReturn(all);
        when(mapController.showSchoolsOnMap(any())).thenReturn(List.of(catholic, tampines));

        mvc.perform(get("/schools/map").param("psle", "12").cookie(new Cookie("SM_SESSION", "session-1")))
                .andExpect(status().isOk());
        mvc.perform(get("/schools/map").param("psle", "12").cookie(new Cookie("SM_SESSION", "expired")))
                .andExpect(status().isOk());
        mvc.perform(get("/schools/map").param("psle", "12"))
                .andExpect(status().isOk());

        List<List<Filter>> calls = capturedFilters(3);
        assertThat(((PsleScoreFilter) calls.get(0).getFirst()).getPrimarySchool())
                .isEqualTo("CATHOLIC HIGH SCHOOL (PRIMARY)");
        assertThat(((PsleScoreFilter) calls.get(1).getFirst()).getPrimarySchool()).isNull();
        assertThat(((PsleScoreFilter) calls.get(2).getFirst()).getPrimarySchool()).isNull();
        verify(profileController, times(2)).getProfile(anyString());
    }

    @Test
    @Tag("FR-MAP-03")
    @DisplayName("TC-SchoolMapUI-14: schools without a map location are counted and the user is told")
    void map_omittedSchools() throws Exception {
        School noLocation = TestSchools.school("no-location-school").noCoordinate().build();
        CurrentResultSet three = new CurrentResultSet(null, List.of(catholic, noLocation, tampines));
        when(schoolController.searchSchools(null)).thenReturn(three);
        when(mapController.showSchoolsOnMap(three)).thenReturn(List.of(catholic, tampines));

        mvc.perform(get("/schools/map"))
                .andExpect(model().attribute("omittedCount", 1))
                .andExpect(content().string(containsString("1 school has no map location")));
    }

    @Test
    @Tag("FR-FILTER-08")
    @DisplayName("TC-SchoolMapUI-15: each active filter is a chip whose remove link stays on the map")
    void map_chipsRemoveLinks() throws Exception {
        when(schoolController.searchSchools("high")).thenReturn(all);
        when(filterController.applyFilters(any(), anyList())).thenReturn(all);
        when(mapController.showSchoolsOnMap(any())).thenReturn(List.of(catholic, tampines));

        mvc.perform(get("/schools/map").param("q", "high").param("district", "BISHAN").param("district", "TAMPINES"))
                .andExpect(content().string(containsString("District: BISHAN")))
                .andExpect(content().string(containsString("href=\"/schools/map?q=high&amp;district=TAMPINES\"")))
                .andExpect(content().string(containsString("name=\"district\" value=\"BISHAN\"")));
    }

    @Test
    @Tag("FR-FILTER-08")
    @DisplayName("TC-SchoolMapUI-16: a filter that could not be applied is listed as not applied, not as an active chip")
    void map_rejectedFilterIsNotAnActiveChip() throws Exception {
        when(schoolController.searchSchools(null)).thenReturn(all);
        when(mapController.showSchoolsOnMap(any())).thenReturn(List.of(catholic, tampines));

        mvc.perform(get("/schools/map").param("radiusKm", "3"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("chips", List.of()))
                .andExpect(model().attribute("notAppliedChips", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(content().string(containsString("Not applied:")))
                .andExpect(content().string(containsString("href=\"/schools/map\"")));
        verify(filterController, never()).applyFilters(any(), anyList());
    }

    private List<List<Filter>> capturedFilters(int calls) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Filter>> captor = ArgumentCaptor.forClass(List.class);
        verify(filterController, times(calls)).applyFilters(any(), captor.capture());
        return captor.getAllValues();
    }

    private static MockHttpSession sessionWith(ReferenceLocation location) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ReferenceLocationStore.CURRENT, location);
        return session;
    }
}

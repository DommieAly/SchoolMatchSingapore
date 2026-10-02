package sg.schoolmatch.boundary.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.FacilityController;
import sg.schoolmatch.control.MapController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityFilterCriteria;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.NotFoundException;
import sg.schoolmatch.support.TestSchools;

/**
 * Web test of FacilityMapUI: nearby facilities as map markers around the school (DM-15; UC View Facilities on
 * Map, FR-FACMAP-01..05). FacilityController and MapController are mocks.
 */
@WebMvcTest(FacilityMapUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import(SessionCookie.class)
@ActiveProfiles("test")
class FacilityMapUITest {

    private static final String CODE = "catholic-high-school";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SchoolController schoolController;

    @MockitoBean
    private FacilityController facilityController;

    @MockitoBean
    private MapController mapController;

    // Shared layout (LayoutModelAdvice, AuthInterceptor); ProfileController in case the layout greets the user.
    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    @MockitoBean
    private ProfileController profileController;

    private final School school = TestSchools.school(CODE).name("CATHOLIC HIGH SCHOOL")
            .at(1.354525, 103.844901).build();
    private final Facility library = facility("lib-1", "BISHAN PUBLIC LIBRARY", FacilityType.LIBRARY,
            new Coordinate(1.3500, 103.8484));
    private final Facility tuition = facility("tc-1", "BRIGHT TUITION CENTRE", FacilityType.TUITION_CENTRE,
            new Coordinate(1.3560, 103.8460));

    @Test
    @Tag("FR-FACMAP-01")
    @Tag("FR-FACMAP-03")
    @Tag("FR-FACILITY-02")
    @DisplayName("TC-FacilityMapUI-01: the school and each facility get a marker; facility markers link to the details page")
    void map_schoolAndFacilities() throws Exception {
        when(schoolController.getSchoolDetails(CODE)).thenReturn(school);
        when(facilityController.filterFacilities(eq(school), any())).thenReturn(List.of(library, tuition));
        when(mapController.showFacilitiesOnMap(List.of(library, tuition))).thenReturn(List.of(library, tuition));

        mvc.perform(get("/schools/" + CODE + "/facilities/map"))
                .andExpect(status().isOk())
                .andExpect(view().name("facility-map"))
                .andExpect(model().attribute("markersJson", containsString("\"id\":\"" + CODE + "\"")))
                .andExpect(model().attribute("markersJson",
                        containsString("\"href\":\"/facilities/lib-1?from=" + CODE + "\"")))
                .andExpect(model().attribute("markersJson", containsString("\"kind\":\"tuition-centre\"")))
                .andExpect(content().string(containsString("BISHAN PUBLIC LIBRARY")))
                .andExpect(content().string(containsString("Tuition centre")))
                .andExpect(content().string(containsString("0.6 km")))
                .andExpect(content().string(not(containsString("Not built yet"))));

        FacilityFilterCriteria criteria = capturedCriteria();
        assertThat(criteria.getRadiusKm()).isEqualTo(FacilityFilterCriteria.DEFAULT_RADIUS_KM);
        assertThat(criteria.isEmpty()).isTrue();
    }

    @Test
    @Tag("FR-FACMAP-04")
    @Tag("FR-FACFILTER-01")
    @DisplayName("TC-FacilityMapUI-02: the map uses the list's type and radius; 'Back to the list' keeps them")
    void map_keepsFilters() throws Exception {
        when(schoolController.getSchoolDetails(CODE)).thenReturn(school);
        when(facilityController.filterFacilities(eq(school), any())).thenReturn(List.of(library));
        when(mapController.showFacilitiesOnMap(List.of(library))).thenReturn(List.of(library));

        mvc.perform(get("/schools/" + CODE + "/facilities/map").param("type", "LIBRARY").param("radiusKm", "1"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("listUrl", "/schools/" + CODE + "/facilities?type=LIBRARY&radiusKm=1"))
                .andExpect(content().string(containsString(
                        "href=\"/schools/" + CODE + "/facilities?type=LIBRARY&amp;radiusKm=1\"")));

        FacilityFilterCriteria criteria = capturedCriteria();
        assertThat(criteria.getRadiusKm()).isEqualTo(1);
        assertThat(criteria.getSelectedTypes()).containsExactly(FacilityType.LIBRARY);
    }

    @Test
    @Tag("FR-FACFILTER-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FacilityMapUI-03: a radius or type that is not offered is shown as an error; the default is used")
    void map_invalidCriteria() throws Exception {
        when(schoolController.getSchoolDetails(CODE)).thenReturn(school);
        when(facilityController.filterFacilities(eq(school), any())).thenReturn(List.of());

        mvc.perform(get("/schools/" + CODE + "/facilities/map").param("radiusKm", "5").param("type", "POOL"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("fieldErrors", hasEntry("radiusKm", "Choose 1, 2 or 3 km")))
                .andExpect(model().attribute("fieldErrors", hasEntry("type", "Choose library or tuition centre")))
                .andExpect(content().string(containsString("Choose 1, 2 or 3 km")));

        FacilityFilterCriteria criteria = capturedCriteria();
        assertThat(criteria.getRadiusKm()).isEqualTo(FacilityFilterCriteria.DEFAULT_RADIUS_KM);
        assertThat(criteria.isEmpty()).isTrue();
    }

    @Test
    @Tag("FR-FACMAP-05")
    @DisplayName("TC-FacilityMapUI-04: facilities without a valid location are left out and the page says how many (AF-1)")
    void map_omittedFacilities() throws Exception {
        Facility nowhere = facility("lib-9", "NOWHERE LIBRARY", FacilityType.LIBRARY, null);
        when(schoolController.getSchoolDetails(CODE)).thenReturn(school);
        when(facilityController.filterFacilities(eq(school), any())).thenReturn(List.of(library, nowhere));
        when(mapController.showFacilitiesOnMap(List.of(library, nowhere))).thenReturn(List.of(library));

        mvc.perform(get("/schools/" + CODE + "/facilities/map"))
                .andExpect(model().attribute("omittedCount", 1))
                .andExpect(content().string(containsString("1 facility has no map location")));
    }

    @Test
    @Tag("FR-FACILITY-06")
    @DisplayName("TC-FacilityMapUI-05: no facility → 'No libraries or tuition centres to show' with the school marker kept (AF-2)")
    void map_noFacilities() throws Exception {
        when(schoolController.getSchoolDetails(CODE)).thenReturn(school);
        when(facilityController.filterFacilities(eq(school), any())).thenReturn(List.of());

        mvc.perform(get("/schools/" + CODE + "/facilities/map"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("markersJson", containsString("\"id\":\"" + CODE + "\"")))
                .andExpect(content().string(containsString("No libraries or tuition centres to show")));
    }

    @Test
    @Tag("FR-FACILITY-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FacilityMapUI-06: the places service failing → 'temporarily unavailable', the school still shows (EX-1)")
    void map_serviceDown() throws Exception {
        when(schoolController.getSchoolDetails(CODE)).thenReturn(school);
        when(facilityController.filterFacilities(eq(school), any()))
                .thenThrow(new ExternalServiceUnavailableException("Google Places", null));

        mvc.perform(get("/schools/" + CODE + "/facilities/map"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nearby facility information is temporarily unavailable")))
                .andExpect(model().attribute("markersJson", containsString("\"id\":\"" + CODE + "\"")));
    }

    @Test
    @Tag("FR-FACILITY-02")
    @DisplayName("TC-FacilityMapUI-07: an unknown school is a 404 page")
    void map_unknownSchool() throws Exception {
        when(schoolController.getSchoolDetails("nope")).thenThrow(new NotFoundException("No school with code 'nope'"));

        mvc.perform(get("/schools/nope/facilities/map"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Tag("FR-FACILITY-02")
    @Tag("NFR-DATA-02")
    @DisplayName("TC-FacilityMapUI-08: a school without a map location cannot be the centre: message, no facility search")
    void map_schoolWithoutLocation() throws Exception {
        School noLocation = TestSchools.school("no-location-school").noCoordinate().build();
        when(schoolController.getSchoolDetails("no-location-school")).thenReturn(noLocation);

        mvc.perform(get("/schools/no-location-school/facilities/map"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("has no map location")));
        verifyNoInteractions(facilityController);
    }

    @Test
    @Tag("FR-FACMAP-02")
    @DisplayName("TC-FacilityMapUI-09: the map is asked for with the school and the facilities that have a location")
    void map_interactiveDecision() throws Exception {
        when(schoolController.getSchoolDetails(CODE)).thenReturn(school);
        when(facilityController.filterFacilities(eq(school), any())).thenReturn(List.of(library));
        when(mapController.showFacilitiesOnMap(List.of(library))).thenReturn(List.of(library));
        when(mapController.displayInteractiveMap(anyList())).thenReturn(true);

        mvc.perform(get("/schools/" + CODE + "/facilities/map"))
                .andExpect(model().attribute("interactive", true))
                .andExpect(content().string(containsString("id=\"map\"")));
        verify(mapController).displayInteractiveMap(List.of(school, library));
    }

    private FacilityFilterCriteria capturedCriteria() {
        ArgumentCaptor<FacilityFilterCriteria> captor = ArgumentCaptor.forClass(FacilityFilterCriteria.class);
        verify(facilityController).filterFacilities(eq(school), captor.capture());
        return captor.getValue();
    }

    private static Facility facility(String placeId, String name, FacilityType type, Coordinate at) {
        Facility facility = new Facility(placeId, name, type);
        facility.setCoordinate(at);
        facility.setAddress("1 TEST ROAD");
        return facility;
    }
}

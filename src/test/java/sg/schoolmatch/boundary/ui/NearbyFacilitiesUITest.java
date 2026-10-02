package sg.schoolmatch.boundary.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
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
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityFilterCriteria;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotFoundException;
import sg.schoolmatch.support.TestSchools;

/**
 * Web test of the boundary class NearbyFacilitiesUI (DM-12/13; View Nearby Facilities, FR-FACILITY-01..06,
 * FR-FACFILTER-01..04). The controls are mocks; the test checks the control call, the model and the HTML.
 */
@WebMvcTest(NearbyFacilitiesUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import(SessionCookie.class)
@ActiveProfiles("test")
class NearbyFacilitiesUITest {

    private static final String CODE = "catholic-high-school";
    private static final String RADIUS_MESSAGE = "Choose 1, 2 or 3 km";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SchoolController schoolController;

    @MockitoBean
    private FacilityController facilityController;

    // Needed by the shared layout (LayoutModelAdvice, AuthInterceptor), not by this page.
    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    private final School school = TestSchools.school(CODE).name("CATHOLIC HIGH SCHOOL").build();   // at Bishan
    private final Facility library = facility("lib-1", "Bishan Public Library", FacilityType.LIBRARY, 0.5);
    private final Facility tuition = facility("tc-1", "Bright Minds Tuition", FacilityType.TUITION_CENTRE, 1.2);

    @BeforeEach
    void setUp() {
        when(schoolController.getSchoolDetails(CODE)).thenReturn(school);
    }

    @Test
    @Tag("FR-FACILITY-01")
    @Tag("FR-FACILITY-04")
    @DisplayName("TC-NearbyUI-01: first visit = all types within 3 km; each row shows name, type, address, distance and links")
    void firstVisit_listsFacilitySummaries() throws Exception {
        when(facilityController.filterFacilities(eq(school), any())).thenReturn(List.of(library, tuition));

        mvc.perform(get("/schools/" + CODE + "/facilities"))
                .andExpect(status().isOk())
                .andExpect(view().name("nearby-facilities"))
                .andExpect(model().attribute("radiusKm", 3))
                .andExpect(model().attribute("facilities", List.of(library, tuition)))
                .andExpect(content().string(containsString("Bishan Public Library")))
                .andExpect(content().string(containsString("Tuition centre")))
                .andExpect(content().string(containsString("Bishan Public Library address")))
                .andExpect(content().string(containsString("0.5 km")))
                .andExpect(content().string(containsString("1.2 km")))
                .andExpect(content().string(containsString("href=\"/facilities/lib-1?from=catholic-high-school\"")))
                .andExpect(content().string(containsString(
                        "href=\"/directions?to=facility:lib-1&amp;from=/schools/catholic-high-school/facilities\"")))
                .andExpect(content().string(containsString(
                        "href=\"/schools/catholic-high-school/facilities/map?radiusKm=3\"")))
                .andExpect(content().string(containsString("Demo data (stub)")))
                .andExpect(content().string(not(containsString("Not built yet"))));

        ArgumentCaptor<FacilityFilterCriteria> criteria = ArgumentCaptor.forClass(FacilityFilterCriteria.class);
        verify(facilityController).filterFacilities(eq(school), criteria.capture());
        assertThat(criteria.getValue().isEmpty()).as("no type = every type").isTrue();
        assertThat(criteria.getValue().getRadiusKm()).isEqualTo(3);
    }

    @Test
    @Tag("FR-FACFILTER-01")
    @Tag("FR-FACFILTER-03")
    @DisplayName("TC-NearbyUI-02: type + radius are passed on; the active filters and Clear all are shown")
    void filtersApplied() throws Exception {
        when(facilityController.filterFacilities(eq(school), any())).thenReturn(List.of(library));

        mvc.perform(get("/schools/" + CODE + "/facilities").param("type", "LIBRARY").param("radiusKm", "1"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("radiusKm", 1))
                .andExpect(model().attribute("selectedTypes", Set.of(FacilityType.LIBRARY)))
                .andExpect(model().attribute("filtersActive", true))
                .andExpect(content().string(containsString("within 1 km")))
                .andExpect(content().string(containsString("Clear all")))
                .andExpect(content().string(containsString(
                        "href=\"/schools/catholic-high-school/facilities/map?type=LIBRARY&amp;radiusKm=1\"")));

        verify(facilityController).filterFacilities(eq(school),
                argThat(c -> c.getRadiusKm() == 1 && c.getSelectedTypes().equals(Set.of(FacilityType.LIBRARY))));
    }

    @Test
    @Tag("FR-FACFILTER-01")
    @DisplayName("TC-NearbyUI-09: the type checkboxes and the \"Showing\" badges are labelled Libraries / Tuition centres")
    void typeCheckboxesAndBadgesHaveLabels() throws Exception {
        when(facilityController.filterFacilities(eq(school), any())).thenReturn(List.of(library));

        String html = mvc.perform(get("/schools/" + CODE + "/facilities").param("type", "LIBRARY"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("for=\"type-LIBRARY\">Libraries</label>")
                .contains("for=\"type-TUITION_CENTRE\">Tuition centres</label>")
                .doesNotContain("for=\"type-LIBRARY\"></label>")
                .doesNotContain("<span class=\"badge text-bg-light border\"></span>");
    }

    @Test
    @Tag("FR-FACFILTER-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-NearbyUI-03: radius 5 or \"abc\" → \"Choose 1, 2 or 3 km\" and the 3 km list")
    void invalidRadius_showsMessageAndDefaultList() throws Exception {
        when(facilityController.filterFacilities(eq(school), argThat(c -> c != null && c.getRadiusKm() != 3)))
                .thenThrow(new InvalidInputException("radiusKm", RADIUS_MESSAGE));
        when(facilityController.filterFacilities(eq(school), argThat(c -> c != null && c.getRadiusKm() == 3)))
                .thenReturn(List.of(library));

        for (String radius : List.of("5", "abc")) {
            mvc.perform(get("/schools/" + CODE + "/facilities").param("radiusKm", radius))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("fieldErrors", Map.of("radiusKm", RADIUS_MESSAGE)))
                    .andExpect(model().attribute("radiusKm", 3))
                    .andExpect(model().attribute("facilities", List.of(library)))
                    .andExpect(content().string(containsString(RADIUS_MESSAGE)));
        }
    }

    @Test
    @Tag("NFR-USE-03")
    @DisplayName("TC-NearbyUI-04: an unknown type is reported and ignored")
    void unknownType_reported() throws Exception {
        when(facilityController.filterFacilities(eq(school), any())).thenReturn(List.of(library, tuition));

        mvc.perform(get("/schools/" + CODE + "/facilities").param("type", "SWIMMING_POOL"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("fieldErrors", Map.of("type", "Choose libraries or tuition centres")))
                .andExpect(model().attribute("facilities", List.of(library, tuition)));
    }

    @Test
    @Tag("FR-FACILITY-06")
    @DisplayName("TC-NearbyUI-05: nothing nearby → \"No libraries or tuition centres found near <school>\" (AF-1)")
    void noFacilities_message() throws Exception {
        when(facilityController.filterFacilities(eq(school), any())).thenReturn(List.of());

        mvc.perform(get("/schools/" + CODE + "/facilities"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "No libraries or tuition centres found near CATHOLIC HIGH SCHOOL.")))
                .andExpect(content().string(not(containsString("View on map"))));
    }

    @Test
    @Tag("FR-FACFILTER-02")
    @DisplayName("TC-NearbyUI-06: nothing matches the filters → the message names them (T-42)")
    void noFacilitiesForFilters_message() throws Exception {
        when(facilityController.filterFacilities(eq(school), any())).thenReturn(List.of());

        mvc.perform(get("/schools/" + CODE + "/facilities").param("type", "TUITION_CENTRE").param("radiusKm", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "No tuition centres found within 1 km of CATHOLIC HIGH SCHOOL.")));
    }

    @Test
    @Tag("FR-FACILITY-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-NearbyUI-07: Google down → \"Nearby facility information is temporarily unavailable\" + school link (EX-1)")
    void serviceDown_message() throws Exception {
        when(facilityController.filterFacilities(any(), any()))
                .thenThrow(new ExternalServiceUnavailableException("Google Places", null));

        mvc.perform(get("/schools/" + CODE + "/facilities"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("serviceUnavailable", true))
                .andExpect(content().string(containsString("Nearby facility information is temporarily unavailable")))
                .andExpect(content().string(containsString("href=\"/schools/catholic-high-school\"")));
    }

    @Test
    @Tag("FR-FACILITY-01")
    @DisplayName("TC-NearbyUI-08: unknown school code → 404 page")
    void unknownSchool_404() throws Exception {
        when(schoolController.getSchoolDetails("no-such-school")).thenThrow(new NotFoundException("No school"));

        mvc.perform(get("/schools/no-such-school/facilities"))
                .andExpect(status().isNotFound());
    }

    /** A facility {@code km} north of Bishan. */
    private static Facility facility(String id, String name, FacilityType type, double km) {
        Facility f = new Facility(id, name, type);
        f.setCoordinate(new Coordinate(TestSchools.BISHAN.getLatitude() + km / 111.195,
                TestSchools.BISHAN.getLongitude()));
        f.setAddress(name + " address");
        return f;
    }
}

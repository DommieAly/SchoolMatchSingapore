package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.control.DirectionsController;
import sg.schoolmatch.control.FacilityController;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.support.TestSchools;

/**
 * Flow test of View Nearby Facilities → View Facility Details, and of the route control, with the whole
 * application in the {@code test} profile (stub Google, fixture snapshot): school page → nearby list →
 * details page, from URL to rendered page (FR-FACILITY-01..05, FR-FACDETAIL-01..03, FR-ROUTE-05..07).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FacilityFlowTest {

    private static final String CODE = "catholic-high-school";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FacilityController facilityController;

    @Autowired
    private DirectionsController directionsController;

    @Autowired
    private SchoolController schoolController;

    @Test
    @Tag("FR-FACILITY-01")
    @Tag("FR-FACDETAIL-01")
    @DisplayName("TC-FacilityFlow-01: school → nearby facilities (stub, nearest first) → one facility's details")
    void nearbyThenDetails() throws Exception {
        mvc.perform(get("/schools/" + CODE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/schools/" + CODE + "/facilities\"")));

        mvc.perform(get("/schools/" + CODE + "/facilities"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("(stub) Nearby Library 1")))
                .andExpect(content().string(containsString("(stub) Nearby Tuition Centre 2")))
                .andExpect(content().string(containsString("Demo data (stub)")));

        List<Facility> nearby = facilityController.getNearbyFacilities(schoolController.getSchoolDetails(CODE));
        assertThat(nearby).hasSize(4);
        Facility nearest = nearby.getFirst();

        mvc.perform(get("/facilities/" + nearest.getPlaceId()).param("from", CODE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(nearest.getName())))
                .andExpect(content().string(containsString("km from CATHOLIC HIGH SCHOOL")))
                .andExpect(content().string(containsString("Back to facilities")));
    }

    @Test
    @Tag("FR-FACFILTER-01")
    @DisplayName("TC-FacilityFlow-02: libraries within 1 km only; an unknown facility id is a 404 page")
    void filterAndNotFound() throws Exception {
        mvc.perform(get("/schools/" + CODE + "/facilities").param("type", "LIBRARY").param("radiusKm", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("(stub) Nearby Library 1")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("(stub) Nearby Library 2"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Tuition Centre 1"))));

        mvc.perform(get("/facilities/unknown-place-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Tag("FR-ROUTE-05")
    @Tag("FR-ROUTE-07")
    @DisplayName("TC-FacilityFlow-03: directions to a school with the stub: 3 steps, a polyline, origin and destination set")
    void stubDirections() {
        School school = schoolController.getSchoolDetails(CODE);
        ReferenceLocation home = new ReferenceLocation(TestSchools.TAMPINES, LocationSource.MANUAL_ENTRY, "Tampines");

        Route route = directionsController.getDirections(home, school, TravelMode.TRANSIT);

        assertThat(route.isAvailable()).isTrue();
        assertThat(route.getSteps()).hasSize(3);
        assertThat(route.getEncodedPolyline()).isNotBlank();
        assertThat(route.getOrigin()).isSameAs(home);
        assertThat(route.getDestination()).isSameAs(school);
        assertThat(directionsController.getCommuteTimes(home, List.of(school), TravelMode.WALK))
                .singleElement().satisfies(r -> assertThat(r.getDurationSeconds()).isPositive());
    }
}

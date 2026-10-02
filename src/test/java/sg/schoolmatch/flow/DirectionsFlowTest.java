package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
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
import sg.schoolmatch.boundary.ui.DirectionsUI.StepView;
import sg.schoolmatch.boundary.ui.FacilityMapUI.FacilityRow;

/**
 * Get Directions and View Facilities on Map end to end (FR-ROUTE-01..04/07, FR-FACMAP-01..05): the real controls
 * with the offline stubs (OneMap and Google Maps Platform) and the fixture snapshot. The stub route is a
 * straight-line estimate with three "(stub)" steps.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DirectionsFlowTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @Tag("FR-ROUTE-01")
    @Tag("FR-ROUTE-07")
    @DisplayName("TC-DirectionsFlow-01: set a starting point by postal code, then walk to a school: distance, time and steps")
    void schoolRoute() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String inputPage = "/directions?to=school:guangyang-secondary-school";

        mvc.perform(get(inputPage + "&mode=WALK").session(session))
                .andExpect(view().name("directions-input"))
                .andExpect(content().string(containsString("Set a starting point first")));

        mvc.perform(post("/location").param("address", "579767").param("returnTo", inputPage).session(session));

        MvcResult result = mvc.perform(get(inputPage + "&mode=WALK").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("directions"))
                .andExpect(model().attribute("distanceKm", notNullValue()))
                .andExpect(model().attribute("durationText", containsString("min")))
                .andExpect(content().string(containsString("GUANGYANG SECONDARY SCHOOL")))
                .andExpect(content().string(not(containsString("Not built yet"))))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<StepView> steps = (List<StepView>) result.getModelAndView().getModel().get("steps");
        assertThat(steps).isNotEmpty();
        assertThat(steps).extracting(StepView::number).isSorted().first().isEqualTo(1);
    }

    @Test
    @Tag("FR-ROUTE-04")
    @DisplayName("TC-DirectionsFlow-02: each travel mode gives a route; public transport is labelled as such")
    void everyMode() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String inputPage = "/directions?to=school:tampines-secondary-school";
        mvc.perform(post("/location").param("address", "579767").param("returnTo", inputPage).session(session));

        for (String mode : new String[] {"WALK", "DRIVE", "TRANSIT"}) {
            mvc.perform(get(inputPage + "&mode=" + mode).session(session))
                    .andExpect(view().name("directions"));
        }
        mvc.perform(get(inputPage + "&mode=TRANSIT").session(session))
                .andExpect(model().attribute("modeLabel", "Public transport"));
    }

    @Test
    @Tag("FR-FACMAP-01")
    @Tag("FR-ROUTE-03")
    @DisplayName("TC-DirectionsFlow-03: facility map → a facility's directions page names it and Cancel returns to the map")
    void facilityMapThenDirections() throws Exception {
        String mapPage = "/schools/catholic-high-school/facilities/map";
        MvcResult map = mvc.perform(get(mapPage))
                .andExpect(status().isOk())
                .andExpect(view().name("facility-map"))
                .andExpect(content().string(containsString("(stub)")))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<FacilityRow> rows = (List<FacilityRow>) map.getModelAndView().getModel().get("facilities");
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> assertThat(row.detailsUrl()).endsWith("?from=catholic-high-school"));
        String placeId = rows.getFirst().facility().getPlaceId();

        mvc.perform(get("/directions").param("to", "facility:" + placeId).param("from", mapPage))
                .andExpect(status().isOk())
                .andExpect(view().name("directions-input"))
                .andExpect(model().attribute("cancelUrl", mapPage))
                .andExpect(content().string(containsString(rows.getFirst().facility().getName())));
    }

    @Test
    @Tag("FR-FACFILTER-01")
    @DisplayName("TC-DirectionsFlow-04: the facility map follows the list's type filter")
    void facilityMapTypeFilter() throws Exception {
        MvcResult map = mvc.perform(get("/schools/catholic-high-school/facilities/map").param("type", "LIBRARY"))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<FacilityRow> rows = (List<FacilityRow>) map.getModelAndView().getModel().get("facilities");
        assertThat(rows).isNotEmpty().allSatisfy(row -> assertThat(row.typeLabel()).isEqualTo("Library"));
    }
}

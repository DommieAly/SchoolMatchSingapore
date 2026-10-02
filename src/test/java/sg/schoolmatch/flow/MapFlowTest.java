package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.boundary.ui.support.FilterParams;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The map pages against the fixture snapshot (10 schools in BISHAN, JURONG WEST, TAMPINES; no Google key in the
 * test profile, so the page shows the list and "Map unavailable"). FR-MAP-01..07, DC-16.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MapFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-MapFlow-01: /api/districts serves the snapshot's planning areas as GeoJSON")
    void apiDistricts() throws Exception {
        String body = mvc.perform(get("/api/districts"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/geo+json")))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = jsonMapper.readTree(body);
        assertThat(json.get("type").asString()).isEqualTo("FeatureCollection");
        List<String> names = new ArrayList<>();
        json.get("features").forEach(f -> names.add(f.get("properties").get("name").asString()));
        assertThat(names).containsExactlyInAnyOrder("BISHAN", "JURONG WEST", "TAMPINES");
        json.get("features").forEach(f -> assertThat(f.get("geometry").get("type").asString()).isEqualTo("Polygon"));
    }

    @Test
    @Tag("FR-MAP-01")
    @Tag("FR-MAP-03")
    @DisplayName("TC-MapFlow-02: without a key the map page lists every school with a location and says the map is unavailable")
    void schoolMap_allSchools() throws Exception {
        mvc.perform(get("/schools/map"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("schools", hasSize(10)))
                .andExpect(model().attribute("interactive", false))
                .andExpect(content().string(containsString("Map unavailable")))
                .andExpect(content().string(containsString("href=\"/schools/catholic-high-school\"")));
    }

    @Test
    @Tag("FR-MAP-01")
    @DisplayName("TC-MapFlow-03: the map page follows the search term (View on map from the details page)")
    void schoolMap_byName() throws Exception {
        mvc.perform(get("/schools/map").param("q", "CATHOLIC HIGH SCHOOL"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("schools", hasSize(1)))
                .andExpect(model().attribute("resultsUrl", "/schools?q=CATHOLIC%20HIGH%20SCHOOL"));
    }

    @Test
    @Tag("FR-MAP-01")
    @Tag("FR-FILTER-02")
    @DisplayName("TC-MapFlow-04: the markers follow the district filter of the result list")
    void schoolMap_districtFilter() throws Exception {
        mvc.perform(get("/schools/map").param("district", "BISHAN"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("schools", hasSize(4)))
                .andExpect(content().string(containsString("District: BISHAN")));
    }

    @Test
    @Tag("FR-MAP-01")
    @Tag("FR-FILTER-05")
    @DisplayName("TC-MapFlow-05: a distance filter needs a starting point; with one (Catholic High, 579767) it keeps schools within 1 km")
    void schoolMap_distanceFilter() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(get("/schools/map").param("radiusKm", "1").session(session))
                .andExpect(model().attribute("schools", hasSize(10)))
                .andExpect(content().string(containsString(FilterParams.LOCATION_MESSAGE)));

        mvc.perform(post("/location").param("address", "579767").param("returnTo", "/schools/map?radiusKm=1")
                .session(session));

        mvc.perform(get("/schools/map").param("radiusKm", "1").session(session))
                .andExpect(model().attribute("schools", hasSize(1)))
                .andExpect(content().string(containsString("CATHOLIC HIGH SCHOOL")));
        mvc.perform(get("/schools/map").param("radiusKm", "3").session(session))
                .andExpect(model().attribute("schools", hasSize(4)));
    }
}

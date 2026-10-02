package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.ModelAndView;
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.control.DirectionsController;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.common.Place;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalServiceUnavailableException;

/**
 * Flow test of the use case Filter Schools (UC #08): GET /schools with filter parameters, from URL to rendered page,
 * against the fixture snapshot {@code snapshot-mini} (10 schools, TEST PSLE ranges).
 * <p>
 * Every row of {@code testcases/TC-FILTER.csv} runs with the starting point Bishan MRT in the HTTP session.
 * Only the routing service is replaced: {@link DirectionsController} answers 4 minutes per straight-line km, so the
 * travel-time rows do not depend on Google. In the {@code input} column, {@code a=1&b=2} are request parameters
 * (spaces allowed). {@code expected} is one of: a result count; {@code error <field>} (that field is reported);
 * {@code first <code>} (the first school shown); {@code clear <count>} (the result count after "Clear all").
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FilterFlowTest {

    private static final ReferenceLocation BISHAN_MRT =
            new ReferenceLocation(new Coordinate(1.3510, 103.8484), LocationSource.MANUAL_ENTRY, "BISHAN MRT");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DirectionsController directionsController;

    @BeforeEach
    void fakeRouting() {
        when(directionsController.getCommuteTimes(any(), anyList(), any())).thenAnswer(call -> {
            ReferenceLocation origin = call.getArgument(0);
            List<? extends Place> destinations = call.getArgument(1);
            TravelMode mode = call.getArgument(2);
            return destinations.stream().map(place -> {
                int seconds = (int) Math.ceil(place.distanceTo(origin.getCoordinate()) * 240);   // 4 min per km
                return new Route(mode, 1000, seconds, null, List.of());
            }).toList();
        });
    }

    @ParameterizedTest(name = "{0}: {3} → {4}")
    @CsvFileSource(resources = "/testcases/TC-FILTER.csv", numLinesToSkip = 1)
    @Tag("FR-FILTER-01")
    @Tag("FR-FILTER-02")
    @Tag("FR-FILTER-03")
    @Tag("FR-FILTER-05")
    @Tag("FR-FILTER-06")
    @Tag("FR-FILTER-07")
    @Tag("FR-FILTER-09")
    @Tag("FR-SEARCH-06")
    @DisplayName("TC-FILTER-xx-yy: filter cases from TC-FILTER.csv")
    void filterCases(String tcId, String requirement, String technique, String input, String expected)
            throws Exception {
        MvcResult result = mvc.perform(withParams(get("/schools"), input).session(sessionWithStart()))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> model = modelOf(result);

        if (expected.startsWith("error ")) {
            assertThat(fieldErrors(model)).as(tcId).containsKey(expected.substring("error ".length()));
        } else if (expected.startsWith("first ")) {
            assertThat(schools(model)).as(tcId).isNotEmpty();
            assertThat(schools(model).getFirst().getSchoolCode()).as(tcId).isEqualTo(expected.substring(6));
        } else if (expected.startsWith("clear ")) {
            String clearAllUrl = (String) model.get("clearAllUrl");
            MvcResult cleared = mvc.perform(get(clearAllUrl).session(sessionWithStart()))
                    .andExpect(status().isOk()).andReturn();
            assertThat(modelOf(cleared).get("totalCount")).as(tcId)
                    .isEqualTo(Integer.parseInt(expected.substring(6)));
            assertThat(modelOf(cleared).get("chips")).as(tcId).asList().isEmpty();
        } else {
            assertThat(fieldErrors(model)).as(tcId).isEmpty();
            assertThat(model.get("totalCount")).as(tcId).isEqualTo(Integer.parseInt(expected));
        }
    }

    @Test
    @Tag("FR-FILTER-04")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FILTER-04-01: a distance filter without a starting point asks for one and keeps all schools")
    void distanceWithoutStartingPoint() throws Exception {
        mvc.perform(get("/schools").param("radiusKm", "3"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 10))
                .andExpect(content().string(containsString(
                        "Set a starting point to use distance or travel-time filters")));
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-FILTER-06-07: when routing fails the other filters still apply and the page says so (EX-1)")
    void travelServiceDown() throws Exception {
        when(directionsController.getCommuteTimes(any(), anyList(), any()))
                .thenThrow(new ExternalServiceUnavailableException("Google Routes", null));

        mvc.perform(get("/schools").param("district", "BISHAN").param("travel", "1")
                        .param("mode", "TRANSIT").param("maxMin", "15").session(sessionWithStart()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 4))
                .andExpect(content().string(containsString("Travel-time filter is temporarily unavailable")));
    }

    @Test
    @Tag("FR-FILTER-08")
    @DisplayName("TC-FILTER-08-01: the results page shows each active filter with a remove link, and Clear all")
    void chipsAndClearAll() throws Exception {
        mvc.perform(get("/schools").param("q", "secondary").param("cca", "BOWLING").param("cca", "RUGBY"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 2))
                .andExpect(content().string(containsString("CCA: BOWLING")))
                .andExpect(content().string(containsString("href=\"/schools?q=secondary&amp;cca=RUGBY\"")))
                .andExpect(content().string(containsString("href=\"/schools?q=secondary\"")));
    }

    @Test
    @Tag("FR-FILTER-03")
    @Tag("NFR-DATA-03")
    @DisplayName("TC-FILTER-03-10: the PSLE filter says how many schools have no PSLE data, that PG3 was used, and that ranges are test values")
    void psleNotes() throws Exception {
        mvc.perform(get("/schools").param("psle", "4"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 9))
                .andExpect(content().string(containsString("1 school has no PSLE data and is hidden")))
                .andExpect(content().string(containsString("posting group 3 is used")))
                .andExpect(content().string(containsString("TEST VALUES – not MOE data")));
    }

    @Test
    @Tag("FR-FILTER-05")
    @DisplayName("TC-FILTER-05-05: with a starting point each card shows the distance, and sorting by distance is offered")
    void distanceOnCards() throws Exception {
        mvc.perform(get("/schools").param("q", "catholic").session(sessionWithStart()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("0.6 km")))
                .andExpect(content().string(containsString("sort=DISTANCE_ASC")));
    }

    @Test
    @Tag("FR-FILTER-05")
    @DisplayName("TC-FILTER-05-06: without a starting point sorting by distance is not offered")
    void noDistanceSortWithoutStart() throws Exception {
        mvc.perform(get("/schools").param("q", "catholic"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("sort=DISTANCE_ASC"))));
    }

    @Test
    @Tag("FR-FILTER-01")
    @Tag("FR-FILTER-02")
    @DisplayName("TC-FILTER-01-01: the filter page lists the dataset's values and refills the current parameters")
    void filterPage() throws Exception {
        mvc.perform(get("/schools/filter").param("q", "secondary").param("district", "TAMPINES").param("psle", "12")
                        .session(sessionWithStart()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("JURONG WEST")))
                .andExpect(content().string(containsString("value=\"TAMPINES\" checked=\"checked\"")))
                .andExpect(content().string(containsString("value=\"12\"")))
                .andExpect(content().string(not(containsString("Not built yet"))))
                .andExpect(model().attribute("returnTo", "/schools/filter?q=secondary&district=TAMPINES&psle=12"));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static MockHttpSession sessionWithStart() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ReferenceLocationStore.CURRENT, BISHAN_MRT);
        return session;
    }

    /** "a=1&b=two words" → .param("a", "1").param("b", "two words"). */
    private static MockHttpServletRequestBuilder withParams(MockHttpServletRequestBuilder request, String input) {
        if (input == null || input.isBlank()) {
            return request;
        }
        for (String pair : input.split("&")) {
            int eq = pair.indexOf('=');
            request.param(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return request;
    }

    private static Map<String, Object> modelOf(MvcResult result) {
        ModelAndView mav = result.getModelAndView();
        assertThat(mav).isNotNull();
        return mav.getModel();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> fieldErrors(Map<String, Object> model) {
        Object errors = model.get("fieldErrors");
        return errors == null ? Map.of() : (Map<String, String>) errors;
    }

    @SuppressWarnings("unchecked")
    private static List<School> schools(Map<String, Object> model) {
        return (List<School>) model.get("schools");
    }
}

package sg.schoolmatch.boundary.ui;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
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
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.FilterController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.search.AttributeCategory;

/**
 * Web test of the boundary class SchoolFilterUI (DM-10 SchoolFilter; use case Filter Schools, FR-FILTER-01..09):
 * the form lists the dataset's values, is refilled from the URL, highlights invalid values and keeps the
 * starting point. FilterController is a mock.
 */
@WebMvcTest(SchoolFilterUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import({SessionCookie.class, ReferenceLocationStore.class})
@ActiveProfiles("test")
class SchoolFilterUITest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FilterController filterController;

    // Needed by the shared layout (LayoutModelAdvice, AuthInterceptor), not by this page.
    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    @BeforeEach
    void options() {
        when(filterController.getFilterOptions(AttributeCategory.SCHOOL_TYPE))
                .thenReturn(new TreeSet<>(List.of("GOVERNMENT SCHOOL", "GOVERNMENT-AIDED SCH")));
        when(filterController.getFilterOptions(AttributeCategory.PROGRAMME))
                .thenReturn(new TreeSet<>(List.of("Mobile Robotics")));
        when(filterController.getFilterOptions(AttributeCategory.CCA))
                .thenReturn(new TreeSet<>(List.of("BOWLING", "RUGBY")));
        when(filterController.getFilterOptions(AttributeCategory.DISTRICT))
                .thenReturn(new TreeSet<>(List.of("BISHAN", "TAMPINES")));
    }

    @Test
    @Tag("FR-FILTER-02")
    @DisplayName("TC-FILTER-02-05: the filter page shows one checkbox per dataset value, in four groups, and no placeholder")
    void showsOptions() throws Exception {
        mvc.perform(get("/schools/filter"))
                .andExpect(status().isOk())
                .andExpect(view().name("school-filter"))
                .andExpect(content().string(containsString("School type")))
                .andExpect(content().string(containsString("value=\"GOVERNMENT-AIDED SCH\"")))
                .andExpect(content().string(containsString("value=\"Mobile Robotics\"")))
                .andExpect(content().string(containsString("value=\"RUGBY\"")))
                .andExpect(content().string(containsString("value=\"TAMPINES\"")))
                .andExpect(content().string(containsString("SchoolFilterUI")))      // the template keeps its DM comment
                .andExpect(content().string(not(containsString("Not built yet"))));
    }

    @Test
    @Tag("FR-FILTER-01")
    @DisplayName("TC-FILTER-01-02: the form is refilled from the URL (checkboxes, PSLE, PG, distance, travel time)")
    void refillsFromUrl() throws Exception {
        mvc.perform(get("/schools/filter").param("q", "high").param("cca", "RUGBY").param("psle", "12")
                        .param("pg", "2").param("radiusKm", "3").param("mode", "WALK").param("maxMin", "30")
                        .param("travel", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"RUGBY\" checked=\"checked\"")))
                .andExpect(content().string(not(containsString("value=\"BOWLING\" checked=\"checked\""))))
                .andExpect(content().string(containsString("name=\"q\" value=\"high\"")))
                .andExpect(content().string(containsString("value=\"12\"")))
                .andExpect(content().string(containsString("value=\"2\" selected=\"selected\"")))
                .andExpect(content().string(containsString("value=\"3\" checked=\"checked\"")))
                .andExpect(content().string(containsString("value=\"WALK\" selected=\"selected\"")))
                .andExpect(content().string(containsString("value=\"30\" selected=\"selected\"")))
                .andExpect(content().string(containsString("name=\"travel\" value=\"1\" checked=\"checked\"")));
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-FILTER-03-15: the page explains the PSLE rule and that ranges are historical; PG3 hint when no PG")
    void explainsPsleRule() throws Exception {
        mvc.perform(get("/schools/filter").param("psle", "12"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("highest score admitted")))
                .andExpect(content().string(containsString("not a guarantee")))
                .andExpect(content().string(containsString("posting group 3 is used")));
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-FILTER-06-11: the travel-time section says it asks a routing service")
    void travelNote() throws Exception {
        mvc.perform(get("/schools/filter"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("routing service")))
                .andExpect(content().string(containsString("Public transport")));
    }

    @Test
    @Tag("NFR-USE-03")
    @DisplayName("TC-FILTER-03-16: invalid values in the URL are highlighted on the form (T-33)")
    void highlightsInvalidValues() throws Exception {
        mvc.perform(get("/schools/filter").param("psle", "40").param("cca", "UNDERWATER HOCKEY")
                        .param("radiusKm", "2"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("fieldErrors", hasEntry("psle", "Enter a whole number from 4 to 32")))
                .andExpect(model().attribute("fieldErrors", hasEntry("cca", "Unknown CCA: UNDERWATER HOCKEY")))
                .andExpect(model().attribute("fieldErrors", hasEntry("radiusKm", "Choose 1, 3 or 5 km")))
                .andExpect(content().string(containsString("is-invalid")));
    }

    @Test
    @Tag("FR-FILTER-04")
    @DisplayName("TC-FILTER-04-02: the starting point from the session is shown; the picker returns to this filter page")
    void startingPoint() throws Exception {
        ReferenceLocation bishan =
                new ReferenceLocation(new Coordinate(1.3510, 103.8484), LocationSource.MANUAL_ENTRY, "BISHAN MRT");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ReferenceLocationStore.CURRENT, bishan);

        mvc.perform(get("/schools/filter").param("q", "st hilda").param("radiusKm", "1").session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("startLocation", bishan))
                .andExpect(model().attribute("returnTo", "/schools/filter?q=st%20hilda&radiusKm=1"))
                .andExpect(content().string(containsString("BISHAN MRT")));
    }

    @Test
    @Tag("FR-FILTER-09")
    @DisplayName("TC-FILTER-09-02: Apply submits GET /schools and Clear all keeps only the search term")
    void applyAndClearAll() throws Exception {
        mvc.perform(get("/schools/filter").param("q", "a+b").param("cca", "RUGBY"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/schools\"")))
                .andExpect(content().string(containsString("href=\"/schools?q=a%2Bb\"")))
                .andExpect(model().attributeDoesNotExist("fieldErrors"));
    }
}

package sg.schoolmatch.boundary.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.NotFoundException;
import sg.schoolmatch.support.ExternalFailures;
import sg.schoolmatch.support.LogCapture;
import sg.schoolmatch.support.TestSchools;

/**
 * Web test of the boundary class FacilityDetailsUI (DM-14; View Facility Details, FR-FACDETAIL-01..03).
 */
@WebMvcTest(FacilityDetailsUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import(SessionCookie.class)
@ActiveProfiles("test")
class FacilityDetailsUITest {

    private static final String CODE = "catholic-high-school";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FacilityController facilityController;

    @MockitoBean
    private SchoolController schoolController;

    // Needed by the shared layout (LayoutModelAdvice, AuthInterceptor), not by this page.
    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    private final School school = TestSchools.school(CODE).name("CATHOLIC HIGH SCHOOL").build();   // at Bishan

    @Test
    @Tag("FR-FACDETAIL-01")
    @Tag("FR-FACDETAIL-02")
    @DisplayName("TC-FacilityDetailsUI-01: every Facility Details field, the distance from the school and the links")
    void allFields() throws Exception {
        Facility library = new Facility("lib-1", "Bishan Public Library", FacilityType.LIBRARY);
        library.setAddress("5 Bishan Pl, Singapore 579841");
        library.setCoordinate(new Coordinate(TestSchools.BISHAN.getLatitude() + 0.5 / 111.195,
                TestSchools.BISHAN.getLongitude()));
        library.setTelephone("6332 3255");
        library.setWebsite("https://www.nlb.gov.sg/");
        library.setOpeningHours("Monday: 10:00 AM – 9:00 PM; Tuesday: 10:00 AM – 9:00 PM");
        when(facilityController.getFacilityDetails("lib-1")).thenReturn(library);
        when(schoolController.getSchoolDetails(CODE)).thenReturn(school);

        mvc.perform(get("/facilities/lib-1").param("from", CODE))
                .andExpect(status().isOk())
                .andExpect(view().name("facility-details"))
                .andExpect(content().string(containsString("Bishan Public Library")))
                .andExpect(content().string(containsString("Library")))
                .andExpect(content().string(containsString("5 Bishan Pl, Singapore 579841")))
                .andExpect(content().string(containsString("6332 3255")))
                .andExpect(content().string(containsString("href=\"https://www.nlb.gov.sg/\"")))
                .andExpect(content().string(containsString("<li>Monday: 10:00 AM – 9:00 PM</li>")))
                .andExpect(content().string(containsString("<li>Tuesday: 10:00 AM – 9:00 PM</li>")))
                .andExpect(content().string(containsString("0.5 km from CATHOLIC HIGH SCHOOL")))
                .andExpect(content().string(containsString("href=\"/schools/catholic-high-school/facilities\"")))
                .andExpect(content().string(containsString(
                        "href=\"/directions?to=facility:lib-1&amp;from=/facilities/lib-1?from%3Dcatholic-high-school\"")))
                .andExpect(content().string(not(containsString("Not available"))))
                .andExpect(content().string(not(containsString("Not built yet"))));
    }

    @Test
    @Tag("FR-FACDETAIL-03")
    @DisplayName("TC-FacilityDetailsUI-02: missing fields show \"Not available\"; a javascript: website is not a link")
    void missingFields_notAvailable() throws Exception {
        Facility bare = new Facility("tc-1", "Bright Minds Tuition", FacilityType.TUITION_CENTRE);
        bare.setWebsite("javascript:alert(1)");
        when(facilityController.getFacilityDetails("tc-1")).thenReturn(bare);

        mvc.perform(get("/facilities/tc-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bright Minds Tuition")))
                .andExpect(content().string(containsString("Not available")))
                .andExpect(content().string(not(containsString("href=\"javascript:"))))
                .andExpect(content().string(not(containsString("Back to facilities"))));
    }

    @Test
    @Tag("FR-FACDETAIL-01")
    @DisplayName("TC-FacilityDetailsUI-03: unknown place → 404 page (EX-1)")
    void unknownPlace_404() throws Exception {
        when(facilityController.getFacilityDetails("nope")).thenThrow(new NotFoundException("No facility"));

        mvc.perform(get("/facilities/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Tag("NFR-USE-03")
    @DisplayName("TC-FacilityDetailsUI-04: Google down → \"temporarily unavailable\" message, still a page")
    void serviceDown_message() throws Exception {
        when(facilityController.getFacilityDetails("lib-1"))
                .thenThrow(new ExternalServiceUnavailableException("Google Places", null));

        mvc.perform(get("/facilities/lib-1"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("serviceUnavailable", true))
                .andExpect(content().string(containsString("Facility details are temporarily unavailable")));
    }

    @Test
    @Tag("NFR-USE-03")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-FacilityDetailsUI-06: the app's daily limit → one WARN line saying so (not 'Google refused')")
    void serviceDown_logsOneWarning() throws Exception {
        when(facilityController.getFacilityDetails("lib-1"))
                .thenThrow(ExternalFailures.dailyLimit("place-details", 26, 1, 26));

        try (LogCapture log = LogCapture.of(FacilityDetailsUI.class)) {
            mvc.perform(get("/facilities/lib-1"))
                    .andExpect(content().string(containsString("Facility details are temporarily unavailable")));

            assertThat(log.warnings()).singleElement().asString()
                    .startsWith("Facility details unavailable: Google place-details: app daily limit reached")
                    .contains("26 of 26");
        }
    }

    @Test
    @Tag("FR-FACDETAIL-02")
    @DisplayName("TC-FacilityDetailsUI-05: an unknown or malformed ?from= is ignored (no distance, no back link)")
    void unknownFrom_ignored() throws Exception {
        Facility library = new Facility("lib-1", "Bishan Public Library", FacilityType.LIBRARY);
        library.setCoordinate(TestSchools.BISHAN);
        when(facilityController.getFacilityDetails("lib-1")).thenReturn(library);
        when(schoolController.getSchoolDetails("gone-school")).thenThrow(new NotFoundException("No school"));

        for (String from : new String[] {"gone-school", "//evil.example"}) {
            mvc.perform(get("/facilities/lib-1").param("from", from))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("from", (Object) null))
                    .andExpect(content().string(not(containsString("Back to facilities"))))
                    .andExpect(content().string(not(containsString("evil.example"))));
        }
    }
}

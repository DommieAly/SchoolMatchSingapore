package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;

/**
 * The starting-point forms end to end (DC-11, DC-23; FR-FILTER-04, FR-ROUTE-02): the real LocationController
 * with the offline OneMap stub ({@code src/main/resources/stub/onemap/}: "579767" has one hit,
 * "catholic high school" two), the HTTP session as the only store, and the picker on the directions page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LocationFlowTest {

    private static final String RETURN_TO = "/directions?to=school:catholic-high-school";
    private static final String CHS_ADDRESS = "9 BISHAN STREET 22 CATHOLIC HIGH SCHOOL SINGAPORE 579767";

    @Autowired
    private MockMvc mvc;

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("FR-FILTER-04")
    @DisplayName("TC-LocationFlow-01: an ambiguous address → the picker lists the matches → choosing one stores it")
    void ambiguousAddress_pickerThenChoose() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/location").param("address", "Catholic High School").param("returnTo", RETURN_TO)
                        .session(session))
                .andExpect(redirectedUrl(RETURN_TO))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CANDIDATES, hasSize(2)))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CURRENT, nullValue()));

        mvc.perform(get(RETURN_TO).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(stringContainsInOrder(CHS_ADDRESS, "COMMIT LEARNING SCHOOLHOUSE",
                        "Use this place")));

        mvc.perform(post("/location/choose").param("index", "0").param("returnTo", RETURN_TO).session(session))
                .andExpect(redirectedUrl(RETURN_TO))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CANDIDATES, nullValue()));

        ReferenceLocation chosen = (ReferenceLocation) session.getAttribute(ReferenceLocationStore.CURRENT);
        assertThat(chosen.getInputText()).isEqualTo(CHS_ADDRESS);
        assertThat(chosen.getSource()).isEqualTo(LocationSource.MANUAL_ENTRY);
        assertThat(chosen.isResolved()).isTrue();

        mvc.perform(get(RETURN_TO).session(session))
                .andExpect(content().string(stringContainsInOrder("Starting point", CHS_ADDRESS)))
                .andExpect(content().string(not(containsString("Use this place"))));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-LocationFlow-02: a postal code with one match is stored straight away")
    void postalCode_oneMatch() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/location").param("address", " 579767 ").param("returnTo", RETURN_TO).session(session))
                .andExpect(redirectedUrl(RETURN_TO))
                .andExpect(flash().attribute("flashMessage", "Starting point set: " + CHS_ADDRESS));

        assertThat(((ReferenceLocation) session.getAttribute(ReferenceLocationStore.CURRENT)).getInputText())
                .isEqualTo(CHS_ADDRESS);
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-LocationFlow-03: an address with no match shows an error and stores nothing")
    void unknownAddress() throws Exception {
        mvc.perform(post("/location").param("address", "no such place 123").param("returnTo", RETURN_TO))
                .andExpect(redirectedUrl(RETURN_TO))
                .andExpect(flash().attribute("flashError", containsString("No address in Singapore matches")))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CURRENT, nullValue()))
                .andExpect(request().sessionAttribute(ReferenceLocationStore.CANDIDATES, nullValue()));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-SEC-06")
    @DisplayName("TC-LocationFlow-04: the browser's position is stored; a position abroad is refused")
    void devicePosition() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/location/device").param("latitude", "1.3521").param("longitude", "103.8198")
                        .param("returnTo", RETURN_TO).session(session))
                .andExpect(redirectedUrl(RETURN_TO));
        assertThat(((ReferenceLocation) session.getAttribute(ReferenceLocationStore.CURRENT)).getSource())
                .isEqualTo(LocationSource.DEVICE_LOCATION);

        mvc.perform(post("/location/device").param("latitude", "51.5").param("longitude", "-0.12")
                        .param("returnTo", RETURN_TO).session(session))
                .andExpect(flash().attribute("flashError", "That location is outside Singapore"));
        assertThat(((ReferenceLocation) session.getAttribute(ReferenceLocationStore.CURRENT)).getSource())
                .isEqualTo(LocationSource.DEVICE_LOCATION);   // the earlier point is kept
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-LocationFlow-05: returnTo=//evil.com is not followed; the location is still stored")
    void foreignReturnTo() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/location").param("address", "579767").param("returnTo", "//evil.com").session(session))
                .andExpect(redirectedUrl("/directions"));
        assertThat(session.getAttribute(ReferenceLocationStore.CURRENT)).isNotNull();
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-SEC-06")
    @DisplayName("TC-LocationFlow-06: opening the directions page creates no HTTP session (nothing is kept for a visitor)")
    void visitorGetsNoSession() throws Exception {
        MvcResult result = mvc.perform(get(RETURN_TO))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Use my current location")))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-LocationFlow-07: Clear removes the starting point")
    void clear() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/location").param("address", "579767").param("returnTo", RETURN_TO).session(session));

        mvc.perform(post("/location/clear").param("returnTo", RETURN_TO).session(session))
                .andExpect(redirectedUrl(RETURN_TO));

        assertThat(session.getAttribute(ReferenceLocationStore.CURRENT)).isNull();
    }
}

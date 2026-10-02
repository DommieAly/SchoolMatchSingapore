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
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.boundary.external.DataGovSgInterface;
import sg.schoolmatch.boundary.external.GoogleMapsPlatformInterface;
import sg.schoolmatch.boundary.external.OneMapInterface;
import sg.schoolmatch.control.SchoolDataController;

/**
 * Smoke test: the whole application starts with the {@code test} profile (in-memory H2, offline stubs,
 * the fixture snapshot) and serves the home page. Replaces the generated context-loads test.
 * <p>
 * Flow tests copy this setup: {@code @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")}.
 * Keep the same three annotations so Spring reuses one application context for all of them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationStartsTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataGovSgInterface dataGovSg;

    @Autowired
    private GoogleMapsPlatformInterface googleMaps;

    @Autowired
    private OneMapInterface oneMap;

    @Autowired
    private SchoolDataController schoolDataController;

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-AppStart-01: with the test profile every external service is the offline stub")
    void externalServicesAreStubs() {
        assertThat(List.<Object>of(dataGovSg, googleMaps, oneMap))
                .allSatisfy(bean -> assertThat(AopUtils.getTargetClass(bean).getPackageName())
                        .isEqualTo("sg.schoolmatch.boundary.external.stub"));
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-AppStart-02: the test profile loads the fixture snapshot (10 schools, 3 districts)")
    void loadsFixtureSnapshot() {
        assertThat(schoolDataController.getSchools()).hasSize(10);
        assertThat(schoolDataController.getDistricts()).hasSize(3);
    }

    @Test
    @Tag("FR-SEARCH-01")
    @DisplayName("TC-AppStart-03: GET / shows the home page with a search box that submits q to /schools")
    void homePageRenders() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("action=\"/schools\"")))
                .andExpect(content().string(containsString("name=\"q\"")));
    }
}

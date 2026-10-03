package sg.schoolmatch.boundary.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
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
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.support.TestSchools;

/** Web test of the PSLE range table on the school details page (FR-SCHOOL-02, NFR-DATA-03). The control is a mock. */
@WebMvcTest(SchoolDetailsUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import(SessionCookie.class)
@ActiveProfiles("test")
class SchoolDetailsUITest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SchoolController schoolController;

    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    @Test
    @Tag("FR-SCHOOL-02")
    @Tag("NFR-DATA-03")
    @DisplayName("TC-SchoolDetailsUI-01: an IP range is a table row marked Integrated Programme and IP; the IP note keeps MOE's text")
    void ipRangeRow() throws Exception {
        School catholic = TestSchools.school("catholic-high-school").name("CATHOLIC HIGH SCHOOL").build();
        catholic.setScoreRanges(List.of(new IndicativePsleScoreRange(2025, 3, false, 6, 8),
                new IndicativePsleScoreRange(2025, 3, false, 4, 7, true)));
        catholic.setIpRangeNote("IP 2025 PG3: 4(D) - 7(M)");
        when(schoolController.getSchoolDetails("catholic-high-school")).thenReturn(catholic);
        when(schoolDataController.hasPsleData()).thenReturn(true);

        String html = mvc.perform(get("/schools/catholic-high-school"))
                .andExpect(status().isOk())
                .andExpect(view().name("school-details"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Integrated Programme").contains("IP 4–7").contains("6–8")
                .contains("4(D) - 7(M)");
        String ipRow = html.substring(html.lastIndexOf("<tr", html.indexOf("IP 4–7")), html.indexOf("IP 4–7"));
        assertThat(ipRow).contains("PG3").contains("Integrated Programme").doesNotContain("Non-affiliated");
    }

    @Test
    @Tag("FR-SCHOOL-02")
    @Tag("NFR-DATA-03")
    @DisplayName("TC-SchoolDetailsUI-02: MOE's text is shown when it has Higher Chinese grades or '30*', with MOE's meaning as a note (DC-82)")
    void moeTextAndNotes() throws Exception {
        School catholic = TestSchools.school("catholic-high-school").name("CATHOLIC HIGH SCHOOL").build();
        catholic.setScoreRanges(List.of(new IndicativePsleScoreRange(2025, 3, false, 6, 8, false, "6(D) - 8(M)"),
                new IndicativePsleScoreRange(2025, 1, false, 26, 30, false, "26 - 30*"),
                new IndicativePsleScoreRange(2025, 2, false, 21, 25, false, "21 - 25")));
        when(schoolController.getSchoolDetails("catholic-high-school")).thenReturn(catholic);
        when(schoolDataController.hasPsleData()).thenReturn(true);

        String html = mvc.perform(get("/schools/catholic-high-school"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("6–8").contains("MOE: 6(D) - 8(M)")
                .contains("26–30*")
                .contains("Higher Chinese")
                .contains("still had places")
                .doesNotContain("MOE: 21 - 25")
                .contains("MOE SchoolFinder");
    }

    @Test
    @Tag("FR-SCHOOL-01")
    @DisplayName("TC-SchoolDetailsUI-03: no affiliated primary school reads 'None' when the dataset has curated affiliations, else 'Not available'")
    void affiliationsNoneOrNotAvailable() throws Exception {
        School bartley = TestSchools.school("bartley-secondary-school").name("BARTLEY SECONDARY SCHOOL").build();
        when(schoolController.getSchoolDetails("bartley-secondary-school")).thenReturn(bartley);
        when(schoolDataController.hasPsleData()).thenReturn(true);

        when(schoolDataController.hasAffiliationData()).thenReturn(true);
        assertThat(affiliationsCell("/schools/bartley-secondary-school")).contains("None").doesNotContain("Not available");

        when(schoolDataController.hasAffiliationData()).thenReturn(false);
        assertThat(affiliationsCell("/schools/bartley-secondary-school")).contains("Not available");
    }

    /** The {@code <dd>} after "Affiliated primary schools". */
    private String affiliationsCell(String url) throws Exception {
        String html = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int start = html.indexOf("<dd", html.indexOf("Affiliated primary schools"));
        return html.substring(start, html.indexOf("</dd>", start));
    }
}

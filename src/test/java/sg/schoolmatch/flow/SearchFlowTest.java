package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Flow test of the use cases Search for Schools → View School Details (example of a flow test).
 * <p>
 * {@code @SpringBootTest} starts the whole application with the {@code test} profile: real controls,
 * the stub external services, and the fixture snapshot {@code src/test/resources/fixtures/snapshot-mini/}
 * (10 real schools, TEST PSLE ranges). Requests go through MockMvc, from URL to rendered page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchFlowTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @Tag("FR-SEARCH-02")
    @Tag("FR-SEARCH-03")
    @DisplayName("TC-SEARCH-02-08: searching part of a school name in lower case finds that school")
    void search_partOfName_findsSchool() throws Exception {
        mvc.perform(get("/schools").param("q", "catholic"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 1))
                .andExpect(content().string(containsString("CATHOLIC HIGH SCHOOL")))
                .andExpect(content().string(containsString("/schools/catholic-high-school")));
    }

    @Test
    @Tag("FR-SEARCH-04")
    @DisplayName("TC-SEARCH-04-08: the search page without a term lists every school in the dataset")
    void search_noTerm_listsAllSchools() throws Exception {
        mvc.perform(get("/schools"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 10));
    }

    @Test
    @Tag("FR-SEARCH-02")
    @DisplayName("TC-SEARCH-02-09: a term with an apostrophe and a full stop finds ST. HILDA'S")
    void search_punctuation_findsSchool() throws Exception {
        mvc.perform(get("/schools").param("q", "st. hilda's"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 1));
    }

    @Test
    @Tag("FR-SCHOOL-01")
    @Tag("FR-SCHOOL-02")
    @Tag("NFR-DATA-03")
    @DisplayName("TC-SCHOOL-01-03: a details page on the seed data warns that its PSLE ranges are test values")
    void details_knownCode_showsSchool() throws Exception {
        String ranges = rangesSection(mvc.perform(get("/schools/catholic-high-school"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CATHOLIC HIGH SCHOOL")))
                .andReturn());
        assertThat(ranges).contains("TEST VALUES – not MOE data").doesNotContain("historical");
    }

    @Test
    @Tag("FR-SEARCH-03")
    @Tag("NFR-DATA-03")
    @DisplayName("TC-SEARCH-03-02: on the seed data every summary card warns that its PSLE ranges are test values")
    void search_seedData_cardsWarnTestValues() throws Exception {
        String html = mvc.perform(get("/schools"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 10 cards, each with the warning (the page size is 20, so all fit on page 1)
        assertThat(html.split("TEST VALUES – not MOE data", -1)).hasSize(11);
    }

    @Test
    @Tag("FR-SCHOOL-03")
    @DisplayName("TC-SCHOOL-03-01: a school without PSLE ranges shows \"Not available\" in the ranges section")
    void details_missingRanges_showsNotAvailable() throws Exception {
        String ranges = rangesSection(mvc.perform(get("/schools/westwood-secondary-school"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("scoreRanges", empty()))
                .andReturn());
        assertThat(ranges).contains("Not available");
    }

    @Test
    @Tag("FR-SCHOOL-03")
    @DisplayName("TC-SCHOOL-03-02: a school with PSLE ranges shows the table, not \"Not available\"")
    void details_withRanges_showsTable() throws Exception {
        String ranges = rangesSection(mvc.perform(get("/schools/catholic-high-school"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("scoreRanges", not(empty())))
                .andReturn());
        assertThat(ranges).contains("<table").doesNotContain("Not available");
    }

    /** The HTML of {@code <section id="psle-ranges">} on a details page. */
    private static String rangesSection(MvcResult result) throws Exception {
        String html = result.getResponse().getContentAsString();
        int start = html.indexOf("id=\"psle-ranges\"");
        assertThat(start).as("details page has a psle-ranges section").isNotNegative();
        return html.substring(start, html.indexOf("</section>", start));
    }

    @Test
    @Tag("FR-SCHOOL-01")
    @DisplayName("TC-SCHOOL-01-04: an unknown school code answers 404")
    void details_unknownCode_answers404() throws Exception {
        mvc.perform(get("/schools/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}

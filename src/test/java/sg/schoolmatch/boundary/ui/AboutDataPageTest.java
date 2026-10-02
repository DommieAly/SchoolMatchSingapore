package sg.schoolmatch.boundary.ui;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.dataset.SnapshotManifest;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.entity.school.ValidationStatus;

/** GET /about/data in HomeUI: what the active snapshot is and where it came from (NFR-DATA-01, DC-09, licence notice). */
@WebMvcTest(HomeUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import(SessionCookie.class)
@ActiveProfiles("test")
class AboutDataPageTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-AboutData-01: the page shows version, kind, dates, validation status, counts and warnings")
    void showsManifest() throws Exception {
        stubDataset("full", manifest("full", "2026-10-02.1"));

        mvc.perform(get("/about/data"))
                .andExpect(status().isOk())
                .andExpect(view().name("about-data"))
                .andExpect(content().string(containsString("2026-10-02.1")))
                .andExpect(content().string(containsString("Full import")))
                .andExpect(content().string(containsString("2 Oct 2026")))
                .andExpect(content().string(containsString("PASSED_WITH_WARNINGS")))
                .andExpect(content().string(containsString("147")))
                .andExpect(content().string(containsString("school-code-slug: 147 warnings")))
                .andExpect(content().string(containsString("No PSLE ranges yet")))
                .andExpect(content().string(not(containsString("TEST VALUES"))));
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-AboutData-02: each source is listed with its dataset id and download date, with the licence notice")
    void showsSourcesAndLicence() throws Exception {
        stubDataset("full", manifest("full", "2026-10-02.1"));

        mvc.perform(get("/about/data"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("d_688b934f82c1059ed0a6993d2a829089")))
                .andExpect(content().string(containsString("onemap-elastic-search")))
                .andExpect(content().string(containsString("Contains information from General information of schools "
                        + "accessed on 2 Oct 2026 from data.gov.sg")))
                .andExpect(content().string(containsString("Singapore Open Data Licence version 1.0")))
                .andExpect(content().string(containsString("https://data.gov.sg/open-data-licence")))
                .andExpect(content().string(containsString("OneMap, Singapore Land Authority")));
    }

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-AboutData-03: the seed snapshot is marked as TEST VALUES")
    void seedSnapshot() throws Exception {
        stubDataset("seed", manifest("seed", "0000-seed"));

        mvc.perform(get("/about/data"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TEST VALUES")))
                .andExpect(content().string(containsString("Seed")));
    }

    @Test
    @Tag("FR-SEARCH-04")
    @DisplayName("TC-AboutData-04: the home page still renders")
    void homeStillWorks() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("home"));
    }

    private void stubDataset(String kind, SnapshotManifest manifest) {
        SchoolDataCache cache = new SchoolDataCache("SchoolMatch snapshot", Instant.parse("2026-10-02T03:00:00Z"),
                null, List.of(), List.of());
        cache.setDatasetKind(kind);
        cache.setDatasetVersion(manifest.version());
        cache.setEffectiveDate(manifest.effectiveDate());
        cache.setImportedAt(manifest.importedAt());
        cache.setValidationStatus(ValidationStatus.PASSED_WITH_WARNINGS);
        when(schoolDataController.getActiveDataset()).thenReturn(cache);
        when(schoolDataController.getActiveManifest()).thenReturn(manifest);
    }

    private static SnapshotManifest manifest(String kind, String version) {
        Instant downloaded = Instant.parse("2026-10-02T02:10:00Z");
        return new SnapshotManifest(kind, version, LocalDate.of(2026, 10, 2), Instant.parse("2026-10-02T02:20:00Z"),
                List.of(new SnapshotManifest.Source("data.gov.sg General information of schools",
                                "d_688b934f82c1059ed0a6993d2a829089", downloaded),
                        new SnapshotManifest.Source("OneMap search (coordinates by postal code)",
                                "onemap-elastic-search", downloaded)),
                ValidationStatus.PASSED_WITH_WARNINGS,
                List.of("school-code-slug: 147 warnings, e.g. ADMIRALTY SECONDARY SCHOOL → admiralty-secondary-school"),
                Map.of("schools", 147, "districts", 55, "scoreRanges", 0),
                "seed".equals(kind) ? "PSLE ranges in this seed are TEST VALUES, not MOE data."
                        : "No PSLE ranges yet: data/curated/psle-ranges.csv has no checked rows.");
    }
}

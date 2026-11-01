package sg.schoolmatch.boundary.ui;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.io.IOException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.SchoolDataController;

/**
 * GET /about/privacy in HomeUI: the Terms and privacy page and the footer that links to it (NFR-PRIV-01, DC-87),
 * and the OneMap notice in the footer (NFR-ATTR-02, DC-89). Whether the page's claims are true of the running app
 * is checked in {@code PrivacyFlowTest}.
 */
@WebMvcTest(HomeUI.class)
@EnableConfigurationProperties(AppProperties.class)
@Import(SessionCookie.class)
@ActiveProfiles("test")
class AboutPrivacyPageTest {

    private static final String MONTH = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*";
    /** "1 Dec", "Dec 1", "December 2026", "2026". */
    private static final String A_DATE = "(\\b\\d{1,2}\\s+" + MONTH + "\\b|\\b" + MONTH + "\\s+\\d{1,4}\\b|\\b20\\d\\d\\b)";
    private static final Pattern ANY_DATE = Pattern.compile(A_DATE, Pattern.CASE_INSENSITIVE);
    /** A word about removing the site or its data and a date in one sentence, in either order. */
    private static final Pattern REMOVAL_DATE = Pattern.compile(
            "\\b(delet|remov|tak\\w* down|shut\\w* down|tear\\w* down)\\w*\\b[^.]{0,120}" + A_DATE
                    + "|" + A_DATE + "[^.]{0,120}\\b(delet|remov|tak\\w* down|shut\\w* down|tear\\w* down)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SchoolDataController schoolDataController;

    @MockitoBean
    private AuthController authController;

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-Privacy-01: a guest gets the page: a student prototype, historical ranges, made-up details only, not meant for under 13s")
    void guestSeesWhatTheSiteIs() throws Exception {
        mvc.perform(get("/about/privacy")).andExpect(status().isOk()).andExpect(view().name("about-privacy"));

        assertThat(privacyPage()).contains(
                "This is an NTU SC2006 student prototype, not an official service of NTU, MOE or any government agency.",
                "PSLE ranges are historical and are not a promise of a place.",
                "Use made-up details only. Do not enter a real child's score, school or home address.",
                "This site is for parents and guardians, and for students aged 13 or over.",
                "It is not meant for children under 13.");
        assertThat(privacyPage()).doesNotContain("permission");
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-Privacy-02: the page lists what is stored")
    void listsWhatIsStored() throws Exception {
        assertThat(privacyPage()).contains("username", "email address", "hashed password", "Display name",
                "PSLE score", "posting group", "primary school", "home address and its coordinates",
                "CCA and programme choices", "commute limit", "The schools you saved", "Your ranked schools",
                "counts some requests by IP address", "These counts stay in memory for about one hour.",
                "If the site is down for a moment, the HTTPS proxy's error log can keep your IP address, your browser's "
                        + "name, and the address of the page you asked for and of the page you came from, with any "
                        + "score or search words in those addresses, until the server is deleted.");
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-Privacy-10: the page says that typed addresses and travel-time answers stay in server memory after logout (CacheConfig)")
    void saysWhatTheCachesKeep() throws Exception {
        assertThat(privacyPage()).contains(
                "Separately, the server keeps each typed address with OneMap's answer for up to 24 hours,",
                "and travel-time answers with the rounded starting point for up to 30 minutes,",
                "These copies carry no name or account, and they go when the server restarts.");
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-Privacy-03: the page says where data goes: OneMap, Google and a server in Singapore")
    void saysWhereDataGoes() throws Exception {
        assertThat(privacyPage()).contains(
                "Data is stored on a server in Singapore (Amazon Web Services).",
                "Typed addresses and postal codes go to OneMap (Singapore Land Authority)",
                "Locations go to Google",
                "Every page loads its styles and scripts from jsDelivr");
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-Privacy-04: the page has the wording of Google's terms, section 3.2.2(a)(i), with both links")
    void hasGoogleWording() throws Exception {
        assertThat(privacyPage()).contains(
                "SchoolMatch SG includes Google Maps features and content.",
                "Use of Google Maps features and content is subject to the then-current versions of the",
                "href=\"https://maps.google.com/help/terms_maps/\"",
                ">Google Maps End User Additional Terms of Service</a>",
                "href=\"https://policies.google.com/privacy\"",
                ">Google Privacy Policy</a>");
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-Privacy-05: the page says how to remove data and who to write to")
    void saysHowToRemoveData() throws Exception {
        assertThat(privacyPage()).contains(
                "You can clear your score, school and address on your",
                "To delete a whole account",
                "href=\"mailto:dommyshappynow@gmail.com\"",
                ">dommyshappynow@gmail.com</a>",
                "All accounts and all data are deleted when the course project ends.");
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-Privacy-06: the page's own text has no date at all")
    void pageHasNoDate() throws Exception {
        String page = privacyPage();
        String main = page.substring(page.indexOf("<main"), page.indexOf("</main>"));

        assertThat(ANY_DATE.matcher(main).results().map(found -> found.group()).toList()).isEmpty();
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-Privacy-07: no template states a date for removing the site or its data")
    void noTemplateStatesARemovalDate() throws IOException {
        Resource[] templates = new PathMatchingResourcePatternResolver().getResources("classpath:templates/**/*.html");
        assertThat(templates).isNotEmpty();

        for (Resource template : templates) {
            String text = template.getContentAsString(UTF_8);
            assertThat(REMOVAL_DATE.matcher(text).results().map(found -> found.group()).toList())
                    .as(template.getFilename()).isEmpty();
        }
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-Privacy-08: the footer links to the page")
    void footerLinksToPage() throws Exception {
        assertThat(rendered("/")).contains("href=\"/about/privacy\">Terms and privacy</a>");
    }

    @Test
    @Tag("NFR-ATTR-02")
    @DisplayName("TC-Privacy-09: the footer names OneMap as a source, once, and links to its licence page")
    void footerHasOneMapNotice() throws Exception {
        String home = rendered("/");

        assertThat(home).contains("Contains information from OneMap search",
                "from OneMap (onemap.gov.sg, Singapore Land Authority) which is made available under the terms of the",
                "href=\"https://www.onemap.gov.sg/legal/opendatalicence.html\"");
        assertThat(home).containsOnlyOnce("href=\"https://data.gov.sg/open-data-licence\"");
        assertThat(home).containsOnlyOnce("href=\"https://www.onemap.gov.sg/legal/opendatalicence.html\"");
    }

    private String privacyPage() throws Exception {
        return rendered("/about/privacy");
    }

    /** The page as text, with every run of white space as one space, so a sentence may wrap in the template. */
    private String rendered(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(UTF_8).replaceAll("\\s+", " ");
    }
}

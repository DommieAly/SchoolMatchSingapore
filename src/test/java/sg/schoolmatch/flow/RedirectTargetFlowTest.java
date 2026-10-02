package sg.schoolmatch.flow;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code next} and {@code returnTo} from a request can never send the user to another site
 * (AuthInterceptor.safeLocalPath; the rule itself is unit-tested in {@code AuthInterceptorTest}).
 * Keep these green when the login and location handlers are built for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedirectTargetFlowTest {

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest(name = "TC-Redirect-01 [{index}]: returnTo={0}")
    @ValueSource(strings = {"//evil.com", "/\\evil.com", "https://evil.com", "javascript:alert(1)"})
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-Redirect-01: a location form with a foreign returnTo goes back to /directions")
    void locationForm_foreignReturnTo_staysOnSite(String returnTo) throws Exception {
        mvc.perform(post("/location/clear").param("returnTo", returnTo))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/directions"));
        mvc.perform(post("/location").param("returnTo", returnTo).param("address", "bishan"))
                .andExpect(redirectedUrl("/directions"));
    }

    @ParameterizedTest(name = "TC-Redirect-02 [{index}]: returnTo={0}")
    @ValueSource(strings = {"/schools/filter?q=bishan", "/recommendations"})
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-Redirect-02: a location form with a local returnTo goes back to that page")
    void locationForm_localReturnTo_goesBack(String returnTo) throws Exception {
        mvc.perform(post("/location/clear").param("returnTo", returnTo))
                .andExpect(redirectedUrl(returnTo));
    }

    @ParameterizedTest(name = "TC-Redirect-03 [{index}]: next={0}")
    @ValueSource(strings = {"//evil.com", "/\\evil.com", "https://evil.com"})
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-Redirect-03: the login form drops a foreign next (no hidden field, no redirect to it)")
    void login_foreignNext_isDropped(String next) throws Exception {
        mvc.perform(get("/login").param("next", next))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("name=\"next\""))));
        mvc.perform(post("/login").param("next", next))
                .andExpect(redirectedUrl("/login"));
    }

    @ParameterizedTest(name = "TC-Redirect-04 [{index}]: next={0}")
    @ValueSource(strings = {"/shortlist"})
    @Tag("FR-LOGIN-04")
    @DisplayName("TC-Redirect-04: the login form keeps a local next as a hidden field")
    void login_localNext_isKept(String next) throws Exception {
        mvc.perform(get("/login").param("next", next))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"next\" value=\"/shortlist\"")));
    }
}

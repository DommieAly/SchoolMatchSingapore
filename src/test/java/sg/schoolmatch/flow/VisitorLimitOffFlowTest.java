package sg.schoolmatch.flow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The limits per visitor stay off unless the profile turns them on (DC-90), so tests and laptops are not affected. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VisitorLimitOffFlowTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @Tag("NFR-LIMIT-01")
    @DisplayName("TC-VisitorLimitFlow-05: without the setting, 100 POST /location in a row all work")
    void offByDefault() throws Exception {
        for (int i = 0; i < 100; i++) {
            mvc.perform(post("/location").param("address", "579767").param("returnTo", "/schools"))
                    .andExpect(redirectedUrl("/schools"));
        }
    }
}

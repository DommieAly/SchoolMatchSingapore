package sg.schoolmatch.flow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring Boot Actuator: only {@code /actuator/health} and {@code /actuator/info} are reachable;
 * endpoints that could leak settings or keys (env, configprops, beans…) are not exposed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorEndpointsTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @Tag("NFR-MAIN-01")
    @DisplayName("TC-Actuator-01: /actuator/health answers UP")
    void health_isUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @Tag("NFR-MAIN-01")
    @DisplayName("TC-Actuator-02: /actuator/info shows the app name and version from pom.xml")
    void info_showsAppVersion() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.name").value("SchoolMatch"))
                .andExpect(jsonPath("$.app.version").value("0.0.1-SNAPSHOT"));
    }

    @ParameterizedTest(name = "TC-Actuator-03 [{index}]: {0}")
    @ValueSource(strings = {"/actuator/env", "/actuator/configprops", "/actuator/beans", "/actuator/heapdump"})
    @Tag("NFR-MAIN-01")
    @DisplayName("TC-Actuator-03: other actuator endpoints are not exposed")
    void otherEndpoints_notExposed(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }
}

package sg.schoolmatch.boundary.ui.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests of the cross-site form-post check (DC-72). */
class OriginCheckInterceptorTest {

    private final OriginCheckInterceptor interceptor = new OriginCheckInterceptor();

    @ParameterizedTest(name = "{0} {1} / Origin={2} / Referer={3} → allowed={4}")
    @CsvSource(nullValues = "-", value = {
            "POST, localhost:8080, http://localhost:8080, -, true",
            "POST, localhost:8080, -, http://localhost:8080/login, true",
            "POST, localhost:8080, -, -, true",
            "POST, localhost:8080, https://evil.example, -, false",
            "POST, localhost:8080, -, https://evil.example/x, false",
            "POST, localhost:8080, http://localhost:9090, -, false",
            "POST, localhost:8080, null, -, false",
            "POST, localhost:8080, https://evil.example, http://localhost:8080/login, false",
            "POST, schoolmatch.example, https://schoolmatch.example, -, true",
            "POST, schoolmatch.example, https://other.example, -, false",
            "POST, schoolmatch.example:443, https://schoolmatch.example, -, true",
            "POST, schoolmatch.example, http://schoolmatch.example, -, true",
            "POST, SchoolMatch.Example, http://schoolmatch.example, -, true",
            "GET, localhost:8080, https://evil.example, https://evil.example/x, true",
            "HEAD, localhost:8080, https://evil.example, -, true",
    })
    @Tag("NFR-SEC-05")
    @DisplayName("TC-OriginCheck-01: a state-changing request whose Origin/Referer is another site gets 403")
    void decides(String method, String host, String origin, String referer, boolean allowed) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/login");
        request.addHeader("Host", host);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        if (referer != null) {
            request.addHeader("Referer", referer);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isEqualTo(allowed);
        assertThat(response.getStatus()).isEqualTo(allowed ? 200 : 403);
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-OriginCheck-02: without a Host header the server name and port are compared")
    void noHostHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");   // localhost:80
        request.addHeader("Origin", "http://localhost");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();

        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/login");
        other.addHeader("Origin", "http://localhost:8080");
        assertThat(interceptor.preHandle(other, new MockHttpServletResponse(), new Object())).isFalse();
    }
}

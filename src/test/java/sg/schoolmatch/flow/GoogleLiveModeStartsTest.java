package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import sg.schoolmatch.boundary.external.GoogleMapsPlatformInterface;
import sg.schoolmatch.boundary.external.google.GoogleMapsPlatformClient;
import sg.schoolmatch.boundary.external.google.GooglePlacesApi;
import sg.schoolmatch.boundary.external.google.GoogleRoutesApi;
import sg.schoolmatch.boundary.external.stub.StubGoogleMapsPlatform;

/**
 * The application starts in live Google mode (with a fake key; no request is sent): the live client and its
 * two API classes replace the stub, and the API classes are cache proxies (NFR-MAIN-02).
 */
@SpringBootTest(properties = {"app.external.google.mode=live", "app.google.server-key=test-key"})
@ActiveProfiles("test")
class GoogleLiveModeStartsTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private GoogleMapsPlatformInterface googleMaps;

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleLive-01: GOOGLE_MODE=live wires GoogleMapsPlatformClient (not the stub) with cached API classes")
    void liveBeansWired() {
        assertThat(googleMaps).isInstanceOf(GoogleMapsPlatformClient.class);
        assertThat(context.getBeansOfType(StubGoogleMapsPlatform.class)).isEmpty();
        assertThat(AopUtils.isAopProxy(context.getBean(GoogleRoutesApi.class))).as("@Cacheable proxy").isTrue();
        assertThat(AopUtils.isAopProxy(context.getBean(GooglePlacesApi.class))).as("@Cacheable proxy").isTrue();
    }
}

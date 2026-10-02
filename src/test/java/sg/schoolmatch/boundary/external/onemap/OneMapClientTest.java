package sg.schoolmatch.boundary.external.onemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sg.schoolmatch.boundary.external.OneMapHit;
import sg.schoolmatch.boundary.external.OneMapInterface;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.config.CacheConfig;
import sg.schoolmatch.error.ExternalServiceUnavailableException;

/** Live OneMapClient against a MockRestServiceServer (DC-11, FR-FILTER-04, FR-ROUTE-02, NFR-MAIN-02). No network. */
class OneMapClientTest {

    private static final String SEARCH = "https://www.onemap.gov.sg/api/common/elastic/search";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-OneMap-01: search sends searchVal, returnGeom=Y, getAddrDetails=Y, pageNum=1 and maps the hits")
    void search() {
        server.expect(requestTo(SEARCH + "?searchVal=579767&returnGeom=Y&getAddrDetails=Y&pageNum=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(resource("/stub/onemap/579767.json"), MediaType.APPLICATION_JSON));

        List<OneMapHit> hits = client(0).search(" 579767 ");

        server.verify();
        assertThat(hits).containsExactly(new OneMapHit("CATHOLIC HIGH SCHOOL", "CATHOLIC HIGH SCHOOL",
                "9 BISHAN STREET 22 CATHOLIC HIGH SCHOOL SINGAPORE 579767", "579767",
                1.354525170657562, 103.8449008048039));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-OneMap-02: the search text is URL-encoded (space, '+', '&')")
    void encodesText() {
        server.expect(requestTo(SEARCH + "?searchVal=Blk%201%20%2B%20A%26B&returnGeom=Y&getAddrDetails=Y&pageNum=1"))
                .andRespond(withSuccess(resource("/fixtures/external/onemap/empty.json"), MediaType.APPLICATION_JSON));

        assertThat(client(0).search("Blk 1 + A&B")).isEmpty();
        server.verify();
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-OneMap-03: a hit with LATITUDE 'NIL' is skipped; a 'NIL' BUILDING becomes null")
    void nilValues() {
        server.expect(requestTo(SEARCH + "?searchVal=test&returnGeom=Y&getAddrDetails=Y&pageNum=1"))
                .andRespond(withSuccess(resource("/fixtures/external/onemap/nil-coordinate.json"), MediaType.APPLICATION_JSON));

        List<OneMapHit> hits = client(0).search("test");

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.building()).isNull();
            assertThat(hit.latitude()).isEqualTo(1.35);
        });
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-OneMap-04: blank text returns no hits without calling OneMap")
    void blankText() {
        assertThat(client(0).search("  ")).isEmpty();
        assertThat(client(0).search(null)).isEmpty();
        server.verify();
    }

    @Test
    @Tag("NFR-MAIN-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-OneMap-05: an HTTP error, a timeout or a body that is not JSON → ExternalServiceUnavailableException")
    void failures() {
        String url = SEARCH + "?searchVal=x&returnGeom=Y&getAddrDetails=Y&pageNum=1";
        server.expect(requestTo(url)).andRespond(withServerError());
        server.expect(requestTo(url)).andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(requestTo(url)).andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));
        OneMapClient client = client(0);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.search("x"))
                    .isInstanceOf(ExternalServiceUnavailableException.class)
                    .satisfies(e -> assertThat(((ExternalServiceUnavailableException) e).getService()).isEqualTo("OneMap"));
        }
        server.verify();
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-OneMap-06: calls are spaced at least the minimum interval apart (OneMap fair use: 1 per second)")
    void throttle() {
        String url = SEARCH + "?searchVal=a&returnGeom=Y&getAddrDetails=Y&pageNum=1";
        String empty = resource("/fixtures/external/onemap/empty.json");
        server.expect(requestTo(url)).andRespond(withSuccess(empty, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withSuccess(empty, MediaType.APPLICATION_JSON));
        OneMapClient client = client(300);

        long start = System.nanoTime();
        client.search("a");
        client.search("a");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(290);
        assertThat(OneMapClient.MIN_INTERVAL_MS).isEqualTo(1000);
    }

    @Test
    @Tag("NFR-PERF-01")
    @DisplayName("TC-OneMap-07: results are cached by normalised text, so 'Bishan' and ' bishan ' call OneMap once")
    void cached() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(CacheTestConfig.class)) {
            MockRestServiceServer cachedServer = context.getBean(MockRestServiceServer.class);
            cachedServer.expect(once(), requestTo(SEARCH + "?searchVal=Bishan&returnGeom=Y&getAddrDetails=Y&pageNum=1"))
                    .andRespond(withSuccess(resource("/stub/onemap/579767.json"), MediaType.APPLICATION_JSON));
            OneMapInterface oneMap = context.getBean(OneMapInterface.class);

            List<OneMapHit> first = oneMap.search("Bishan");
            List<OneMapHit> second = oneMap.search(" bishan  ");

            cachedServer.verify();
            assertThat(second).isEqualTo(first).hasSize(1);
        }
        assertThat(OneMapClient.cacheKey("  Bishan   Street 22 ")).isEqualTo("bishan street 22");
    }

    /** The cache from CacheConfig around a OneMapClient whose HTTP calls go to a mock server. */
    @Configuration
    @Import(CacheConfig.class)
    static class CacheTestConfig {

        @Bean
        AppProperties appProperties() {
            return props();
        }

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        MockRestServiceServer mockServer(RestClient.Builder restClientBuilder) {
            return MockRestServiceServer.bindTo(restClientBuilder).build();
        }

        @Bean
        OneMapClient oneMapClient(RestClient.Builder restClientBuilder, MockRestServiceServer mockServer,
                                  AppProperties appProperties) {
            return new OneMapClient(restClientBuilder, appProperties, 0);
        }
    }

    private OneMapClient client(long minIntervalMillis) {
        return new OneMapClient(builder, props(), minIntervalMillis);
    }

    private static AppProperties props() {
        return new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("app", AppProperties.class);
    }

    private static String resource(String path) {
        try (InputStream in = OneMapClientTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

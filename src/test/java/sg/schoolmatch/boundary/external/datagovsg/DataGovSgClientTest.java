package sg.schoolmatch.boundary.external.datagovsg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sg.schoolmatch.boundary.external.DataGovSgRecord;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.error.ExternalServiceUnavailableException;

/**
 * Live DataGovSgClient against a MockRestServiceServer (no network; fixtures are real datastore_search responses).
 * FR-DATA-03, NFR-MAIN-02, DC-12, DC-33.
 */
class DataGovSgClientTest {

    private static final String SEARCH = "https://data.gov.sg/api/action/datastore_search";
    private static final String SCHOOLS_PAGE_1 = SEARCH + "?resource_id=" + DataGovSgClient.SCHOOLS_DATASET
            + "&limit=1000&offset=0";
    private static final String POLL = "https://api-open.data.gov.sg/v1/public/api/datasets/"
            + DataGovSgClient.PLANNING_AREAS_DATASET + "/poll-download";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-DataGovSg-01: fetchSchools keeps only secondary and mixed-level schools, every value as text")
    void fetchSchools() {
        server.expect(requestTo(SCHOOLS_PAGE_1)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("schools.json"), MediaType.APPLICATION_JSON));

        List<DataGovSgRecord> schools = client(Map.of(), 1000).fetchSchools();

        server.verify();
        assertThat(schools).extracting(r -> r.get("school_name")).containsExactly("CATHOLIC HIGH SCHOOL",
                "CHIJ ST. THERESA'S CONVENT", "HUA YI SECONDARY SCHOOL", "ST. HILDA'S SECONDARY SCHOOL");
        assertThat(schools.getFirst().get("_id")).isEqualTo("3");
        assertThat(schools.getFirst().get("mainlevel_code")).isEqualTo("MIXED LEVEL (P1-S4)");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-DataGovSg-02: the school-level filter: SECONDARY (…) and MIXED LEVEL (…) in, PRIMARY and JC out")
    void secondaryFilter() {
        assertThat(DataGovSgClient.isSecondary(level("SECONDARY (S1-S5)"))).isTrue();
        assertThat(DataGovSgClient.isSecondary(level("SECONDARY (S1-S4)"))).isTrue();
        assertThat(DataGovSgClient.isSecondary(level("MIXED LEVEL (S1-JC2)"))).isTrue();
        assertThat(DataGovSgClient.isSecondary(level("MIXED LEVEL (P1-S4)"))).isTrue();
        assertThat(DataGovSgClient.isSecondary(level("MIXED LEVEL (S1-S5, JC1-JC2)"))).isTrue();
        assertThat(DataGovSgClient.isSecondary(level("PRIMARY"))).isFalse();
        assertThat(DataGovSgClient.isSecondary(level("JUNIOR COLLEGE"))).isFalse();
        assertThat(DataGovSgClient.isSecondary(level("CENTRALISED INSTITUTE"))).isFalse();
        assertThat(DataGovSgClient.isSecondary(level(null))).isFalse();
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-DataGovSg-03: rows are read page by page (limit + offset) until 'total' is reached")
    void paging() {
        String ccas = SEARCH + "?resource_id=" + DataGovSgClient.CCAS_DATASET + "&limit=2&offset=";
        server.expect(requestTo(ccas + "0")).andRespond(withSuccess(page(5, "a", "b"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ccas + "2")).andRespond(withSuccess(page(5, "c", "d"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ccas + "4")).andRespond(withSuccess(page(5, "e"), MediaType.APPLICATION_JSON));

        List<DataGovSgRecord> rows = client(Map.of(), 2).fetchSchoolCcas();

        server.verify();
        assertThat(rows).extracting(r -> r.get("School_name")).containsExactly("a", "b", "c", "d", "e");
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-DataGovSg-04: HTTP 429 (too many requests) is retried once after a pause")
    void retriesOnce() {
        server.expect(requestTo(SCHOOLS_PAGE_1)).andRespond(withTooManyRequests());
        server.expect(requestTo(SCHOOLS_PAGE_1)).andRespond(withSuccess(fixture("schools.json"), MediaType.APPLICATION_JSON));

        assertThat(client(Map.of(), 1000).fetchSchools()).hasSize(4);
        server.verify();
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-DataGovSg-05: a second 429, a 500, a timeout or success=false → ExternalServiceUnavailableException")
    void failures() {
        server.expect(requestTo(SCHOOLS_PAGE_1)).andRespond(withTooManyRequests());
        server.expect(requestTo(SCHOOLS_PAGE_1)).andRespond(withTooManyRequests());
        assertUnavailable(client(Map.of(), 1000));

        server.reset();
        server.expect(requestTo(SCHOOLS_PAGE_1)).andRespond(withServerError());
        assertUnavailable(client(Map.of(), 1000));

        server.reset();
        server.expect(requestTo(SCHOOLS_PAGE_1)).andRespond(withException(new SocketTimeoutException("read timed out")));
        assertUnavailable(client(Map.of(), 1000));

        server.reset();
        server.expect(requestTo(SCHOOLS_PAGE_1))
                .andRespond(withSuccess("{\"success\":false,\"error\":{\"message\":\"x\"}}", MediaType.APPLICATION_JSON));
        assertUnavailable(client(Map.of(), 1000));
    }

    @Test
    @Tag("NFR-SEC-03")
    @DisplayName("TC-DataGovSg-06: the optional API key goes in the x-api-key header, only when configured")
    void apiKeyHeader() {
        server.expect(requestTo(SCHOOLS_PAGE_1)).andExpect(header("x-api-key", "test-key"))
                .andRespond(withSuccess(fixture("schools.json"), MediaType.APPLICATION_JSON));
        client(Map.of("app.datagovsg.api-key", "test-key"), 1000).fetchSchools();
        server.verify();

        server.reset();
        server.expect(requestTo(SCHOOLS_PAGE_1)).andExpect(headerDoesNotExist("x-api-key"))
                .andRespond(withSuccess(fixture("schools.json"), MediaType.APPLICATION_JSON));
        client(Map.of(), 1000).fetchSchools();
        server.verify();
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-DataGovSg-07: planning areas: poll-download gives a signed URL, fetched as is (no key sent there)")
    void fetchDistricts() {
        String geoJson = fixture("planning-areas.geojson");
        server.expect(requestTo(POLL)).andExpect(header("x-api-key", "test-key"))
                .andRespond(withSuccess(fixture("poll-download.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URI.create("https://s3.example.test/blobs/planning-areas.geojson?X-Amz-Signature=abc%2Bdef")))
                .andExpect(headerDoesNotExist("x-api-key"))
                .andRespond(withSuccess(geoJson, MediaType.APPLICATION_OCTET_STREAM));

        String result = client(Map.of("app.datagovsg.api-key", "test-key"), 1000).fetchDistrictsGeoJson();

        server.verify();
        assertThat(result).isEqualTo(geoJson);
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-DataGovSg-08: a poll-download answer without a URL → ExternalServiceUnavailableException")
    void pollWithoutUrl() {
        server.expect(requestTo(POLL))
                .andRespond(withSuccess("{\"code\":17,\"data\":{},\"errorMsg\":\"not ready\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(Map.of(), 1000).fetchDistrictsGeoJson())
                .isInstanceOf(ExternalServiceUnavailableException.class)
                .satisfies(e -> assertThat(((ExternalServiceUnavailableException) e).getService()).isEqualTo("data.gov.sg"));
    }

    private DataGovSgClient client(Map<String, String> settings, int pageSize) {
        AppProperties props = new Binder(new MapConfigurationPropertySource(settings)).bindOrCreate("app", AppProperties.class);
        return new DataGovSgClient(builder, props, pageSize, 0, 0);
    }

    private void assertUnavailable(DataGovSgClient client) {
        assertThatThrownBy(client::fetchSchools)
                .isInstanceOf(ExternalServiceUnavailableException.class)
                .satisfies(e -> assertThat(((ExternalServiceUnavailableException) e).getService()).isEqualTo("data.gov.sg"));
        server.verify();
    }

    private static DataGovSgRecord level(String mainLevel) {
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        fields.put("mainlevel_code", mainLevel);
        return new DataGovSgRecord(fields);
    }

    /** A datastore_search page with one row per name. */
    private static String page(int total, String... names) {
        StringBuilder records = new StringBuilder();
        for (String name : names) {
            records.append(records.isEmpty() ? "" : ",").append("{\"_id\":1,\"School_name\":\"").append(name)
                    .append("\",\"cca_customized_name\":null}");
        }
        return "{\"success\":true,\"result\":{\"records\":[" + records + "],\"total\":" + total + "}}";
    }

    private static String fixture(String file) {
        try (InputStream in = DataGovSgClientTest.class.getResourceAsStream("/fixtures/external/datagovsg/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

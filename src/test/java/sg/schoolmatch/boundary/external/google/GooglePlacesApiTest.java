package sg.schoolmatch.boundary.external.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sg.schoolmatch.boundary.external.ExternalCallBudget;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.error.ExternalServiceUnavailableException;

/**
 * {@link GooglePlacesApi} against a {@link MockRestServiceServer}: the two searches, place details, errors and
 * the daily budget (FR-FACILITY-01, FR-FACILITY-04, FR-FACDETAIL-02, NFR-MAIN-02).
 */
class GooglePlacesApiTest {

    private static final String BASE = "https://places.test";
    private static final Coordinate CENTRE = new Coordinate(1.3546, 103.8443);
    private static final String SEARCH_MASK = "places.id,places.displayName,places.formattedAddress,places.location";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ExternalCallBudget budget = mock(ExternalCallBudget.class);
    private final GooglePlacesApi api = new GooglePlacesApi(builder, props(), budget);

    @Test
    @Tag("FR-FACILITY-01")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GooglePlaces-01: libraries = searchNearby, type library, 3 km circle, 20 results, key + field mask")
    void libraries_useNearbySearch() {
        server.expect(requestTo(BASE + "/v1/places:searchNearby"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                .andExpect(header("X-Goog-FieldMask", SEARCH_MASK))
                .andExpect(jsonPath("$.includedTypes[0]").value("library"))
                .andExpect(jsonPath("$.maxResultCount").value(20))
                .andExpect(jsonPath("$.locationRestriction.circle.center.latitude").value(1.3546))
                .andExpect(jsonPath("$.locationRestriction.circle.center.longitude").value(103.8443))
                .andExpect(jsonPath("$.locationRestriction.circle.radius").value(3000.0))
                .andRespond(withSuccess(GoogleRoutesApiTest.fixture("places-search-libraries.json"),
                        MediaType.APPLICATION_JSON));

        List<Facility> found = api.searchPlaces(FacilityType.LIBRARY, CENTRE);

        server.verify();
        verify(budget).charge(ExternalCallBudget.PLACES_SEARCH, 1);
        assertThat(found).hasSize(2);
        Facility library = found.getFirst();
        assertThat(library.getPlaceId()).isEqualTo("ChIJ-bishan-library-test");
        assertThat(library.getName()).isEqualTo("Bishan Public Library");
        assertThat(library.getFacilityType()).isEqualTo(FacilityType.LIBRARY);
        assertThat(library.getAddress()).isEqualTo("5 Bishan Pl, #01-01, Singapore 579841");
        assertThat(library.getCoordinate()).isEqualTo(new Coordinate(1.3503, 103.8486));
        assertThat(found.get(1).getCoordinate()).as("no location in the answer").isNull();
    }

    @Test
    @Tag("FR-FACILITY-01")
    @DisplayName("TC-GooglePlaces-02: tuition centres = searchText \"tuition centre\" biased to the 3 km circle, pageSize 20")
    void tuitionCentres_useTextSearch() {
        server.expect(requestTo(BASE + "/v1/places:searchText"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                .andExpect(header("X-Goog-FieldMask", SEARCH_MASK))
                .andExpect(jsonPath("$.textQuery").value("tuition centre"))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.locationBias.circle.radius").value(3000.0))
                .andExpect(jsonPath("$.locationBias.circle.center.latitude").value(1.3546))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));   // nothing found: no "places"

        List<Facility> found = api.searchPlaces(FacilityType.TUITION_CENTRE, CENTRE);

        server.verify();
        assertThat(found).isEmpty();
    }

    @Test
    @Tag("FR-FACDETAIL-02")
    @DisplayName("TC-GooglePlaces-03: details = GET /v1/places/{id} with phone, website and opening hours")
    void details_parsed() {
        server.expect(requestTo(BASE + "/v1/places/ChIJ-bishan-library-test"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                .andExpect(header("X-Goog-FieldMask", "id,displayName,formattedAddress,location,"
                        + "nationalPhoneNumber,websiteUri,regularOpeningHours.weekdayDescriptions"))
                .andRespond(withSuccess(GoogleRoutesApiTest.fixture("place-details.json"), MediaType.APPLICATION_JSON));

        Optional<Facility> details = api.getPlaceDetails("ChIJ-bishan-library-test");

        server.verify();
        verify(budget).charge(ExternalCallBudget.PLACE_DETAILS, 1);
        assertThat(details).get().satisfies(f -> {
            assertThat(f.getName()).isEqualTo("Bishan Public Library");
            assertThat(f.getTelephone()).isEqualTo("6332 3255");
            assertThat(f.getWebsite()).isEqualTo("https://www.nlb.gov.sg/");
            assertThat(f.getOpeningHours()).isEqualTo("Monday: 10:00 AM – 9:00 PM; Tuesday: 10:00 AM – 9:00 PM");
            assertThat(f.getFacilityType()).as("not in the details answer").isNull();
        });
    }

    @Test
    @Tag("FR-FACDETAIL-01")
    @DisplayName("TC-GooglePlaces-04: details of an unknown id (404 or 400) → empty, not an error")
    void details_unknownId_isEmpty() {
        server.expect(requestTo(BASE + "/v1/places/unknown-id")).andRespond(withResourceNotFound());
        server.expect(requestTo(BASE + "/v1/places/bad-id")).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(api.getPlaceDetails("unknown-id")).isEmpty();
        assertThat(api.getPlaceDetails("bad-id")).isEmpty();
        server.verify();
    }

    @Test
    @Tag("FR-FACILITY-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-GooglePlaces-05: 429, 500 and a timeout → ExternalServiceUnavailableException(\"Google Places\")")
    void errors_becomeServiceUnavailable() {
        server.expect(requestTo(BASE + "/v1/places:searchNearby")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(BASE + "/v1/places/some-id")).andRespond(withServerError());
        server.expect(requestTo(BASE + "/v1/places:searchText"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertServiceUnavailable(() -> api.searchPlaces(FacilityType.LIBRARY, CENTRE));
        assertServiceUnavailable(() -> api.getPlaceDetails("some-id"));
        assertServiceUnavailable(() -> api.searchPlaces(FacilityType.TUITION_CENTRE, CENTRE));
        server.verify();
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GooglePlaces-06: budget used up → ExternalServiceUnavailableException and no HTTP request")
    void budgetExhausted_noHttpCall() {
        doThrow(new ExternalServiceUnavailableException("Google places-search", null))
                .when(budget).charge(anyString(), anyInt());

        assertThatThrownBy(() -> api.searchPlaces(FacilityType.LIBRARY, CENTRE))
                .isInstanceOf(ExternalServiceUnavailableException.class);
        assertThatThrownBy(() -> api.getPlaceDetails("ChIJ-bishan-library-test"))
                .isInstanceOf(ExternalServiceUnavailableException.class);
        server.verify();
    }

    @Test
    @Tag("FR-FACILITY-01")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GooglePlaces-07: Google's 403 answer is kept for the log line (status, reason, message), never the key")
    void refusal_describedForTheLog() {
        server.expect(requestTo(BASE + "/v1/places:searchNearby"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(
                        "{\"error\": {\"code\": 403, \"message\": \"API key not valid. Please pass a valid API key.\","
                        + " \"status\": \"PERMISSION_DENIED\", \"details\": [{\"@type\": "
                        + "\"type.googleapis.com/google.rpc.ErrorInfo\", \"reason\": \"API_KEY_INVALID\"}]}}"));

        ExternalServiceUnavailableException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                ExternalServiceUnavailableException.class, () -> api.searchPlaces(FacilityType.LIBRARY, CENTRE));

        assertThat(sg.schoolmatch.error.ExternalFailureLog.describe(e))
                .isEqualTo("Google Places: Google refused the request: HTTP 403, PERMISSION_DENIED, API_KEY_INVALID: "
                        + "API key not valid. Please pass a valid API key.")
                .doesNotContain("test-key");
        server.verify();
    }

    private static void assertServiceUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ExternalServiceUnavailableException.class)
                .extracting(e -> ((ExternalServiceUnavailableException) e).getService())
                .isEqualTo("Google Places");
    }

    private static AppProperties props() {
        return new Binder(new MapConfigurationPropertySource(Map.of(
                "app.google.server-key", "test-key",
                "app.google.places-base-url", BASE)))
                .bindOrCreate("app", AppProperties.class);
    }
}

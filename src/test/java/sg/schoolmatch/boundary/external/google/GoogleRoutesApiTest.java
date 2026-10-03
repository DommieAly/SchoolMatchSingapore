package sg.schoolmatch.boundary.external.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sg.schoolmatch.boundary.external.ExternalCallBudget;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.config.CacheConfig;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.RouteStep;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.error.ExternalServiceUnavailableException;

/**
 * {@link GoogleRoutesApi} against a {@link MockRestServiceServer} (no network, no real key): request shape,
 * headers, parsing, batching, caching, errors and the daily budget (FR-ROUTE-05, FR-ROUTE-06, DC-05,
 * NFR-MAIN-02). Responses are recorded-style fixtures in {@code fixtures/external/google/}.
 */
class GoogleRoutesApiTest {

    private static final String BASE = "https://routes.test";
    private static final Coordinate BISHAN = new Coordinate(1.351, 103.848);
    private static final Coordinate TOA_PAYOH = new Coordinate(1.33269, 103.84776);

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ExternalCallBudget budget = mock(ExternalCallBudget.class);
    private final ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager(CacheConfig.ROUTES);

    @Test
    @Tag("FR-ROUTE-05")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleRoutes-01: computeRoutes is a POST with the key and field-mask headers and the documented body")
    void computeRoute_sendsDocumentedRequest() {
        GoogleRoutesApi api = api(100);
        server.expect(requestTo(BASE + "/directions/v2:computeRoutes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                .andExpect(header("X-Goog-FieldMask", GoogleRoutesApi.ROUTE_FIELD_MASK))
                .andExpect(jsonPath("$.origin.location.latLng.latitude").value(1.351))
                .andExpect(jsonPath("$.origin.location.latLng.longitude").value(103.848))
                .andExpect(jsonPath("$.destination.location.latLng.latitude").value(1.33269))
                .andExpect(jsonPath("$.travelMode").value("TRANSIT"))
                .andExpect(jsonPath("$.computeAlternativeRoutes").value(false))
                .andExpect(jsonPath("$.languageCode").value("en-GB"))
                .andExpect(jsonPath("$.units").value("METRIC"))
                .andRespond(withSuccess(fixture("compute-routes-transit.json"), MediaType.APPLICATION_JSON));

        api.computeRoute(BISHAN, TOA_PAYOH, TravelMode.TRANSIT);

        server.verify();
        assertThat(GoogleRoutesApi.ROUTE_FIELD_MASK).isEqualTo("routes.distanceMeters,routes.duration,"
                + "routes.polyline.encodedPolyline,routes.legs.steps.distanceMeters,"
                + "routes.legs.steps.navigationInstruction.instructions,"
                + "routes.legs.steps.transitDetails.transitLine.nameShort,routes.legs.steps.travelMode");
    }

    @Test
    @Tag("FR-ROUTE-06")
    @Tag("FR-ROUTE-07")
    @DisplayName("TC-GoogleRoutes-02: the answer becomes a Route: \"1234s\" → 1234 s, distance, polyline, steps 1..n")
    void computeRoute_parsesResponse() {
        GoogleRoutesApi api = api(100);
        server.expect(requestTo(BASE + "/directions/v2:computeRoutes"))
                .andRespond(withSuccess(fixture("compute-routes-transit.json"), MediaType.APPLICATION_JSON));

        Route route = api.computeRoute(BISHAN, TOA_PAYOH, TravelMode.TRANSIT);

        assertThat(route.isAvailable()).isTrue();
        assertThat(route.getTravelMode()).isEqualTo(TravelMode.TRANSIT);
        assertThat(route.getDurationSeconds()).isEqualTo(1234);
        assertThat(route.getDistanceMetres()).isEqualTo(5321);
        assertThat(route.getEncodedPolyline()).isEqualTo("kxkGa}lyRoAcBuCaE");
        assertThat(route.getSteps()).extracting(RouteStep::getSequenceNo).containsExactly(1, 2, 3);
        assertThat(route.getSteps()).extracting(RouteStep::getInstruction).containsExactly(
                "Walk to Bishan Int", "Bus towards Toa Payoh Int (52)", "Continue");
        assertThat(route.getSteps()).extracting(RouteStep::getDistanceMetres).containsExactly(250, 4900, 171);
    }

    @Test
    @Tag("FR-ROUTE-08")
    @DisplayName("TC-GoogleRoutes-03: no routes in the answer ({}) → Route.unavailable(mode)")
    void computeRoute_emptyAnswer_isUnavailable() {
        GoogleRoutesApi api = api(100);
        server.expect(requestTo(BASE + "/directions/v2:computeRoutes"))
                .andRespond(withSuccess(fixture("compute-routes-empty.json"), MediaType.APPLICATION_JSON));

        Route route = api.computeRoute(BISHAN, TOA_PAYOH, TravelMode.WALK);

        assertThat(route.isAvailable()).isFalse();
        assertThat(route.getTravelMode()).isEqualTo(TravelMode.WALK);
        assertThat(route.getSteps()).isEmpty();
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleRoutes-04: the budget is charged (routes, 1) before the request is sent")
    void computeRoute_chargesBudgetFirst() {
        GoogleRoutesApi api = api(100);
        server.expect(requestTo(BASE + "/directions/v2:computeRoutes"))
                .andRespond(withSuccess(fixture("compute-routes-empty.json"), MediaType.APPLICATION_JSON));

        api.computeRoute(BISHAN, TOA_PAYOH, TravelMode.DRIVE);

        verify(budget).charge(ExternalCallBudget.ROUTES, 1);
        server.verify();
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleRoutes-05: budget used up → ExternalServiceUnavailableException and no HTTP request")
    void budgetExhausted_noHttpCall() {
        GoogleRoutesApi api = api(100);
        doThrow(new ExternalServiceUnavailableException("Google routes", null))
                .when(budget).charge(anyString(), anyInt());

        assertThatThrownBy(() -> api.computeRoute(BISHAN, TOA_PAYOH, TravelMode.WALK))
                .isInstanceOf(ExternalServiceUnavailableException.class);
        assertThatThrownBy(() -> api.computeRouteMatrix(BISHAN, List.of(TOA_PAYOH), TravelMode.WALK))
                .isInstanceOf(ExternalServiceUnavailableException.class);
        server.verify();   // no request was expected, and none was sent
    }

    @Test
    @Tag("FR-ROUTE-05")
    @Tag("NFR-USE-03")
    @DisplayName("TC-GoogleRoutes-06: HTTP 429, HTTP 500, a timeout and unreadable JSON → ExternalServiceUnavailableException(\"Google Routes\")")
    void httpErrorsAndTimeout_becomeServiceUnavailable() {
        GoogleRoutesApi api = api(100);
        server.expect(ExpectedCount.once(), requestTo(BASE + "/directions/v2:computeRoutes"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(ExpectedCount.once(), requestTo(BASE + "/directions/v2:computeRoutes"))
                .andRespond(withServerError());
        server.expect(ExpectedCount.once(), requestTo(BASE + "/directions/v2:computeRoutes"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(ExpectedCount.once(), requestTo(BASE + "/directions/v2:computeRoutes"))
                .andRespond(withSuccess("{not json", MediaType.APPLICATION_JSON));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> api.computeRoute(BISHAN, TOA_PAYOH, TravelMode.WALK))
                    .isInstanceOf(ExternalServiceUnavailableException.class)
                    .extracting(e -> ((ExternalServiceUnavailableException) e).getService())
                    .isEqualTo("Google Routes");
        }
        server.verify();
    }

    @Test
    @Tag("FR-ROUTE-05")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleRoutes-07: the key is never in the URL or in the error message")
    void keyNeverInUrlOrMessage() {
        GoogleRoutesApi api = api(100);
        server.expect(requestTo(Matchers.not(Matchers.containsString("test-key"))))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"error\":{\"status\":\"PERMISSION_DENIED\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> api.computeRoute(BISHAN, TOA_PAYOH, TravelMode.WALK))
                .isInstanceOf(ExternalServiceUnavailableException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("test-key");
                    assertThat(e.getCause().getMessage()).doesNotContain("test-key");
                });
        server.verify();
    }

    @Test
    @Tag("FR-FILTER-06")
    @Tag("FR-ROUTE-05")
    @DisplayName("TC-GoogleRoutes-08: the matrix answer (a JSON array) is matched by destinationIndex; a missing index means 0")
    void matrix_parsesArrayInInputOrder() {
        GoogleRoutesApi api = api(100);
        Coordinate a = new Coordinate(1.34, 103.84);
        Coordinate b = new Coordinate(1.35, 103.85);
        Coordinate c = new Coordinate(1.36, 103.86);
        server.expect(requestTo(BASE + "/distanceMatrix/v2:computeRouteMatrix"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                .andExpect(header("X-Goog-FieldMask", "originIndex,destinationIndex,duration,distanceMeters,status,condition"))
                .andExpect(jsonPath("$.origins.length()").value(1))
                .andExpect(jsonPath("$.origins[0].waypoint.location.latLng.latitude").value(1.351))
                .andExpect(jsonPath("$.destinations.length()").value(3))
                .andExpect(jsonPath("$.destinations[2].waypoint.location.latLng.longitude").value(103.86))
                .andExpect(jsonPath("$.travelMode").value("TRANSIT"))
                .andRespond(withSuccess(fixture("route-matrix.json"), MediaType.APPLICATION_JSON));

        List<Route> routes = api.computeRouteMatrix(BISHAN, List.of(a, b, c), TravelMode.TRANSIT);

        assertThat(routes).hasSize(3);
        assertThat(routes.get(0).isAvailable()).isTrue();
        assertThat(routes.get(0).getDurationSeconds()).isEqualTo(300);
        assertThat(routes.get(0).getDistanceMetres()).isEqualTo(1200);
        assertThat(routes.get(1).isAvailable()).as("ROUTE_NOT_FOUND").isFalse();
        assertThat(routes.get(2).getDurationSeconds()).isEqualTo(900);
        verify(budget).charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, 3);
        server.verify();
    }

    @Test
    @Tag("FR-FILTER-06")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleRoutes-09: 5 destinations with batch size 2 → 3 requests, all 5 elements charged once, before the first")
    void matrix_batches() {
        GoogleRoutesApi api = api(2);
        List<Coordinate> destinations = destinations(5);
        server.expect(requestTo(BASE + "/distanceMatrix/v2:computeRouteMatrix"))
                .andExpect(jsonPath("$.destinations.length()").value(2))
                .andRespond(withSuccess(matrixAnswer(2, 100), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/distanceMatrix/v2:computeRouteMatrix"))
                .andExpect(jsonPath("$.destinations.length()").value(2))
                .andRespond(withSuccess(matrixAnswer(2, 200), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/distanceMatrix/v2:computeRouteMatrix"))
                .andExpect(jsonPath("$.destinations.length()").value(1))
                .andRespond(withSuccess(matrixAnswer(1, 300), MediaType.APPLICATION_JSON));

        List<Route> routes = api.computeRouteMatrix(BISHAN, destinations, TravelMode.DRIVE);

        assertThat(routes).extracting(Route::getDurationSeconds).containsExactly(100, 101, 200, 201, 300);
        verify(budget).charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, 5);
        verifyNoMoreInteractions(budget);
        server.verify();
    }

    @Test
    @Tag("FR-FILTER-06")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleRoutes-13: a matrix that does not fit in what is left of today's limit sends no batch at all (147 TRANSIT elements)")
    void matrix_refusedWholeRequestSendsNothing() {
        GoogleRoutesApi api = api(150);
        InOrder order = inOrder(budget);
        doThrow(new ExternalServiceUnavailableException("Google " + ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, null))
                .when(budget).charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, 147);

        assertThatThrownBy(() -> api.computeRouteMatrix(BISHAN, destinations(147), TravelMode.TRANSIT))
                .isInstanceOf(ExternalServiceUnavailableException.class);

        order.verify(budget).charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, 147);
        verifyNoMoreInteractions(budget);
        server.verify();   // no request was expected, and none was sent (before: batch 1 of 100 was paid and sent)
    }

    @Test
    @Tag("FR-FILTER-06")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleRoutes-15: Google's daily cap (429 RESOURCE_EXHAUSTED, JSON array) is described as Google refusing, without the key")
    void matrixQuotaRefusal_describedForTheLog() {
        GoogleRoutesApi api = api(100);
        server.expect(requestTo(BASE + "/distanceMatrix/v2:computeRouteMatrix"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("[{\"error\": {\"code\": 429, \"message\": \"Quota exceeded for quota metric "
                                + "'DistanceMatrix elements' and limit 'per day'.\", \"status\": \"RESOURCE_EXHAUSTED\"}}]"));

        ExternalServiceUnavailableException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                ExternalServiceUnavailableException.class,
                () -> api.computeRouteMatrix(BISHAN, destinations(3), TravelMode.TRANSIT));

        assertThat(sg.schoolmatch.error.ExternalFailureLog.describe(e))
                .startsWith("Google Routes: Google refused the request: HTTP 429, RESOURCE_EXHAUSTED: Quota exceeded")
                .doesNotContain("test-key");
        server.verify();
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleRoutes-14: a matrix whose destinations are all cached or null charges nothing")
    void matrix_nothingToFetchChargesNothing() {
        GoogleRoutesApi api = api(100);
        List<Coordinate> first = destinations(2);
        server.expect(requestTo(BASE + "/distanceMatrix/v2:computeRouteMatrix"))
                .andRespond(withSuccess(matrixAnswer(2, 100), MediaType.APPLICATION_JSON));
        api.computeRouteMatrix(BISHAN, first, TravelMode.DRIVE);

        List<Coordinate> again = new ArrayList<>(first);
        again.add(null);
        List<Route> routes = api.computeRouteMatrix(BISHAN, again, TravelMode.DRIVE);

        assertThat(routes).extracting(Route::getDurationSeconds).containsExactly(100, 101, null);
        verify(budget).charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, 2);
        verifyNoMoreInteractions(budget);
        server.verify();
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-GoogleRoutes-10: TRANSIT batches never pass Google's 100-element limit, whatever the setting")
    void matrix_transitBatchCappedAt100() {
        GoogleRoutesApi api = api(150);
        server.expect(requestTo(BASE + "/distanceMatrix/v2:computeRouteMatrix"))
                .andExpect(jsonPath("$.destinations.length()").value(100))
                .andRespond(withSuccess(matrixAnswer(100, 1000), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/distanceMatrix/v2:computeRouteMatrix"))
                .andExpect(jsonPath("$.destinations.length()").value(20))
                .andRespond(withSuccess(matrixAnswer(20, 2000), MediaType.APPLICATION_JSON));

        List<Route> routes = api.computeRouteMatrix(BISHAN, destinations(120), TravelMode.TRANSIT);

        assertThat(routes).hasSize(120).allMatch(Route::isAvailable);
        server.verify();
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleRoutes-11: matrix answers are cached; a repeat sends only new destinations; null → unavailable, not sent")
    void matrix_cachesElements() {
        GoogleRoutesApi api = api(100);
        List<Coordinate> first = destinations(2);
        Coordinate extra = new Coordinate(1.40, 103.90);
        server.expect(requestTo(BASE + "/distanceMatrix/v2:computeRouteMatrix"))
                .andExpect(jsonPath("$.destinations.length()").value(2))
                .andRespond(withSuccess(matrixAnswer(2, 100), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/distanceMatrix/v2:computeRouteMatrix"))
                .andExpect(jsonPath("$.destinations.length()").value(1))
                .andExpect(jsonPath("$.destinations[0].waypoint.location.latLng.latitude").value(1.40))
                .andRespond(withSuccess(matrixAnswer(1, 500), MediaType.APPLICATION_JSON));

        api.computeRouteMatrix(BISHAN, first, TravelMode.WALK);
        List<Coordinate> second = new ArrayList<>(first);
        second.add(1, extra);
        second.add(null);
        List<Route> routes = api.computeRouteMatrix(BISHAN, second, TravelMode.WALK);

        assertThat(routes).extracting(Route::getDurationSeconds).containsExactly(100, 500, 101, null);
        assertThat(routes.get(3).isAvailable()).isFalse();
        server.verify();
        verify(budget).charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, 2);
        verify(budget).charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, 1);
        verifyNoMoreInteractions(budget);
    }

    @Test
    @Tag("FR-ROUTE-06")
    @DisplayName("TC-GoogleRoutes-12: durations \"1234s\" and \"3.5s\" are read; a bad one is null")
    void parseSeconds() {
        assertThat(RoutesJson.parseSeconds("1234s")).isEqualTo(1234);
        assertThat(RoutesJson.parseSeconds("3.5s")).isEqualTo(4);
        assertThat(RoutesJson.parseSeconds("0s")).isZero();
        assertThat(RoutesJson.parseSeconds("12m")).isNull();
        assertThat(RoutesJson.parseSeconds(null)).isNull();
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private GoogleRoutesApi api(int batchSize) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("app.google.server-key", "test-key");
        settings.put("app.google.routes-base-url", BASE);
        settings.put("app.google.matrix-batch-size", String.valueOf(batchSize));
        AppProperties props = new Binder(new MapConfigurationPropertySource(settings))
                .bindOrCreate("app", AppProperties.class);
        return new GoogleRoutesApi(builder, props, budget, cacheManager);
    }

    /** {@code count} different points in Singapore. */
    private static List<Coordinate> destinations(int count) {
        List<Coordinate> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new Coordinate(1.30 + i * 0.0001, 103.80 + i * 0.0001));
        }
        return points;
    }

    /** A matrix answer for {@code count} destinations, in reverse order, element i taking firstSeconds + i. */
    private static String matrixAnswer(int count, int firstSeconds) {
        List<String> elements = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            String index = i == 0 ? "" : "\"destinationIndex\": " + i + ", ";   // Google leaves out index 0
            elements.add("{" + index + "\"status\": {}, \"distanceMeters\": " + (1000 + i) + ", \"duration\": \""
                    + (firstSeconds + i) + "s\", \"condition\": \"ROUTE_EXISTS\"}");
        }
        return "[" + String.join(",", elements) + "]";
    }

    static String fixture(String name) {
        try (InputStream in = GoogleRoutesApiTest.class.getResourceAsStream("/fixtures/external/google/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

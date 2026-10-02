package sg.schoolmatch.boundary.external.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sg.schoolmatch.boundary.external.ExternalCallBudget;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.config.CacheConfig;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.TravelMode;

/**
 * The live {@link GoogleMapsPlatformClient} wired with Spring's caching (the app's {@link CacheConfig}), the two
 * API classes and mock HTTP servers: coordinates are rounded before they become cache keys, so a repeat
 * near the same point costs no second request or charge (NFR-MAIN-02).
 */
@SpringJUnitConfig(GoogleMapsPlatformClientTest.Config.class)
class GoogleMapsPlatformClientTest {

    private static final String ROUTES = "https://routes.test";
    private static final String PLACES = "https://places.test";

    @Autowired
    private GoogleMapsPlatformClient client;

    @Autowired
    @Qualifier("routesServer")
    private MockRestServiceServer routesServer;

    @Autowired
    @Qualifier("placesServer")
    private MockRestServiceServer placesServer;

    @Autowired
    private ExternalCallBudget budget;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCachesAndMocks() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        routesServer.reset();
        placesServer.reset();
        reset(budget);
    }

    @Test
    @Tag("FR-ROUTE-05")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleClient-01: two origins that round to the same 3 decimals share one cached route (one request, one charge)")
    void routeCachedByRoundedOrigin() {
        routesServer.expect(requestTo(ROUTES + "/directions/v2:computeRoutes"))
                .andExpect(jsonPath("$.origin.location.latLng.latitude").value(1.351))
                .andExpect(jsonPath("$.origin.location.latLng.longitude").value(103.848))
                .andExpect(jsonPath("$.destination.location.latLng.latitude").value(1.33269))
                .andRespond(withSuccess(GoogleRoutesApiTest.fixture("compute-routes-transit.json"),
                        MediaType.APPLICATION_JSON));
        Coordinate school = new Coordinate(1.332691, 103.847761);

        Route first = client.computeRoute(new Coordinate(1.35101, 103.84849), school, TravelMode.TRANSIT);
        Route second = client.computeRoute(new Coordinate(1.35149, 103.84801), school, TravelMode.TRANSIT);

        routesServer.verify();
        verify(budget, times(1)).charge(ExternalCallBudget.ROUTES, 1);
        assertThat(second.getDurationSeconds()).isEqualTo(first.getDurationSeconds()).isEqualTo(1234);
    }

    @Test
    @Tag("FR-FACILITY-01")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-GoogleClient-02: places search is cached by (type, centre to 4 decimals); details by id")
    void placesAndDetailsCached() {
        placesServer.expect(requestTo(PLACES + "/v1/places:searchNearby"))
                .andExpect(jsonPath("$.locationRestriction.circle.center.latitude").value(1.3546))
                .andRespond(withSuccess(GoogleRoutesApiTest.fixture("places-search-libraries.json"),
                        MediaType.APPLICATION_JSON));
        placesServer.expect(requestTo(PLACES + "/v1/places/ChIJ-bishan-library-test"))
                .andRespond(withSuccess(GoogleRoutesApiTest.fixture("place-details.json"), MediaType.APPLICATION_JSON));

        assertThat(client.searchPlaces(FacilityType.LIBRARY, new Coordinate(1.35461, 103.84432))).hasSize(2);
        assertThat(client.searchPlaces(FacilityType.LIBRARY, new Coordinate(1.35459, 103.84428))).hasSize(2);
        assertThat(client.getPlaceDetails("ChIJ-bishan-library-test")).isPresent();
        assertThat(client.getPlaceDetails("ChIJ-bishan-library-test")).isPresent();

        placesServer.verify();
        verify(budget, times(1)).charge(ExternalCallBudget.PLACES_SEARCH, 1);
        verify(budget, times(1)).charge(ExternalCallBudget.PLACE_DETAILS, 1);
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-GoogleClient-03: matrix destinations are rounded to 5 decimals; no destinations or no origin → no request")
    void matrixRoundingAndEmptyCases() {
        routesServer.expect(requestTo(ROUTES + "/distanceMatrix/v2:computeRouteMatrix"))
                .andExpect(jsonPath("$.destinations[0].waypoint.location.latLng.latitude").value(1.36))
                .andExpect(jsonPath("$.destinations[0].waypoint.location.latLng.longitude").value(103.85001))
                .andRespond(withSuccess("[{\"status\": {}, \"distanceMeters\": 900, \"duration\": \"60s\","
                        + " \"condition\": \"ROUTE_EXISTS\"}]", MediaType.APPLICATION_JSON));

        List<Route> routes = client.computeRouteMatrix(new Coordinate(1.351, 103.848),
                List.of(new Coordinate(1.360001, 103.850009)), TravelMode.WALK);
        assertThat(routes).extracting(Route::getDurationSeconds).containsExactly(60);

        assertThat(client.computeRouteMatrix(new Coordinate(1.351, 103.848), List.of(), TravelMode.WALK)).isEmpty();
        assertThat(client.computeRouteMatrix(null, Arrays.asList(new Coordinate(1.36, 103.85), null), TravelMode.WALK))
                .hasSize(2).noneMatch(Route::isAvailable);
        routesServer.verify();
    }

    @Test
    @Tag("FR-ROUTE-05")
    @DisplayName("TC-GoogleClient-04: live mode without a server key stops start-up with a clear message")
    void noServerKey_failsFast() {
        AppProperties noKey = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("app", AppProperties.class);

        assertThatThrownBy(() -> new GoogleMapsPlatformClient(noKey, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GOOGLE_MAPS_SERVER_KEY");
    }

    @Test
    @Tag("FR-ROUTE-05")
    @DisplayName("TC-GoogleClient-05: rounding keeps the point inside Singapore and to the given decimals")
    void roundCoordinate() {
        assertThat(GoogleMapsPlatformClient.round(new Coordinate(1.3456789, 103.8765432), 3))
                .isEqualTo(new Coordinate(1.346, 103.877));
        assertThat(GoogleMapsPlatformClient.round(new Coordinate(1.3456789, 103.8765432), 5))
                .isEqualTo(new Coordinate(1.34568, 103.87654));
    }

    /** The real CacheConfig plus the three Google classes, each API with its own builder and mock server. */
    @Configuration
    @Import(CacheConfig.class)
    static class Config {

        @Bean
        AppProperties appProperties() {
            return new Binder(new MapConfigurationPropertySource(Map.of(
                    "app.google.server-key", "test-key",
                    "app.google.routes-base-url", ROUTES,
                    "app.google.places-base-url", PLACES)))
                    .bindOrCreate("app", AppProperties.class);
        }

        @Bean
        ExternalCallBudget externalCallBudget() {
            return mock(ExternalCallBudget.class);
        }

        @Bean
        RestClient.Builder routesBuilder() {
            return RestClient.builder();
        }

        @Bean
        RestClient.Builder placesBuilder() {
            return RestClient.builder();
        }

        @Bean
        MockRestServiceServer routesServer(@Qualifier("routesBuilder") RestClient.Builder routesBuilder) {
            return MockRestServiceServer.bindTo(routesBuilder).build();
        }

        @Bean
        MockRestServiceServer placesServer(@Qualifier("placesBuilder") RestClient.Builder placesBuilder) {
            return MockRestServiceServer.bindTo(placesBuilder).build();
        }

        @Bean
        GoogleRoutesApi googleRoutesApi(@Qualifier("routesBuilder") RestClient.Builder routesBuilder,
                                        @Qualifier("routesServer") MockRestServiceServer bound,
                                        AppProperties props, ExternalCallBudget budget, CacheManager cacheManager) {
            return new GoogleRoutesApi(routesBuilder, props, budget, cacheManager);
        }

        @Bean
        GooglePlacesApi googlePlacesApi(@Qualifier("placesBuilder") RestClient.Builder placesBuilder,
                                        @Qualifier("placesServer") MockRestServiceServer bound,
                                        AppProperties props, ExternalCallBudget budget) {
            return new GooglePlacesApi(placesBuilder, props, budget);
        }

        @Bean
        GoogleMapsPlatformClient googleMapsPlatformClient(AppProperties props, GoogleRoutesApi routesApi,
                                                          GooglePlacesApi placesApi) {
            return new GoogleMapsPlatformClient(props, routesApi, placesApi);
        }
    }
}

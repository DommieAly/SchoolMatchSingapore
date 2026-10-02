package sg.schoolmatch.boundary.external.google;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import sg.schoolmatch.boundary.external.ExternalCallBudget;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.config.CacheConfig;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.error.ExternalServiceUnavailableException;

/**
 * Live calls to the Google Routes API v2 for {@link GoogleMapsPlatformClient} (FR-ROUTE-05, DC-05, NFR-MAIN-02).
 * <ul>
 *   <li>Every request first calls {@link ExternalCallBudget#charge}; when today's limit is used up nothing is
 *       sent.</li>
 *   <li>The server key goes in the {@code X-Goog-Api-Key} header, never in a URL or a log line.</li>
 *   <li>4xx/5xx, timeouts and unreadable answers → {@code ExternalServiceUnavailableException("Google Routes")}.</li>
 *   <li>Caching ({@code routes} cache, 30 min): a route by (origin, destination, mode); the matrix per
 *       destination, so a repeated travel-time filter costs nothing. The client rounds the coordinates first.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.external.google.mode", havingValue = "live")
public class GoogleRoutesApi {   // DC-63

    static final String SERVICE = "Google Routes";
    static final String COMPUTE_ROUTES_PATH = "/directions/v2:computeRoutes";
    static final String COMPUTE_ROUTE_MATRIX_PATH = "/distanceMatrix/v2:computeRouteMatrix";
    static final String ROUTE_FIELD_MASK = String.join(",",
            "routes.distanceMeters",
            "routes.duration",
            "routes.polyline.encodedPolyline",
            "routes.legs.steps.distanceMeters",
            "routes.legs.steps.navigationInstruction.instructions",
            "routes.legs.steps.transitDetails.transitLine.nameShort",
            "routes.legs.steps.travelMode");
    static final String MATRIX_FIELD_MASK = "originIndex,destinationIndex,duration,distanceMeters,status,condition";

    /** Google's limit of elements per matrix request: 100 for TRANSIT, 625 otherwise (one origin here). */
    private static final int MAX_TRANSIT_ELEMENTS = 100;
    private static final int MAX_ELEMENTS = 625;

    private static final ParameterizedTypeReference<RoutesJson.ComputeRoutesResponse> ROUTES_RESPONSE =
            new ParameterizedTypeReference<>() { };
    /** The matrix answer is a JSON array of elements. */
    private static final ParameterizedTypeReference<List<RoutesJson.MatrixElement>> MATRIX_RESPONSE =
            new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final ExternalCallBudget budget;
    private final Cache cache;
    private final int batchSize;

    public GoogleRoutesApi(RestClient.Builder restClientBuilder, AppProperties props, ExternalCallBudget budget,
                           CacheManager cacheManager) {
        this.restClient = restClientBuilder
                .baseUrl(props.google().routesBaseUrl())
                .defaultHeader(GoogleHeaders.API_KEY, props.google().serverKey())
                .build();
        this.budget = budget;
        this.cache = cacheManager.getCache(CacheConfig.ROUTES);
        this.batchSize = Math.max(1, props.google().matrixBatchSize());
    }

    /**
     * One route (computeRoutes). {@code Route.unavailable(mode)} when Google returns no route.
     * Cached for 30 min by (origin, destination, mode).
     */
    @Cacheable(CacheConfig.ROUTES)
    public Route computeRoute(Coordinate origin, Coordinate destination, TravelMode mode) {
        budget.charge(ExternalCallBudget.ROUTES, 1);
        RoutesJson.ComputeRoutesResponse response = post(COMPUTE_ROUTES_PATH, ROUTE_FIELD_MASK,
                RoutesJson.ComputeRoutesRequest.of(origin, destination, mode), ROUTES_RESPONSE);
        return response == null ? Route.unavailable(mode) : response.toRoute(mode);
    }

    /**
     * Commute time and distance from one origin to each destination, in the same order (DC-05).
     * A null destination gives an unavailable route without a request. Destinations already in the cache are
     * not sent again; the rest go in batches of {@code app.google.matrix-batch-size}, each charged as
     * {@code ROUTE_MATRIX_ELEMENTS} = batch size before it is sent.
     */
    public List<Route> computeRouteMatrix(Coordinate origin, List<Coordinate> destinations, TravelMode mode) {
        Route[] result = new Route[destinations.size()];
        Map<Coordinate, List<Integer>> toFetch = new LinkedHashMap<>();   // distinct destination → positions
        for (int i = 0; i < destinations.size(); i++) {
            Coordinate destination = destinations.get(i);
            if (destination == null) {
                result[i] = Route.unavailable(mode);
                continue;
            }
            Route cached = cache == null ? null : cache.get(new MatrixKey(origin, destination, mode), Route.class);
            if (cached != null) {
                result[i] = cached;
            } else {
                toFetch.computeIfAbsent(destination, d -> new ArrayList<>()).add(i);
            }
        }
        List<Coordinate> pending = new ArrayList<>(toFetch.keySet());
        int size = Math.min(batchSize, mode == TravelMode.TRANSIT ? MAX_TRANSIT_ELEMENTS : MAX_ELEMENTS);
        for (int start = 0; start < pending.size(); start += size) {
            List<Coordinate> batch = pending.subList(start, Math.min(start + size, pending.size()));
            Route[] routes = fetchBatch(origin, batch, mode);
            for (int j = 0; j < batch.size(); j++) {
                for (int position : toFetch.get(batch.get(j))) {
                    result[position] = routes[j];
                }
            }
        }
        return List.of(result);
    }

    /** One matrix request; answers are cached, except elements Google did not return or marked as errors. */
    private Route[] fetchBatch(Coordinate origin, List<Coordinate> batch, TravelMode mode) {
        budget.charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, batch.size());
        List<RoutesJson.MatrixElement> elements = post(COMPUTE_ROUTE_MATRIX_PATH, MATRIX_FIELD_MASK,
                RoutesJson.ComputeRouteMatrixRequest.of(origin, batch, mode), MATRIX_RESPONSE);
        Route[] routes = new Route[batch.size()];
        for (RoutesJson.MatrixElement element : elements == null ? List.<RoutesJson.MatrixElement>of() : elements) {
            int index = element == null ? -1 : element.destination();
            if (index < 0 || index >= batch.size()) {
                continue;
            }
            routes[index] = element.toRoute(mode);
            boolean answered = element.routeExists() || "ROUTE_NOT_FOUND".equals(element.condition());
            if (cache != null && answered) {
                cache.put(new MatrixKey(origin, batch.get(index), mode), routes[index]);
            }
        }
        for (int j = 0; j < routes.length; j++) {
            if (routes[j] == null) {
                routes[j] = Route.unavailable(mode);   // Google returned no element for this destination
            }
        }
        return routes;
    }

    private <T> T post(String path, String fieldMask, Object body, ParameterizedTypeReference<T> type) {
        try {
            return restClient.post()
                    .uri(path)
                    .header(GoogleHeaders.FIELD_MASK, fieldMask)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(type);
        } catch (RestClientException e) {   // HTTP 4xx/5xx, timeout or I/O error, unreadable JSON
            throw new ExternalServiceUnavailableException(SERVICE, e);
        }
    }

    /** Cache key of one matrix element; a different type from computeRoute's keys, so the two never mix. */
    record MatrixKey(Coordinate origin, Coordinate destination, TravelMode mode) {
    }
}

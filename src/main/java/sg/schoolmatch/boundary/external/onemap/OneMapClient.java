package sg.schoolmatch.boundary.external.onemap;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import sg.schoolmatch.boundary.external.OneMapHit;
import sg.schoolmatch.boundary.external.OneMapInterface;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.config.CacheConfig;
import sg.schoolmatch.error.ExternalFailureLog;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live {@link OneMapInterface} (app.external.onemap.mode=live), DC-11. OneMap search needs no token.
 * <p>
 * {@code GET {app.onemap.base-url}/api/common/elastic/search?searchVal=<text>&returnGeom=Y&getAddrDetails=Y&pageNum=1}
 * returns the first page of hits (up to 10). Calls are at least 1 s apart across all callers (OneMap fair use),
 * and results are cached for 24 h by normalised text ({@link CacheConfig#ONEMAP}), so the same address costs one call.
 * HTTP errors, timeouts (app.http.*) and unreadable answers throw {@code ExternalServiceUnavailableException("OneMap")}.
 * DC-91: so does a call when {@link #MAX_WAITING} calls already wait for their turn (NFR-LIMIT-02), so that a burst of
 * searches cannot hold every web server thread and freeze the other pages.
 */
@Component
@ConditionalOnProperty(name = "app.external.onemap.mode", havingValue = "live")
public class OneMapClient implements OneMapInterface {

    static final String SEARCH_PATH = "/api/common/elastic/search";
    static final long MIN_INTERVAL_MS = 1000;
    static final int MAX_WAITING = 5;

    private final RestClient restClient;
    private final JsonMapper json = JsonMapper.builder().build();
    private final long minIntervalMillis;
    private final Semaphore waitingPlaces = new Semaphore(MAX_WAITING);
    private long lastCallMillis;   // guarded by this

    @Autowired
    public OneMapClient(RestClient.Builder restClientBuilder, AppProperties props) {
        this(restClientBuilder, props, MIN_INTERVAL_MS);
    }

    /** For tests: a shorter (or no) pause between calls. */
    OneMapClient(RestClient.Builder restClientBuilder, AppProperties props, long minIntervalMillis) {
        this.restClient = restClientBuilder.baseUrl(props.onemap().baseUrl()).build();
        this.minIntervalMillis = minIntervalMillis;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.ONEMAP,
            key = "T(sg.schoolmatch.boundary.external.onemap.OneMapClient).cacheKey(#p0)")
    public List<OneMapHit> search(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            waitForTurn();
            String body = restClient.get()
                    .uri(SEARCH_PATH + "?searchVal={text}&returnGeom=Y&getAddrDetails=Y&pageNum=1", text.trim())
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return List.of();
            }
            return json.readValue(body, OneMapSearchResponse.class).toHits();
        } catch (RestClientException | JacksonException e) {
            throw new ExternalServiceUnavailableException("OneMap", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceUnavailableException("OneMap", e);
        }
    }

    /** Cache key: lower case, trimmed, single spaces ("  Bishan  St " → "bishan st"). */
    public static String cacheKey(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Keeps calls at least {@code minIntervalMillis} apart across all threads. At most {@link #MAX_WAITING} calls are
     * in here at a time; another one fails at once instead of waiting.
     */
    private void waitForTurn() throws InterruptedException {
        if (!waitingPlaces.tryAcquire()) {
            throw new ExternalServiceUnavailableException("OneMap", new TooManyWaitingException());
        }
        try {
            synchronized (this) {
                long wait = lastCallMillis + minIntervalMillis - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                lastCallMillis = System.currentTimeMillis();
            }
        } finally {
            waitingPlaces.release();
        }
    }

    /** Calls in {@link #waitForTurn} now, for tests. */
    int waitingCalls() {
        return MAX_WAITING - waitingPlaces.availablePermits();
    }

    /** The cause when too many calls already wait: the app refused the call itself and sent nothing to OneMap. */
    static final class TooManyWaitingException extends RuntimeException implements ExternalFailureLog.Described {

        TooManyWaitingException() {
            super(MAX_WAITING + " searches are already waiting for their turn; nothing was sent to OneMap",
                    null, false, false);
        }
    }
}

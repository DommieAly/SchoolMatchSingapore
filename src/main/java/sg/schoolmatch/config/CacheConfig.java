package sg.schoolmatch.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-memory caches for external calls, used with {@code @Cacheable("routes")} etc.
 * Only the caches listed here exist; a typo in a cache name fails loudly.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String ROUTES = "routes";
    public static final String PLACES = "places";
    public static final String PLACE_DETAILS = "placeDetails";
    public static final String ONEMAP = "onemap";

    @Bean
    public CacheManager cacheManager(AppProperties props) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheNames(List.of());   // static mode: no caches created on the fly
        Duration facilityTtl = props.facility().cacheTtl();
        manager.registerCustomCache(ROUTES, build(Duration.ofMinutes(30)));
        manager.registerCustomCache(PLACES, build(facilityTtl));
        manager.registerCustomCache(PLACE_DETAILS, build(facilityTtl));
        manager.registerCustomCache(ONEMAP, build(Duration.ofHours(24)));
        return manager;
    }

    private static com.github.benmanes.caffeine.cache.Cache<Object, Object> build(Duration ttl) {
        // recordStats: Actuator's cache metrics need it (without it Boot logs a warning per cache at start-up).
        return Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).recordStats().build();
    }
}

package sg.schoolmatch.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import sg.schoolmatch.entity.recommend.MatchFactor;
import sg.schoolmatch.entity.route.TravelMode;

/**
 * All {@code app.*} settings (see application.yml). Every value has a default, so a missing
 * property never breaks start-up. Keys come from environment variables / {@code .env}, never from git.
 * <p>
 * Read it by injecting {@code AppProperties}, e.g. {@code props.google().hasBrowserKey()}.
 */
@ConfigurationProperties("app")
public record AppProperties(
        @DefaultValue ExternalSettings external,
        @DefaultValue GoogleSettings google,
        @DefaultValue DataGovSgSettings datagovsg,
        @DefaultValue DatasetSettings dataset,
        @DefaultValue SessionSettings session,
        @DefaultValue FacilitySettings facility,
        @DefaultValue RecommendationSettings recommendation,
        @DefaultValue SearchSettings search,
        @DefaultValue OneMapSettings onemap,
        @DefaultValue HttpSettings http,
        @DefaultValue TransportFilterSettings transportFilter) {

    /**
     * {@code app.external.<service>.mode}: "stub" (default) or "live". Selects the bean for each interface:
     * the live bean needs exactly "live"; anything else, including an empty value, gives the stub.
     */
    public record ExternalSettings(
            @DefaultValue ServiceMode google,
            @DefaultValue ServiceMode onemap,
            @DefaultValue ServiceMode datagovsg,
            @DefaultValue BudgetSettings budget) {
    }

    /**
     * {@code app.external.budget}: daily spending limit for live Google calls (see ExternalCallBudget).
     * Daily limit of a SKU = floor(monthlyFree × safety ÷ 30), at least 1.
     * {@code monthlyFree} keys are the SKU names in ExternalCallBudget ({@code routes}, {@code route-matrix-elements},
     * {@code places-search}, {@code place-details}, {@code map-loads}); a key that is not set keeps its default.
     * The defaults are Google's free usage per month (checked on Google's pricing page on 2026-10-03; see
     * docs/google-maps-setup.md). {@code zone}: the time zone of the budget day; the default
     * {@code America/Los_Angeles} is Google's quota day (daily quotas reset at midnight Pacific Time).
     */
    public record BudgetSettings(
            @DefaultValue("0.8") double safety,
            Map<String, Integer> monthlyFree,
            @DefaultValue(BudgetSettings.GOOGLE_QUOTA_ZONE) ZoneId zone) {

        /** Google resets daily quotas at midnight Pacific Time. */
        public static final String GOOGLE_QUOTA_ZONE = "America/Los_Angeles";

        public BudgetSettings {
            if (!(safety > 0 && safety <= 1)) {
                throw new IllegalArgumentException("app.external.budget.safety must be more than 0 and at most 1, not "
                        + safety);
            }
            Map<String, Integer> merged = new LinkedHashMap<>(defaultMonthlyFree());
            if (monthlyFree != null) {
                merged.putAll(monthlyFree);
            }
            merged.forEach((sku, free) -> {
                if (free == null || free < 0) {
                    throw new IllegalArgumentException("app.external.budget.monthly-free." + sku
                            + " must be 0 or more, not " + free);
                }
            });
            monthlyFree = Collections.unmodifiableMap(merged);
            zone = zone == null ? ZoneId.of(GOOGLE_QUOTA_ZONE) : zone;
        }

        private static Map<String, Integer> defaultMonthlyFree() {
            Map<String, Integer> free = new LinkedHashMap<>();
            free.put("routes", 10000);                  // Compute Routes Essentials
            free.put("route-matrix-elements", 10000);   // Compute Route Matrix Essentials
            free.put("places-search", 5000);
            free.put("place-details", 1000);
            free.put("map-loads", 10000);
            return free;
        }
    }

    public record ServiceMode(@DefaultValue("stub") String mode) {

        /** Blank means stub; a typo stops start-up with a message naming the .env variables. */
        public ServiceMode {
            mode = (mode == null || mode.isBlank()) ? "stub" : mode;
            if (!"stub".equalsIgnoreCase(mode) && !"live".equalsIgnoreCase(mode)) {
                throw new IllegalArgumentException("app.external.*.mode must be 'stub' or 'live' "
                        + "(check GOOGLE_MODE and ONEMAP_MODE in .env), not '" + mode + "'");
            }
        }

        public boolean isStub() {
            return !isLive();
        }

        public boolean isLive() {
            return "live".equalsIgnoreCase(mode);
        }
    }

    /**
     * Google keys: server key for Routes/Places (never sent to the browser), browser key for Maps JS.
     * The base URLs are settings so tests can point the live client at a mock server.
     */
    public record GoogleSettings(
            String serverKey,
            String browserKey,
            String mapId,
            @DefaultValue("100") int matrixBatchSize,
            @DefaultValue("https://routes.googleapis.com") String routesBaseUrl,
            @DefaultValue("https://places.googleapis.com") String placesBaseUrl) {

        public GoogleSettings {
            serverKey = serverKey == null ? "" : serverKey;
            browserKey = browserKey == null ? "" : browserKey;
            mapId = mapId == null ? "" : mapId;
        }

        public boolean hasServerKey() {
            return !serverKey.isBlank();
        }

        public boolean hasBrowserKey() {
            return !browserKey.isBlank();
        }

        @Override
        public String toString() {
            return "GoogleSettings[serverKey=" + (hasServerKey() ? "***" : "") + ", browserKey="
                    + (hasBrowserKey() ? "***" : "") + ", mapId=" + mapId + ", matrixBatchSize=" + matrixBatchSize
                    + ", routesBaseUrl=" + routesBaseUrl + ", placesBaseUrl=" + placesBaseUrl + "]";
        }
    }

    /**
     * data.gov.sg: optional API key (higher rate limit; the public API works without it), the
     * {@code datastore_search} host ({@code baseUrl}) and the {@code poll-download} host ({@code openApiBaseUrl}).
     */
    public record DataGovSgSettings(
            String apiKey,
            @DefaultValue("https://data.gov.sg") String baseUrl,
            @DefaultValue("https://api-open.data.gov.sg") String openApiBaseUrl) {

        public DataGovSgSettings {
            apiKey = apiKey == null ? "" : apiKey;
        }

        @Override
        public String toString() {
            return "DataGovSgSettings[apiKey=" + (apiKey.isBlank() ? "" : "***") + ", baseUrl=" + baseUrl
                    + ", openApiBaseUrl=" + openApiBaseUrl + "]";
        }
    }

    /** OneMap search host (DC-11). Search needs no token. */
    public record OneMapSettings(@DefaultValue("https://www.onemap.gov.sg") String baseUrl) {
    }

    /** Timeouts for every outside HTTP call (config/RestClientConfig). */
    public record HttpSettings(
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("15s") Duration readTimeout) {
    }

    /**
     * Where the school snapshot is read from, and where the importer writes (DC-12).
     * {@code snapshotLocation} (optional, e.g. {@code classpath:fixtures/snapshot-mini/}) points at ONE
     * snapshot folder and overrides {@code dir} + {@code ACTIVE}.
     * {@code importOutputDir}: folder for new snapshots; blank (default) means {@code dir}.
     * {@code activateOnImport}: when true, a successful import also updates {@code ACTIVE} (default false).
     * <p>
     * DC-83 (docs/database-design.md, sections 6.2 and 6.3): the school data is served from the database.
     * {@code loadOnStartup} (default true): at start-up, read and validate the snapshot and load it into the database
     * when it is not the active version yet; false reads no snapshot file and builds the cache from the database on
     * first use (the import profile). {@code recheckAfter}: how often the database's active version is checked.
     * {@code allowRollback} (default true; false only in prod): whether a snapshot older than the database's active
     * version may replace it.
     */
    public record DatasetSettings(
            @DefaultValue("data/snapshots") String dir,
            String snapshotLocation,
            @DefaultValue("1h") Duration recheckAfter,
            String importOutputDir,
            @DefaultValue("false") boolean activateOnImport,
            @DefaultValue("true") boolean loadOnStartup,
            @DefaultValue("true") boolean allowRollback) {

        public DatasetSettings {
            importOutputDir = importOutputDir == null ? "" : importOutputDir;
        }

        public boolean hasSnapshotLocation() {
            return snapshotLocation != null && !snapshotLocation.isBlank();
        }

        /** The folder the importer writes to: {@code importOutputDir}, or {@code dir} when that is blank. */
        public String resolvedImportOutputDir() {
            return importOutputDir.isBlank() ? dir : importOutputDir;
        }
    }

    /** Login session cookie (see AuthInterceptor / SessionCookie). */
    public record SessionSettings(
            @DefaultValue("SM_SESSION") String cookieName,
            @DefaultValue("30m") Duration idleTimeout,
            @DefaultValue("false") boolean cookieSecure,
            /* how often ended login sessions are deleted; read by AuthController's @Scheduled (DC-73) */
            @DefaultValue("1h") Duration cleanupInterval) {
    }

    /**
     * Nearby facilities: how long Places results stay cached. The radius options and default are
     * constants on {@code FacilityFilterCriteria} (DC-24), not settings.
     */
    public record FacilitySettings(@DefaultValue("24h") Duration cacheTtl) {
    }

    /**
     * Recommendation scoring (DC-20; docs/recommendation-scoring.md sections 5 and 6).
     * {@code psleFit}: PSLE_FIT score of a MATCH, SAFE and near-REACH school; {@code reachNearMargin}: a REACH school
     * still counts while score ≤ U + this margin.
     */
    public record RecommendationSettings(
            Map<MatchFactor, Double> weights,
            @DefaultValue("20") int commuteCandidates,
            @DefaultValue("10") int topN,
            @DefaultValue PsleFitSettings psleFit,
            @DefaultValue("2") int reachNearMargin) {

        public RecommendationSettings {
            if (weights == null || weights.isEmpty()) {
                weights = defaultWeights();
            }
            weights = Collections.unmodifiableMap(new EnumMap<>(weights));   // fixed factor order
        }

        private static Map<MatchFactor, Double> defaultWeights() {
            Map<MatchFactor, Double> w = new EnumMap<>(MatchFactor.class);
            w.put(MatchFactor.PSLE_FIT, 0.4);
            w.put(MatchFactor.COMMUTE, 0.3);
            w.put(MatchFactor.CCA, 0.15);
            w.put(MatchFactor.PROGRAMME, 0.15);
            return w;
        }
    }

    /** {@code app.recommendation.psle-fit}: PSLE_FIT scores per label (docs/recommendation-scoring.md section 5). */
    public record PsleFitSettings(
            @DefaultValue("1.0") double match,
            @DefaultValue("0.7") double safe,
            @DefaultValue("0.3") double reachNear) {
    }

    /** Search results paging. */
    public record SearchSettings(@DefaultValue("20") int pageSize) {
    }

    /**
     * Travel-time filter (FilterController, FR-FILTER-06). {@code maxSpeedKmh}: fastest plausible straight-line speed
     * per travel mode; a school farther than speed × time is left out without asking the routing service (a mode
     * that is not set keeps its default: WALK 6, TRANSIT 45, DRIVE 80). {@code maxRoutedSchools}: at most this many
     * schools (the nearest) are sent to the routing service per request; the default 160 is the most schools a full
     * snapshot may have ({@code SnapshotValidator.FULL_MAX_SCHOOLS}), so no reachable school is ever left out.
     */
    public record TransportFilterSettings(
            Map<TravelMode, Integer> maxSpeedKmh,
            @DefaultValue("160") int maxRoutedSchools) {

        public TransportFilterSettings {
            Map<TravelMode, Integer> merged = new EnumMap<>(TravelMode.class);
            merged.put(TravelMode.WALK, 6);
            merged.put(TravelMode.TRANSIT, 45);
            merged.put(TravelMode.DRIVE, 80);
            if (maxSpeedKmh != null) {
                merged.putAll(maxSpeedKmh);
            }
            merged.forEach((mode, speed) -> {
                if (speed == null || speed <= 0) {
                    throw new IllegalArgumentException("app.transport-filter.max-speed-kmh." + mode
                            + " must be more than 0, not " + speed);
                }
            });
            if (maxRoutedSchools < 1) {
                throw new IllegalArgumentException("app.transport-filter.max-routed-schools must be at least 1, not "
                        + maxRoutedSchools);
            }
            maxSpeedKmh = Collections.unmodifiableMap(merged);
        }
    }
}

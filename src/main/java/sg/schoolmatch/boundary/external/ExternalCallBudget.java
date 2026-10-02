package sg.schoolmatch.boundary.external;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Component;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.persistence.ExternalUsage;
import sg.schoolmatch.persistence.ExternalUsageRepository;

/**
 * Daily spending limit for LIVE Google calls, so the team stays inside the free tier (NFR-MAIN-02).
 * Every live Google call first calls {@link #charge}; stubs never do.
 * <p>
 * Daily limit of a SKU = floor({@code app.external.budget.monthly-free.<sku>} × {@code safety} ÷ 30), at least 1.
 * A day is a Singapore calendar day; the counters restart at midnight. Counters are stored in table
 * {@code external_usage}, so a restart does not reset them.
 */
@Component
public class ExternalCallBudget {   // DC-41

    /** Routes API computeRoutes: 1 unit per route request. */
    public static final String ROUTES = "routes";
    /** Routes API computeRouteMatrix: 1 unit per element (origins × destinations). */
    public static final String ROUTE_MATRIX_ELEMENTS = "route-matrix-elements";
    /** Places API (New) searchNearby / searchText: 1 unit per request. */
    public static final String PLACES_SEARCH = "places-search";
    /** Places API (New) place details: 1 unit per request. */
    public static final String PLACE_DETAILS = "place-details";
    /** Maps JavaScript API: 1 unit per map page shown with the browser key. */
    public static final String MAP_LOADS = "map-loads";

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final int DAYS_PER_MONTH = 30;

    private final ExternalUsageRepository usageRepository;
    private final AppProperties.BudgetSettings settings;
    private final Clock clock;

    public ExternalCallBudget(ExternalUsageRepository usageRepository, AppProperties props, Clock clock) {
        this.usageRepository = usageRepository;
        this.settings = props.external().budget();
        this.clock = clock;
    }

    /**
     * Records {@code units} of {@code sku} for today, or refuses when that would pass today's limit
     * (nothing is recorded then). Call it BEFORE the HTTP request.
     *
     * @throws ExternalServiceUnavailableException ("Google " + sku) when today's used + units &gt; daily limit
     * @throws IllegalArgumentException for an unknown SKU or units &lt; 1
     */
    public synchronized void charge(String sku, int units) {
        int limit = dailyLimit(sku);
        if (units < 1) {
            throw new IllegalArgumentException("units must be at least 1, not " + units);
        }
        LocalDate today = today();
        ExternalUsage usage = usageRepository.findById(new ExternalUsage.Key(today, sku))
                .orElseGet(() -> new ExternalUsage(today, sku));
        if (usage.getUsed() + units > limit) {
            throw new ExternalServiceUnavailableException("Google " + sku, null);
        }
        usage.add(units);
        usageRepository.save(usage);
    }

    /** Units of {@code sku} used today (Singapore date); 0 when none. */
    public synchronized int usedToday(String sku) {
        dailyLimit(sku);   // rejects unknown SKUs
        return usageRepository.findById(new ExternalUsage.Key(today(), sku)).map(ExternalUsage::getUsed).orElse(0);
    }

    /**
     * floor(monthly free units × safety ÷ 30), at least 1.
     *
     * @throws IllegalArgumentException for an SKU that has no {@code monthly-free} setting
     */
    public int dailyLimit(String sku) {
        Map<String, Integer> monthlyFree = settings.monthlyFree();
        Integer free = sku == null ? null : monthlyFree.get(sku);
        if (free == null) {
            throw new IllegalArgumentException("Unknown Google SKU '" + sku + "'; known: " + monthlyFree.keySet());
        }
        double perDay = free * settings.safety() / DAYS_PER_MONTH;
        return Math.max(1, (int) Math.floor(perDay + 1e-9));   // 1e-9: 2400.0000001-style rounding noise
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SINGAPORE);
    }
}

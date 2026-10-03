package sg.schoolmatch.boundary.external;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Component;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.error.ExternalFailureLog;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.persistence.ExternalUsage;
import sg.schoolmatch.persistence.ExternalUsageRepository;

/**
 * Daily spending limit for LIVE Google calls, so the team stays inside the free tier (NFR-MAIN-02).
 * Every live Google call first calls {@link #charge}; stubs never do.
 * <p>
 * Daily limit of a SKU = floor({@code app.external.budget.monthly-free.<sku>} × {@code safety} ÷ 30), at least 1.
 * A day is a calendar day in {@code app.external.budget.zone} (default America/Los_Angeles, because Google resets
 * its daily quotas at midnight Pacific Time: 15:00 Singapore time during US daylight saving time, 16:00 otherwise).
 * The counters restart at that midnight. They are stored in table {@code external_usage}, keyed by that day, so a
 * restart does not reset them. A refusal carries a {@link DailyLimitReachedException} as its cause, so the log line
 * ({@code ExternalFailureLog}) can tell the app's own limit apart from Google refusing.
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

    private static final int DAYS_PER_MONTH = 30;

    private final ExternalUsageRepository usageRepository;
    private final AppProperties.BudgetSettings settings;
    private final ZoneId zone;
    private final Clock clock;

    public ExternalCallBudget(ExternalUsageRepository usageRepository, AppProperties props, Clock clock) {
        this.usageRepository = usageRepository;
        this.settings = props.external().budget();
        this.zone = settings.zone();
        this.clock = clock;
    }

    /**
     * Records {@code units} of {@code sku} for today, or refuses when that would pass today's limit
     * (nothing is recorded then). Call it BEFORE the HTTP request.
     *
     * @throws ExternalServiceUnavailableException ("Google " + sku, cause {@link DailyLimitReachedException}) when
     *         today's used + units &gt; daily limit
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
            throw new ExternalServiceUnavailableException("Google " + sku,
                    new DailyLimitReachedException(sku, usage.getUsed(), units, limit, today, zone));
        }
        usage.add(units);
        usageRepository.save(usage);
    }

    /** Units of {@code sku} used today (the day in {@link #zone()}); 0 when none. */
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

    /** The time zone of the budget day ({@code app.external.budget.zone}). */
    public ZoneId zone() {
        return zone;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /**
     * The cause of the exception {@link #charge} throws when today's limit would be passed: the app refused the call
     * itself and sent nothing to Google. Its message has no key and no URL, so it is safe in a log line.
     */
    public static final class DailyLimitReachedException extends RuntimeException
            implements ExternalFailureLog.Described {

        private final String sku;
        private final int used;
        private final int requested;
        private final int limit;
        private final LocalDate day;
        private final ZoneId zone;

        public DailyLimitReachedException(String sku, int used, int requested, int limit, LocalDate day, ZoneId zone) {
            super("app daily limit reached for " + sku + " (" + used + " of " + limit + " used on " + day + ", " + zone
                    + " day; " + requested + " more asked); nothing was sent to Google", null, false, false);
            this.sku = sku;
            this.used = used;
            this.requested = requested;
            this.limit = limit;
            this.day = day;
            this.zone = zone;
        }

        public String getSku() {
            return sku;
        }

        /** Units already used on {@link #getDay()} before this request. */
        public int getUsed() {
            return used;
        }

        /** Units this request asked for. */
        public int getRequested() {
            return requested;
        }

        public int getLimit() {
            return limit;
        }

        /** The budget day (a date in {@link #getZone()}). */
        public LocalDate getDay() {
            return day;
        }

        public ZoneId getZone() {
            return zone;
        }
    }
}

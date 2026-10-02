package sg.schoolmatch.boundary.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.persistence.ExternalUsage;
import sg.schoolmatch.persistence.ExternalUsageRepository;
import sg.schoolmatch.support.FixedClock;

/**
 * {@link ExternalCallBudget}: the daily limit for live Google calls (NFR-MAIN-02). Real repository on the
 * in-memory H2 ({@code @DataJpaTest}), a {@link FixedClock} for the Singapore day, and {@code app.*} settings
 * bound exactly as Spring would bind them.
 */
@DataJpaTest
@ActiveProfiles("test")
class ExternalCallBudgetTest {

    /** 23:59:59 on Monday 12 October 2026 in Singapore (UTC+8). */
    private static final String LAST_SECOND_OF_12_OCT = "2026-10-12T15:59:59Z";

    @Autowired
    private ExternalUsageRepository usageRepository;

    private final FixedClock clock = FixedClock.at(LAST_SECOND_OF_12_OCT);

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-01: default daily limits are floor(monthly free × 0.8 ÷ 30)")
    void defaultDailyLimits() {
        ExternalCallBudget budget = budget(Map.of());

        assertThat(budget.dailyLimit(ExternalCallBudget.ROUTES)).isEqualTo(133);                  // 5000
        assertThat(budget.dailyLimit(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS)).isEqualTo(133);   // 5000
        assertThat(budget.dailyLimit(ExternalCallBudget.PLACES_SEARCH)).isEqualTo(133);           // 5000
        assertThat(budget.dailyLimit(ExternalCallBudget.PLACE_DETAILS)).isEqualTo(26);            // 1000
        assertThat(budget.dailyLimit(ExternalCallBudget.MAP_LOADS)).isEqualTo(266);               // 10000
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-02: settings override one SKU (dashed key) and the safety factor; the limit is at least 1")
    void settingsOverrideAndMinimumOne() {
        ExternalCallBudget budget = budget(Map.of(
                "app.external.budget.safety", "0.5",
                "app.external.budget.monthly-free.route-matrix-elements", "3000",
                "app.external.budget.monthly-free.place-details", "10"));

        assertThat(budget.dailyLimit(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS)).isEqualTo(50);   // 3000 × 0.5 ÷ 30
        assertThat(budget.dailyLimit(ExternalCallBudget.PLACE_DETAILS)).isEqualTo(1);            // 0.17 → at least 1
        assertThat(budget.dailyLimit(ExternalCallBudget.ROUTES)).isEqualTo(83);                  // default 5000 kept
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-03: charging up to the limit works; one unit more is refused and not recorded")
    void chargeUpToLimitThenRefuse() {
        ExternalCallBudget budget = budget(Map.of("app.external.budget.monthly-free.place-details", "150"));   // 4/day

        budget.charge(ExternalCallBudget.PLACE_DETAILS, 3);
        budget.charge(ExternalCallBudget.PLACE_DETAILS, 1);
        assertThat(budget.usedToday(ExternalCallBudget.PLACE_DETAILS)).isEqualTo(4);

        assertThatThrownBy(() -> budget.charge(ExternalCallBudget.PLACE_DETAILS, 1))
                .isInstanceOf(ExternalServiceUnavailableException.class)
                .extracting(e -> ((ExternalServiceUnavailableException) e).getService())
                .isEqualTo("Google place-details");
        assertThat(budget.usedToday(ExternalCallBudget.PLACE_DETAILS)).isEqualTo(4);
        assertThat(budget.usedToday(ExternalCallBudget.ROUTES)).isZero();   // other SKUs have their own counter
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-04: a batch bigger than what is left is refused as a whole")
    void batchBiggerThanRemainderIsRefused() {
        ExternalCallBudget budget = budget(Map.of("app.external.budget.monthly-free.route-matrix-elements", "3750"));

        budget.charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, 60);   // limit 100/day
        assertThatThrownBy(() -> budget.charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, 41))
                .isInstanceOf(ExternalServiceUnavailableException.class);
        budget.charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, 40);
        assertThat(budget.usedToday(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS)).isEqualTo(100);
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-05: the counter starts again at midnight Singapore time; yesterday's row is kept")
    void newDayStartsAtSingaporeMidnight() {
        ExternalCallBudget budget = budget(Map.of("app.external.budget.monthly-free.place-details", "75"));   // 2/day
        budget.charge(ExternalCallBudget.PLACE_DETAILS, 2);
        assertThatThrownBy(() -> budget.charge(ExternalCallBudget.PLACE_DETAILS, 1))
                .isInstanceOf(ExternalServiceUnavailableException.class);

        clock.advance(Duration.ofSeconds(1));   // 00:00:00 on 13 October in Singapore (still 12 October in UTC)

        assertThat(budget.usedToday(ExternalCallBudget.PLACE_DETAILS)).isZero();
        budget.charge(ExternalCallBudget.PLACE_DETAILS, 2);
        assertThat(usageRepository.findById(new ExternalUsage.Key(LocalDate.of(2026, 10, 12), "place-details")))
                .get().extracting(ExternalUsage::getUsed).isEqualTo(2);
        assertThat(usageRepository.findById(new ExternalUsage.Key(LocalDate.of(2026, 10, 13), "place-details")))
                .get().extracting(ExternalUsage::getUsed).isEqualTo(2);
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-06: the counters are in the database, so a new budget object (app restart) sees them")
    void countersSurviveRestart() {
        budget(Map.of()).charge(ExternalCallBudget.MAP_LOADS, 5);

        assertThat(budget(Map.of()).usedToday(ExternalCallBudget.MAP_LOADS)).isEqualTo(5);
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-07: an unknown SKU or fewer than 1 unit is a programming error (IllegalArgumentException)")
    void badArguments() {
        ExternalCallBudget budget = budget(Map.of());

        assertThatThrownBy(() -> budget.charge("geocoding", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.usedToday(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.charge(ExternalCallBudget.ROUTES, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(usageRepository.count()).isZero();
    }

    private ExternalCallBudget budget(Map<String, String> settings) {
        AppProperties props = new Binder(new MapConfigurationPropertySource(settings))
                .bindOrCreate("app", AppProperties.class);
        return new ExternalCallBudget(usageRepository, props, clock);
    }
}

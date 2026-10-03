package sg.schoolmatch.boundary.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.persistence.ExternalUsage;
import sg.schoolmatch.persistence.ExternalUsageRepository;
import sg.schoolmatch.support.FixedClock;

/**
 * {@link ExternalCallBudget}: the daily limit for live Google calls (NFR-MAIN-02). Real repository on the
 * in-memory H2 ({@code @DataJpaTest}), a {@link FixedClock} around Google's quota day (midnight Pacific Time), and
 * {@code app.*} settings bound exactly as Spring would bind them.
 */
@DataJpaTest
@ActiveProfiles("test")
class ExternalCallBudgetTest {

    /** 23:59:59 on Monday 12 October 2026 in Pacific Time (PDT, UTC−7); 14:59:59 on 13 October in Singapore. */
    private static final String LAST_PACIFIC_SECOND_OF_12_OCT = "2026-10-13T06:59:59Z";
    /** 23:59:59 on Monday 12 October 2026 in Singapore (UTC+8); 08:59:59 on 12 October in Pacific Time. */
    private static final String LAST_SINGAPORE_SECOND_OF_12_OCT = "2026-10-12T15:59:59Z";
    /** 23:59:59 on Thursday 14 January 2027 in Pacific Time (PST, UTC−8); 15:59:59 on 15 January in Singapore. */
    private static final String LAST_PACIFIC_SECOND_OF_14_JAN = "2027-01-15T07:59:59Z";

    @Autowired
    private ExternalUsageRepository usageRepository;

    private final FixedClock clock = FixedClock.at(LAST_PACIFIC_SECOND_OF_12_OCT);

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-01: default daily limits are floor(monthly free × 0.8 ÷ 30); Routes SKUs have 10,000 free")
    void defaultDailyLimits() {
        ExternalCallBudget budget = budget(Map.of());

        assertThat(budget.dailyLimit(ExternalCallBudget.ROUTES)).isEqualTo(266);                  // 10000
        assertThat(budget.dailyLimit(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS)).isEqualTo(266);   // 10000
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
        assertThat(budget.dailyLimit(ExternalCallBudget.ROUTES)).isEqualTo(166);                 // default 10000 kept
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
    @DisplayName("TC-Budget-05: the counter starts again at midnight Pacific Time (Google's quota day); yesterday's row is kept")
    void newDayStartsAtPacificMidnight() {
        ExternalCallBudget budget = budget(Map.of("app.external.budget.monthly-free.place-details", "75"));   // 2/day
        budget.charge(ExternalCallBudget.PLACE_DETAILS, 2);
        assertThatThrownBy(() -> budget.charge(ExternalCallBudget.PLACE_DETAILS, 1))
                .isInstanceOf(ExternalServiceUnavailableException.class);

        clock.advance(Duration.ofSeconds(1));   // 00:00:00 on 13 October in Pacific Time = 15:00 in Singapore

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

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-08: midnight in Singapore is not a new budget day (Google's day has 15 hours left)")
    void singaporeMidnightDoesNotReset() {
        clock.setInstant(java.time.Instant.parse(LAST_SINGAPORE_SECOND_OF_12_OCT));
        ExternalCallBudget budget = budget(Map.of("app.external.budget.monthly-free.place-details", "75"));   // 2/day
        budget.charge(ExternalCallBudget.PLACE_DETAILS, 2);

        clock.advance(Duration.ofSeconds(1));   // 00:00:00 on 13 October in Singapore, 09:00 on 12 October in Pacific Time

        assertThat(budget.usedToday(ExternalCallBudget.PLACE_DETAILS)).isEqualTo(2);
        assertThatThrownBy(() -> budget.charge(ExternalCallBudget.PLACE_DETAILS, 1))
                .isInstanceOf(ExternalServiceUnavailableException.class);
        assertThat(usageRepository.findById(new ExternalUsage.Key(LocalDate.of(2026, 10, 12), "place-details")))
                .get().extracting(ExternalUsage::getUsed).isEqualTo(2);
        assertThat(usageRepository.count()).isEqualTo(1);
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-09: in US winter time (PST, UTC−8) the day changes at 08:00 UTC, 16:00 in Singapore")
    void winterPacificMidnight() {
        clock.setInstant(java.time.Instant.parse(LAST_PACIFIC_SECOND_OF_14_JAN));
        ExternalCallBudget budget = budget(Map.of());
        budget.charge(ExternalCallBudget.ROUTES, 3);

        clock.advance(Duration.ofSeconds(1));   // 00:00:00 on 15 January 2027 in Pacific Time
        budget.charge(ExternalCallBudget.ROUTES, 1);

        assertThat(usageRepository.findById(new ExternalUsage.Key(LocalDate.of(2027, 1, 14), "routes")))
                .get().extracting(ExternalUsage::getUsed).isEqualTo(3);
        assertThat(usageRepository.findById(new ExternalUsage.Key(LocalDate.of(2027, 1, 15), "routes")))
                .get().extracting(ExternalUsage::getUsed).isEqualTo(1);
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-10: app.external.budget.zone sets the budget day (default America/Los_Angeles); a bad zone stops binding")
    void zoneSetting() {
        assertThat(budget(Map.of()).zone()).isEqualTo(ZoneId.of("America/Los_Angeles"));

        clock.setInstant(java.time.Instant.parse(LAST_SINGAPORE_SECOND_OF_12_OCT));
        ExternalCallBudget singaporeDay = budget(Map.of("app.external.budget.zone", "Asia/Singapore",
                "app.external.budget.monthly-free.place-details", "75"));   // 2/day
        singaporeDay.charge(ExternalCallBudget.PLACE_DETAILS, 2);
        clock.advance(Duration.ofSeconds(1));   // 00:00 on 13 October in Singapore
        assertThat(singaporeDay.usedToday(ExternalCallBudget.PLACE_DETAILS)).isZero();

        assertThatThrownBy(() -> budget(Map.of("app.external.budget.zone", "Pacific/Nowhere")))
                .isInstanceOf(BindException.class);
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-11: a refusal says it is the app's own daily limit: SKU, used, asked, limit, day and zone")
    void refusalCarriesLimitDetails() {
        ExternalCallBudget budget = budget(Map.of("app.external.budget.monthly-free.route-matrix-elements", "3750"));
        budget.charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, 60);   // limit 100/day

        assertThatThrownBy(() -> budget.charge(ExternalCallBudget.ROUTE_MATRIX_ELEMENTS, 41))
                .isInstanceOf(ExternalServiceUnavailableException.class)
                .extracting(Throwable::getCause)
                .isInstanceOfSatisfying(ExternalCallBudget.DailyLimitReachedException.class, limit -> {
                    assertThat(limit.getSku()).isEqualTo("route-matrix-elements");
                    assertThat(limit.getUsed()).isEqualTo(60);
                    assertThat(limit.getRequested()).isEqualTo(41);
                    assertThat(limit.getLimit()).isEqualTo(100);
                    assertThat(limit.getDay()).isEqualTo(LocalDate.of(2026, 10, 12));
                    assertThat(limit.getZone()).isEqualTo(ZoneId.of("America/Los_Angeles"));
                    assertThat(limit.getMessage()).contains("app daily limit").contains("route-matrix-elements")
                            .contains("60").contains("41").contains("100").contains("2026-10-12")
                            .contains("America/Los_Angeles");
                });
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Budget-12: application.yml: Routes and Route Matrix 10,000 free, Places 5,000 / 1,000, maps 10,000, Pacific day")
    void applicationYmlValues() throws IOException {
        java.util.List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        AppProperties.BudgetSettings settings = new Binder(ConfigurationPropertySources.from(yaml))
                .bind("app.external.budget", AppProperties.BudgetSettings.class).get();

        assertThat(settings.safety()).isEqualTo(0.8);
        assertThat(settings.monthlyFree()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "routes", 10000, "route-matrix-elements", 10000,
                "places-search", 5000, "place-details", 1000, "map-loads", 10000));
        assertThat(settings.zone()).isEqualTo(ZoneId.of("America/Los_Angeles"));
    }

    private ExternalCallBudget budget(Map<String, String> settings) {
        AppProperties props = new Binder(new MapConfigurationPropertySource(settings))
                .bindOrCreate("app", AppProperties.class);
        return new ExternalCallBudget(usageRepository, props, clock);
    }
}

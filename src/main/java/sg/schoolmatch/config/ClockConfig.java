package sg.schoolmatch.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link Clock} bean. Controls inject it instead of calling {@code Instant.now()},
 * so tests can use a fixed clock.
 */
@Configuration
public class ClockConfig {

    /** The app's time zone: dates and the per-day counters are Singapore days. */
    public static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    @Bean
    public Clock clock() {
        return Clock.system(SINGAPORE);
    }
}

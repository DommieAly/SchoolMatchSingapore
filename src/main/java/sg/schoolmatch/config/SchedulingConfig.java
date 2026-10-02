package sg.schoolmatch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} methods, e.g. the removal of ended login sessions (DC-73, AuthController). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

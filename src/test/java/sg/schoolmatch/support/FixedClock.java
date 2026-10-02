package sg.schoolmatch.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Test helper: a {@link Clock} that stands still until the test moves it.
 * <p>
 * Controls get the time from the {@code Clock} bean (see {@code ClockConfig}), never from
 * {@code Instant.now()}. A test passes a {@code FixedClock} instead and calls {@link #advance(Duration)}
 * to check time rules (e.g. the 30-minute session idle timeout) without sleeping:
 * <pre>
 * FixedClock clock = FixedClock.atDefault();
 * AuthController auth = new AuthController(accounts, sessions, clock, props);
 * clock.advance(Duration.ofMinutes(31));
 * </pre>
 * Not thread-safe; one instance per test.
 */
public final class FixedClock extends Clock {

    /** Same zone as the application's {@code Clock} bean. */
    public static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    /** Default test time: Monday 12 October 2026, 10:00 in Singapore. */
    public static final Instant DEFAULT_INSTANT = Instant.parse("2026-10-12T02:00:00Z");

    private final ZoneId zone;
    private Instant instant;

    private FixedClock(Instant instant, ZoneId zone) {
        this.instant = Objects.requireNonNull(instant, "instant");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** A clock stopped at {@code instant}, in the Singapore zone. */
    public static FixedClock at(Instant instant) {
        return new FixedClock(instant, SINGAPORE);
    }

    /** A clock stopped at an ISO-8601 instant, e.g. {@code "2026-10-12T02:00:00Z"}. */
    public static FixedClock at(String isoInstant) {
        return at(Instant.parse(isoInstant));
    }

    /** A clock stopped at {@link #DEFAULT_INSTANT}. */
    public static FixedClock atDefault() {
        return at(DEFAULT_INSTANT);
    }

    /** Moves the clock forward (or back, for a negative duration). Returns this clock. */
    public FixedClock advance(Duration duration) {
        instant = instant.plus(duration);
        return this;
    }

    /** Moves the clock to {@code newInstant}. */
    public void setInstant(Instant newInstant) {
        instant = Objects.requireNonNull(newInstant, "newInstant");
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** A copy at the current instant in another zone; it does not follow later {@link #advance} calls. */
    @Override
    public Clock withZone(ZoneId newZone) {
        return new FixedClock(instant, newZone);
    }

    @Override
    public String toString() {
        return "FixedClock[" + instant + ", " + zone + "]";
    }
}

package sg.schoolmatch.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Test helper: records what one class logs, so a test can check its WARN lines.
 * <pre>
 * try (LogCapture log = LogCapture.of(FacilityDetailsUI.class)) {
 *     mvc.perform(get("/facilities/lib-1"));
 *     assertThat(log.warnings()).singleElement().asString().contains("app daily limit reached");
 * }
 * </pre>
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
    }

    /** Starts recording the log lines of {@code type}'s logger. */
    public static LogCapture of(Class<?> type) {
        return new LogCapture(type);
    }

    /** The WARN lines so far, formatted as they would be printed (without any stack trace). */
    public List<String> warnings() {
        return List.copyOf(appender.list).stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** True when any recorded line carries a stack trace. */
    public boolean hasStackTrace() {
        return List.copyOf(appender.list).stream().anyMatch(event -> event.getThrowableProxy() != null);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}

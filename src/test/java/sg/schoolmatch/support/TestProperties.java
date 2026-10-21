package sg.schoolmatch.support;

import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import sg.schoolmatch.config.AppProperties;

/** {@link AppProperties} for plain unit tests, bound the way Spring binds {@code app.*} (no application context). */
public final class TestProperties {

    private TestProperties() {
    }

    /** The settings in {@code values} (full keys, e.g. {@code "app.google.server-key"}); every other one is its default. */
    public static AppProperties bind(Map<String, ?> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("app", AppProperties.class);
    }

    /** Every setting at its default. */
    public static AppProperties defaults() {
        return bind(Map.of());
    }
}

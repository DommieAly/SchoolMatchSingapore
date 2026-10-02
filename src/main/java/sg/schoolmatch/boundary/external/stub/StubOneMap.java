package sg.schoolmatch.boundary.external.stub;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import sg.schoolmatch.boundary.external.OneMapHit;
import sg.schoolmatch.boundary.external.OneMapInterface;
import sg.schoolmatch.boundary.external.onemap.OneMapSearchResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline {@link OneMapInterface} (app.external.onemap.mode=stub, the default). Answers from recorded real
 * OneMap responses in {@code src/main/resources/stub/onemap/<key>.json}, where {@code <key>} is the normalised
 * search text (lower case, runs of other characters → "-"), e.g. "Catholic High School" → catholic-high-school.json.
 * Anything else returns no hits. Recorded samples: 579767 (one hit), catholic high school (two), bishan (a page).
 */
@Component
// Any mode except "live" (also an empty or missing value) selects the stub, so exactly one bean always exists.
@ConditionalOnExpression("!'live'.equalsIgnoreCase('${app.external.onemap.mode:stub}')")
public class StubOneMap implements OneMapInterface {

    static final String LOCATION = "classpath*:stub/onemap/*.json";

    private final Map<String, List<OneMapHit>> hitsByKey = new HashMap<>();

    public StubOneMap() {
        JsonMapper json = JsonMapper.builder().build();
        try {
            for (Resource file : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                String name = file.getFilename();
                if (name == null) {
                    continue;
                }
                try (InputStream in = file.getInputStream()) {
                    hitsByKey.put(name.substring(0, name.length() - ".json".length()),
                            json.readValue(in, OneMapSearchResponse.class).toHits());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read OneMap stub files " + LOCATION, e);
        }
    }

    @Override
    public List<OneMapHit> search(String text) {
        return text == null ? List.of() : hitsByKey.getOrDefault(key(text), List.of());
    }

    /** "  Catholic High School " → "catholic-high-school" (same rule as the file names). */
    static String key(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }
}

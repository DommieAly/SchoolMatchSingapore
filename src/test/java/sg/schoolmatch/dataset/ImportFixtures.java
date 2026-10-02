package sg.schoolmatch.dataset;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sg.schoolmatch.boundary.external.DataGovSgRecord;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Importer test helper: reads the data.gov.sg fixtures in {@code src/test/resources/fixtures/external/datagovsg/}
 * (real {@code datastore_search} responses) into the rows the importer gets from {@link
 * sg.schoolmatch.boundary.external.DataGovSgInterface}. No network.
 */
final class ImportFixtures {

    static final String DIR = "/fixtures/external/datagovsg/";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ImportFixtures() {
    }

    /** The {@code result.records} of a fixture file as rows (every value as text, JSON null as null). */
    static List<DataGovSgRecord> records(String file) {
        JsonNode root = JSON.readTree(text(file));
        List<DataGovSgRecord> rows = new ArrayList<>();
        for (JsonNode record : root.path("result").path("records").values()) {
            Map<String, String> fields = new LinkedHashMap<>();
            record.properties().forEach(e -> fields.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
            rows.add(new DataGovSgRecord(fields));
        }
        return rows;
    }

    /** The school rows a live DataGovSgClient.fetchSchools() returns (secondary and mixed-level only). */
    static List<DataGovSgRecord> secondarySchools() {
        return records("schools.json").stream()
                .filter(r -> r.get("mainlevel_code").startsWith("SECONDARY") || r.get("mainlevel_code").startsWith("MIXED LEVEL"))
                .toList();
    }

    static String text(String file) {
        try (InputStream in = ImportFixtures.class.getResourceAsStream(DIR + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + DIR + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One row from name/value pairs, e.g. {@code row("school_name", "X", "postal_code", "123456")}. */
    static DataGovSgRecord row(String... namesAndValues) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            fields.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return new DataGovSgRecord(fields);
    }
}

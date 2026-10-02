package sg.schoolmatch.boundary.external;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One raw row of a data.gov.sg dataset, field name → value as published (e.g. "school_name").
 * The importer maps these rows to School objects.
 */
public record DataGovSgRecord(Map<String, String> fields) {

    public DataGovSgRecord {
        // Copy keeps null values (Map.copyOf would reject them).
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** The trimmed value of {@code name}, or {@code null} when missing or blank. */
    public String get(String name) {
        String value = fields.get(name);
        return value == null || value.isBlank() ? null : value.trim();
    }
}

package sg.schoolmatch.dataset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What happened during one dataset import (DC-12): info lines, warnings grouped by rule, and counters.
 * Written to {@code import-log.txt}; the warning summary goes into {@code manifest.json} and the report.
 * Every warning starts with its rule id, like the validator's messages.
 */
public class ImportLog {

    // Warning rules (also the counter names)
    public static final String CCA_JOIN_MISS = "cca-join-miss";
    public static final String SUBJECT_JOIN_MISS = "subject-join-miss";
    public static final String DUPLICATE_NAME = "duplicate-name";
    public static final String SCHOOL_CODE_SLUG = "school-code-slug";
    public static final String GEOCODE_SINGLE_HIT = "geocode-single-hit";
    public static final String GEOCODE_SAME_PLACE = "geocode-same-place";
    public static final String GEOCODE_AMBIGUOUS = "geocode-ambiguous";
    public static final String GEOCODE_FAILED = "geocode-failed";
    public static final String DISTRICT_MISMATCH = "district-mismatch";
    public static final String DISTRICT_NOT_FOUND = "district-not-found";
    public static final String DISTRICT_SIMPLIFIED = "district-simplified";
    public static final String CURATED = "curated";

    private final List<String> lines = new ArrayList<>();
    private final Map<String, List<String>> warningsByRule = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    /** A plain progress line for import-log.txt. */
    public void info(String line) {
        lines.add(line);
    }

    /** A warning of {@code rule}; also counts it. Stored as "rule: message". */
    public void warn(String rule, String message) {
        String warning = rule + ": " + message;
        warningsByRule.computeIfAbsent(rule, r -> new ArrayList<>()).add(warning);
        lines.add("WARNING " + warning);
        increment(rule);
    }

    /** Adds 1 to counter {@code key}. */
    public void increment(String key) {
        counts.merge(key, 1, Integer::sum);
    }

    /** Counter values in first-use order (warning rules count their warnings). */
    public Map<String, Integer> counts() {
        return Collections.unmodifiableMap(counts);
    }

    public int count(String key) {
        return counts.getOrDefault(key, 0);
    }

    /** All warnings, grouped by rule in first-seen order. */
    public List<String> warnings() {
        return warningsByRule.values().stream().flatMap(List::stream).toList();
    }

    /** The warnings of one rule. */
    public List<String> warnings(String rule) {
        return List.copyOf(warningsByRule.getOrDefault(rule, List.of()));
    }

    /**
     * One line per rule, for the manifest: the warning itself when there is one, else
     * "rule: N warnings, e.g. …" (the full list is in validation-report.md).
     */
    public List<String> summary() {
        List<String> summary = new ArrayList<>();
        warningsByRule.forEach((rule, list) -> summary.add(list.size() == 1 ? list.getFirst()
                : rule + ": " + list.size() + " warnings, e.g. " + list.getFirst().substring(rule.length() + 2)));
        return summary;
    }

    /** The whole log, one entry per line. */
    public String text() {
        return String.join("\n", lines) + "\n";
    }
}

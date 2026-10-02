package sg.schoolmatch.dataset;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import sg.schoolmatch.dataset.CuratedCsvReader.NameAlias;

/**
 * Puts school names from different datasets into one form, so the importer can join them (DC-12, FR-DATA-03).
 * The data.gov.sg CCA and subject datasets have no school code; they are joined to the schools by this name.
 * <ul>
 *   <li>{@link #normalise}: upper case, trimmed, runs of spaces → one space, curly apostrophes → {@code '}.</li>
 *   <li>{@link #canonical}: the normalised name, or the {@code canonical_name} of a matching row in
 *       {@code data/curated/name-aliases.csv}. The {@code dataset} column is {@code schools}, {@code ccas},
 *       {@code subjects}, or {@code *} for all of them; a dataset-specific row wins over {@code *}.</li>
 * </ul>
 */
public class NameNormaliser {

    public static final String SCHOOLS = "schools";
    public static final String CCAS = "ccas";
    public static final String SUBJECTS = "subjects";
    public static final String ANY_DATASET = "*";

    private final Map<String, String> aliases = new HashMap<>();   // "dataset|NORMALISED RAW" → NORMALISED CANONICAL

    public NameNormaliser(Collection<NameAlias> aliasRows) {
        for (NameAlias alias : aliasRows) {
            String raw = normalise(alias.rawName());
            String canonical = normalise(alias.canonicalName());
            if (raw != null && canonical != null) {
                aliases.put(key(alias.dataset(), raw), canonical);
            }
        }
    }

    /** "  St.  Hilda’s school " → "ST. HILDA'S SCHOOL"; null or blank → null. */
    public static String normalise(String name) {
        if (name == null) {
            return null;
        }
        String result = name.replaceAll("[\\u2018\\u2019\\u02BC\\u0060\\u00B4]", "'")
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
        return result.isEmpty() ? null : result;
    }

    /**
     * The fallback schoolCode when {@code school-codes.csv} has no row: lower case, punctuation dropped,
     * spaces → '-' ("ST. HILDA'S SECONDARY SCHOOL" → "st-hildas-secondary-school"; same rule as the seed).
     */
    public static String slug(String name) {
        if (name == null) {
            return null;
        }
        String slug = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return slug.isEmpty() ? null : slug;
    }

    /** The name used to join {@code dataset}'s row to a school (see class comment). */
    public String canonical(String dataset, String rawName) {
        String name = normalise(rawName);
        if (name == null) {
            return null;
        }
        String specific = aliases.get(key(dataset, name));
        if (specific != null) {
            return specific;
        }
        return aliases.getOrDefault(key(ANY_DATASET, name), name);
    }

    private static String key(String dataset, String normalisedName) {
        String set = dataset == null || dataset.isBlank() ? ANY_DATASET : dataset.trim().toLowerCase(Locale.ROOT);
        return set + "|" + normalisedName;
    }
}

package sg.schoolmatch.dataset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import sg.schoolmatch.boundary.external.DataGovSgRecord;
import sg.schoolmatch.dataset.CuratedCsvReader.SubjectExclusion;

/**
 * Joins the data.gov.sg CCA and subject rows to the school rows (DC-12, FR-DATA-03). Those datasets have no
 * school code, so rows are matched by {@link NameNormaliser#canonical} name. A school with no CCA or subject
 * rows is kept and reported ({@link ImportLog#CCA_JOIN_MISS}, {@link ImportLog#SUBJECT_JOIN_MISS}).
 * <ul>
 *   <li>CCAs: {@code cca_grouping_desc}, from rows whose {@code school_section} is not PRIMARY or JUNIOR COLLEGE.</li>
 *   <li>Programmes: {@code Subject_Desc} (the seed did the same; "programmes = subjects offered"), DC-76:
 *     <ul>
 *       <li>rows listed in {@code data/curated/subject-exclusions.csv} (placeholders such as "Test Subject") are
 *           left out; an exclusion row that matches nothing is reported ({@link ImportLog#CURATED});</li>
 *       <li>subject names that differ only in letter case ("BIOLOGY", "Biology") get one spelling across all
 *           schools: a spelling that is not all upper case first, then the one most schools use, then
 *           alphabetical. Each school then lists the subject once.</li>
 *     </ul></li>
 * </ul>
 * Values are distinct and sorted. Field names are matched ignoring case (the datasets mix School_name /
 * School_Name / school_name).
 */
public class SchoolRecordJoiner {

    static final String SCHOOL_NAME = "school_name";
    static final String SCHOOL_SECTION = "school_section";
    static final String CCA_NAME = "cca_grouping_desc";
    static final String SUBJECT_NAME = "subject_desc";
    private static final Set<String> NOT_SECONDARY_SECTIONS = Set.of("PRIMARY", "JUNIOR COLLEGE");

    /** {@link ImportLog} counter: subject rows left out by {@code subject-exclusions.csv}. */
    public static final String SUBJECTS_EXCLUDED = "subjects-excluded";
    /** {@link ImportLog} counter: subject names spelled in more than one letter case, each given one spelling. */
    public static final String SUBJECT_SPELLINGS_MERGED = "subject-spellings-merged";

    private final NameNormaliser names;
    private final Map<String, SubjectExclusion> exclusions = new LinkedHashMap<>();   // "SCHOOL|subject" → row

    public SchoolRecordJoiner(NameNormaliser names) {
        this(names, List.of());
    }

    public SchoolRecordJoiner(NameNormaliser names, Collection<SubjectExclusion> subjectExclusions) {
        this.names = names;
        for (SubjectExclusion exclusion : subjectExclusions) {
            String key = exclusionKey(names.canonical(NameNormaliser.SUBJECTS, exclusion.schoolName()),
                    exclusion.subjectDesc());
            if (key != null) {
                exclusions.putIfAbsent(key, exclusion);
            }
        }
    }

    /**
     * One {@link JoinedSchool} per school row, in input order.
     *
     * @param schools  rows of the schools dataset (already limited to secondary schools)
     * @param ccas     rows of the CCA dataset (all schools)
     * @param subjects rows of the subjects dataset (all schools)
     */
    public List<JoinedSchool> join(List<DataGovSgRecord> schools, List<DataGovSgRecord> ccas,
                                   List<DataGovSgRecord> subjects, ImportLog log) {
        Map<String, Set<String>> ccasByName = new HashMap<>();
        for (DataGovSgRecord row : ccas) {
            String section = field(row, SCHOOL_SECTION);
            if (section != null && NOT_SECONDARY_SECTIONS.contains(section.toUpperCase(Locale.ROOT))) {
                continue;
            }
            add(ccasByName, names.canonical(NameNormaliser.CCAS, field(row, SCHOOL_NAME)), field(row, CCA_NAME));
        }
        Map<String, Set<String>> subjectsByName = new HashMap<>();
        Set<String> usedExclusions = new HashSet<>();
        for (DataGovSgRecord row : subjects) {
            String schoolName = names.canonical(NameNormaliser.SUBJECTS, field(row, SCHOOL_NAME));
            String subject = field(row, SUBJECT_NAME);
            String key = exclusionKey(schoolName, subject);
            if (key != null && exclusions.containsKey(key)) {
                if (usedExclusions.add(key)) {
                    log.info("subject left out (" + CuratedCsvReader.SUBJECT_EXCLUSIONS + "): " + schoolName + " / "
                            + cleanValue(subject));
                }
                log.increment(SUBJECTS_EXCLUDED);
                continue;
            }
            add(subjectsByName, schoolName, subject);
        }
        exclusions.forEach((key, exclusion) -> {
            if (!usedExclusions.contains(key)) {
                log.warn(ImportLog.CURATED, CuratedCsvReader.SUBJECT_EXCLUSIONS + ": no subject row matches "
                        + exclusion.schoolName() + " / " + exclusion.subjectDesc() + " (row no longer needed?)");
            }
        });

        Map<String, String> spellings = preferredSpellings(schools, subjectsByName, log);

        List<JoinedSchool> joined = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DataGovSgRecord row : schools) {
            String name = names.canonical(NameNormaliser.SCHOOLS, field(row, SCHOOL_NAME));
            if (name == null) {
                log.warn(ImportLog.DUPLICATE_NAME, "a school row has no school_name and was skipped: " + row.fields());
                continue;
            }
            if (!seen.add(name)) {
                log.warn(ImportLog.DUPLICATE_NAME, name + " appears more than once in the schools dataset");
            }
            List<String> schoolCcas = sorted(ccasByName.get(name));
            List<String> programmes = sorted(respell(subjectsByName.get(name), spellings));
            if (schoolCcas.isEmpty()) {
                log.warn(ImportLog.CCA_JOIN_MISS, name + " has no rows in the CCA dataset");
            }
            if (programmes.isEmpty()) {
                log.warn(ImportLog.SUBJECT_JOIN_MISS, name + " has no rows in the subjects dataset");
            }
            joined.add(new JoinedSchool(row, name, schoolCcas, programmes));
        }
        return joined;
    }

    /** The value of {@code name} in {@code row}, matching the field name ignoring case; null when missing. */
    static String field(DataGovSgRecord row, String name) {
        String exact = row.get(name);
        if (exact != null) {
            return exact;
        }
        for (String key : row.fields().keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return row.get(key);
            }
        }
        return null;
    }

    /**
     * For every subject name that the joined schools spell in more than one letter case: lower-cased name →
     * the spelling to keep (see class comment). Names spelled one way are not in the map.
     */
    private Map<String, String> preferredSpellings(List<DataGovSgRecord> schools,
                                                   Map<String, Set<String>> subjectsByName, ImportLog log) {
        Map<String, Map<String, Integer>> schoolsBySpelling = new TreeMap<>();   // lower case → spelling → schools
        Set<String> counted = new HashSet<>();
        for (DataGovSgRecord row : schools) {
            String name = names.canonical(NameNormaliser.SCHOOLS, field(row, SCHOOL_NAME));
            Set<String> values = name == null || !counted.add(name) ? null : subjectsByName.get(name);
            for (String value : values == null ? Set.<String>of() : values) {
                schoolsBySpelling.computeIfAbsent(value.toLowerCase(Locale.ROOT), k -> new TreeMap<>())
                        .merge(value, 1, Integer::sum);
            }
        }
        Comparator<Map.Entry<String, Integer>> preference = Comparator
                .comparing((Map.Entry<String, Integer> e) -> isAllUpperCase(e.getKey()))
                .thenComparing(Map.Entry::getValue, Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey);
        Map<String, String> result = new HashMap<>();
        schoolsBySpelling.forEach((lower, counts) -> {
            if (counts.size() > 1) {
                String keep = counts.entrySet().stream().min(preference).orElseThrow().getKey();
                result.put(lower, keep);
                log.increment(SUBJECT_SPELLINGS_MERGED);
                log.info("subject spelling: " + counts + " → \"" + keep + "\"");
            }
        });
        return result;
    }

    /** {@code values} with every spelling replaced by its preferred one (so each subject appears once). */
    private static Set<String> respell(Set<String> values, Map<String, String> spellings) {
        if (values == null || spellings.isEmpty()) {
            return values;
        }
        Set<String> result = new TreeSet<>();
        for (String value : values) {
            result.add(spellings.getOrDefault(value.toLowerCase(Locale.ROOT), value));
        }
        return result;
    }

    private static boolean isAllUpperCase(String value) {
        return value.equals(value.toUpperCase(Locale.ROOT)) && !value.equals(value.toLowerCase(Locale.ROOT));
    }

    /** "SCHOOL NAME|subject" with the subject cleaned and lower-cased; null when either part is missing. */
    private static String exclusionKey(String canonicalSchoolName, String subject) {
        String clean = cleanValue(subject);
        return canonicalSchoolName == null || clean == null ? null
                : canonicalSchoolName + "|" + clean.toLowerCase(Locale.ROOT);
    }

    private static void add(Map<String, Set<String>> map, String schoolName, String value) {
        String clean = cleanValue(value);
        if (schoolName != null && clean != null) {
            map.computeIfAbsent(schoolName, k -> new TreeSet<>()).add(clean);
        }
    }

    /** Trimmed with single spaces; null for missing, "na", "n/a", "nil" or "-". */
    public static String cleanValue(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        return clean.isEmpty() || lower.equals("na") || lower.equals("n/a") || lower.equals("nil") || lower.equals("-")
                ? null : clean;
    }

    private static List<String> sorted(Set<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * One school row with its joined CCAs and programmes.
     *
     * @param row  the published row of the schools dataset
     * @param name the canonical (normalised) name used for joining and for {@code school-codes.csv}
     */
    public record JoinedSchool(DataGovSgRecord row, String name, List<String> ccas, List<String> programmes) {

        public JoinedSchool {
            ccas = List.copyOf(ccas);
            programmes = List.copyOf(programmes);
        }
    }
}

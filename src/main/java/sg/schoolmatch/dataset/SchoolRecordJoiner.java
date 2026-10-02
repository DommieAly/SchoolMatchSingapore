package sg.schoolmatch.dataset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import sg.schoolmatch.boundary.external.DataGovSgRecord;

/**
 * Joins the data.gov.sg CCA and subject rows to the school rows (DC-12, FR-DATA-03). Those datasets have no
 * school code, so rows are matched by {@link NameNormaliser#canonical} name. A school with no CCA or subject
 * rows is kept and reported ({@link ImportLog#CCA_JOIN_MISS}, {@link ImportLog#SUBJECT_JOIN_MISS}).
 * <ul>
 *   <li>CCAs: {@code cca_grouping_desc}, from rows whose {@code school_section} is not PRIMARY or JUNIOR COLLEGE.</li>
 *   <li>Programmes: {@code Subject_Desc} (the seed did the same; "programmes = subjects offered").</li>
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

    private final NameNormaliser names;

    public SchoolRecordJoiner(NameNormaliser names) {
        this.names = names;
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
        for (DataGovSgRecord row : subjects) {
            add(subjectsByName, names.canonical(NameNormaliser.SUBJECTS, field(row, SCHOOL_NAME)),
                    field(row, SUBJECT_NAME));
        }

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
            List<String> programmes = sorted(subjectsByName.get(name));
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

package sg.schoolmatch.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static sg.schoolmatch.dataset.ImportFixtures.records;
import static sg.schoolmatch.dataset.ImportFixtures.row;
import static sg.schoolmatch.dataset.ImportFixtures.secondarySchools;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.boundary.external.DataGovSgRecord;
import sg.schoolmatch.dataset.CuratedCsvReader.NameAlias;
import sg.schoolmatch.dataset.SchoolRecordJoiner.JoinedSchool;

/** SchoolRecordJoiner: CCAs and subjects are joined to schools by normalised name (DC-12). Fixture data only. */
class SchoolRecordJoinerTest {

    private final ImportLog log = new ImportLog();
    private final SchoolRecordJoiner joiner = new SchoolRecordJoiner(new NameNormaliser(List.of()));

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-Joiner-01: CCAs are joined by normalised name (a curly apostrophe and double space still match)")
    void joinsCcas() {
        Map<String, JoinedSchool> joined = byName(joiner.join(secondarySchools(), records("cca.json"),
                records("subjects.json"), log));

        assertThat(joined.get("CATHOLIC HIGH SCHOOL").ccas())
                .containsExactly("ART AND CRAFTS", "ARTISTIC GYMNASTICS", "BASKETBALL");
        assertThat(joined.get("ST. HILDA'S SECONDARY SCHOOL").ccas())
                .containsExactly("ART AND CRAFTS", "AUDIO VISUAL AID", "BADMINTON");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-Joiner-02: PRIMARY and JUNIOR COLLEGE section rows are not a secondary school's CCAs")
    void ignoresOtherSections() {
        Map<String, JoinedSchool> joined = byName(joiner.join(secondarySchools(), records("cca.json"),
                records("subjects.json"), log));

        assertThat(joined.get("HUA YI SECONDARY SCHOOL").ccas()).containsExactly("ART AND CRAFTS", "BADMINTON");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-Joiner-03: subjects become the school's programmes, distinct and sorted")
    void joinsSubjects() {
        List<DataGovSgRecord> subjects = new java.util.ArrayList<>(records("subjects.json"));
        subjects.add(row("School_Name", "HUA YI SECONDARY SCHOOL", "Subject_Desc", "Art"));   // duplicate row

        Map<String, JoinedSchool> joined = byName(joiner.join(secondarySchools(), records("cca.json"), subjects, log));

        assertThat(joined.get("HUA YI SECONDARY SCHOOL").programmes()).containsExactly("Additional Mathematics", "Art");
        assertThat(joined.get("CATHOLIC HIGH SCHOOL").programmes())
                .containsExactly("Additional Mathematics", "Appreciation of Chinese Culture");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-Joiner-04: a school with no CCA or subject rows is kept and reported as a join miss")
    void reportsMisses() {
        Map<String, JoinedSchool> joined = byName(joiner.join(secondarySchools(), records("cca.json"),
                records("subjects.json"), log));

        assertThat(joined).containsKey("CHIJ ST. THERESA'S CONVENT");
        assertThat(joined.get("CHIJ ST. THERESA'S CONVENT").ccas()).isEmpty();
        assertThat(log.warnings(ImportLog.CCA_JOIN_MISS)).containsExactly(
                ImportLog.CCA_JOIN_MISS + ": CHIJ ST. THERESA'S CONVENT has no rows in the CCA dataset");
        assertThat(log.warnings(ImportLog.SUBJECT_JOIN_MISS)).hasSize(1);
        assertThat(log.counts()).containsEntry(ImportLog.CCA_JOIN_MISS, 1);
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-Joiner-05: a name-aliases.csv row joins a CCA row whose name is spelled differently")
    void aliasJoins() {
        SchoolRecordJoiner withAlias = new SchoolRecordJoiner(new NameNormaliser(List.of(
                new NameAlias(NameNormaliser.CCAS, "CHIJ ST THERESAS CONVENT", "CHIJ ST. THERESA'S CONVENT"))));
        List<DataGovSgRecord> ccas = List.of(row("School_name", "CHIJ St Theresas Convent",
                "school_section", "SECONDARY (S1-S5)", "cca_grouping_desc", "CHOIR"));

        Map<String, JoinedSchool> joined = byName(withAlias.join(secondarySchools(), ccas, List.of(), log));

        assertThat(joined.get("CHIJ ST. THERESA'S CONVENT").ccas()).containsExactly("CHOIR");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-Joiner-06: the school keeps its published row; 'na' values are cleaned later, not dropped here")
    void keepsRow() {
        JoinedSchool catholic = byName(joiner.join(secondarySchools(), List.of(), List.of(), log))
                .get("CATHOLIC HIGH SCHOOL");

        assertThat(catholic.row().get("postal_code")).isEqualTo("579767");
        assertThat(catholic.row().get("school_name")).isEqualTo("CATHOLIC HIGH SCHOOL");
    }

    @Test
    @Tag("FR-DATA-01")
    @DisplayName("TC-Joiner-07: two school rows with the same normalised name are reported")
    void duplicateNames() {
        List<DataGovSgRecord> schools = List.of(row("school_name", "ABC SECONDARY SCHOOL"),
                row("school_name", "abc  secondary school"));

        List<JoinedSchool> joined = joiner.join(schools, List.of(), List.of(), log);

        assertThat(joined).hasSize(2);
        assertThat(log.warnings(ImportLog.DUPLICATE_NAME)).hasSize(1);
    }

    private static Map<String, JoinedSchool> byName(List<JoinedSchool> joined) {
        return joined.stream().collect(Collectors.toMap(JoinedSchool::name, Function.identity()));
    }
}

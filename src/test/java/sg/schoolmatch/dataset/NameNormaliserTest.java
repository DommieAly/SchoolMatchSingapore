package sg.schoolmatch.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.dataset.CuratedCsvReader.NameAlias;

/** NameNormaliser: school names from different datasets are compared in one normal form (DC-12). */
class NameNormaliserTest {

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-NameNormaliser-01: upper case, trimmed, single spaces, straight apostrophes")
    void normalise() {
        assertThat(NameNormaliser.normalise("  St.  Hilda’s \t Secondary   School "))
                .isEqualTo("ST. HILDA'S SECONDARY SCHOOL");
        assertThat(NameNormaliser.normalise("Crescent Girls‘ School")).isEqualTo("CRESCENT GIRLS' SCHOOL");
        assertThat(NameNormaliser.normalise("Holy Innocentsʼ High School")).isEqualTo("HOLY INNOCENTS' HIGH SCHOOL");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-NameNormaliser-02: null or blank names normalise to null")
    void blank() {
        assertThat(NameNormaliser.normalise(null)).isNull();
        assertThat(NameNormaliser.normalise("   ")).isNull();
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-NameNormaliser-03: a name-aliases.csv row maps a dataset's raw name to the canonical name")
    void alias() {
        NameNormaliser names = new NameNormaliser(List.of(
                new NameAlias("ccas", "nus high sch of maths & science", "NUS High School of Mathematics and Science")));

        assertThat(names.canonical("ccas", "NUS HIGH SCH OF MATHS  & SCIENCE"))
                .isEqualTo("NUS HIGH SCHOOL OF MATHEMATICS AND SCIENCE");
        assertThat(names.canonical("subjects", "NUS HIGH SCH OF MATHS & SCIENCE"))
                .as("the alias is only for the ccas dataset")
                .isEqualTo("NUS HIGH SCH OF MATHS & SCIENCE");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-NameNormaliser-04: dataset '*' applies to every dataset; a dataset-specific alias wins")
    void wildcardAlias() {
        NameNormaliser names = new NameNormaliser(List.of(
                new NameAlias("*", "SOTA", "SCHOOL OF THE ARTS, SINGAPORE"),
                new NameAlias("subjects", "SOTA", "SCHOOL OF THE ARTS SINGAPORE (SUBJECTS)")));

        assertThat(names.canonical("ccas", "sota")).isEqualTo("SCHOOL OF THE ARTS, SINGAPORE");
        assertThat(names.canonical("subjects", "sota")).isEqualTo("SCHOOL OF THE ARTS SINGAPORE (SUBJECTS)");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-NameNormaliser-05: without an alias the canonical name is the normalised name")
    void noAlias() {
        NameNormaliser names = new NameNormaliser(List.of());

        assertThat(names.canonical("schools", " catholic  high school")).isEqualTo("CATHOLIC HIGH SCHOOL");
        assertThat(names.canonical("schools", null)).isNull();
    }
}

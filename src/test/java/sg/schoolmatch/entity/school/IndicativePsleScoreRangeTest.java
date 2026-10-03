package sg.schoolmatch.entity.school;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit tests for «entity» IndicativePsleScoreRange. */
class IndicativePsleScoreRangeTest {

    private final IndicativePsleScoreRange pg3 = new IndicativePsleScoreRange(2025, 3, false, 8, 12);

    @ParameterizedTest(name = "score {0} in 8–12 → {1}")
    @CsvSource({"7, false", "8, true", "10, true", "12, true", "13, false"})
    @Tag("FR-FILTER-03")
    @DisplayName("TC-IndicativePsleScoreRange-01: contains is inclusive at both ends (lower ≤ score ≤ upper)")
    void containsBoundaries(int score, boolean expected) {
        assertThat(pg3.contains(score)).isEqualTo(expected);
    }

    @Test
    @Tag("FR-SCHOOL-02")
    @Tag("NFR-DATA-03")
    @DisplayName("TC-IndicativePsleScoreRange-02: describe shows posting group, range, year and affiliation")
    void describe() {
        assertThat(pg3.describe()).isEqualTo("PG3 8–12 (2025, non-affiliated)");
        assertThat(new IndicativePsleScoreRange(2024, 1, true, 20, 25).describe())
                .isEqualTo("PG1 20–25 (2024, affiliated)");
    }

    @Test
    @Tag("FR-SCHOOL-02")
    @Tag("NFR-DATA-03")
    @DisplayName("TC-IndicativePsleScoreRange-03: an Integrated Programme range says IP in describe and getRangeText (DC-77)")
    void integratedProgramme() {
        IndicativePsleScoreRange ip = new IndicativePsleScoreRange(2025, 3, false, 4, 8, true);

        assertThat(ip.isIntegratedProgramme()).isTrue();
        assertThat(ip.describe()).isEqualTo("PG3 IP 4–8 (2025)");
        assertThat(ip.getRangeText()).isEqualTo("IP 4–8");
        assertThat(ip.contains(8)).isTrue();
        // The 5-argument constructor stays and means a non-IP range.
        assertThat(pg3.isIntegratedProgramme()).isFalse();
        assertThat(pg3.getRangeText()).isEqualTo("8–12");
    }

    @Test
    @Tag("FR-SCHOOL-02")
    @DisplayName("TC-IndicativePsleScoreRange-04: an affiliated IP range says so in describe (DC-82)")
    void affiliatedIntegratedProgramme() {
        IndicativePsleScoreRange ip = new IndicativePsleScoreRange(2025, 3, true, 4, 8, true);

        assertThat(ip.describe()).isEqualTo("PG3 IP 4–8 (2025, affiliated)");
        assertThat(ip.getRangeText()).isEqualTo("IP 4–8");
    }

    @Test
    @Tag("FR-SCHOOL-02")
    @Tag("NFR-DATA-03")
    @DisplayName("TC-IndicativePsleScoreRange-05: MOE's text is kept; Higher Chinese grades and '30*' (places left) are recognised (DC-82)")
    void moeText() {
        IndicativePsleScoreRange sap = new IndicativePsleScoreRange(2025, 3, false, 6, 8, false, "6(D) - 8(M)");
        IndicativePsleScoreRange vacancies = new IndicativePsleScoreRange(2025, 1, false, 26, 30, false, "26 - 30*");
        IndicativePsleScoreRange plain = new IndicativePsleScoreRange(2025, 3, false, 8, 12, false, "8 - 12");

        assertThat(sap.getMoeText()).isEqualTo("6(D) - 8(M)");
        assertThat(sap.hasHigherChineseGrades()).isTrue();
        assertThat(sap.hadPlacesLeft()).isFalse();
        assertThat(vacancies.hadPlacesLeft()).isTrue();
        assertThat(vacancies.hasHigherChineseGrades()).isFalse();
        assertThat(plain.hasHigherChineseGrades()).isFalse();
        assertThat(plain.hadPlacesLeft()).isFalse();
        // No MOE text (seed data, older snapshots): no flags.
        assertThat(pg3.getMoeText()).isNull();
        assertThat(pg3.hasHigherChineseGrades()).isFalse();
        assertThat(pg3.hadPlacesLeft()).isFalse();
    }
}

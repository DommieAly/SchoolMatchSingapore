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
}

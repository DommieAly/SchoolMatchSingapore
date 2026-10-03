package sg.schoolmatch.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static sg.schoolmatch.dataset.DatasetTestSupport.reader;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MoeRangeText: MOE's range cell text is stored as its parts (lower score and grade, upper score and grade, the
 * {@code *} flag) and rebuilt exactly (docs/database-design.md, section 5.2; DC-85).
 */
class MoeRangeTextTest {

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-MoeRangeText-01: the five forms MOE uses are split into their parts")
    void parsesTheFiveForms() {
        assertThat(MoeRangeText.parse("8 - 12")).contains(new MoeRangeText.Parts(8, null, 12, null, false));
        assertThat(MoeRangeText.parse("26 - 30*")).contains(new MoeRangeText.Parts(26, null, 30, null, true));
        assertThat(MoeRangeText.parse("6(D) - 8(M)")).contains(new MoeRangeText.Parts(6, "D", 8, "M", false));
        assertThat(MoeRangeText.parse("7(M) - 9(M)")).contains(new MoeRangeText.Parts(7, "M", 9, "M", false));
        assertThat(MoeRangeText.parse("5(M) - 7")).contains(new MoeRangeText.Parts(5, "M", 7, null, false));
    }

    @ParameterizedTest(name = "TC-MoeRangeText-02 [{index}]: \"{0}\" is refused")
    @ValueSource(strings = {"", "8-12", "8 -12", "8 to 12", "8 - 12 *", "8(A) - 12", "8 - 12**", " 8 - 12",
            "8 - 12 ", "(D) - 12", "8 - ", "8", "8(D)(M) - 12", "8 - 12(d)"})
    @Tag("NFR-DATA-03")
    @DisplayName("TC-MoeRangeText-02: any other text is refused, so nothing is lost silently")
    void refusesOtherTexts(String text) {
        assertThat(MoeRangeText.parse(text)).isEmpty();
        assertThat(MoeRangeText.problem(text, 8, 12)).isNotNull();
    }

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-MoeRangeText-03: format rebuilds the text from the parts; unknown text (places_left NULL) gives null")
    void formatRebuilds() {
        assertThat(MoeRangeText.format(6, "D", 8, "M", false)).isEqualTo("6(D) - 8(M)");
        assertThat(MoeRangeText.format(26, null, 30, null, true)).isEqualTo("26 - 30*");
        assertThat(MoeRangeText.format(5, "M", 7, null, false)).isEqualTo("5(M) - 7");
        assertThat(MoeRangeText.format(8, null, 12, null, null)).isNull();
        assertThat(MoeRangeText.parts(null, 8, 12)).isEqualTo(new MoeRangeText.Parts(8, null, 12, null, null));
    }

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-MoeRangeText-04: the numbers in the text must equal the range's lower and upper score")
    void numbersMustMatchTheScores() {
        assertThat(MoeRangeText.problem("8 - 12", 8, 12)).isNull();
        assertThat(MoeRangeText.problem(null, 8, 12)).isNull();
        assertThat(MoeRangeText.problem("8 - 12", 8, 13)).contains("12").contains("13");
        assertThat(MoeRangeText.problem("9 - 12", 8, 12)).contains("9").contains("8");
    }

    @Test
    @Tag("NFR-DATA-03")
    @Tag("FR-DATA-03")
    @DisplayName("TC-MoeRangeText-05: every MOE text in the ACTIVE snapshot (444 ranges, 113 texts) round-trips exactly")
    void everyRealTextRoundTrips() {
        LoadedSnapshot active = reader(Map.of()).read();
        Set<String> distinct = new TreeSet<>();
        int ranges = 0;
        for (SchoolRecord r : active.records()) {
            for (ScoreRangeRecord range : r.scoreRanges()) {
                ranges++;
                String text = range.moeText();
                if (text == null) {
                    continue;
                }
                distinct.add(text);
                assertThat(MoeRangeText.problem(text, range.lowerScore(), range.upperScore()))
                        .as(r.schoolCode() + " " + text).isNull();
                MoeRangeText.Parts parts = MoeRangeText.parts(text, range.lowerScore(), range.upperScore());
                assertThat(MoeRangeText.format(parts.lowerScore(), parts.lowerGrade(), parts.upperScore(),
                        parts.upperGrade(), parts.placesLeft())).isEqualTo(text);
            }
        }
        assertThat(ranges).isGreaterThan(400);
        assertThat(distinct).hasSizeGreaterThan(100);
    }
}

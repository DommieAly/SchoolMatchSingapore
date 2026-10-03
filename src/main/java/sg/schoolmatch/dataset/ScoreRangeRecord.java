package sg.schoolmatch.dataset;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One PSLE score range inside {@code schools.json} (JSON DTO for {@code IndicativePsleScoreRange}).
 * DC-77: {@code integratedProgramme} is true for an Integrated Programme range; a missing value (snapshots written
 * before DC-77) reads as false. DC-82: {@code moeText} is MOE's text of the cell (e.g. {@code "6(D) - 8(M)"},
 * {@code "26 - 30*"}); missing (seed, older snapshots) reads as null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoreRangeRecord(
        Integer admissionYear,
        Integer postingGroup,
        Boolean affiliated,
        Integer lowerScore,
        Integer upperScore,
        Boolean integratedProgramme,
        String moeText) {

    @JsonCreator
    public ScoreRangeRecord {
        integratedProgramme = Boolean.TRUE.equals(integratedProgramme);
    }

    /** A range of the normal (non-IP) track. */
    public ScoreRangeRecord(Integer admissionYear, Integer postingGroup, Boolean affiliated, Integer lowerScore,
                            Integer upperScore) {
        this(admissionYear, postingGroup, affiliated, lowerScore, upperScore, false, null);
    }

    /** DC-77: a range with its IP flag and no MOE text. */
    public ScoreRangeRecord(Integer admissionYear, Integer postingGroup, Boolean affiliated, Integer lowerScore,
                            Integer upperScore, Boolean integratedProgramme) {
        this(admissionYear, postingGroup, affiliated, lowerScore, upperScore, integratedProgramme, null);
    }

    /**
     * True when every value is present (the validator reports incomplete ranges). DC-85: a derived value, not a
     * schools.json field; without {@code @JsonIgnore} Jackson wrote it into every range as {@code "complete"}.
     */
    @JsonIgnore
    public boolean isComplete() {
        return admissionYear != null && postingGroup != null && affiliated != null
                && lowerScore != null && upperScore != null;
    }
}

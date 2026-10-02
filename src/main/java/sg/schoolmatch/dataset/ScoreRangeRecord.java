package sg.schoolmatch.dataset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One PSLE score range inside {@code schools.json} (JSON DTO for {@code IndicativePsleScoreRange}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoreRangeRecord(
        Integer admissionYear,
        Integer postingGroup,
        Boolean affiliated,
        Integer lowerScore,
        Integer upperScore) {

    /** True when every value is present (the validator reports incomplete ranges). */
    public boolean isComplete() {
        return admissionYear != null && postingGroup != null && affiliated != null
                && lowerScore != null && upperScore != null;
    }
}

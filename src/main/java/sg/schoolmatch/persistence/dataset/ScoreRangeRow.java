package sg.schoolmatch.persistence.dataset;

/** One row of {@code indicative_psle_score_range}; MOE's text is stored as parts ({@code dataset.MoeRangeText}). */
public record ScoreRangeRow(
        String schoolCode,
        int admissionYear,
        int postingGroup,
        boolean affiliated,
        boolean integratedProgramme,
        int lowerScore,
        int upperScore,
        String lowerHclGrade,
        String upperHclGrade,
        Boolean placesLeft) {
}

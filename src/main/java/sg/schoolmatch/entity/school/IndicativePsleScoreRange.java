package sg.schoolmatch.entity.school;

/**
 * Design class «entity» IndicativePsleScoreRange — one historical PSLE AL score range of a school
 * for one admission year, posting group and affiliation (NFR-DATA-03: historical, not guaranteed).
 * Lower AL score is better. The constructor does not validate: SnapshotValidator reports bad ranges.
 */
public class IndicativePsleScoreRange {

    private final int admissionYear;
    private final int postingGroup;
    private final boolean affiliated;
    private final int lowerScore;
    private final int upperScore;

    public IndicativePsleScoreRange(int admissionYear, int postingGroup, boolean affiliated,
                                    int lowerScore, int upperScore) {
        this.admissionYear = admissionYear;
        this.postingGroup = postingGroup;
        this.affiliated = affiliated;
        this.lowerScore = lowerScore;
        this.upperScore = upperScore;
    }

    /** True when {@code lowerScore <= score <= upperScore}. */
    public boolean contains(int score) {
        return lowerScore <= score && score <= upperScore;
    }

    /** Short label, e.g. {@code "PG3 8–12 (2025, non-affiliated)"}. */
    public String describe() {   // DC-36 helper
        return "PG" + postingGroup + " " + lowerScore + "–" + upperScore
                + " (" + admissionYear + ", " + (affiliated ? "affiliated" : "non-affiliated") + ")";
    }

    public int getAdmissionYear() {
        return admissionYear;
    }

    public int getPostingGroup() {
        return postingGroup;
    }

    public boolean isAffiliated() {
        return affiliated;
    }

    public int getLowerScore() {
        return lowerScore;
    }

    public int getUpperScore() {
        return upperScore;
    }

    @Override
    public String toString() {
        return describe();
    }
}

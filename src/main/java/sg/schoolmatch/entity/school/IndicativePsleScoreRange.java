package sg.schoolmatch.entity.school;

/**
 * Design class «entity» IndicativePsleScoreRange — one historical PSLE AL score range of a school
 * for one admission year, posting group and affiliation (NFR-DATA-03: historical, not guaranteed).
 * Lower AL score is better. The constructor does not validate: SnapshotValidator reports bad ranges.
 * <p>
 * DC-77 (IP range as PG3 fallback): {@code integratedProgramme} marks the school's Integrated Programme range.
 * MOE SchoolFinder files IP under posting group 3, so an IP range has {@code postingGroup = 3}; it is usually
 * non-affiliated, but DC-82 allows an affiliated IP range too (Nanyang Girls' High has both).
 * {@link School#getScoreRange} uses an IP range only when the school has no non-IP PG3 range.
 * <p>
 * DC-82: {@code moeText} is MOE's own text of the cell (e.g. {@code "6(D) - 8(M)"}, {@code "26 - 30*"}), or null when
 * unknown (seed data, older snapshots). The numbers are what the app uses; the text keeps what the numbers drop.
 */
public class IndicativePsleScoreRange {

    private final int admissionYear;
    private final int postingGroup;
    private final boolean affiliated;
    private final int lowerScore;
    private final int upperScore;
    private final boolean integratedProgramme;   // DC-77
    private final String moeText;                // DC-82: MOE's text of the cell, or null

    /** A range of the normal (non-IP) track. */
    public IndicativePsleScoreRange(int admissionYear, int postingGroup, boolean affiliated,
                                    int lowerScore, int upperScore) {
        this(admissionYear, postingGroup, affiliated, lowerScore, upperScore, false);
    }

    /** DC-77: {@code integratedProgramme = true} for an Integrated Programme range (PG3). */
    public IndicativePsleScoreRange(int admissionYear, int postingGroup, boolean affiliated,
                                    int lowerScore, int upperScore, boolean integratedProgramme) {
        this(admissionYear, postingGroup, affiliated, lowerScore, upperScore, integratedProgramme, null);
    }

    /** DC-82: with MOE's text of the cell ({@code moeText}, may be null). */
    public IndicativePsleScoreRange(int admissionYear, int postingGroup, boolean affiliated,
                                    int lowerScore, int upperScore, boolean integratedProgramme, String moeText) {
        this.admissionYear = admissionYear;
        this.postingGroup = postingGroup;
        this.affiliated = affiliated;
        this.lowerScore = lowerScore;
        this.upperScore = upperScore;
        this.integratedProgramme = integratedProgramme;
        this.moeText = moeText == null || moeText.isBlank() ? null : moeText.strip();
    }

    /** True when {@code lowerScore <= score <= upperScore}. */
    public boolean contains(int score) {
        return lowerScore <= score && score <= upperScore;
    }

    /**
     * Short label, e.g. {@code "PG3 8–12 (2025, non-affiliated)"}; for an IP range {@code "PG3 IP 4–8 (2025)"},
     * or {@code "PG3 IP 4–8 (2025, affiliated)"} for the rare affiliated IP range (DC-82).
     */
    public String describe() {   // DC-36 helper
        if (integratedProgramme) {
            return "PG" + postingGroup + " " + getRangeText() + " (" + admissionYear + (affiliated ? ", affiliated" : "")
                    + ")";
        }
        return "PG" + postingGroup + " " + getRangeText()
                + " (" + admissionYear + ", " + (affiliated ? "affiliated" : "non-affiliated") + ")";
    }

    /** The scores for pages: {@code "8–12"}, or {@code "IP 4–8"} for an Integrated Programme range (DC-77). */
    public String getRangeText() {
        return (integratedProgramme ? "IP " : "") + lowerScore + "–" + upperScore;
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

    /** DC-77: true for the school's Integrated Programme range. */
    public boolean isIntegratedProgramme() {
        return integratedProgramme;
    }

    /** DC-82: MOE's own text of the cell, e.g. {@code "6(D) - 8(M)"} or {@code "26 - 30*"}; null when unknown. */
    public String getMoeText() {
        return moeText;
    }

    /**
     * DC-82: MOE's text carries Higher Chinese Language grades, e.g. {@code "6(D) - 8(M)"} at a SAP school: the
     * grade (D = Distinction, M = Merit) of the first and last student posted, used to order students with the same
     * score.
     */
    public boolean hasHigherChineseGrades() {
        return moeText != null && moeText.matches(".*\\d\\s*\\([A-Za-z]\\).*");
    }

    /**
     * DC-82: MOE's text ends in {@code *} (e.g. {@code "26 - 30*"}): the school still had places after posting, so
     * the last student posted had a better score than the upper value shown.
     */
    public boolean hadPlacesLeft() {
        return moeText != null && moeText.endsWith("*");
    }

    @Override
    public String toString() {
        return describe();
    }
}

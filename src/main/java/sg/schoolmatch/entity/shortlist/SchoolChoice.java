package sg.schoolmatch.entity.shortlist;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Transient;
import java.util.Optional;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;

/**
 * Design class «entity» SchoolChoice — one ranked school (1..6) in a ChoicePlan (FR-PLAN-01).
 * Stores only the school code (DC-21); {@code school} and {@code affiliated} are filled by the
 * controls after loading and are not stored. {@code /affiliated} and {@code /admissionChance} are derived.
 */
@Embeddable
public class SchoolChoice {

    /**
     * DC-20: SAFE needs a score at least this many AL points better than U. A fixed rule, not a setting:
     * an entity cannot read {@code AppProperties} (ArchitectureTest rule 4). ChoicePlanUI shows it on the page.
     */
    public static final int SAFE_MARGIN = 2;

    @Column(name = "school_code", nullable = false, length = 100)
    private String schoolCode;

    // "rank" is an SQL function name, so the column is called choice_rank.
    @Column(name = "choice_rank", nullable = false)
    private int rank;

    @Transient
    private School school;

    @Transient
    private boolean affiliated;

    /** For JPA only. */
    protected SchoolChoice() {
    }

    public SchoolChoice(String schoolCode, int rank) {
        this.schoolCode = schoolCode;
        this.rank = rank;
    }

    /**
     * DC-20 rule. U = upperScore of {@code school.getScoreRange(postingGroup, affiliated)}:
     * SAFE if score ≤ U − {@link #SAFE_MARGIN}; MATCH if U − {@link #SAFE_MARGIN} &lt; score ≤ U;
     * REACH if score &gt; U; {@code null} when there is no range (shown as "Not available").
     * See docs/recommendation-scoring.md §2.
     */
    public AdmissionChance assess(int score, int postingGroup) {
        Optional<IndicativePsleScoreRange> range = getApplicableRange(postingGroup);
        if (range.isEmpty()) {
            return null;
        }
        int upper = range.get().getUpperScore();
        if (score <= upper - SAFE_MARGIN) {
            return AdmissionChance.SAFE;
        }
        if (score <= upper) {
            return AdmissionChance.MATCH;
        }
        return AdmissionChance.REACH;
    }

    /**
     * The range this choice is assessed with (DC-22): {@code school.getScoreRange(postingGroup, affiliated)}.
     * Empty when the school has no such range, or is not resolved (no longer in the dataset).
     */
    public Optional<IndicativePsleScoreRange> getApplicableRange(int postingGroup) {   // DC-36 helper
        return school == null ? Optional.empty() : school.getScoreRange(postingGroup, affiliated);
    }

    public String getSchoolCode() {
        return schoolCode;
    }

    public int getRank() {
        return rank;
    }

    void setRank(int rank) {
        this.rank = rank;
    }

    /** Null when not resolved yet, or when the school is no longer in the dataset. */
    public School getSchool() {
        return school;
    }

    public void setSchool(School school) {
        this.school = school;
    }

    public boolean isAffiliated() {
        return affiliated;
    }

    public void setAffiliated(boolean affiliated) {
        this.affiliated = affiliated;
    }

    @Override
    public String toString() {
        return "SchoolChoice{" + rank + ": " + schoolCode + "}";
    }
}

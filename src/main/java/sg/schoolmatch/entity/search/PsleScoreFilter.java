package sg.schoolmatch.entity.search;

import java.util.Optional;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;

/**
 * Design class «entity» PsleScoreFilter — keeps schools a PSLE AL score can reach (FR-FILTER-03).
 * Valid: score 4..32 and posting group 1..3.
 * <p>
 * DC-22: the applicable range of a school is {@code school.getScoreRange(postingGroup, affiliated)}, the latest
 * admission year. DC-40: {@code affiliated} is decided per school — true when the logged-in user's
 * {@code primarySchool} is one of the school's affiliated primary schools. A guest has no primary school, so a
 * guest always gets the non-affiliated range. Rules: docs/recommendation-scoring.md §1 and §3.
 */
public class PsleScoreFilter extends Filter {

    private final int score;
    private final int postingGroup;
    private final String primarySchool;   // DC-40 (replaces `boolean affiliated`): the user's primary school, or null

    public PsleScoreFilter(int score, int postingGroup, String primarySchool) {
        this.score = score;
        this.postingGroup = postingGroup;
        this.primarySchool = primarySchool;
    }

    public int getScore() {
        return score;
    }

    public int getPostingGroup() {
        return postingGroup;
    }

    public String getPrimarySchool() {
        return primarySchool;
    }

    /**
     * DC-22: matches when score ≤ upperScore U of the applicable range (the SAFE and MATCH schools; a score better
     * than the lower score still matches). A school with no applicable range does not match.
     */
    @Override
    public boolean matches(School school) {
        return applicableRange(school).map(range -> score <= range.getUpperScore()).orElse(false);
    }

    @Override
    public boolean isValid() {
        return score >= 4 && score <= 32 && postingGroup >= 1 && postingGroup <= 3;
    }

    /** e.g. "PSLE 12 (PG3)" (FR-FILTER-08). */
    @Override
    public String describe() {
        return "PSLE " + score + " (PG" + postingGroup + ")";
    }

    /**
     * DC-40: true when the user's primary school is one of the school's affiliated primary schools (name rule:
     * {@link School#hasAffiliatedPrimarySchool}, shared with the choice plan and recommendations).
     */
    public boolean isAffiliatedWith(School school) {   // DC-36 helper
        return school.hasAffiliatedPrimarySchool(primarySchool);
    }

    /** True when the school has a range for this posting group; the results page counts the others. */
    public boolean hasApplicableRange(School school) {   // DC-36 helper
        return applicableRange(school).isPresent();
    }

    private Optional<IndicativePsleScoreRange> applicableRange(School school) {
        return school.getScoreRange(postingGroup, isAffiliatedWith(school));
    }
}

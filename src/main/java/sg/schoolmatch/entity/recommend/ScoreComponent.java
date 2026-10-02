package sg.schoolmatch.entity.recommend;

/**
 * Design class «entity» ScoreComponent — one factor's part of a Recommendation's score (FR-REC-01).
 * {@code score} is 0..1; {@code reason} is shown to the user.
 */
public class ScoreComponent {

    private final MatchFactor factor;
    private final double score;
    private final String reason;

    public ScoreComponent(MatchFactor factor, double score, String reason) {
        this.factor = factor;
        this.score = score;
        this.reason = reason;
    }

    public MatchFactor getFactor() {
        return factor;
    }

    public double getScore() {
        return score;
    }

    public String getReason() {
        return reason;
    }
}

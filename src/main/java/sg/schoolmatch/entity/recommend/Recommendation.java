package sg.schoolmatch.entity.recommend;

import java.util.List;
import java.util.Objects;
import sg.schoolmatch.entity.school.School;

/**
 * Design class «entity» Recommendation — one recommended school with its score and reasons (FR-REC-01).
 * {@code rank} and {@code commuteMin} are set after scoring (RecommendationController.rank / addCommuteTimes).
 */
public class Recommendation {

    private int rank;
    private double totalScore;
    private Integer commuteMin;   // null = not available
    private final School school;
    private final List<ScoreComponent> components;

    public Recommendation(School school, double totalScore, List<ScoreComponent> components) {
        this.school = school;
        this.totalScore = totalScore;
        this.components = components == null ? List.of() : List.copyOf(components);
    }

    /** The reason of every component, in order (blank reasons skipped). */
    public List<String> getReasons() {
        return components.stream().map(ScoreComponent::getReason)
                .filter(Objects::nonNull).filter(r -> !r.isBlank()).toList();
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public double getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(double totalScore) {
        this.totalScore = totalScore;
    }

    public Integer getCommuteMin() {
        return commuteMin;
    }

    public void setCommuteMin(Integer commuteMin) {
        this.commuteMin = commuteMin;
    }

    public School getSchool() {
        return school;
    }

    public List<ScoreComponent> getComponents() {
        return components;
    }
}

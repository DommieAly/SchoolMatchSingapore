package sg.schoolmatch.entity.recommend;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.TravelMode;

/**
 * Design class «entity» MatchCriteria — what a student wants, used to rank schools (FR-REC-01).
 * Prefilled from UserProfile.toMatchCriteria() (DC-07). Public no-arg constructor + setters so the
 * criteria form can bind to it.
 * <p>
 * {@code primarySchool} (DC-50) is the student's primary school from the profile: it decides whether
 * a school's affiliated PSLE range applies (DC-22). Empty {@code weights} mean "use app.recommendation.weights".
 */
public class MatchCriteria {

    private Integer psleScore;
    private Integer postingGroup;
    private ReferenceLocation startLocation;
    private TravelMode travelMode;
    private Integer maxCommuteMin;
    private String primarySchool;
    private final Set<String> preferredCCAs = new LinkedHashSet<>();
    private final Set<String> preferredProgrammes = new LinkedHashSet<>();
    private final Map<MatchFactor, Double> weights = new EnumMap<>(MatchFactor.class);

    public MatchCriteria() {
    }

    /** The student's primary school (for affiliated PSLE ranges, DC-22); null when unknown. */
    public String getPrimarySchool() {
        return primarySchool;
    }

    public void setPrimarySchool(String primarySchool) {
        this.primarySchool = primarySchool;
    }

    /** Valid: PSLE score 4..32, posting group 1..3, and no negative weight. */
    public boolean isValid() {
        return psleScore != null && psleScore >= 4 && psleScore <= 32
                && postingGroup != null && postingGroup >= 1 && postingGroup <= 3
                && weights.values().stream().allMatch(w -> w != null && w >= 0);
    }

    public Integer getPsleScore() {
        return psleScore;
    }

    public void setPsleScore(Integer psleScore) {
        this.psleScore = psleScore;
    }

    public Integer getPostingGroup() {
        return postingGroup;
    }

    public void setPostingGroup(Integer postingGroup) {
        this.postingGroup = postingGroup;
    }

    public ReferenceLocation getStartLocation() {
        return startLocation;
    }

    public void setStartLocation(ReferenceLocation startLocation) {
        this.startLocation = startLocation;
    }

    public TravelMode getTravelMode() {
        return travelMode;
    }

    public void setTravelMode(TravelMode travelMode) {
        this.travelMode = travelMode;
    }

    public Integer getMaxCommuteMin() {
        return maxCommuteMin;
    }

    public void setMaxCommuteMin(Integer maxCommuteMin) {
        this.maxCommuteMin = maxCommuteMin;
    }

    public Set<String> getPreferredCCAs() {
        return Collections.unmodifiableSet(preferredCCAs);
    }

    public void setPreferredCCAs(Collection<String> preferredCCAs) {
        this.preferredCCAs.clear();
        if (preferredCCAs != null) {
            this.preferredCCAs.addAll(preferredCCAs);
        }
    }

    public Set<String> getPreferredProgrammes() {
        return Collections.unmodifiableSet(preferredProgrammes);
    }

    public void setPreferredProgrammes(Collection<String> preferredProgrammes) {
        this.preferredProgrammes.clear();
        if (preferredProgrammes != null) {
            this.preferredProgrammes.addAll(preferredProgrammes);
        }
    }

    public Map<MatchFactor, Double> getWeights() {
        return Collections.unmodifiableMap(weights);
    }

    public void setWeights(Map<MatchFactor, Double> weights) {
        this.weights.clear();
        if (weights != null) {
            this.weights.putAll(weights);
        }
    }
}

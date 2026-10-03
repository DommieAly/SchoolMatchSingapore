package sg.schoolmatch.entity.shortlist;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import sg.schoolmatch.entity.school.School;

/**
 * Design class «entity» ChoicePlan — up to 6 ordered school choices for S1 posting (FR-PLAN-01, FR-PLAN-02).
 * Owned by the account's Shortlist. Ranks are always 1..n in list order.
 * <p>
 * SAFE/MATCH/REACH and the warnings use the plan's own {@code basedOnScore} and {@code postingGroup}
 * (docs/recommendation-scoring.md §2, §4). The member's current profile values are not stored here:
 * ChoicePlanController fills them in after loading ({@link #setCurrentProfile}, like SchoolChoice.school, DC-21),
 * so {@link #isOutdated()} can tell when the profile changed after the plan was made.
 */
@Entity
@Table(name = "choice_plan")
public class ChoicePlan {

    public static final int MAX_CHOICES = 6;   // DC-36 helper

    /** Warn when more than this many choices are REACH (docs/recommendation-scoring.md §6). */
    public static final int MAX_REACH_CHOICES = 3;

    /** Posting group used when the profile has a PSLE score but no posting group (same default as the filter). */
    public static final int DEFAULT_POSTING_GROUP = 3;

    public static final String NO_SAFE_WARNING = "None of your choices is SAFE. "
            + "Add at least one school where your score is clearly inside the range.";

    /** DC-74: replaces the range-based warnings when the dataset has no PSLE score ranges at all. */
    public static final String NO_PSLE_DATA_WARNING = "PSLE score ranges are not available yet, so no choice has a "
            + "SAFE/MATCH/REACH label and the plan check cannot use them.";

    @Id
    @GeneratedValue
    private Long id;

    private Integer basedOnScore;

    private Integer postingGroup;

    private Instant updatedAt;

    // EAGER + separate select: small list, open-in-view is off, and Hibernate cannot join-fetch two lists at once.
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @CollectionTable(name = "choice_plan_choice", joinColumns = @JoinColumn(name = "choice_plan_id"))
    @OrderBy("rank")
    private List<SchoolChoice> choices = new ArrayList<>();

    // The member's profile now (not stored; filled by ChoicePlanController).
    @Transient
    private boolean currentProfileKnown;

    @Transient
    private Integer currentProfileScore;

    @Transient
    private Integer currentProfilePostingGroup;

    /** For JPA only. */
    protected ChoicePlan() {
    }

    public ChoicePlan(Integer basedOnScore, Integer postingGroup) {
        this.basedOnScore = basedOnScore;
        this.postingGroup = postingGroup;
    }

    /**
     * Inserts {@code school} at {@code rank} (1..6); later choices move down. A rank beyond the end
     * appends. Ranks are renumbered 1..n.
     *
     * @throws IllegalArgumentException if the school is already in the plan, the rank is outside 1..6,
     *                                  or the plan already has 6 choices
     */
    public void addChoice(School school, int rank) {
        Objects.requireNonNull(school, "school");
        if (contains(school.getSchoolCode())) {
            throw new IllegalArgumentException("School is already in the plan: " + school.getSchoolCode());
        }
        if (rank < 1 || rank > MAX_CHOICES) {
            throw new IllegalArgumentException("Rank must be between 1 and " + MAX_CHOICES + ", got " + rank);
        }
        if (choices.size() >= MAX_CHOICES) {
            throw new IllegalArgumentException("A plan has at most " + MAX_CHOICES + " choices");
        }
        SchoolChoice choice = new SchoolChoice(school.getSchoolCode(), rank);
        choice.setSchool(school);
        choices.add(Math.min(rank, choices.size() + 1) - 1, choice);
        renumber();
    }

    /**
     * Moves the choice at rank {@code from} to rank {@code to} (both 1..n); ranks are renumbered 1..n.
     *
     * @throws IllegalArgumentException if either rank is outside 1..n
     */
    public void reorder(int from, int to) {
        if (from < 1 || from > choices.size() || to < 1 || to > choices.size()) {
            throw new IllegalArgumentException("Ranks must be between 1 and " + choices.size());
        }
        choices.add(to - 1, choices.remove(from - 1));
        renumber();
    }

    /** Removes the school's choice if present; ranks are renumbered 1..n. */
    public void removeChoice(String schoolCode) {
        if (choices.removeIf(c -> c.getSchoolCode().equals(schoolCode))) {
            renumber();
        }
    }

    /**
     * Plan warnings (FR-PLAN-02, docs/recommendation-scoring.md §4), one message per rule that fires:
     * no SAFE choice; more than {@link #MAX_REACH_CHOICES} REACH choices; fewer than {@link #MAX_CHOICES}
     * choices; each choice without range data. The first two need a score. The "profile changed" warning
     * is added by ChoicePlanController.assessPlan. Same as {@code getRiskWarnings(true)}.
     */
    public List<String> getRiskWarnings() {
        return getRiskWarnings(true);
    }

    /**
     * DC-74: {@code psleData} is false when the active dataset has no PSLE score range at all. The warnings that
     * need ranges (no SAFE choice, too many REACH, one per choice without range data) are then replaced by one
     * {@link #NO_PSLE_DATA_WARNING}; "N of 6 choices" and "no longer in the dataset" stay.
     */
    public List<String> getRiskWarnings(boolean psleData) {
        List<String> warnings = new ArrayList<>();
        if (!psleData) {
            warnings.add(NO_PSLE_DATA_WARNING);
        }
        if (psleData && basedOnScore != null && postingGroup != null && !choices.isEmpty()) {
            long safe = choices.stream().filter(c -> getAdmissionChance(c) == AdmissionChance.SAFE).count();
            long reach = choices.stream().filter(c -> getAdmissionChance(c) == AdmissionChance.REACH).count();
            if (safe == 0) {
                warnings.add(NO_SAFE_WARNING);
            }
            if (reach > MAX_REACH_CHOICES) {
                warnings.add("More than " + MAX_REACH_CHOICES + " of your choices are REACH.");
            }
        }
        if (choices.size() < MAX_CHOICES) {
            warnings.add("You have " + choices.size() + " of " + MAX_CHOICES + " choices. Fill all " + MAX_CHOICES
                    + " to lower the risk of being posted to a school you did not choose.");
        }
        if (postingGroup != null || !psleData) {
            for (SchoolChoice choice : choices) {
                if (choice.getSchool() == null) {
                    warnings.add(choice.getSchoolCode() + (psleData
                            ? ": this school is no longer in the dataset, so it has no SAFE/MATCH/REACH label."
                            : ": this school is no longer in the dataset."));
                } else if (psleData && choice.getApplicableRange(postingGroup).isEmpty()) {
                    warnings.add(choice.getSchool().getName() + ": no PSLE range data, so no SAFE/MATCH/REACH label.");
                }
            }
        }
        return warnings;
    }

    /**
     * SAFE/MATCH/REACH of one of this plan's choices for the plan's score and posting group (DC-20);
     * null when the plan has no score or the school has no applicable range ("Not available").
     */
    public AdmissionChance getAdmissionChance(SchoolChoice choice) {
        if (basedOnScore == null || postingGroup == null) {
            return null;
        }
        return choice.assess(basedOnScore, postingGroup);
    }

    /**
     * True when the member's profile (filled in by {@link #setCurrentProfile}) no longer has the score or
     * posting group this plan was made for. A profile without a posting group counts as
     * {@link #DEFAULT_POSTING_GROUP}. False when the plan has no score yet or the profile is not filled in.
     */
    public boolean isOutdated() {   // DC-65
        if (!currentProfileKnown || basedOnScore == null) {
            return false;
        }
        if (currentProfileScore == null) {
            return true;
        }
        int profilePostingGroup = currentProfilePostingGroup != null ? currentProfilePostingGroup : DEFAULT_POSTING_GROUP;
        return !basedOnScore.equals(currentProfileScore) || !Objects.equals(postingGroup, profilePostingGroup);
    }

    /** The member's current profile score and posting group (either may be null); not stored. */
    public void setCurrentProfile(Integer score, Integer postingGroup) {
        this.currentProfileKnown = true;
        this.currentProfileScore = score;
        this.currentProfilePostingGroup = postingGroup;
    }

    /** The member's profile score now (null when unknown or not set). */
    public Integer getCurrentProfileScore() {
        return currentProfileScore;
    }

    public Integer getCurrentProfilePostingGroup() {
        return currentProfilePostingGroup;
    }

    public boolean contains(String schoolCode) {   // DC-36 helper
        return choices.stream().anyMatch(c -> c.getSchoolCode().equals(schoolCode));
    }

    /** Fills each choice's transient {@code school} from {@code byCode} (null when missing; DC-21). */
    public void resolveSchools(Map<String, School> byCode) {   // DC-36 helper
        choices.forEach(c -> c.setSchool(byCode.get(c.getSchoolCode())));
    }

    private void renumber() {
        for (int i = 0; i < choices.size(); i++) {
            choices.get(i).setRank(i + 1);
        }
    }

    /** Choices in rank order (read-only). */
    public List<SchoolChoice> getChoices() {
        return Collections.unmodifiableList(choices);
    }

    public Long getId() {
        return id;
    }

    public Integer getBasedOnScore() {
        return basedOnScore;
    }

    public void setBasedOnScore(Integer basedOnScore) {
        this.basedOnScore = basedOnScore;
    }

    public Integer getPostingGroup() {
        return postingGroup;
    }

    public void setPostingGroup(Integer postingGroup) {
        this.postingGroup = postingGroup;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "ChoicePlan{id=" + id + ", choices=" + choices + "}";
    }
}

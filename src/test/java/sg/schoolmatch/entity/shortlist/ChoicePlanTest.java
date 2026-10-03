package sg.schoolmatch.entity.shortlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.support.TestSchools;

/** Unit tests for «entity» ChoicePlan: up to 6 ranked choices, ranks always 1..n (FR-PLAN-01, proposed id). */
class ChoicePlanTest {

    private final School a = TestSchools.school("school-a").build();
    private final School b = TestSchools.school("school-b").build();
    private final School c = TestSchools.school("school-c").build();
    private final School d = TestSchools.school("school-d").build();

    private final ChoicePlan plan = new ChoicePlan(12, 3);

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlan-01: adding at a taken rank moves later choices down; ranks stay 1..n")
    void insertAtRank() {
        plan.addChoice(a, 1);
        plan.addChoice(b, 2);
        plan.addChoice(c, 1);

        assertThat(codes()).containsExactly("school-c", "school-a", "school-b");
        assertThat(ranks()).containsExactly(1, 2, 3);
        assertThat(plan.getChoices().getFirst().getSchool()).isSameAs(c);
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlan-02: a rank past the end of the list appends the choice")
    void rankPastEndAppends() {
        plan.addChoice(a, 5);
        plan.addChoice(b, 6);

        assertThat(codes()).containsExactly("school-a", "school-b");
        assertThat(ranks()).containsExactly(1, 2);
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlan-03: adding a school that is already in the plan is rejected")
    void rejectsDuplicateSchool() {
        plan.addChoice(a, 1);

        assertThatIllegalArgumentException().isThrownBy(() -> plan.addChoice(a, 2));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> plan.addChoice(TestSchools.named("school-a", "Same code, other object"), 1));
        assertThat(codes()).containsExactly("school-a");
    }

    @ParameterizedTest(name = "rank {0} → accepted: {1}")
    @CsvSource({"0, false", "1, true", "6, true", "7, false", "-1, false"})
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlan-04: rank boundaries — 1 and 6 are accepted, 0 and 7 are rejected")
    void rankBoundaries(int rank, boolean accepted) {
        if (accepted) {
            plan.addChoice(a, rank);
            assertThat(codes()).containsExactly("school-a");
        } else {
            assertThatIllegalArgumentException().isThrownBy(() -> plan.addChoice(a, rank));
            assertThat(plan.getChoices()).isEmpty();
        }
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlan-05: a 7th choice is rejected")
    void rejectsSeventhChoice() {
        for (int i = 1; i <= ChoicePlan.MAX_CHOICES; i++) {
            plan.addChoice(TestSchools.school("school-" + i).build(), i);
        }
        School seventh = TestSchools.school("school-7").build();

        assertThatIllegalArgumentException().isThrownBy(() -> plan.addChoice(seventh, 6));
        assertThat(plan.getChoices()).hasSize(6);
        assertThat(plan.contains("school-7")).isFalse();
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlan-06: reorder moves a choice to another rank and renumbers 1..n")
    void reorder() {
        addInOrder(a, b, c, d);

        plan.reorder(4, 1);
        assertThat(codes()).containsExactly("school-d", "school-a", "school-b", "school-c");

        plan.reorder(1, 3);
        assertThat(codes()).containsExactly("school-a", "school-b", "school-d", "school-c");
        assertThat(ranks()).containsExactly(1, 2, 3, 4);
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlan-07: reorder with a rank outside 1..n is rejected and changes nothing")
    void reorderOutOfRange() {
        addInOrder(a, b, c);

        assertThatIllegalArgumentException().isThrownBy(() -> plan.reorder(0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> plan.reorder(1, 4));
        assertThat(codes()).containsExactly("school-a", "school-b", "school-c");
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlan-08: removeChoice closes the gap; an unknown code changes nothing")
    void removeChoice() {
        addInOrder(a, b, c);

        plan.removeChoice("school-b");
        plan.removeChoice("not-in-plan");

        assertThat(codes()).containsExactly("school-a", "school-c");
        assertThat(ranks()).containsExactly(1, 2);
    }

    // ---- Risk warnings (FR-PLAN-02, docs/recommendation-scoring.md §4); plan score 12, PG3 --------------------

    /** PG3 U = 20: 12 is SAFE. */
    private final School safe = TestSchools.school("safe-school").range(2025, 3, 15, 20).build();
    /** PG3 U = 12: 12 is MATCH. */
    private final School match = TestSchools.school("match-school").range(2025, 3, 9, 12).build();
    /** PG3 U = 10: 12 is REACH. */
    private final School reach = TestSchools.school("reach-school").range(2025, 3, 6, 10).build();
    /** No PG3 range. */
    private final School noRange = TestSchools.school("no-range-school").range(2025, 1, 6, 10).build();

    @Test
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlan-09: an empty plan only says how many choices are missing")
    void warnings_emptyPlan() {
        assertThat(plan.getRiskWarnings()).containsExactly(
                "You have 0 of 6 choices. Fill all 6 to lower the risk of being posted to a school you did not choose.");
    }

    @Test
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlan-10: six choices with no SAFE one give the \"no SAFE choice\" warning only")
    void warnings_noSafeChoice() {
        addInOrder(match, reach, sixth("m2", 12), sixth("m3", 13), sixth("m4", 13), sixth("m5", 12));

        assertThat(plan.getRiskWarnings()).containsExactly(ChoicePlan.NO_SAFE_WARNING);
    }

    @Test
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlan-11: 4 REACH choices give the \"more than 3 REACH\" warning; exactly 3 do not")
    void warnings_moreThanThreeReach() {
        addInOrder(safe, reach, sixth("r2", 10), sixth("r3", 11), match, sixth("m2", 13));
        assertThat(plan.getRiskWarnings()).isEmpty();                    // 3 REACH: the boundary, no warning

        plan.removeChoice("match-school");
        plan.addChoice(sixth("r4", 9), 6);
        assertThat(plan.getRiskWarnings()).containsExactly("More than 3 of your choices are REACH.");
    }

    @Test
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlan-12: fewer than 6 choices are counted in the warning")
    void warnings_fewerThanSix() {
        addInOrder(safe, match);

        assertThat(plan.getRiskWarnings()).containsExactly(
                "You have 2 of 6 choices. Fill all 6 to lower the risk of being posted to a school you did not choose.");
    }

    @Test
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlan-13: a choice without range data (or no longer in the dataset) is named in a warning")
    void warnings_choiceWithoutRange() {
        addInOrder(safe, noRange, sixth("x2", 12), sixth("x3", 12), sixth("x4", 12), sixth("gone-school", 12));
        plan.resolveSchools(TestSchools.byCode(plan.getChoices().stream()
                .map(SchoolChoice::getSchool).filter(s -> !s.getSchoolCode().equals("gone-school"))
                .toArray(School[]::new)));

        assertThat(plan.getRiskWarnings()).containsExactly(
                "No Range School: no PSLE range data, so no SAFE/MATCH/REACH label.",
                "gone-school: this school is no longer in the dataset, so it has no SAFE/MATCH/REACH label.");
        assertThat(plan.getAdmissionChance(plan.getChoices().get(1))).isNull();
    }

    @Test
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlan-14: a plan without a PSLE score gives no SAFE/REACH warnings and no labels")
    void warnings_noScore() {
        ChoicePlan noScore = new ChoicePlan(null, null);
        noScore.addChoice(reach, 1);

        assertThat(noScore.getAdmissionChance(noScore.getChoices().getFirst())).isNull();
        assertThat(noScore.getRiskWarnings()).containsExactly(
                "You have 1 of 6 choices. Fill all 6 to lower the risk of being posted to a school you did not choose.");
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlan-15: getAdmissionChance uses the plan's score and posting group")
    void admissionChance_usesPlanScore() {
        addInOrder(safe, match, reach);

        assertThat(plan.getChoices()).extracting(plan::getAdmissionChance)
                .containsExactly(AdmissionChance.SAFE, AdmissionChance.MATCH, AdmissionChance.REACH);
    }

    @ParameterizedTest(name = "plan 12/PG3, profile {0}/PG{1} → outdated: {2}")
    @CsvSource(nullValues = "null", value = {
            "12, 3, false",
            "12, null, false",      // a profile without a posting group counts as PG3
            "11, 3, true",
            "12, 2, true",
            "null, null, true",     // the profile no longer has a score
            "null, 3, true"})
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlan-16: isOutdated compares the plan's score and PG with the member's profile")
    void isOutdated(Integer profileScore, Integer profilePostingGroup, boolean outdated) {
        plan.setCurrentProfile(profileScore, profilePostingGroup);

        assertThat(plan.isOutdated()).isEqualTo(outdated);
    }

    @Test
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlan-17: a plan without a score is never outdated (the control copies the profile score in)")
    void isOutdated_planWithoutScore() {
        ChoicePlan noScore = new ChoicePlan(null, null);
        noScore.setCurrentProfile(12, 3);

        assertThat(noScore.isOutdated()).isFalse();
        assertThat(plan.isOutdated()).isFalse();        // profile values not filled in: nothing to compare
    }

    @Test
    @Tag("FR-PLAN-02")
    @Tag("DC-74")
    @DisplayName("TC-ChoicePlan-18: without PSLE data the range-based warnings become one note; the count warning stays")
    void warnings_noPsleData() {
        ChoicePlan noRanges = new ChoicePlan(12, 3);
        noRanges.addChoice(TestSchools.school("no-range-a").name("No Range A").build(), 1);
        noRanges.addChoice(TestSchools.school("no-range-b").name("No Range B").build(), 2);

        assertThat(noRanges.getRiskWarnings(false)).containsExactly(
                ChoicePlan.NO_PSLE_DATA_WARNING,
                "You have 2 of 6 choices. Fill all 6 to lower the risk of being posted to a school you did not choose.");
        assertThat(noRanges.getChoices()).extracting(noRanges::getAdmissionChance).containsOnlyNulls();
        // with PSLE data (the default) the same plan gets the usual range warnings
        assertThat(noRanges.getRiskWarnings()).contains(ChoicePlan.NO_SAFE_WARNING,
                "No Range A: no PSLE range data, so no SAFE/MATCH/REACH label.");
    }

    @Test
    @Tag("FR-PLAN-02")
    @Tag("DC-74")
    @DisplayName("TC-ChoicePlan-19: without PSLE data a full plan has only the note, and a removed school is still named")
    void warnings_noPsleData_fullPlanAndRemovedSchool() {
        addInOrder(safe, match, reach, sixth("x4", 12), sixth("x5", 12), sixth("gone-school", 12));
        plan.resolveSchools(TestSchools.byCode(plan.getChoices().stream()
                .map(SchoolChoice::getSchool).filter(s -> !s.getSchoolCode().equals("gone-school"))
                .toArray(School[]::new)));

        assertThat(plan.getRiskWarnings(false)).containsExactly(ChoicePlan.NO_PSLE_DATA_WARNING,
                "gone-school: this school is no longer in the dataset.");
        assertThat(plan.getRiskWarnings(true)).isEqualTo(plan.getRiskWarnings());
    }

    /** A school with a PG3 range whose upper score is {@code upper}. */
    private static School sixth(String code, int upper) {
        return TestSchools.school(code).range(2025, 3, upper - 3, upper).build();
    }

    private void addInOrder(School... schools) {
        for (int i = 0; i < schools.length; i++) {
            plan.addChoice(schools[i], i + 1);
        }
    }

    private List<String> codes() {
        return plan.getChoices().stream().map(SchoolChoice::getSchoolCode).toList();
    }

    private List<Integer> ranks() {
        return plan.getChoices().stream().map(SchoolChoice::getRank).toList();
    }
}

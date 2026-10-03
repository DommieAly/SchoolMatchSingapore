package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.shortlist.AdmissionChance;
import sg.schoolmatch.entity.shortlist.ChoicePlan;
import sg.schoolmatch.entity.shortlist.SchoolChoice;
import sg.schoolmatch.entity.shortlist.Shortlist;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotAuthenticatedException;
import sg.schoolmatch.persistence.ShortlistRepository;
import sg.schoolmatch.support.FixedClock;
import sg.schoolmatch.support.TestSchools;

/**
 * Unit test of the control class ChoicePlanController (Mockito, no Spring): use case Plan School Choices
 * (FR-PLAN-01, FR-PLAN-02, DC-06, DC-20). The member's shortlist comes from a mocked ShortlistController
 * and the score from a mocked ProfileController.
 */
@ExtendWith(MockitoExtension.class)
class ChoicePlanControllerTest {

    private static final String SESSION = "session-alice";

    @Mock
    private ShortlistController shortlistController;

    @Mock
    private ProfileController profileController;

    @Mock
    private SchoolDataController schoolDataController;

    @Mock
    private ShortlistRepository shortlistRepository;

    private final FixedClock clock = FixedClock.atDefault();

    private ChoicePlanController choicePlanController;

    private final Account alice = new Account("alice", "alice@example.com", "hash", FixedClock.DEFAULT_INSTANT);
    private final UserProfile profile = new UserProfile(alice);

    /** PG3 U = 9 / 13 / 15 / 25: with score 12 they are REACH / MATCH / SAFE / SAFE. */
    private final School catholic = TestSchools.school("catholic-high-school").range(2025, 3, 6, 9).build();
    private final School huaYi = TestSchools.school("hua-yi-secondary-school").range(2025, 3, 10, 13).build();
    private final School jurongWest = TestSchools.school("jurong-west-secondary-school").range(2025, 3, 12, 15).build();
    private final School tampines = TestSchools.school("tampines-secondary-school").range(2025, 3, 22, 25).build();
    /** Non-affiliated U = 17, affiliated U = 19. */
    private final School kuoChuan = TestSchools.school("kuo-chuan-presbyterian-secondary-school")
            .range(2025, 3, 14, 17).affiliatedRange(2025, 3, 14, 19)
            .affiliatedPrimarySchools("KUO CHUAN PRESBYTERIAN PRIMARY SCHOOL").build();
    private final School westwood = TestSchools.school("westwood-secondary-school").build();
    private final School peirce = TestSchools.school("peirce-secondary-school").range(2025, 3, 18, 21).build();

    private Shortlist shortlist;

    @BeforeEach
    void setUp() {
        choicePlanController = new ChoicePlanController(shortlistController, profileController, schoolDataController,
                shortlistRepository, clock);
        shortlist = new Shortlist(alice);
        for (School school : List.of(catholic, huaYi, jurongWest, tampines, kuoChuan, westwood, peirce)) {
            shortlist.addSchool(school);
        }
        profile.setPsleScore(12);
        profile.setPostingGroup(3);
        lenient().when(shortlistController.getShortlist(SESSION)).thenReturn(shortlist);
        lenient().when(profileController.getProfile(SESSION)).thenReturn(profile);
        lenient().when(schoolDataController.hasPsleData()).thenReturn(true);   // DC-74: ranges unless a test says not
        lenient().when(schoolDataController.getSchools())
                .thenReturn(List.of(catholic, huaYi, jurongWest, kuoChuan, peirce, tampines, westwood));
        // save() returns a copy without the transient school data, like Hibernate's merge
        lenient().when(shortlistRepository.save(any(Shortlist.class))).thenAnswer(call -> {
            Shortlist saved = call.getArgument(0);
            saved.resolveSchools(Map.of());
            return saved;
        });
    }

    // ---- getPlan ------------------------------------------------------------------------------------------

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-01: the first visit creates and saves an empty plan with the profile's score and PG")
    void getPlan_firstUse_createsPlanFromProfile() {
        ChoicePlan plan = choicePlanController.getPlan(SESSION);

        assertThat(plan.getChoices()).isEmpty();
        assertThat(plan.getBasedOnScore()).isEqualTo(12);
        assertThat(plan.getPostingGroup()).isEqualTo(3);
        assertThat(plan.getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(shortlist.getChoicePlan()).isSameAs(plan);
        verify(shortlistRepository).save(shortlist);
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-02: without a profile (or a score) the plan has no score, so no labels")
    void getPlan_noProfile_planWithoutScore() {
        when(profileController.getProfile(SESSION)).thenReturn(null);

        ChoicePlan plan = choicePlanController.getPlan(SESSION);

        assertThat(plan.getBasedOnScore()).isNull();
        assertThat(plan.getCurrentProfileScore()).isNull();
        assertThat(plan.isOutdated()).isFalse();
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-03: a profile score without a posting group plans with PG3 (the default)")
    void getPlan_scoreWithoutPostingGroup_usesPg3() {
        profile.setPostingGroup(null);

        ChoicePlan plan = choicePlanController.getPlan(SESSION);

        assertThat(plan.getBasedOnScore()).isEqualTo(12);
        assertThat(plan.getPostingGroup()).isEqualTo(ChoicePlan.DEFAULT_POSTING_GROUP);
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-04: a plan made before the member had a score takes the profile's score")
    void getPlan_planWithoutScore_takesProfileScore() {
        ChoicePlan old = new ChoicePlan(null, null);
        old.addChoice(catholic, 1);
        shortlist.setChoicePlan(old);

        ChoicePlan plan = choicePlanController.getPlan(SESSION);

        assertThat(plan.getBasedOnScore()).isEqualTo(12);
        assertThat(plan.getAdmissionChance(plan.getChoices().getFirst())).isEqualTo(AdmissionChance.REACH);
        verify(shortlistRepository).save(shortlist);
    }

    @Test
    @Tag("FR-PLAN-01")
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlanController-05: an existing plan keeps its score; the profile's new score only makes it outdated")
    void getPlan_existingPlan_keepsScore() {
        shortlist.setChoicePlan(planWith(10, 3, huaYi, catholic));
        profile.setPsleScore(14);

        ChoicePlan plan = choicePlanController.getPlan(SESSION);

        assertThat(plan.getBasedOnScore()).isEqualTo(10);
        assertThat(plan.getCurrentProfileScore()).isEqualTo(14);
        assertThat(plan.isOutdated()).isTrue();
        assertThat(plan.getChoices()).extracting(SchoolChoice::getSchool).containsExactly(huaYi, catholic);
        verify(shortlistRepository, never()).save(any());
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-06: a choice is affiliated when the profile's primary school is affiliated (any case)")
    void getPlan_fillsAffiliation() {
        shortlist.setChoicePlan(planWith(18, 3, kuoChuan, peirce));
        profile.setPsleScore(18);
        profile.setPrimarySchool("  Kuo Chuan Presbyterian  Primary School ");

        ChoicePlan plan = choicePlanController.getPlan(SESSION);

        assertThat(plan.getChoices()).extracting(SchoolChoice::isAffiliated).containsExactly(true, false);
        assertThat(plan.getChoices()).extracting(plan::getAdmissionChance)
                .containsExactly(AdmissionChance.MATCH, AdmissionChance.SAFE);    // 18 vs U = 19 (affiliated), 21

        profile.setPrimarySchool("Other Primary School");
        assertThat(choicePlanController.getPlan(SESSION).getAdmissionChance(plan.getChoices().getFirst()))
                .isEqualTo(AdmissionChance.REACH);                                 // 18 vs U = 17
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-06b: the affiliation check uses School.hasAffiliatedPrimarySchool, so punctuation does not matter")
    void getPlan_affiliationIgnoresPunctuation() {
        shortlist.setChoicePlan(planWith(18, 3, kuoChuan, peirce));
        profile.setPsleScore(18);
        profile.setPrimarySchool("Kuo-Chuan Presbyterian Primary School.");

        ChoicePlan plan = choicePlanController.getPlan(SESSION);

        assertThat(plan.getChoices()).extracting(SchoolChoice::isAffiliated).containsExactly(true, false);
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-ChoicePlanController-07: an ended session cannot read or change a plan")
    void notAuthenticated() {
        when(shortlistController.getShortlist("expired")).thenThrow(new NotAuthenticatedException());

        assertThatThrownBy(() -> choicePlanController.getPlan("expired")).isInstanceOf(NotAuthenticatedException.class);
        assertThatThrownBy(() -> choicePlanController.addChoice("expired", "catholic-high-school", 1))
                .isInstanceOf(NotAuthenticatedException.class);
        verifyNoInteractions(shortlistRepository);
    }

    // ---- addChoice ----------------------------------------------------------------------------------------

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-08: addChoice inserts the school at the rank, moves later ones down, and saves")
    void addChoice_insertsAtRank() {
        ChoicePlan plan = planWith(12, 3, catholic, huaYi);
        shortlist.setChoicePlan(plan);

        choicePlanController.addChoice(SESSION, "tampines-secondary-school", 1);

        assertThat(plan.getChoices()).extracting(SchoolChoice::getSchoolCode)
                .containsExactly("tampines-secondary-school", "catholic-high-school", "hua-yi-secondary-school");
        assertThat(plan.getUpdatedAt()).isEqualTo(clock.instant());
        verify(shortlistRepository).save(shortlist);
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-09: the first addChoice creates the plan with the profile's score")
    void addChoice_noPlanYet_createsPlan() {
        choicePlanController.addChoice(SESSION, "catholic-high-school", 1);

        ChoicePlan plan = shortlist.getChoicePlan();
        assertThat(plan.getBasedOnScore()).isEqualTo(12);
        assertThat(plan.getChoices()).extracting(SchoolChoice::getSchoolCode).containsExactly("catholic-high-school");
        verify(shortlistRepository).save(shortlist);
    }

    @Test
    @Tag("FR-PLAN-01")
    @Tag("NFR-SEC-05")
    @DisplayName("TC-ChoicePlanController-10: only schools on the member's own shortlist can be added (DC-06)")
    void addChoice_notInShortlist_rejected() {
        assertInvalid(() -> choicePlanController.addChoice(SESSION, "raffles-institution", 1),
                "code", ChoicePlanController.NOT_SHORTLISTED_MESSAGE);
        verify(shortlistRepository, never()).save(any());
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-11: a school that is already in the plan is rejected (DC-06)")
    void addChoice_alreadyInPlan_rejected() {
        shortlist.setChoicePlan(planWith(12, 3, catholic));

        assertInvalid(() -> choicePlanController.addChoice(SESSION, "catholic-high-school", 2),
                "code", "Catholic High School is already in your plan.");
        verify(shortlistRepository, never()).save(any());
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-12: a 7th choice is rejected")
    void addChoice_seventh_rejected() {
        shortlist.setChoicePlan(planWith(12, 3, catholic, huaYi, jurongWest, tampines, kuoChuan, westwood));

        assertInvalid(() -> choicePlanController.addChoice(SESSION, "peirce-secondary-school", 6),
                "code", ChoicePlanController.PLAN_FULL_MESSAGE);
    }

    @ParameterizedTest(name = "2 choices, rank {0} → accepted: {1}")
    @CsvSource({"0, false", "1, true", "2, true", "3, true", "4, false", "-1, false"})
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-13: with n choices the rank must be 1..n+1")
    void addChoice_rankBoundaries(int rank, boolean accepted) {
        ChoicePlan plan = planWith(12, 3, catholic, huaYi);
        shortlist.setChoicePlan(plan);

        if (accepted) {
            choicePlanController.addChoice(SESSION, "tampines-secondary-school", rank);
            assertThat(plan.getChoices().get(rank - 1).getSchoolCode()).isEqualTo("tampines-secondary-school");
        } else {
            assertInvalid(() -> choicePlanController.addChoice(SESSION, "tampines-secondary-school", rank),
                    "rank", "Choose a position from 1 to 3.");
            assertThat(plan.getChoices()).hasSize(2);
        }
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-14: a shortlisted school that left the dataset cannot be added")
    void addChoice_schoolLeftDataset_rejected() {
        shortlist.resolveSchools(Map.of("catholic-high-school", catholic));   // the others are gone

        assertInvalid(() -> choicePlanController.addChoice(SESSION, "hua-yi-secondary-school", 1),
                "code", ChoicePlanController.NOT_IN_DATASET_MESSAGE);
    }

    // ---- reorderChoices / removeChoice --------------------------------------------------------------------

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-15: reorderChoices moves a choice and saves; ranks outside 1..n are rejected")
    void reorderChoices() {
        ChoicePlan plan = planWith(12, 3, catholic, huaYi, jurongWest);
        shortlist.setChoicePlan(plan);

        choicePlanController.reorderChoices(SESSION, 3, 1);

        assertThat(plan.getChoices()).extracting(SchoolChoice::getSchoolCode).containsExactly(
                "jurong-west-secondary-school", "catholic-high-school", "hua-yi-secondary-school");
        verify(shortlistRepository).save(shortlist);
        assertInvalid(() -> choicePlanController.reorderChoices(SESSION, 1, 4), "rank", "Choose a position from 1 to 3.");
        assertInvalid(() -> choicePlanController.reorderChoices(SESSION, 0, 1), "rank", "Choose a position from 1 to 3.");
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-16: reorderChoices on a plan without choices is rejected")
    void reorderChoices_emptyPlan() {
        assertInvalid(() -> choicePlanController.reorderChoices(SESSION, 1, 2), "rank", ChoicePlanController.NO_CHOICES_MESSAGE);
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-17: removeChoice removes and saves; a school not in the plan changes nothing")
    void removeChoice() {
        ChoicePlan plan = planWith(12, 3, catholic, huaYi);
        shortlist.setChoicePlan(plan);

        choicePlanController.removeChoice(SESSION, "catholic-high-school");
        choicePlanController.removeChoice(SESSION, "not-in-plan");

        assertThat(plan.getChoices()).extracting(SchoolChoice::getSchoolCode, SchoolChoice::getRank)
                .containsExactly(tuple("hua-yi-secondary-school", 1));
        assertThat(shortlist.contains("catholic-high-school")).isTrue();    // still shortlisted
        verify(shortlistRepository).save(shortlist);                         // once: the no-op did not save
    }

    // ---- assessPlan / useProfileScore ---------------------------------------------------------------------

    @Test
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlanController-18: assessPlan gives the plan's warnings plus \"score changed\" when the profile differs")
    void assessPlan_addsScoreChangedWarning() {
        shortlist.setChoicePlan(planWith(10, 3, catholic, huaYi, jurongWest, tampines, kuoChuan, peirce));
        profile.setPsleScore(14);

        List<String> warnings = choicePlanController.assessPlan(choicePlanController.getPlan(SESSION));

        assertThat(warnings).containsExactly("This plan was made for a score of 10, but your profile now says 14.");
    }

    @Test
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlanController-19: a changed posting group or a removed score is reported too")
    void assessPlan_postingGroupChangedOrScoreRemoved() {
        shortlist.setChoicePlan(planWith(12, 3, tampines, jurongWest, peirce, kuoChuan, huaYi, catholic));
        profile.setPostingGroup(2);
        assertThat(choicePlanController.assessPlan(choicePlanController.getPlan(SESSION))).containsExactly(
                "This plan was made for posting group 3, but your profile now says posting group 2.");

        profile.setPsleScore(null);
        assertThat(choicePlanController.assessPlan(choicePlanController.getPlan(SESSION))).containsExactly(
                "This plan was made for a score of 12, but your profile has no PSLE score now.");
    }

    @Test
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ChoicePlanController-20: a plan that matches the profile and has a SAFE choice and 6 choices has no warnings")
    void assessPlan_noWarnings() {
        shortlist.setChoicePlan(planWith(12, 3, tampines, jurongWest, peirce, kuoChuan, huaYi, catholic));

        assertThat(choicePlanController.assessPlan(choicePlanController.getPlan(SESSION))).isEmpty();
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-21: useProfileScore re-bases the plan on the profile's score and PG")
    void useProfileScore() {
        ChoicePlan plan = planWith(10, 2, catholic);
        shortlist.setChoicePlan(plan);

        choicePlanController.useProfileScore(SESSION);

        assertThat(plan.getBasedOnScore()).isEqualTo(12);
        assertThat(plan.getPostingGroup()).isEqualTo(3);
        verify(shortlistRepository).save(shortlist);
    }

    @Test
    @Tag("FR-PLAN-01")
    @DisplayName("TC-ChoicePlanController-22: useProfileScore without a score in the profile is rejected")
    void useProfileScore_noScore() {
        shortlist.setChoicePlan(planWith(10, 3, catholic));
        profile.setPsleScore(null);

        assertInvalid(() -> choicePlanController.useProfileScore(SESSION), "score", ChoicePlanController.NO_SCORE_MESSAGE);
        verify(shortlistRepository, never()).save(any());
    }

    @Test
    @Tag("FR-PLAN-02")
    @Tag("DC-74")
    @DisplayName("TC-ChoicePlanController-23: without PSLE data assessPlan gives one note instead of the range warnings")
    void assessPlan_noPsleData() {
        when(schoolDataController.hasPsleData()).thenReturn(false);
        shortlist.setChoicePlan(planWith(12, 3, westwood, catholic));

        ChoicePlan plan = choicePlanController.getPlan(SESSION);
        List<String> warnings = choicePlanController.assessPlan(plan);

        assertThat(warnings).containsExactly(ChoicePlan.NO_PSLE_DATA_WARNING,
                "You have 2 of 6 choices. Fill all 6 to lower the risk of being posted to a school you did not choose.");
        assertThat(warnings).doesNotContain(ChoicePlan.NO_SAFE_WARNING);
    }

    @Test
    @Tag("FR-PLAN-01")
    @Tag("DC-74")
    @DisplayName("TC-ChoicePlanController-24: without PSLE data choices can still be added, moved and removed")
    void editPlan_noPsleData() {
        lenient().when(schoolDataController.hasPsleData()).thenReturn(false);   // editing does not ask
        shortlist.setChoicePlan(planWith(12, 3, westwood));

        choicePlanController.addChoice(SESSION, "catholic-high-school", 1);
        choicePlanController.reorderChoices(SESSION, 1, 2);
        choicePlanController.removeChoice(SESSION, "westwood-secondary-school");

        assertThat(shortlist.getChoicePlan().getChoices()).extracting(SchoolChoice::getSchoolCode)
                .containsExactly("catholic-high-school");
    }

    private static ChoicePlan planWith(Integer score, Integer postingGroup, School... schools) {
        ChoicePlan plan = new ChoicePlan(score, postingGroup);
        for (int i = 0; i < schools.length; i++) {
            plan.addChoice(schools[i], i + 1);
        }
        return plan;
    }

    private static void assertInvalid(Runnable call, String field, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InvalidInputException.class,
                e -> assertThat(e.getFieldErrors()).isEqualTo(Map.of(field, message)));
    }
}

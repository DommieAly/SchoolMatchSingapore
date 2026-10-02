package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.shortlist.ChoicePlan;
import sg.schoolmatch.entity.shortlist.Shortlist;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotAuthenticatedException;
import sg.schoolmatch.error.NotFoundException;
import sg.schoolmatch.persistence.ShortlistRepository;
import sg.schoolmatch.support.FixedClock;
import sg.schoolmatch.support.TestSchools;

/**
 * Unit test of the control class ShortlistController (Mockito, no Spring): Add School to Shortlist,
 * Remove School from Shortlist, View Shortlisted Schools, Compare Schools
 * (FR-SHORTLIST-01..07, FR-COMPARE-01, NFR-SEC-05). The compare-selection rows are in
 * {@code testcases/TC-SHORTLIST.csv}.
 */
@ExtendWith(MockitoExtension.class)
class ShortlistControllerTest {

    private static final String ALICE_SESSION = "session-alice";

    @Mock
    private AuthController authController;

    @Mock
    private SchoolDataController schoolDataController;

    @Mock
    private ShortlistRepository shortlistRepository;

    private final FixedClock clock = FixedClock.atDefault();

    private ShortlistController shortlistController;

    private final Account alice = new Account("alice", "alice@example.com", "hash", FixedClock.DEFAULT_INSTANT);
    private final Account bob = new Account("bob", "bob@example.com", "hash", FixedClock.DEFAULT_INSTANT);

    private final School catholic = TestSchools.named("catholic-high-school", "CATHOLIC HIGH SCHOOL");
    private final School raffles = TestSchools.named("raffles-institution", "RAFFLES INSTITUTION");
    private final School tampines = TestSchools.named("tampines-secondary-school", "TAMPINES SECONDARY SCHOOL");
    private final School anglican = TestSchools.named("anglican-high-school", "ANGLICAN HIGH SCHOOL");
    private final School westwood = TestSchools.named("westwood-secondary-school", "WESTWOOD SECONDARY SCHOOL");
    private final School bishanPark = TestSchools.named("bishan-park-secondary-school", "BISHAN PARK SECONDARY SCHOOL");

    @BeforeEach
    void setUp() {
        shortlistController = new ShortlistController(authController, schoolDataController, shortlistRepository, clock);
        lenient().when(authController.getAccount(ALICE_SESSION)).thenReturn(alice);
        lenient().when(schoolDataController.getSchools())
                .thenReturn(List.of(anglican, bishanPark, catholic, raffles, tampines, westwood));
        lenient().when(shortlistRepository.save(any(Shortlist.class))).thenAnswer(call -> call.getArgument(0));
    }

    // ---- Add School to Shortlist (UC #11) -----------------------------------------------------------------

    @Test
    @Tag("FR-SHORTLIST-01")
    @Tag("FR-SHORTLIST-03")
    @DisplayName("TC-ShortlistController-01: addSchool adds the school to the member's shortlist and saves it")
    void addSchool_addsAndSaves() {
        Shortlist saved = shortlistOf(alice, raffles);
        when(schoolDataController.getSchool("catholic-high-school")).thenReturn(catholic);

        boolean added = shortlistController.addSchool(ALICE_SESSION, "catholic-high-school");

        assertThat(added).isTrue();
        assertThat(saved.getSchoolCodes()).containsExactly("raffles-institution", "catholic-high-school");
        assertThat(saved.getUpdatedAt()).isEqualTo(clock.instant());
        verify(shortlistRepository).save(saved);
    }

    @Test
    @Tag("FR-SHORTLIST-02")
    @DisplayName("TC-ShortlistController-02: adding a school that is already shortlisted returns false and saves nothing (AF-1)")
    void addSchool_duplicate_returnsFalse() {
        Shortlist saved = shortlistOf(alice, catholic);
        when(schoolDataController.getSchool("catholic-high-school")).thenReturn(catholic);

        assertThat(shortlistController.addSchool(ALICE_SESSION, "catholic-high-school")).isFalse();

        assertThat(saved.getSchoolCodes()).containsExactly("catholic-high-school");
        verify(shortlistRepository, never()).save(any());
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-ShortlistController-03: an unknown school code is NotFound and nothing is saved")
    void addSchool_unknownSchool_notFound() {
        when(schoolDataController.getSchool("no-such-school")).thenThrow(new NotFoundException("No school"));

        assertThatThrownBy(() -> shortlistController.addSchool(ALICE_SESSION, "no-such-school"))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(shortlistRepository);
    }

    @Test
    @Tag("FR-SHORTLIST-03")
    @Tag("NFR-SEC-05")
    @DisplayName("TC-ShortlistController-04: an expired or unknown session cannot touch any shortlist")
    void addSchool_notAuthenticated() {
        when(authController.getAccount("expired")).thenThrow(new NotAuthenticatedException());

        assertThatThrownBy(() -> shortlistController.addSchool("expired", "catholic-high-school"))
                .isInstanceOf(NotAuthenticatedException.class);
        assertThatThrownBy(() -> shortlistController.removeSchool("expired", "catholic-high-school"))
                .isInstanceOf(NotAuthenticatedException.class);
        assertThatThrownBy(() -> shortlistController.getShortlist("expired"))
                .isInstanceOf(NotAuthenticatedException.class);
        verifyNoInteractions(shortlistRepository);
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @Tag("FR-DATA-05")
    @DisplayName("TC-ShortlistController-05: with no shortlist row yet, the first add creates one for the account")
    void addSchool_noShortlistYet_createsOne() {
        when(shortlistRepository.findById(alice.getAccountId())).thenReturn(Optional.empty());
        when(schoolDataController.getSchool("catholic-high-school")).thenReturn(catholic);

        assertThat(shortlistController.addSchool(ALICE_SESSION, "catholic-high-school")).isTrue();

        ArgumentCaptor<Shortlist> captor = ArgumentCaptor.forClass(Shortlist.class);
        verify(shortlistRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(alice.getAccountId());
        assertThat(captor.getValue().getSchoolCodes()).containsExactly("catholic-high-school");
    }

    @Test
    @Tag("FR-SHORTLIST-02")
    @DisplayName("TC-ShortlistController-06: a double click that hits the unique key counts as \"already shortlisted\"")
    void addSchool_uniqueKeyViolation_returnsFalse() {
        shortlistOf(alice);
        when(schoolDataController.getSchool("catholic-high-school")).thenReturn(catholic);
        when(shortlistRepository.save(any(Shortlist.class)))
                .thenThrow(new DataIntegrityViolationException("unique (account_id, school_code)"));

        assertThat(shortlistController.addSchool(ALICE_SESSION, "catholic-high-school")).isFalse();
    }

    // ---- Remove School from Shortlist (UC #12) ------------------------------------------------------------

    @Test
    @Tag("FR-SHORTLIST-06")
    @DisplayName("TC-ShortlistController-07: removeSchool removes the school, also from the plan, and saves")
    void removeSchool_removesFromShortlistAndPlan() {
        Shortlist saved = shortlistOf(alice, catholic, raffles);
        ChoicePlan plan = new ChoicePlan(12, 3);
        plan.addChoice(catholic, 1);
        plan.addChoice(raffles, 2);
        saved.setChoicePlan(plan);

        shortlistController.removeSchool(ALICE_SESSION, "catholic-high-school");

        assertThat(saved.getSchoolCodes()).containsExactly("raffles-institution");
        assertThat(plan.contains("catholic-high-school")).isFalse();
        assertThat(saved.getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(plan.getUpdatedAt()).isEqualTo(clock.instant());
        verify(shortlistRepository).save(saved);
    }

    @Test
    @Tag("FR-SHORTLIST-06")
    @DisplayName("TC-ShortlistController-08: removing a school that is not shortlisted (or with no shortlist) changes nothing")
    void removeSchool_notShortlisted_noOp() {
        shortlistOf(alice, raffles);

        shortlistController.removeSchool(ALICE_SESSION, "catholic-high-school");

        when(authController.getAccount("session-bob")).thenReturn(bob);
        when(shortlistRepository.findById(bob.getAccountId())).thenReturn(Optional.empty());
        shortlistController.removeSchool("session-bob", "catholic-high-school");

        verify(shortlistRepository, never()).save(any());
    }

    // ---- View Shortlisted Schools (UC #13) ----------------------------------------------------------------

    @Test
    @Tag("FR-SHORTLIST-04")
    @Tag("FR-SHORTLIST-05")
    @DisplayName("TC-ShortlistController-09: getShortlistedSchools resolves the saved codes; codes that left the dataset are reported separately")
    void getShortlistedSchools_resolvesSchools() {
        Shortlist saved = new Shortlist(alice);
        saved.addSchool(tampines);
        saved.addSchool(TestSchools.named("closed-secondary-school", "CLOSED SECONDARY SCHOOL"));
        saved.addSchool(catholic);
        saved.resolveSchools(Map.of());        // as loaded from the database: nothing resolved yet
        when(shortlistRepository.findById(alice.getAccountId())).thenReturn(Optional.of(saved));

        assertThat(shortlistController.getShortlistedSchools(ALICE_SESSION)).containsExactly(tampines, catholic);
        assertThat(shortlistController.getShortlist(ALICE_SESSION).getUnresolvedSchoolCodes())
                .containsExactly("closed-secondary-school");
    }

    @Test
    @Tag("FR-SHORTLIST-07")
    @DisplayName("TC-ShortlistController-10: a member without a shortlist row sees an empty shortlist; nothing is saved")
    void getShortlist_noRow_emptyAndNotSaved() {
        when(shortlistRepository.findById(alice.getAccountId())).thenReturn(Optional.empty());

        Shortlist shortlist = shortlistController.getShortlist(ALICE_SESSION);

        assertThat(shortlist.isEmpty()).isTrue();
        assertThat(shortlist.getAccountId()).isEqualTo(alice.getAccountId());
        verify(shortlistRepository, never()).save(any());
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-ShortlistController-11: the shortlist is always looked up by the session's own account id")
    void getShortlist_usesSessionAccountOnly() {
        shortlistOf(alice, catholic);
        when(authController.getAccount("session-bob")).thenReturn(bob);
        when(shortlistRepository.findById(bob.getAccountId())).thenReturn(Optional.of(new Shortlist(bob)));

        assertThat(shortlistController.getShortlistedSchools("session-bob")).isEmpty();
        assertThat(shortlistController.getShortlistedSchools(ALICE_SESSION)).containsExactly(catholic);
        verify(shortlistRepository).findById(bob.getAccountId());
        verify(shortlistRepository).findById(alice.getAccountId());
    }

    // ---- Compare Schools (UC #14) -------------------------------------------------------------------------

    /**
     * One run per row of TC-SHORTLIST.csv. The member's shortlist holds Catholic High, Raffles, Tampines,
     * Anglican, Westwood and a school that has left the dataset; codes in the input are separated by ';'.
     */
    @ParameterizedTest(name = "{0}: {2}")
    @CsvFileSource(resources = "/testcases/TC-SHORTLIST.csv", numLinesToSkip = 1)
    @Tag("FR-COMPARE-01")
    @Tag("NFR-SEC-05")
    @DisplayName("TC-COMPARE-01-xx: which selections can be compared, from TC-SHORTLIST.csv")
    void compareSchools_followsTheTestCaseTable(String tcId, String requirement, String technique,
                                                String input, String expected) {
        Shortlist saved = shortlistOf(alice, catholic, raffles, tampines, anglican, westwood,
                TestSchools.named("closed-secondary-school", "CLOSED SECONDARY SCHOOL"));
        saved.resolveSchools(Map.of());
        Set<String> codes = input == null ? Set.of() : new LinkedHashSet<>(Arrays.asList(input.split(";")));

        if (expected.equals("error")) {
            assertThatThrownBy(() -> shortlistController.compareSchools(ALICE_SESSION, codes))
                    .isInstanceOf(InvalidInputException.class)
                    .satisfies(e -> assertThat(((InvalidInputException) e).getFieldErrors()).containsKey("codes"));
        } else {
            assertThat(shortlistController.compareSchools(ALICE_SESSION, codes))
                    .extracting(School::getSchoolCode).containsExactlyElementsOf(codes);
        }
    }

    @Test
    @Tag("FR-COMPARE-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-ShortlistController-12: compare errors use clear messages and keep the chosen order otherwise")
    void compareSchools_messagesAndOrder() {
        shortlistOf(alice, catholic, raffles, tampines);

        assertThatThrownBy(() -> shortlistController.compareSchools(ALICE_SESSION, Set.of("catholic-high-school")))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.getFieldErrors())
                        .isEqualTo(Map.of("codes", ShortlistController.COMPARE_COUNT_MESSAGE)));
        assertThatThrownBy(() -> shortlistController.compareSchools(ALICE_SESSION,
                new LinkedHashSet<>(List.of("catholic-high-school", "westwood-secondary-school"))))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.getFieldErrors())
                        .isEqualTo(Map.of("codes", ShortlistController.COMPARE_NOT_SHORTLISTED_MESSAGE)));

        assertThat(shortlistController.compareSchools(ALICE_SESSION,
                new LinkedHashSet<>(List.of("tampines-secondary-school", "catholic-high-school"))))
                .containsExactly(tampines, catholic);
    }

    /** Stubs the repository so {@code account}'s saved shortlist holds {@code schools}; returns that shortlist. */
    private Shortlist shortlistOf(Account account, School... schools) {
        Shortlist shortlist = new Shortlist(account);
        for (School school : schools) {
            shortlist.addSchool(school);
        }
        when(shortlistRepository.findById(account.getAccountId())).thenReturn(Optional.of(shortlist));
        return shortlist;
    }
}

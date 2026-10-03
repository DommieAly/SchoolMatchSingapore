package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotAuthenticatedException;
import sg.schoolmatch.persistence.UserProfileRepository;
import sg.schoolmatch.support.ExternalFailures;
import sg.schoolmatch.support.LogCapture;

/**
 * Unit test of the control class ProfileController (use case Manage Profile; FR-PROFILE-01, NFR-SEC-05, DC-27).
 * AuthController, LocationController (OneMap address search) and the repository are Mockito mocks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProfileControllerTest {

    private static final String SESSION = "session-of-alice";
    private static final Coordinate BISHAN = new Coordinate(1.3510, 103.8484);
    private static final Coordinate TAMPINES = new Coordinate(1.3543, 103.9453);

    @Mock
    private AuthController authController;

    @Mock
    private LocationController locationController;

    @Mock
    private UserProfileRepository profileRepository;

    private final Account alice = new Account("alice_01", "alice@example.com", "hash", Instant.EPOCH);
    private final Account mallory = new Account("mallory", "mallory@example.com", "hash", Instant.EPOCH);
    private ProfileController profileController;

    @BeforeEach
    void setUp() {
        profileController = new ProfileController(authController, locationController, profileRepository);
        when(authController.getAccount(SESSION)).thenReturn(alice);
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.saveAndFlush(any(UserProfile.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-ProfileController-01: before the first save the member gets an empty profile; nothing is stored")
    void getProfile_noneSaved_emptyProfile() {
        UserProfile profile = profileController.getProfile(SESSION);

        assertThat(profile.getAccount()).isEqualTo(alice);
        assertThat(profile.getPsleScore()).isNull();
        assertThat(profile.getHomeLocation()).isNull();
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-ProfileController-02: getProfile returns the saved profile of the session's account")
    void getProfile_saved() {
        UserProfile saved = new UserProfile(alice);
        saved.setPsleScore(12);
        when(profileRepository.findById(alice.getAccountId())).thenReturn(Optional.of(saved));

        assertThat(profileController.getProfile(SESSION)).isSameAs(saved);
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-ProfileController-03: an ended session cannot read or save a profile")
    void endedSession_notAuthenticated() {
        when(authController.getAccount("ended")).thenThrow(new NotAuthenticatedException());

        assertThatThrownBy(() -> profileController.getProfile("ended")).isInstanceOf(NotAuthenticatedException.class);
        assertThatThrownBy(() -> profileController.saveProfile("ended", new UserProfile(alice)))
                .isInstanceOf(NotAuthenticatedException.class);
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-ProfileController-04: the first save stores every field (create on first save)")
    void saveProfile_firstSave_storesFields() {
        oneCandidate("579767", BISHAN, "9 BISHAN STREET 22 SINGAPORE 579767");
        UserProfile edited = new UserProfile(alice);
        edited.setDisplayName("  Alice  ");
        edited.setPsleScore(12);
        edited.setPostingGroup(3);
        edited.setPrimarySchool("Ai Tong School");
        edited.setHomeAddress(" 579767 ");
        edited.setPreferredCCAs(Set.of("BADMINTON"));
        edited.setPreferredProgrammes(Set.of("Applied Learning Programme"));
        edited.setMaxCommuteMin(45);
        edited.setTravelMode(TravelMode.TRANSIT);

        profileController.saveProfile(SESSION, edited);

        UserProfile saved = savedProfile();
        assertThat(saved.getAccount()).isEqualTo(alice);
        assertThat(saved.getDisplayName()).isEqualTo("Alice");
        assertThat(saved.getPsleScore()).isEqualTo(12);
        assertThat(saved.getPostingGroup()).isEqualTo(3);
        assertThat(saved.getPrimarySchool()).isEqualTo("Ai Tong School");
        assertThat(saved.getHomeAddress()).isEqualTo("579767");
        assertThat(saved.getHomeLocation()).isEqualTo(BISHAN);
        assertThat(saved.getPreferredCCAs()).containsExactly("BADMINTON");
        assertThat(saved.getPreferredProgrammes()).containsExactly("Applied Learning Programme");
        assertThat(saved.getMaxCommuteMin()).isEqualTo(45);
        assertThat(saved.getTravelMode()).isEqualTo(TravelMode.TRANSIT);
        assertThat(saved.isComplete()).isTrue();
    }

    @ParameterizedTest(name = "{0}: {1}={2} → {3}")
    @CsvSource(delimiter = '|', textBlock = """
            TC-PROFILE-01-01 | displayName   | {x*50}  | ok
            TC-PROFILE-01-02 | displayName   | {x*51}  | error
            TC-PROFILE-01-03 | psleScore     | 3       | error
            TC-PROFILE-01-04 | psleScore     | 4       | ok
            TC-PROFILE-01-05 | psleScore     | 32      | ok
            TC-PROFILE-01-06 | psleScore     | 33      | error
            TC-PROFILE-01-07 | postingGroup  | 0       | error
            TC-PROFILE-01-08 | postingGroup  | 1       | ok
            TC-PROFILE-01-09 | postingGroup  | 3       | ok
            TC-PROFILE-01-10 | postingGroup  | 4       | error
            TC-PROFILE-01-11 | primarySchool | {x*100} | ok
            TC-PROFILE-01-12 | primarySchool | {x*101} | error
            TC-PROFILE-01-13 | maxCommuteMin | 15      | ok
            TC-PROFILE-01-14 | maxCommuteMin | 20      | error
            TC-PROFILE-01-15 | maxCommuteMin | 60      | ok
            TC-PROFILE-01-16 | maxCommuteMin | 90      | error
            TC-PROFILE-01-17 | homeAddress   | {x*201} | error
            """)
    @Tag("FR-PROFILE-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-PROFILE-01-xx: field limits; an invalid value saves nothing (AF-2)")
    void saveProfile_fieldLimits(String tcId, String field, String value, String expected) {
        UserProfile edited = new UserProfile(alice);
        String text = AccountTestCases.expand(value);
        switch (field) {
            case "displayName" -> edited.setDisplayName(text);
            case "psleScore" -> edited.setPsleScore(Integer.valueOf(text));
            case "postingGroup" -> edited.setPostingGroup(Integer.valueOf(text));
            case "primarySchool" -> edited.setPrimarySchool(text);
            case "maxCommuteMin" -> edited.setMaxCommuteMin(Integer.valueOf(text));
            case "homeAddress" -> edited.setHomeAddress(text);
            default -> throw new IllegalArgumentException(field);
        }

        if (expected.equals("ok")) {
            profileController.saveProfile(SESSION, edited);
            verify(profileRepository).saveAndFlush(any(UserProfile.class));
        } else {
            InvalidInputException e = catchThrowableOfType(InvalidInputException.class,
                    () -> profileController.saveProfile(SESSION, edited));
            assertThat(e).isNotNull();
            assertThat(e.getFieldErrors()).containsOnlyKeys(field);
            verify(profileRepository, never()).saveAndFlush(any());
        }
    }

    @Test
    @Tag("FR-PROFILE-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-PROFILE-01-18: every invalid field is reported at once, with the shared messages")
    void saveProfile_allErrorsAtOnce() {
        UserProfile edited = new UserProfile(alice);
        edited.setDisplayName("x".repeat(51));
        edited.setPsleScore(33);
        edited.setPostingGroup(4);
        edited.setMaxCommuteMin(20);

        InvalidInputException e = catchThrowableOfType(InvalidInputException.class,
                () -> profileController.saveProfile(SESSION, edited));

        assertThat(e.getFieldErrors())
                .containsEntry("displayName", ProfileController.DISPLAY_NAME_MESSAGE)
                .containsEntry("psleScore", "Enter a whole number from 4 to 32")
                .containsEntry("postingGroup", "Choose posting group 1, 2 or 3")
                .containsEntry("maxCommuteMin", "Choose 15, 30, 45 or 60 minutes")
                .hasSize(4);
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-PROFILE-01-19: optional fields left blank are saved as empty (null), not as 0 or \"\"")
    void saveProfile_blankOptionalFields() {
        UserProfile edited = new UserProfile(alice);
        edited.setDisplayName("   ");
        edited.setPrimarySchool("");
        edited.setHomeAddress(" ");

        profileController.saveProfile(SESSION, edited);

        UserProfile saved = savedProfile();
        assertThat(saved.getDisplayName()).isNull();
        assertThat(saved.getPrimarySchool()).isNull();
        assertThat(saved.getHomeAddress()).isNull();
        assertThat(saved.getHomeLocation()).isNull();
        assertThat(saved.getPsleScore()).isNull();
        verifyNoInteractions(locationController);
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-ProfileController-05: an address with no match is a field error and nothing is saved")
    void saveProfile_addressNotFound() {
        when(locationController.findCandidates("nowhere street")).thenReturn(List.of());
        UserProfile edited = withAddress("nowhere street");

        InvalidInputException e = catchThrowableOfType(InvalidInputException.class,
                () -> profileController.saveProfile(SESSION, edited));

        assertThat(e.getFieldErrors()).containsEntry("homeAddress", "Address not found in Singapore");
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-ProfileController-06: several matches ask the member to choose; the candidates come with the error")
    void saveProfile_severalMatches() {
        List<ReferenceLocation> candidates = List.of(
                new ReferenceLocation(BISHAN, LocationSource.MANUAL_ENTRY, "9 BISHAN STREET 22"),
                new ReferenceLocation(TAMPINES, LocationSource.MANUAL_ENTRY, "1 TAMPINES STREET 11"));
        when(locationController.findCandidates("street")).thenReturn(candidates);
        UserProfile edited = withAddress("street");
        edited.setPsleScore(40);   // other errors are still reported

        ProfileController.AmbiguousAddressException e = catchThrowableOfType(
                ProfileController.AmbiguousAddressException.class,
                () -> profileController.saveProfile(SESSION, edited));

        assertThat(e.getCandidates()).isEqualTo(candidates);
        assertThat(e.getFieldErrors()).containsEntry("homeAddress", ProfileController.CHOOSE_ADDRESS_MESSAGE)
                .containsKey("psleScore");
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-ProfileController-07: an unchanged address keeps the saved location without a new search")
    void saveProfile_unchangedAddress_noSearch() {
        UserProfile saved = new UserProfile(alice);
        saved.setHomeAddress("579767");
        saved.setHomeLocation(BISHAN);
        when(profileRepository.findById(alice.getAccountId())).thenReturn(Optional.of(saved));

        profileController.saveProfile(SESSION, withAddress(" 579767 "));

        assertThat(savedProfile().getHomeLocation()).isEqualTo(BISHAN);
        verifyNoInteractions(locationController);
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-ProfileController-08: a location chosen from the candidates is kept without a new search")
    void saveProfile_chosenLocation_kept() {
        UserProfile edited = withAddress("1 TAMPINES STREET 11");
        edited.setHomeLocation(TAMPINES);

        profileController.saveProfile(SESSION, edited);

        assertThat(savedProfile().getHomeLocation()).isEqualTo(TAMPINES);
        verifyNoInteractions(locationController);
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-ProfileController-09: a chosen location outside Singapore is rejected")
    void saveProfile_chosenLocationOutsideSingapore() {
        UserProfile edited = withAddress("somewhere");
        edited.setHomeLocation(new Coordinate(40.0, 100.0));

        InvalidInputException e = catchThrowableOfType(InvalidInputException.class,
                () -> profileController.saveProfile(SESSION, edited));

        assertThat(e.getFieldErrors()).containsEntry("homeAddress", "Address not found in Singapore");
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-ProfileController-10: a changed address is searched again and replaces the old location")
    void saveProfile_changedAddress_newLocation() {
        UserProfile saved = new UserProfile(alice);
        saved.setHomeAddress("579767");
        saved.setHomeLocation(BISHAN);
        when(profileRepository.findById(alice.getAccountId())).thenReturn(Optional.of(saved));
        oneCandidate("tampines", TAMPINES, "1 TAMPINES STREET 11");

        profileController.saveProfile(SESSION, withAddress("tampines"));

        assertThat(savedProfile().getHomeLocation()).isEqualTo(TAMPINES);
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-ProfileController-11: clearing the address also clears the saved location")
    void saveProfile_clearedAddress_clearsLocation() {
        UserProfile saved = new UserProfile(alice);
        saved.setHomeAddress("579767");
        saved.setHomeLocation(BISHAN);
        when(profileRepository.findById(alice.getAccountId())).thenReturn(Optional.of(saved));

        profileController.saveProfile(SESSION, withAddress(""));

        assertThat(savedProfile().getHomeLocation()).isNull();
        assertThat(savedProfile().getHomeAddress()).isNull();
    }

    @Test
    @Tag("FR-PROFILE-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-ProfileController-12: OneMap unavailable is a field error; the previous profile stays unchanged")
    void saveProfile_addressSearchUnavailable() {
        when(locationController.findCandidates("bishan"))
                .thenThrow(new ExternalServiceUnavailableException("OneMap", new RuntimeException("timeout")));

        InvalidInputException e = catchThrowableOfType(InvalidInputException.class,
                () -> profileController.saveProfile(SESSION, withAddress("bishan")));

        assertThat(e.getFieldErrors()).containsEntry("homeAddress", ProfileController.ADDRESS_UNAVAILABLE_MESSAGE);
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    @Tag("FR-PROFILE-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-ProfileController-16: OneMap unavailable while saving a home address writes one WARN line")
    void saveProfile_addressSearchUnavailable_logsOneWarning() {
        when(locationController.findCandidates("bishan")).thenThrow(ExternalFailures.timeout("OneMap"));

        try (LogCapture log = LogCapture.of(ProfileController.class)) {
            catchThrowableOfType(InvalidInputException.class,
                    () -> profileController.saveProfile(SESSION, withAddress("bishan")));

            assertThat(log.warnings()).singleElement().asString()
                    .startsWith("Home address search unavailable: OneMap: OneMap failed: ResourceAccessException");
        }
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-ProfileController-13: an address the location search rejects becomes a homeAddress error")
    void saveProfile_addressRejectedByLocationSearch() {
        when(locationController.findCandidates("!!!"))
                .thenThrow(new InvalidInputException("address", "Enter 1–200 characters"));

        InvalidInputException e = catchThrowableOfType(InvalidInputException.class,
                () -> profileController.saveProfile(SESSION, withAddress("!!!")));

        assertThat(e.getFieldErrors()).containsOnlyKeys("homeAddress");
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-ProfileController-14: only the session's own profile is changed, whatever profile object is passed in")
    void saveProfile_ownerOnly() {
        UserProfile malloryProfile = new UserProfile(mallory);
        malloryProfile.setDisplayName("Alice was here");

        profileController.saveProfile(SESSION, malloryProfile);

        UserProfile saved = savedProfile();
        assertThat(saved.getAccount()).isEqualTo(alice);
        assertThat(saved.getDisplayName()).isEqualTo("Alice was here");
        verify(profileRepository, never()).findById(mallory.getAccountId());
    }

    @Test
    @Tag("FR-PROFILE-01")
    @DisplayName("TC-ProfileController-15: a later save updates the stored profile instead of creating a second one")
    void saveProfile_updatesExisting() {
        UserProfile saved = new UserProfile(alice);
        saved.setPsleScore(20);
        saved.setPreferredCCAs(Set.of("BADMINTON", "CHOIR"));
        when(profileRepository.findById(alice.getAccountId())).thenReturn(Optional.of(saved));
        UserProfile edited = new UserProfile(alice);
        edited.setPsleScore(12);
        edited.setPreferredCCAs(Set.of("CHOIR"));

        profileController.saveProfile(SESSION, edited);

        assertThat(savedProfile()).isSameAs(saved);
        assertThat(saved.getPsleScore()).isEqualTo(12);
        assertThat(saved.getPreferredCCAs()).containsExactly("CHOIR");
    }

    private UserProfile withAddress(String address) {
        UserProfile edited = new UserProfile(alice);
        edited.setHomeAddress(address);
        return edited;
    }

    private void oneCandidate(String address, Coordinate coordinate, String label) {
        when(locationController.findCandidates(address))
                .thenReturn(List.of(new ReferenceLocation(coordinate, LocationSource.MANUAL_ENTRY, label)));
    }

    private UserProfile savedProfile() {
        ArgumentCaptor<UserProfile> saved = ArgumentCaptor.forClass(UserProfile.class);
        verify(profileRepository).saveAndFlush(saved.capture());
        return saved.getValue();
    }
}

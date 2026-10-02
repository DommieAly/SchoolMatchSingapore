package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.AccountStatus;
import sg.schoolmatch.entity.shortlist.Shortlist;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.persistence.AccountRepository;
import sg.schoolmatch.persistence.ShortlistRepository;
import sg.schoolmatch.support.FixedClock;

/**
 * Unit test of the control class AccountController (use case Register; FR-REG-01..05, NFR-SEC-01/02, DC-19).
 * Mockito repositories; the rows of {@code testcases/TC-REGISTER.csv} except FR-REG-03 (the confirmation is checked
 * by RegisterUI, see {@code RegisterUITest}). "taken_user" / "taken@example.com" already exist.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)   // the duplicate lookups are not reached by every row
class AccountControllerTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ShortlistRepository shortlistRepository;

    private final FixedClock clock = FixedClock.atDefault();
    private AccountController accountController;

    @BeforeEach
    void setUp() {
        // strength 4: fast in tests; the app uses the default strength (PasswordConfig)
        accountController = new AccountController(accountRepository, shortlistRepository,
                new BCryptPasswordEncoder(4), clock);
        when(accountRepository.existsByUsernameKey(anyString()))
                .thenAnswer(call -> call.<String>getArgument(0).equals("taken_user"));   // the key is lower-case
        when(accountRepository.existsByEmailIgnoreCase(anyString()))
                .thenAnswer(call -> call.<String>getArgument(0).toLowerCase(Locale.ROOT).equals("taken@example.com"));
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(call -> call.getArgument(0));
    }

    static List<AccountTestCases.Row> registerRows() {
        return AccountTestCases.rows("TC-REGISTER.csv", row -> !row.requirement().equals("FR-REG-03"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("registerRows")
    @Tag("FR-REG-01")
    @Tag("FR-REG-02")
    @Tag("FR-REG-04")
    @Tag("FR-REG-05")
    @DisplayName("TC-REG-xx-yy: registration cases from TC-REGISTER.csv")
    void register_followsTheTestCaseTable(AccountTestCases.Row row) {
        Map<String, String> form = row.registration();
        String username = form.get("username");
        String email = form.get("email");
        String password = form.get("password");

        assertThat(accountController.validateRegistration(username, email, password).keySet())
                .as("validateRegistration error fields").containsExactlyElementsOf(row.errorFields());

        if (row.expectsError()) {
            InvalidInputException e = catchThrowableOfType(InvalidInputException.class,
                    () -> accountController.register(username, email, password));
            assertThat(e).as("register throws InvalidInputException").isNotNull();
            assertThat(e.getFieldErrors().keySet()).containsExactlyElementsOf(row.errorFields());
            verify(accountRepository, never()).saveAndFlush(any());
            verify(shortlistRepository, never()).save(any());
        } else {
            assertThat(accountController.register(username, email, password)).isTrue();
            verify(accountRepository).saveAndFlush(any(Account.class));
        }
    }

    @Test
    @Tag("FR-REG-05")
    @Tag("NFR-SEC-01")
    @Tag("NFR-SEC-02")
    @DisplayName("TC-REG-05-01: success stores an ACTIVE account with a BCrypt hash (not the password) and a lower-case email")
    void register_storesHashedPasswordAndLowerCaseEmail() {
        accountController.register("Alice_01", "Alice@Example.COM", "Passw0rd");

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(saved.capture());
        Account account = saved.getValue();
        assertThat(account.getUsername()).isEqualTo("Alice_01");
        assertThat(account.getEmail()).isEqualTo("alice@example.com");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getCreatedAt()).isEqualTo(clock.instant());
        assertThat(account.getPasswordHash()).startsWith("$2").doesNotContain("Passw0rd");
        assertThat(account.verifyPassword("Passw0rd")).isTrue();
    }

    @Test
    @Tag("FR-REG-05")
    @Tag("FR-DATA-02")
    @DisplayName("TC-REG-05-02: success also creates the account's empty shortlist")
    void register_createsEmptyShortlist() {
        accountController.register("alice_01", "alice@example.com", "Passw0rd");

        ArgumentCaptor<Shortlist> saved = ArgumentCaptor.forClass(Shortlist.class);
        verify(shortlistRepository).save(saved.capture());
        assertThat(saved.getValue().isEmpty()).isTrue();
        assertThat(saved.getValue().getAccount().getUsername()).isEqualTo("alice_01");
    }

    @Test
    @Tag("FR-REG-02")
    @DisplayName("TC-REG-02-27: spaces around the username and email are ignored")
    void register_trimsUsernameAndEmail() {
        accountController.register("  alice_01 ", " alice@example.com  ", "Passw0rd");

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("alice_01");
        assertThat(saved.getValue().getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    @Tag("FR-REG-04")
    @DisplayName("TC-REG-04-05: the duplicate messages say the account already exists (AF-2)")
    void register_duplicateMessages() {
        InvalidInputException e = catchThrowableOfType(InvalidInputException.class,
                () -> accountController.register("taken_user", "taken@example.com", "Passw0rd"));

        assertThat(e.getFieldErrors())
                .containsEntry("username", AccountController.USERNAME_TAKEN_MESSAGE)
                .containsEntry("email", AccountController.EMAIL_TAKEN_MESSAGE);
        assertThat(AccountController.USERNAME_TAKEN_MESSAGE).isEqualTo("An account with this username already exists");
        assertThat(AccountController.EMAIL_TAKEN_MESSAGE).isEqualTo("An account with this email already exists");
    }

    @Test
    @Tag("FR-REG-04")
    @DisplayName("TC-REG-04-06: a double submit that loses the race to the unique key gets the AF-2 message, not an error page (EX-2)")
    void register_uniqueKeyRace_isDuplicateMessage() {
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("unique key on username"));

        InvalidInputException e = catchThrowableOfType(InvalidInputException.class,
                () -> accountController.register("alice_01", "alice@example.com", "Passw0rd"));

        assertThat(e).isNotNull();
        assertThat(e.getFieldErrors()).containsEntry("username", AccountController.ALREADY_REGISTERED_MESSAGE);
        verify(shortlistRepository, never()).save(any());
    }

    @Test
    @Tag("FR-REG-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-REG-02-28: null fields (not sent at all) are missing-field errors, not a crash")
    void register_nullFields_areMissing() {
        assertThat(accountController.validateRegistration(null, null, null))
                .containsOnlyKeys("username", "email", "password");
    }
}

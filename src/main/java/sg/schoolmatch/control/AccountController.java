package sg.schoolmatch.control;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.shortlist.Shortlist;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.persistence.AccountRepository;
import sg.schoolmatch.persistence.ShortlistRepository;

/**
 * Design class «control» AccountController — registers new accounts (use case Register;
 * FR-REG-01..05, NFR-SEC-01, NFR-SEC-02). Also creates the account's empty Shortlist.
 * Called by RegisterUI. The rules are the data dictionary's "Registration Information" plus DC-19.
 */
@Service
public class AccountController {

    public static final String USERNAME_MISSING_MESSAGE = "Enter a username";
    public static final String USERNAME_MESSAGE = "Use 3–30 letters, digits or underscores (_)";
    public static final String EMAIL_MISSING_MESSAGE = "Enter your email address";
    public static final String EMAIL_MESSAGE = "Enter a valid email address, e.g. name@example.com";
    public static final String PASSWORD_MISSING_MESSAGE = "Enter a password";
    public static final String PASSWORD_LENGTH_MESSAGE = "Use 8–64 characters";
    public static final String PASSWORD_CLASSES_MESSAGE =
            "Include an upper-case letter, a lower-case letter and a digit";
    public static final String PASSWORD_ASCII_MESSAGE =
            "Use only English letters, digits, spaces and keyboard symbols (no accents or emoji)";
    public static final String USERNAME_TAKEN_MESSAGE = "An account with this username already exists";
    public static final String EMAIL_TAKEN_MESSAGE = "An account with this email already exists";
    /** A double submit that lost the race to the unique key: we cannot tell which field it was (EX-2). */
    public static final String ALREADY_REGISTERED_MESSAGE = "An account with this username or email already exists";

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,30}$");
    /** Exactly one @, no white space, something before and after the @. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");
    private static final int EMAIL_MAX_LENGTH = 254;   // the longest address mail servers accept (RFC 5321)
    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final int PASSWORD_MAX_LENGTH = 64;

    private final AccountRepository accountRepository;
    private final ShortlistRepository shortlistRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AccountController(AccountRepository accountRepository, ShortlistRepository shortlistRepository,
                             PasswordEncoder passwordEncoder, Clock clock) {
        this.accountRepository = accountRepository;
        this.shortlistRepository = shortlistRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Creates an ACTIVE account and its empty shortlist in one transaction (FR-REG-05). No session is created.
     * Username and email are trimmed; the email is stored lower-case. Duplicates ignore case (FR-REG-04).
     * DC-19: the password is 8–64 characters, printable ASCII only (BCrypt reads at most 72 bytes, and
     * {@code BCryptPasswordEncoder.encode} throws IllegalArgumentException above that, which would be a 500 page).
     *
     * @return true on success
     * @throws InvalidInputException on missing/invalid fields or a duplicate username/email (all fields at once)
     */
    @Transactional
    public boolean register(String username, String email, String password) {
        Map<String, String> errors = validateRegistration(username, email, password);
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        Account account = new Account(username.trim(), email.trim(), hashPassword(password), clock.instant());
        try {
            // Flush now: a double submit that got past isDuplicate fails here, not later at commit (EX-2).
            accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            throw new InvalidInputException("username", ALREADY_REGISTERED_MESSAGE);
        }
        shortlistRepository.save(new Shortlist(account));
        return true;
    }

    /**
     * Checks the registration fields without creating anything: the format rules, then duplicates.
     * RegisterUI calls it so these errors show together with its own confirmation check (AF-1).
     *
     * @return field → message; empty when every field is valid
     */
    @Transactional(readOnly = true)
    public Map<String, String> validateRegistration(String username, String email, String password) {   // DC-44
        Map<String, String> errors = new LinkedHashMap<>();
        String user = username == null ? "" : username.trim();
        String mail = email == null ? "" : email.trim();
        if (user.isEmpty()) {
            errors.put("username", USERNAME_MISSING_MESSAGE);
        } else if (!USERNAME.matcher(user).matches()) {
            errors.put("username", USERNAME_MESSAGE);
        }
        if (mail.isEmpty()) {
            errors.put("email", EMAIL_MISSING_MESSAGE);
        } else if (mail.length() > EMAIL_MAX_LENGTH || !EMAIL.matcher(mail).matches()) {
            errors.put("email", EMAIL_MESSAGE);
        }
        String passwordError = passwordError(password);
        if (passwordError != null) {
            errors.put("password", passwordError);
        }
        isDuplicate(errors.containsKey("username") ? null : user, errors.containsKey("email") ? null : mail, errors);
        return errors;
    }

    /**
     * FR-REG-04: puts an error on each field whose value is already registered (ignoring case).
     * A null value is skipped (it already has a format error).
     */
    private boolean isDuplicate(String username, String email, Map<String, String> errors) {
        boolean duplicate = false;
        if (username != null && accountRepository.existsByUsernameKey(Account.usernameKey(username))) {
            errors.put("username", USERNAME_TAKEN_MESSAGE);
            duplicate = true;
        }
        if (email != null && accountRepository.existsByEmailIgnoreCase(email)) {
            errors.put("email", EMAIL_TAKEN_MESSAGE);
            duplicate = true;
        }
        return duplicate;
    }

    /** The first password rule that is broken, or null when the password is valid. */
    private static String passwordError(String password) {
        if (password == null || password.isEmpty()) {
            return PASSWORD_MISSING_MESSAGE;
        }
        if (password.length() < PASSWORD_MIN_LENGTH || password.length() > PASSWORD_MAX_LENGTH) {
            return PASSWORD_LENGTH_MESSAGE;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        for (char c : password.toCharArray()) {
            if (c < ' ' || c > '~') {
                return PASSWORD_ASCII_MESSAGE;   // DC-19: printable ASCII (space to ~) only
            }
            upper |= c >= 'A' && c <= 'Z';
            lower |= c >= 'a' && c <= 'z';
            digit |= c >= '0' && c <= '9';
        }
        return upper && lower && digit ? null : PASSWORD_CLASSES_MESSAGE;
    }

    /** BCrypt via the injected PasswordEncoder (NFR-SEC-02). Call it only after the DC-19 check above. */
    private String hashPassword(String password) {
        return passwordEncoder.encode(password);
    }
}

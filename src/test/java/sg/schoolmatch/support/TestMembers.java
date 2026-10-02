package sg.schoolmatch.support;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AccountController;
import sg.schoolmatch.control.AuthController;

/**
 * Test helper: registers a member and logs in through the real controls (owner A), so a MockMvc test can send
 * the {@code SM_SESSION} cookie of a logged-in member.
 * <pre>
 * String sessionId = TestMembers.registerAndLogIn(accountController, authController, "smoke");
 * mvc.perform(get("/shortlist").cookie(TestMembers.cookie(props, sessionId)));
 * </pre>
 * Usernames get a random suffix, because Spring test contexts (and their in-memory database) are shared between
 * test classes.
 */
public final class TestMembers {

    /** Meets the password rule (8–64 printable ASCII, upper, lower and digit). */
    public static final String PASSWORD = "Secret123";

    private TestMembers() {
    }

    /** Registers {@code <prefix>_<random>} with {@link #PASSWORD} and returns the new login session id. */
    public static String registerAndLogIn(AccountController accounts, AuthController auth, String prefix) {
        String username = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        accounts.register(username, username + "@example.com", PASSWORD);
        return auth.login(username, PASSWORD).getSessionId();
    }

    /** The login cookie for {@code sessionId}. */
    public static Cookie cookie(AppProperties props, String sessionId) {
        return new Cookie(props.session().cookieName(), sessionId);
    }
}

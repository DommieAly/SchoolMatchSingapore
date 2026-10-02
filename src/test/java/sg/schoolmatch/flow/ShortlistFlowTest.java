package sg.schoolmatch.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sg.schoolmatch.boundary.ui.ShortlistUI;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.ChoicePlanController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.control.ShortlistController;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.shortlist.SchoolChoice;
import sg.schoolmatch.entity.shortlist.Shortlist;
import sg.schoolmatch.persistence.AccountRepository;
import sg.schoolmatch.persistence.ShortlistRepository;

/**
 * Flow test of Add to Shortlist → View Shortlist → Compare → Plan School Choices → Remove, with two members
 * (FR-SHORTLIST-01..07, FR-COMPARE-01, FR-PLAN-01/02, NFR-SEC-05).
 * <p>
 * Real controls, repositories and the fixture snapshot. Only login is faked: AuthController and
 * ProfileController are mocks that map two session ids to two saved accounts, so this test does not depend
 * on the login pages (owner A). Each request carries the session cookie; no URL ever carries an account id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShortlistFlowTest {

    private static final String ALICE = "session-alice";
    private static final String BOB = "session-bob";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppProperties props;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ShortlistRepository shortlistRepository;

    @MockitoBean
    private AuthController authController;

    @MockitoBean
    private ProfileController profileController;

    /** New accounts per test; the in-memory database is shared with the other test classes. */
    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    private Account alice;
    private Account bob;

    @BeforeEach
    void twoMembers() {
        alice = accountRepository.save(new Account("alice_" + suffix, "alice_" + suffix + "@example.com",
                "not-a-real-hash", Instant.EPOCH));
        bob = accountRepository.save(new Account("bob_" + suffix, "bob_" + suffix + "@example.com",
                "not-a-real-hash", Instant.EPOCH));
        shortlistRepository.save(new Shortlist(alice));   // registration creates it (owner A); bob has none yet
        logIn(ALICE, alice);
        logIn(BOB, bob);

        UserProfile aliceProfile = new UserProfile(alice);
        aliceProfile.setPsleScore(12);
        aliceProfile.setPostingGroup(3);
        when(profileController.getProfile(ALICE)).thenReturn(aliceProfile);
        when(profileController.getProfile(BOB)).thenReturn(new UserProfile(bob));
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @Tag("FR-SHORTLIST-04")
    @Tag("FR-COMPARE-01")
    @Tag("FR-PLAN-01")
    @Tag("FR-PLAN-02")
    @DisplayName("TC-ShortlistFlow-01: add three schools, view, compare two, plan with SAFE/MATCH/REACH, remove")
    void happyPath() throws Exception {
        for (String code : List.of("catholic-high-school", "hua-yi-secondary-school", "jurong-west-secondary-school")) {
            mvc.perform(post("/schools/" + code + "/shortlist").cookie(cookie(ALICE)))
                    .andExpect(redirectedUrl("/schools/" + code))
                    .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, ShortlistUI.ADDED_MESSAGE));
        }
        mvc.perform(post("/schools/catholic-high-school/shortlist").cookie(cookie(ALICE)))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, ShortlistUI.ALREADY_MESSAGE));

        mvc.perform(get("/shortlist").cookie(cookie(ALICE)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CATHOLIC HIGH SCHOOL")))
                .andExpect(content().string(containsString("HUA YI SECONDARY SCHOOL")))
                .andExpect(content().string(containsString("JURONG WEST SECONDARY SCHOOL")));

        mvc.perform(get("/compare").param("codes", "catholic-high-school", "hua-yi-secondary-school")
                        .cookie(cookie(ALICE)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CATHOLIC HIGH SCHOOL")))
                .andExpect(content().string(containsString("HUA YI SECONDARY SCHOOL")))
                .andExpect(content().string(not(containsString("JURONG WEST SECONDARY SCHOOL"))));

        addChoice(ALICE, "hua-yi-secondary-school", 1);
        addChoice(ALICE, "jurong-west-secondary-school", 2);
        addChoice(ALICE, "catholic-high-school", 1);
        assertThat(planCodes(alice)).containsExactly(
                "catholic-high-school", "hua-yi-secondary-school", "jurong-west-secondary-school");

        mvc.perform(post("/plan/choices/jurong-west-secondary-school/move").param("dir", "up").cookie(cookie(ALICE)))
                .andExpect(redirectedUrl("/plan"));
        assertThat(planCodes(alice)).containsExactly(
                "catholic-high-school", "jurong-west-secondary-school", "hua-yi-secondary-school");

        // Fixture PG3 upper scores: Catholic High 9, Jurong West 15, Hua Yi 13; score 12 → REACH, SAFE, MATCH
        String plan = mvc.perform(get("/plan").cookie(cookie(ALICE)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(plan).contains("data-chance=\"REACH\"", "data-chance=\"SAFE\"", "data-chance=\"MATCH\"")
                .contains("You have 3 of 6 choices.")
                .contains("TEST VALUES – not MOE data");

        mvc.perform(post("/shortlist/catholic-high-school/remove").cookie(cookie(ALICE)))
                .andExpect(redirectedUrl("/shortlist"));
        assertThat(shortlistCodes(alice)).containsExactly("hua-yi-secondary-school", "jurong-west-secondary-school");
        assertThat(planCodes(alice)).containsExactly("jurong-west-secondary-school", "hua-yi-secondary-school");

        mvc.perform(post("/plan/choices/hua-yi-secondary-school/remove").cookie(cookie(ALICE)))
                .andExpect(redirectedUrl("/plan"));
        assertThat(planCodes(alice)).containsExactly("jurong-west-secondary-school");
        assertThat(shortlistCodes(alice)).contains("hua-yi-secondary-school");   // removing a choice keeps the school
    }

    @Test
    @Tag("NFR-SEC-05")
    @DisplayName("TC-ShortlistFlow-02: another member can neither see nor change Alice's shortlist or plan")
    void otherMemberCannotSeeOrChange() throws Exception {
        mvc.perform(post("/schools/catholic-high-school/shortlist").cookie(cookie(ALICE)));
        mvc.perform(post("/schools/hua-yi-secondary-school/shortlist").cookie(cookie(ALICE)));
        addChoice(ALICE, "catholic-high-school", 1);

        mvc.perform(get("/shortlist").cookie(cookie(BOB)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You have not shortlisted any schools yet")))
                .andExpect(content().string(not(containsString("CATHOLIC HIGH SCHOOL"))));

        mvc.perform(post("/shortlist/catholic-high-school/remove").cookie(cookie(BOB)))
                .andExpect(redirectedUrl("/shortlist"));
        mvc.perform(post("/plan/choices/catholic-high-school/remove").cookie(cookie(BOB)))
                .andExpect(redirectedUrl("/plan"));
        mvc.perform(get("/compare").param("codes", "catholic-high-school,hua-yi-secondary-school").cookie(cookie(BOB)))
                .andExpect(redirectedUrl("/shortlist"))
                .andExpect(flash().attribute(PageMessages.FLASH_ERROR,
                        ShortlistController.COMPARE_NOT_SHORTLISTED_MESSAGE));
        mvc.perform(post("/plan/choices").param("code", "catholic-high-school").param("rank", "1").cookie(cookie(BOB)))
                .andExpect(redirectedUrl("/plan"))
                .andExpect(flash().attribute(PageMessages.FLASH_ERROR, ChoicePlanController.NOT_SHORTLISTED_MESSAGE));

        assertThat(shortlistCodes(alice)).containsExactly("catholic-high-school", "hua-yi-secondary-school");
        assertThat(planCodes(alice)).containsExactly("catholic-high-school");
        assertThat(shortlistRepository.findById(bob.getAccountId())).isEmpty();   // Bob's attempts saved nothing
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @Tag("FR-DATA-05")
    @DisplayName("TC-ShortlistFlow-03: a member without a shortlist row gets one on the first add, saved in the database")
    void firstAddCreatesShortlist() throws Exception {
        assertThat(shortlistRepository.findById(bob.getAccountId())).isEmpty();

        mvc.perform(post("/schools/tampines-secondary-school/shortlist").cookie(cookie(BOB)))
                .andExpect(redirectedUrl("/schools/tampines-secondary-school"))
                .andExpect(flash().attribute(PageMessages.FLASH_MESSAGE, ShortlistUI.ADDED_MESSAGE));

        assertThat(shortlistCodes(bob)).containsExactly("tampines-secondary-school");
    }

    @Test
    @Tag("FR-SHORTLIST-03")
    @DisplayName("TC-ShortlistFlow-04: a guest's add-to-shortlist and an ended session both go to the login page")
    void guestAndEndedSession() throws Exception {
        mvc.perform(post("/schools/catholic-high-school/shortlist"))
                .andExpect(redirectedUrl("/login?next=/schools/catholic-high-school&add=catholic-high-school"));
        mvc.perform(get("/plan").cookie(cookie("expired")))
                .andExpect(redirectedUrl("/login?next=/plan"));
        assertThat(shortlistCodes(alice)).isEmpty();
    }

    @Test
    @Tag("FR-SHORTLIST-01")
    @DisplayName("TC-ShortlistFlow-05: adding a school code that is not in the dataset answers 404")
    void unknownSchool() throws Exception {
        mvc.perform(post("/schools/no-such-school/shortlist").cookie(cookie(ALICE)))
                .andExpect(status().isNotFound());
        assertThat(shortlistCodes(alice)).isEmpty();
    }

    private void addChoice(String session, String code, int rank) throws Exception {
        mvc.perform(post("/plan/choices").param("code", code).param("rank", String.valueOf(rank)).cookie(cookie(session)))
                .andExpect(redirectedUrl("/plan"));
    }

    private void logIn(String sessionId, Account account) {
        when(authController.verifySession(sessionId)).thenReturn(true);
        when(authController.getAccount(sessionId)).thenReturn(account);
    }

    private Cookie cookie(String sessionId) {
        return new Cookie(props.session().cookieName(), sessionId);
    }

    private List<String> shortlistCodes(Account account) {
        return shortlistRepository.findById(account.getAccountId()).orElseThrow().getSchoolCodes();
    }

    private List<String> planCodes(Account account) {
        Shortlist shortlist = shortlistRepository.findById(account.getAccountId()).orElseThrow();
        return shortlist.getChoicePlan().getChoices().stream().map(SchoolChoice::getSchoolCode).toList();
    }
}

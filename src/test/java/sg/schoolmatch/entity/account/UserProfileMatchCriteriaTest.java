package sg.schoolmatch.entity.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.recommend.MatchCriteria;
import sg.schoolmatch.entity.route.TravelMode;

/**
 * UserProfile.toMatchCriteria() (DC-07, FR-REC-01): the recommendation form is prefilled from the profile.
 * Owner B writes this method; owner A owns the rest of UserProfile.
 */
class UserProfileMatchCriteriaTest {

    private static final Coordinate HOME = new Coordinate(1.3510, 103.8484);
    private static final Instant NOW = Instant.parse("2026-10-12T02:00:00Z");

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-Profile-01: every criterion is copied from the profile; home becomes the start location")
    void copiesProfile() {
        UserProfile profile = new UserProfile(new Account("ann", "ann@example.test", "hash", NOW));
        profile.setPsleScore(12);
        profile.setPostingGroup(3);
        profile.setPrimarySchool("Ai Tong School");
        profile.setHomeAddress("9 Bishan Street 22");
        profile.setHomeLocation(HOME);
        profile.setTravelMode(TravelMode.TRANSIT);
        profile.setMaxCommuteMin(45);
        profile.setPreferredCCAs(List.of("CHOIR", "BASKETBALL"));
        profile.setPreferredProgrammes(List.of("Art"));

        MatchCriteria criteria = profile.toMatchCriteria();

        assertThat(criteria.getPsleScore()).isEqualTo(12);
        assertThat(criteria.getPostingGroup()).isEqualTo(3);
        assertThat(criteria.getPrimarySchool()).isEqualTo("Ai Tong School");
        assertThat(criteria.getTravelMode()).isEqualTo(TravelMode.TRANSIT);
        assertThat(criteria.getMaxCommuteMin()).isEqualTo(45);
        assertThat(criteria.getPreferredCCAs()).containsExactly("CHOIR", "BASKETBALL");
        assertThat(criteria.getPreferredProgrammes()).containsExactly("Art");
        assertThat(criteria.getStartLocation().getCoordinate()).isEqualTo(HOME);
        assertThat(criteria.getStartLocation().getSource()).isEqualTo(LocationSource.MANUAL_ENTRY);
        assertThat(criteria.getStartLocation().getInputText()).isEqualTo("9 Bishan Street 22");
        assertThat(criteria.getWeights()).as("weights come from app.recommendation.weights, not the profile").isEmpty();
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-Profile-02: an empty profile gives empty criteria (no start location), not an error")
    void emptyProfile() {
        MatchCriteria criteria = new UserProfile(new Account("bob", "bob@example.test", "hash", NOW)).toMatchCriteria();

        assertThat(criteria.getPsleScore()).isNull();
        assertThat(criteria.getStartLocation()).isNull();
        assertThat(criteria.getPreferredCCAs()).isEmpty();
        assertThat(criteria.isValid()).isFalse();
    }

    @Test
    @Tag("FR-REC-01")
    @DisplayName("TC-REC-Profile-03: a home location without an address text is labelled 'Home'")
    void homeWithoutAddress() {
        UserProfile profile = new UserProfile(new Account("cat", "cat@example.test", "hash", NOW));
        profile.setHomeLocation(HOME);

        assertThat(profile.toMatchCriteria().getStartLocation().getInputText()).isEqualTo("Home");
    }
}

package sg.schoolmatch.entity.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.support.TestSchools;

/** Unit tests for «entity» TransportationFilter: reads commute minutes computed beforehand (FR-FILTER-06, DC-32). */
class TransportationFilterTest {

    private final ReferenceLocation bishan =
            new ReferenceLocation(TestSchools.BISHAN, LocationSource.MANUAL_ENTRY, "BISHAN MRT");
    private final School near = TestSchools.named("near-school", "NEAR SCHOOL");
    private final School edge = TestSchools.named("edge-school", "EDGE SCHOOL");
    private final School far = TestSchools.named("far-school", "FAR SCHOOL");
    private final School unknown = TestSchools.named("unknown-school", "UNKNOWN SCHOOL");

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-Transport-01: kept when minutes ≤ max (30 kept, 31 not); no minutes → left out")
    void matchesByMinutes() {
        TransportationFilter filter = new TransportationFilter(bishan, TravelMode.TRANSIT, 30);
        filter.setCommuteMinutesBySchool(Map.of("near-school", 12, "edge-school", 30, "far-school", 31));

        assertThat(filter.matches(near)).isTrue();
        assertThat(filter.matches(edge)).isTrue();
        assertThat(filter.matches(far)).isFalse();
        assertThat(filter.matches(unknown)).isFalse();
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-Transport-02: before the commute times are set, nothing matches")
    void nothingBeforeTimesAreSet() {
        assertThat(new TransportationFilter(bishan, TravelMode.WALK, 60).matches(near)).isFalse();
    }

    @ParameterizedTest(name = "{0} min → valid")
    @ValueSource(ints = {15, 30, 45, 60})
    @Tag("FR-FILTER-06")
    @DisplayName("TC-Transport-03: 15, 30, 45 and 60 minutes are valid")
    void validDurations(int minutes) {
        assertThat(new TransportationFilter(bishan, TravelMode.DRIVE, minutes).isValid()).isTrue();
    }

    @ParameterizedTest(name = "{0} min → invalid")
    @ValueSource(ints = {0, 14, 20, 61})
    @Tag("FR-FILTER-06")
    @DisplayName("TC-Transport-04: any other duration is invalid, and so is a missing mode or starting point")
    void invalidInputs(int minutes) {
        assertThat(new TransportationFilter(bishan, TravelMode.DRIVE, minutes).isValid()).isFalse();
        assertThat(new TransportationFilter(bishan, null, 30).isValid()).isFalse();
        assertThat(new TransportationFilter(null, TravelMode.WALK, 30).isValid()).isFalse();
    }

    @Test
    @Tag("FR-FILTER-08")
    @DisplayName("TC-Transport-05: describe gives the time and the travel mode in words")
    void describe() {
        assertThat(new TransportationFilter(bishan, TravelMode.TRANSIT, 30).describe())
                .isEqualTo("Within 30 min by public transport");
        assertThat(new TransportationFilter(bishan, TravelMode.WALK, 15).describe()).isEqualTo("Within 15 min walking");
        assertThat(new TransportationFilter(bishan, TravelMode.DRIVE, 45).describe()).isEqualTo("Within 45 min by car");
    }
}

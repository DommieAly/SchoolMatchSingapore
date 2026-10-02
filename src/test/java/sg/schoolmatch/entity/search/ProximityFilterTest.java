package sg.schoolmatch.entity.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.support.TestSchools;

/** Unit tests for «entity» ProximityFilter: straight-line distance from the starting point (FR-FILTER-05). */
class ProximityFilterTest {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final ReferenceLocation bishan =
            new ReferenceLocation(TestSchools.BISHAN, LocationSource.MANUAL_ENTRY, "BISHAN MRT");

    /** A school {@code km} kilometres due north of Bishan MRT (along a meridian the haversine is exact). */
    private static School northOfBishan(double km) {
        double latitude = TestSchools.BISHAN.getLatitude() + Math.toDegrees(km / EARTH_RADIUS_KM);
        return TestSchools.school("school-" + km).at(latitude, TestSchools.BISHAN.getLongitude()).build();
    }

    @Test
    @Tag("FR-FILTER-05")
    @DisplayName("TC-Proximity-01: a school exactly at the radius is kept; 10 m further is not")
    void boundaryAtRadius() {
        ProximityFilter threeKm = new ProximityFilter(bishan, 3);

        assertThat(threeKm.matches(northOfBishan(2.99))).isTrue();
        assertThat(threeKm.matches(northOfBishan(3.0))).isTrue();
        assertThat(threeKm.matches(northOfBishan(3.01))).isFalse();
    }

    @Test
    @Tag("FR-FILTER-05")
    @Tag("NFR-DATA-02")
    @DisplayName("TC-Proximity-02: a school without a valid coordinate is left out")
    void noCoordinateExcluded() {
        ProximityFilter fiveKm = new ProximityFilter(bishan, 5);

        assertThat(fiveKm.matches(TestSchools.school("no-coordinate").noCoordinate().build())).isFalse();
        assertThat(fiveKm.matches(TestSchools.school("abroad").at(TestSchools.OUTSIDE_SINGAPORE).build())).isFalse();
    }

    @Test
    @Tag("FR-FILTER-05")
    @DisplayName("TC-Proximity-03: Tampines (about 10.8 km away) is outside 5 km of Bishan")
    void farSchoolExcluded() {
        assertThat(new ProximityFilter(bishan, 5).matches(TestSchools.school("t").at(TestSchools.TAMPINES).build()))
                .isFalse();
    }

    @ParameterizedTest(name = "radius {0} km → valid")
    @ValueSource(ints = {1, 3, 5})
    @Tag("FR-FILTER-05")
    @DisplayName("TC-Proximity-04: radius 1, 3 and 5 km are valid")
    void validRadius(int km) {
        assertThat(new ProximityFilter(bishan, km).isValid()).isTrue();
    }

    @ParameterizedTest(name = "radius {0} km → invalid")
    @ValueSource(ints = {0, 2, 4, 6})
    @Tag("FR-FILTER-05")
    @DisplayName("TC-Proximity-05: any other radius is invalid")
    void invalidRadius(int km) {
        assertThat(new ProximityFilter(bishan, km).isValid()).isFalse();
    }

    @Test
    @Tag("FR-FILTER-04")
    @DisplayName("TC-Proximity-06: without a resolved starting point the filter is invalid and keeps nothing")
    void unresolvedLocation() {
        ReferenceLocation unresolved = new ReferenceLocation(null, LocationSource.MANUAL_ENTRY, "somewhere");
        ReferenceLocation abroad = new ReferenceLocation(new Coordinate(40.0, 100.0), LocationSource.DEVICE_LOCATION, null);

        assertThat(new ProximityFilter(unresolved, 3).isValid()).isFalse();
        assertThat(new ProximityFilter(abroad, 3).isValid()).isFalse();
        assertThat(new ProximityFilter(null, 3).isValid()).isFalse();
        assertThat(new ProximityFilter(unresolved, 3).matches(northOfBishan(0.5))).isFalse();
        assertThat(new ProximityFilter(null, 3).matches(northOfBishan(0.5))).isFalse();
    }

    @Test
    @Tag("FR-FILTER-08")
    @DisplayName("TC-Proximity-07: describe names the radius and the starting point")
    void describe() {
        assertThat(new ProximityFilter(bishan, 3).describe()).isEqualTo("Within 3 km of BISHAN MRT");
        ReferenceLocation device = new ReferenceLocation(TestSchools.BISHAN, LocationSource.DEVICE_LOCATION, null);
        assertThat(new ProximityFilter(device, 1).describe()).isEqualTo("Within 1 km of your location");
    }
}

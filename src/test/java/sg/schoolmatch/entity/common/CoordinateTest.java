package sg.schoolmatch.entity.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for «entity» Coordinate — the reference example of a plain JUnit entity test.
 * <p>
 * Pattern to copy: no Spring, one behaviour per test method, {@code @Tag} = the requirement id(s),
 * {@code @DisplayName} = test case id + what is checked. Run the tests of one requirement with
 * {@code ./mvnw test -Dgroups=FR-DATA-06}.
 * <p>
 * Test case ids: unit tests of one design class use {@code TC-<DesignClass>-nn} (here
 * {@code TC-Coordinate-01}); requirement cases from the {@code testcases/TC-*.csv} tables use
 * {@code TC-<AREA>-<req>-<nn>} (e.g. {@code TC-SEARCH-04-01}).
 */
class CoordinateTest {

    // Reference points: Bishan and Tampines MRT stations.
    private static final Coordinate BISHAN = new Coordinate(1.3510, 103.8484);
    private static final Coordinate TAMPINES = new Coordinate(1.3543, 103.9453);

    @Test
    @Tag("FR-FILTER-05")
    @Tag("FR-FACILITY-03")
    @DisplayName("TC-Coordinate-01: Bishan MRT to Tampines MRT is about 10.78 km (haversine)")
    void distanceBishanToTampines() {
        // 10.778 km was computed separately (Python haversine, R = 6371.0088 km); a map shows about 10.8 km.
        assertThat(BISHAN.distanceTo(TAMPINES)).isCloseTo(10.78, within(0.05));
    }

    @Test
    @Tag("FR-FILTER-05")
    @DisplayName("TC-Coordinate-02: one degree of latitude is 111.195 km (earth radius 6371.0088 km)")
    void oneDegreeOfLatitude() {
        double km = new Coordinate(1.0, 103.8).distanceTo(new Coordinate(2.0, 103.8));

        assertThat(km).isCloseTo(6371.0088 * Math.PI / 180, within(1e-6));
    }

    @Test
    @Tag("FR-FILTER-05")
    @DisplayName("TC-Coordinate-03: distance is 0 to itself and the same in both directions")
    void distanceIsSymmetric() {
        assertThat(BISHAN.distanceTo(BISHAN)).isZero();
        assertThat(TAMPINES.distanceTo(BISHAN)).isCloseTo(BISHAN.distanceTo(TAMPINES), within(1e-9));
    }

    @ParameterizedTest(name = "({0}, {1}) is rejected")
    @CsvSource({
            "-90.0001, 0",
            "90.0001, 0",
            "0, -180.0001",
            "0, 180.0001",
            "NaN, 103.8",
            "1.35, NaN"
    })
    @Tag("FR-DATA-06")
    @DisplayName("TC-Coordinate-04: latitude outside -90..90 or longitude outside -180..180 is rejected")
    void rejectsOutOfRange(double latitude, double longitude) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Coordinate(latitude, longitude));
    }

    @ParameterizedTest(name = "({0}, {1}) is accepted")
    @CsvSource({"-90, -180", "90, 180", "0, 0"})
    @Tag("FR-DATA-06")
    @DisplayName("TC-Coordinate-05: the limits -90, 90, -180 and 180 themselves are accepted")
    void acceptsLimits(double latitude, double longitude) {
        Coordinate c = new Coordinate(latitude, longitude);

        assertThat(c.getLatitude()).isEqualTo(latitude);
        assertThat(c.getLongitude()).isEqualTo(longitude);
    }

    @ParameterizedTest(name = "({0}, {1}) → {2}")
    @CsvSource({
            "1.3510, 103.8484, true",    // Bishan
            "1.15,   103.59,   true",    // south-west corner of the box
            "1.48,   104.10,   true",    // north-east corner of the box
            "1.1499, 103.8,    false",   // just south
            "1.4801, 103.8,    false",   // just north (Johor)
            "1.35,   103.5899, false",   // just west
            "1.35,   104.1001, false",   // just east
            "40.0,   100.0,    false"    // the bad-coordinate fixture point (China)
    })
    @Tag("NFR-DATA-02")
    @Tag("FR-MAP-03")
    @DisplayName("TC-Coordinate-06: isWithinSingapore uses the box lat 1.15..1.48, lng 103.59..104.10")
    void withinSingaporeBox(double latitude, double longitude, boolean expected) {
        assertThat(new Coordinate(latitude, longitude).isWithinSingapore()).isEqualTo(expected);
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-Coordinate-07: coordinates with the same latitude and longitude are equal")
    void equalsByValue() {
        assertThat(new Coordinate(1.3510, 103.8484)).isEqualTo(BISHAN).hasSameHashCodeAs(BISHAN);
        assertThat(BISHAN).isNotEqualTo(TAMPINES);
    }
}

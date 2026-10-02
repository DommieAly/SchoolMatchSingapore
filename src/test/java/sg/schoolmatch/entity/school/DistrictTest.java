package sg.schoolmatch.entity.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.entity.common.Coordinate;

/**
 * District.contains (FR-MAP-06, DC-04): point-in-polygon with JTS on the GeoJSON geometry (lng = x, lat = y).
 * The small square runs from (1.30, 103.80) to (1.40, 103.90) and has a hole from (1.34, 103.84) to (1.36, 103.86).
 */
class DistrictTest {

    private static final String SQUARE_WITH_HOLE = """
            {"type":"Polygon","coordinates":[
              [[103.80,1.30],[103.90,1.30],[103.90,1.40],[103.80,1.40],[103.80,1.30]],
              [[103.84,1.34],[103.86,1.34],[103.86,1.36],[103.84,1.36],[103.84,1.34]]]}""";

    private static final String TWO_ISLANDS = """
            {"type":"MultiPolygon","coordinates":[
              [[[103.80,1.20],[103.81,1.20],[103.81,1.21],[103.80,1.21],[103.80,1.20]]],
              [[[103.90,1.20],[103.91,1.20],[103.91,1.21],[103.90,1.21],[103.90,1.20]]]]}""";

    private final District square = new District("SQ", "SQUARE", SQUARE_WITH_HOLE);

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-District-01: a point inside the polygon is contained")
    void pointInside() {
        assertThat(square.contains(new Coordinate(1.32, 103.82))).isTrue();
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-District-02: a point outside the polygon is not contained")
    void pointOutside() {
        assertThat(square.contains(new Coordinate(1.45, 103.82))).isFalse();
        assertThat(square.contains(new Coordinate(1.32, 103.95))).isFalse();
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-District-03: latitude is y and longitude is x (swapping them gives a different answer)")
    void longitudeIsX() {
        District tall = new District("T", "TALL", """
                {"type":"Polygon","coordinates":[[[103.80,1.20],[103.81,1.20],[103.81,1.50],[103.80,1.50],[103.80,1.20]]]}""");

        assertThat(tall.contains(new Coordinate(1.45, 103.805))).isTrue();
        assertThat(tall.contains(new Coordinate(1.205, 103.85))).isFalse();
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-District-04: a point in a hole is not contained; a point on the edge is (covers)")
    void holeAndEdge() {
        assertThat(square.contains(new Coordinate(1.35, 103.85))).as("inside the hole").isFalse();
        assertThat(square.contains(new Coordinate(1.30, 103.85))).as("on the outer edge").isTrue();
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-District-05: a MultiPolygon contains points in each of its parts")
    void multiPolygon() {
        District islands = new District("IS", "ISLANDS", TWO_ISLANDS);

        assertThat(islands.contains(new Coordinate(1.205, 103.805))).isTrue();
        assertThat(islands.contains(new Coordinate(1.205, 103.905))).isTrue();
        assertThat(islands.contains(new Coordinate(1.205, 103.85))).isFalse();
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-District-06: a null point or a district without a boundary contains nothing")
    void nullInputs() {
        assertThat(square.contains(null)).isFalse();
        assertThat(new District("X", "NO BOUNDARY", null).contains(new Coordinate(1.32, 103.82))).isFalse();
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-District-07: a boundary that is not GeoJSON fails loudly with the district code")
    void invalidGeoJson() {
        District broken = new District("BR", "BROKEN", "{\"type\":\"Polygon\",\"coordinates\":\"oops\"}");

        assertThatThrownBy(() -> broken.contains(new Coordinate(1.32, 103.82)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BR");
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-District-08: the parsed boundary is reused (same answer on repeated calls)")
    void repeatedCalls() {
        for (int i = 0; i < 3; i++) {
            assertThat(square.contains(new Coordinate(1.32, 103.82))).isTrue();
        }
    }
}

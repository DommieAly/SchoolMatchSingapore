package sg.schoolmatch.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.school.District;

/** DistrictLocator: reads the planning-area GeoJSON and finds the planning area of a point (DC-04, DC-12). */
class DistrictLocatorTest {

    private static final Coordinate CATHOLIC_HIGH = new Coordinate(1.354525, 103.844901);
    private static final Coordinate AT_SEA = new Coordinate(1.25, 103.95);

    private final List<District> districts =
            DistrictLocator.parseFeatureCollection(ImportFixtures.text("planning-areas.geojson"));

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-DistrictLocator-01: the raw data.gov.sg file (PLN_AREA_C / PLN_AREA_N) becomes Districts")
    void parsesRawFile() {
        assertThat(districts).extracting(District::getPlanningAreaCode).containsExactly("BS", "JW", "TM");
        assertThat(districts).extracting(District::getPlanningAreaName)
                .containsExactly("BISHAN", "JURONG WEST", "TAMPINES");
        assertThat(districts.getFirst().getBoundaryGeoJson()).startsWith("{\"type\":\"Polygon\"");
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-DistrictLocator-02: a snapshot's own districts.geojson (planningAreaCode / Name) reads the same")
    void parsesSnapshotFile() {
        List<District> mini = DistrictLocator.parseFeatureCollection(
                new String(readMini(), java.nio.charset.StandardCharsets.UTF_8));

        assertThat(mini).extracting(District::getPlanningAreaCode).containsExactly("BS", "JW", "TM");
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-DistrictLocator-03: a point is placed in the planning area that contains it; at sea in none")
    void locate() {
        DistrictLocator locator = new DistrictLocator(districts);

        assertThat(locator.locate(CATHOLIC_HIGH)).map(District::getPlanningAreaName).contains("BISHAN");
        assertThat(locator.locate(AT_SEA)).isEmpty();
        assertThat(locator.locate(null)).isEmpty();
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-DistrictLocator-04: names are compared on letters only ('SENG KANG' = 'SENGKANG')")
    void sameArea() {
        DistrictLocator locator = new DistrictLocator(List.of(new District("SK", "SENGKANG", null)));

        assertThat(DistrictLocator.sameArea("SENG KANG", "SENGKANG")).isTrue();
        assertThat(DistrictLocator.sameArea("jurong west", "JURONG WEST")).isTrue();
        assertThat(DistrictLocator.sameArea("CENTRAL", "BISHAN")).isFalse();
        assertThat(DistrictLocator.sameArea(null, "BISHAN")).isFalse();
        assertThat(locator.byName("Seng Kang")).map(District::getPlanningAreaCode).contains("SK");
        assertThat(locator.byName("CENTRAL")).isEmpty();
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-DistrictLocator-05: a file without features or names is rejected with a clear message")
    void rejectsBadFile() {
        assertThatThrownBy(() -> DistrictLocator.parseFeatureCollection("{}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("features");
        assertThatThrownBy(() -> DistrictLocator.parseFeatureCollection(
                "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},"
                        + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[103.8,1.3]}}]}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PLN_AREA_C");
        assertThatThrownBy(() -> DistrictLocator.parseFeatureCollection("not json"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static byte[] readMini() {
        try (var in = DistrictLocatorTest.class.getResourceAsStream("/fixtures/snapshot-mini/districts.geojson")) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}

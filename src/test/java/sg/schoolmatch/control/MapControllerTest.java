package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import sg.schoolmatch.boundary.external.ExternalCallBudget;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.search.CurrentResultSet;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.support.TestSchools;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit test of the control class MapController (FR-MAP-01..07, FR-FACMAP-03/05, DC-16, spending limit §2.5).
 * {@code app.*} settings are bound exactly as Spring binds them; the budget and the data control are mocks.
 */
@ExtendWith(MockitoExtension.class)
class MapControllerTest {

    private static final String SQUARE = "{\"type\":\"Polygon\",\"coordinates\":"
            + "[[[103.83,1.34],[103.86,1.34],[103.86,1.37],[103.83,1.37],[103.83,1.34]]]}";

    @Mock
    private SchoolDataController schoolDataController;

    @Mock
    private ExternalCallBudget budget;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final School bishan = TestSchools.school("catholic-high-school").build();
    private final School noLocation = TestSchools.school("no-location-school").noCoordinate().build();
    private final School abroad = TestSchools.school("abroad-school").at(TestSchools.OUTSIDE_SINGAPORE).build();

    // ---- showSchoolsOnMap / showFacilitiesOnMap -------------------------------------------------------------

    @Test
    @Tag("FR-MAP-03")
    @Tag("NFR-DATA-02")
    @DisplayName("TC-MapController-01: only schools with a valid coordinate get a marker, in result order")
    void showSchoolsOnMap_validCoordinatesOnly() {
        School tampines = TestSchools.school("tampines-secondary-school").at(TestSchools.TAMPINES).build();
        CurrentResultSet results = new CurrentResultSet(null, List.of(bishan, noLocation, abroad, tampines));

        assertThat(controller(Map.of()).showSchoolsOnMap(results)).containsExactly(bishan, tampines);
    }

    @Test
    @Tag("FR-FACMAP-03")
    @Tag("FR-FACMAP-05")
    @DisplayName("TC-MapController-02: facilities without a valid coordinate are left out without blocking the others")
    void showFacilitiesOnMap_validCoordinatesOnly() {
        Facility library = facility("lib-1", TestSchools.BISHAN);
        Facility nowhere = facility("lib-2", null);

        assertThat(controller(Map.of()).showFacilitiesOnMap(List.of(nowhere, library))).containsExactly(library);
    }

    // ---- toggleDistricts --------------------------------------------------------------------------------------

    @Test
    @Tag("FR-MAP-06")
    @Tag("FR-MAP-07")
    @DisplayName("TC-MapController-03: hiding the districts gives an empty string and reads no data")
    void toggleDistricts_hidden() {
        assertThat(controller(Map.of()).toggleDistricts(false)).isEmpty();
        verifyNoInteractions(schoolDataController);
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-MapController-04: showing the districts gives a GeoJSON FeatureCollection with code and name")
    void toggleDistricts_visible() {
        when(schoolDataController.getDistricts()).thenReturn(List.of(
                new District("BS", "BISHAN", SQUARE),
                new District("TM", "TAMPINES", SQUARE)));

        JsonNode json = jsonMapper.readTree(controller(Map.of()).toggleDistricts(true));

        assertThat(json.get("type").asString()).isEqualTo("FeatureCollection");
        assertThat(json.get("features")).hasSize(2);
        JsonNode first = json.get("features").get(0);
        assertThat(first.get("type").asString()).isEqualTo("Feature");
        assertThat(first.get("properties").get("code").asString()).isEqualTo("BS");
        assertThat(first.get("properties").get("name").asString()).isEqualTo("BISHAN");
        assertThat(first.get("geometry")).isEqualTo(jsonMapper.readTree(SQUARE));
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-MapController-05: a district with a missing or broken boundary is left out; the others still show")
    void toggleDistricts_skipsBrokenBoundaries() {
        when(schoolDataController.getDistricts()).thenReturn(List.of(
                new District("XX", "NO BOUNDARY", null),
                new District("YY", "BROKEN", "{not json"),
                new District("ZZ", "NOT A GEOMETRY", "[1,2,3]"),
                new District("BS", "BISHAN", SQUARE)));

        JsonNode json = jsonMapper.readTree(controller(Map.of()).toggleDistricts(true));

        assertThat(json.get("features")).hasSize(1);
        assertThat(json.get("features").get(0).get("properties").get("code").asString()).isEqualTo("BS");
    }

    @Test
    @Tag("FR-MAP-06")
    @DisplayName("TC-MapController-06: no districts gives an empty FeatureCollection")
    void toggleDistricts_none() {
        when(schoolDataController.getDistricts()).thenReturn(List.of());

        JsonNode json = jsonMapper.readTree(controller(Map.of()).toggleDistricts(true));

        assertThat(json.get("type").asString()).isEqualTo("FeatureCollection");
        assertThat(json.get("features")).isEmpty();
    }

    // ---- displayInteractiveMap --------------------------------------------------------------------------------

    @Test
    @Tag("FR-MAP-02")
    @DisplayName("TC-MapController-07: no browser key → no interactive map, nothing charged")
    void displayInteractiveMap_noKey() {
        assertThat(controller(Map.of()).displayInteractiveMap(List.of(bishan))).isFalse();
        verifyNoInteractions(budget);
    }

    @Test
    @Tag("FR-MAP-03")
    @DisplayName("TC-MapController-08: no place with a valid coordinate → no interactive map, nothing charged")
    void displayInteractiveMap_noValidPlace() {
        MapController controller = controller(Map.of("app.google.browser-key", "test-key",
                "app.external.google.mode", "live"));

        assertThat(controller.displayInteractiveMap(List.of(noLocation, abroad))).isFalse();
        assertThat(controller.displayInteractiveMap(List.of())).isFalse();
        assertThat(controller.displayInteractiveMap(null)).isFalse();
        verifyNoInteractions(budget);
    }

    @Test
    @Tag("FR-MAP-02")
    @DisplayName("TC-MapController-09: stub mode with a browser key shows the map and charges nothing")
    void displayInteractiveMap_stubMode() {
        MapController controller = controller(Map.of("app.google.browser-key", "test-key"));

        assertThat(controller.displayInteractiveMap(List.of(noLocation, bishan))).isTrue();
        verify(budget, never()).charge(anyString(), anyInt());
    }

    @Test
    @Tag("FR-MAP-02")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-MapController-10: live mode with a browser key charges one map load")
    void displayInteractiveMap_liveCharges() {
        MapController controller = controller(Map.of("app.google.browser-key", "test-key",
                "app.external.google.mode", "live"));

        assertThat(controller.displayInteractiveMap(List.of(bishan))).isTrue();
        verify(budget).charge(ExternalCallBudget.MAP_LOADS, 1);
    }

    @Test
    @Tag("FR-MAP-02")
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-MapController-11: when today's map-load limit is used up the page gets no map (list only)")
    void displayInteractiveMap_budgetExhausted() {
        doThrow(new ExternalServiceUnavailableException("Google map-loads", null))
                .when(budget).charge(ExternalCallBudget.MAP_LOADS, 1);
        MapController controller = controller(Map.of("app.google.browser-key", "test-key",
                "app.external.google.mode", "live"));

        assertThat(controller.displayInteractiveMap(List.of(bishan))).isFalse();
    }

    private MapController controller(Map<String, String> settings) {
        AppProperties props = new Binder(new MapConfigurationPropertySource(settings))
                .bindOrCreate("app", AppProperties.class);
        return new MapController(schoolDataController, props, budget, jsonMapper);
    }

    private static Facility facility(String placeId, sg.schoolmatch.entity.common.Coordinate at) {
        Facility facility = new Facility(placeId, "Library " + placeId, FacilityType.LIBRARY);
        facility.setCoordinate(at);
        return facility;
    }
}

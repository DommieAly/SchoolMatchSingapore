package sg.schoolmatch.boundary.external.stub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.RouteStep;
import sg.schoolmatch.entity.route.TravelMode;

/** The offline Google stub: deterministic routes with steps and a line to draw, and "(stub)" facilities. */
class StubGoogleMapsPlatformTest {

    private static final Coordinate BISHAN = new Coordinate(1.3510, 103.8484);
    private static final Coordinate TOA_PAYOH = new Coordinate(1.3327, 103.8478);

    private final StubGoogleMapsPlatform stub = new StubGoogleMapsPlatform();

    @Test
    @Tag("FR-ROUTE-07")
    @DisplayName("TC-StubGoogle-01: a stub route has 3 numbered steps that add up to the distance, and a polyline")
    void route_hasThreeStepsAndPolyline() {
        Route route = stub.computeRoute(BISHAN, TOA_PAYOH, TravelMode.WALK);

        assertThat(route.isAvailable()).isTrue();
        assertThat(route.getSteps()).extracting(RouteStep::getSequenceNo).containsExactly(1, 2, 3);
        assertThat(route.getSteps()).allMatch(s -> s.getInstruction().startsWith("(stub)"));
        assertThat(route.getSteps().stream().mapToInt(RouteStep::getDistanceMetres).sum())
                .isEqualTo(route.getDistanceMetres());
        assertThat(route.getEncodedPolyline()).isEqualTo(StubGoogleMapsPlatform.encodePolyline(List.of(BISHAN, TOA_PAYOH)));
        assertThat(route.getDurationSeconds()).isPositive();
        assertThat(stub.computeRoute(BISHAN, TOA_PAYOH, TravelMode.WALK).getDurationSeconds())
                .as("deterministic").isEqualTo(route.getDurationSeconds());
    }

    @Test
    @Tag("FR-ROUTE-07")
    @DisplayName("TC-StubGoogle-02: the polyline uses Google's algorithm (the documented example)")
    void polyline_matchesGoogleExample() {
        // Example from Google's "Encoded Polyline Algorithm Format" page
        String encoded = StubGoogleMapsPlatform.encodePolyline(List.of(
                new Coordinate(38.5, -120.2), new Coordinate(40.7, -120.95), new Coordinate(43.252, -126.453)));

        assertThat(encoded).isEqualTo("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
    }

    @Test
    @Tag("FR-ROUTE-04")
    @DisplayName("TC-StubGoogle-03: walking is slower than public transport, which is slower than driving")
    void speedsByMode() {
        int walk = stub.computeRoute(BISHAN, TOA_PAYOH, TravelMode.WALK).getDurationSeconds();
        int transit = stub.computeRoute(BISHAN, TOA_PAYOH, TravelMode.TRANSIT).getDurationSeconds();
        int drive = stub.computeRoute(BISHAN, TOA_PAYOH, TravelMode.DRIVE).getDurationSeconds();

        assertThat(walk).isGreaterThan(transit);
        assertThat(transit).isGreaterThan(drive);
    }

    @Test
    @Tag("FR-FACDETAIL-02")
    @DisplayName("TC-StubGoogle-04: stub facilities are labelled (stub), and their details work from the id alone")
    void facilities_labelledAndDetailsFromId() {
        List<Facility> libraries = stub.searchPlaces(FacilityType.LIBRARY, BISHAN);

        assertThat(libraries).hasSize(2).allMatch(f -> f.getName().startsWith("(stub)"));
        Facility first = libraries.getFirst();
        assertThat(stub.getPlaceDetails(first.getPlaceId())).get()
                .satisfies(f -> {
                    assertThat(f.getName()).isEqualTo(first.getName());
                    assertThat(f.getFacilityType()).isEqualTo(FacilityType.LIBRARY);
                    assertThat(f.getCoordinate()).isEqualTo(first.getCoordinate());
                });
        assertThat(stub.getPlaceDetails("ChIJ-not-a-stub-id")).isEmpty();
    }
}

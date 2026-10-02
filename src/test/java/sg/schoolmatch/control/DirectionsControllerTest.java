package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import sg.schoolmatch.boundary.external.GoogleMapsPlatformInterface;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.common.Place;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.RouteStep;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.support.TestSchools;

/**
 * Unit test of the control class DirectionsController (use cases Get Directions, Calculate Route;
 * FR-ROUTE-01..08, DC-05). Google is a Mockito mock; the black-box cases come from
 * {@code testcases/TC-ROUTE.csv}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DirectionsControllerTest {

    private static final ReferenceLocation HOME = new ReferenceLocation(TestSchools.BISHAN,
            LocationSource.MANUAL_ENTRY, "Bishan");

    @Mock
    private GoogleMapsPlatformInterface googleMaps;

    private DirectionsController directionsController;

    private final School tampines = TestSchools.school("tampines-secondary-school").at(TestSchools.TAMPINES).build();

    @BeforeEach
    void setUp() {
        directionsController = new DirectionsController(googleMaps);
    }

    /** One run per row of TC-ROUTE.csv: {@code expected} is available, unavailable or error:&lt;field|service&gt;. */
    @ParameterizedTest(name = "{0}: {2}")
    @CsvFileSource(resources = "/testcases/TC-ROUTE.csv", numLinesToSkip = 1)
    @Tag("FR-ROUTE-01")
    @Tag("FR-ROUTE-02")
    @Tag("FR-ROUTE-03")
    @Tag("FR-ROUTE-04")
    @Tag("FR-ROUTE-05")
    @Tag("FR-ROUTE-06")
    @Tag("FR-ROUTE-08")
    @DisplayName("TC-ROUTE-xx-yy: route cases from TC-ROUTE.csv")
    void getDirections_followsTheTestCaseTable(String tcId, String requirement, String technique,
                                               String input, String expected) {
        ReferenceLocation origin = switch (input) {
            case "no-origin" -> null;
            case "origin-outside" -> new ReferenceLocation(TestSchools.OUTSIDE_SINGAPORE, LocationSource.MANUAL_ENTRY,
                    "Somewhere");
            default -> HOME;
        };
        Place destination = input.equals("destination-no-coordinate")
                ? TestSchools.school("no-location-school").noCoordinate().build() : tampines;
        TravelMode mode = input.equals("no-mode") ? null : TravelMode.TRANSIT;
        if (input.equals("google-down")) {
            when(googleMaps.computeRoute(any(), any(), any()))
                    .thenThrow(new ExternalServiceUnavailableException("Google Routes", null));
        } else {
            when(googleMaps.computeRoute(any(), any(), any())).thenReturn(routeFor(input));
        }

        if (expected.startsWith("error:")) {
            String field = expected.substring("error:".length());
            if (field.equals("service")) {
                assertThatThrownBy(() -> directionsController.getDirections(origin, destination, mode))
                        .isInstanceOf(ExternalServiceUnavailableException.class);
            } else {
                assertThatThrownBy(() -> directionsController.getDirections(origin, destination, mode))
                        .isInstanceOf(InvalidInputException.class)
                        .satisfies(e -> assertThat(((InvalidInputException) e).getFieldErrors()).containsKey(field));
                verify(googleMaps, never()).computeRoute(any(), any(), any());   // invalid input never reaches Google
            }
            return;
        }
        Route route = directionsController.getDirections(origin, destination, mode);
        assertThat(route.isAvailable()).isEqualTo(expected.equals("available"));
        assertThat(route.getTravelMode()).isEqualTo(TravelMode.TRANSIT);
        assertThat(route.getOrigin()).isSameAs(HOME);
        assertThat(route.getDestination()).isSameAs(destination);
        if (!route.isAvailable()) {
            assertThat(route.getSteps()).as("no partial route (FR-ROUTE-08)").isEmpty();
            assertThat(route.getDistanceMetres()).isNull();
        }
    }

    @Test
    @Tag("FR-ROUTE-05")
    @DisplayName("TC-Directions-01: the request goes from the starting point's coordinate to the destination's")
    void getDirections_usesCoordinates() {
        when(googleMaps.computeRoute(TestSchools.BISHAN, TestSchools.TAMPINES, TravelMode.WALK))
                .thenReturn(routeFor("valid"));

        Route route = directionsController.getDirections(HOME, tampines, TravelMode.WALK);

        assertThat(route.getSteps()).hasSize(3);
        verify(googleMaps).computeRoute(TestSchools.BISHAN, TestSchools.TAMPINES, TravelMode.WALK);
    }

    @Test
    @Tag("FR-ROUTE-06")
    @DisplayName("TC-Directions-02: a shared (cached) Route from Google is never changed; the result is a copy")
    void getDirections_doesNotChangeSharedRoute() {
        Route shared = routeFor("valid");
        when(googleMaps.computeRoute(any(), any(), any())).thenReturn(shared);

        Route result = directionsController.getDirections(HOME, tampines, TravelMode.TRANSIT);

        assertThat(result).isNotSameAs(shared);
        assertThat(shared.getOrigin()).isNull();
        assertThat(shared.getDestination()).isNull();
        assertThat(result.getEncodedPolyline()).isEqualTo(shared.getEncodedPolyline());
    }

    @Test
    @Tag("FR-FILTER-06")
    @Tag("FR-REC-01")
    @DisplayName("TC-Directions-03: commute times come back in input order; places without a coordinate are not sent")
    void getCommuteTimes_alignedWithInput() {
        School near = TestSchools.school("near").at(1.355, 103.85).build();
        School noLocation = TestSchools.school("no-location").noCoordinate().build();
        School far = TestSchools.school("far").at(TestSchools.TAMPINES).build();
        when(googleMaps.computeRouteMatrix(TestSchools.BISHAN, List.of(near.getCoordinate(), far.getCoordinate()),
                TravelMode.TRANSIT)).thenReturn(List.of(matrixRoute(600), Route.unavailable(TravelMode.TRANSIT)));

        List<Route> routes = directionsController.getCommuteTimes(HOME, List.of(near, noLocation, far),
                TravelMode.TRANSIT);

        assertThat(routes).hasSize(3);
        assertThat(routes.get(0).getDurationSeconds()).isEqualTo(600);
        assertThat(routes.get(1).isAvailable()).as("no coordinate").isFalse();
        assertThat(routes.get(2).isAvailable()).as("no route").isFalse();
        assertThat(routes).extracting(Route::getDestination).containsExactly(near, noLocation, far);
        assertThat(routes).allMatch(r -> r.getOrigin() == HOME);
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-Directions-04: no destinations → empty list, no call; only places without coordinates → no call")
    void getCommuteTimes_nothingToAsk() {
        assertThat(directionsController.getCommuteTimes(HOME, List.of(), TravelMode.WALK)).isEmpty();
        School noLocation = TestSchools.school("no-location").noCoordinate().build();
        assertThat(directionsController.getCommuteTimes(HOME, Arrays.asList(noLocation, null), TravelMode.WALK))
                .hasSize(2).noneMatch(Route::isAvailable);
        verify(googleMaps, never()).computeRouteMatrix(any(), anyList(), any());
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-Directions-05: commute times need a starting point in Singapore and a travel mode")
    void getCommuteTimes_invalidInput() {
        assertThatThrownBy(() -> directionsController.getCommuteTimes(null, List.of(tampines), TravelMode.WALK))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> directionsController.getCommuteTimes(HOME, List.of(tampines), null))
                .isInstanceOf(InvalidInputException.class);
        verifyNoInteractions(googleMaps);
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-Directions-06: Google down during the matrix → ExternalServiceUnavailableException for the caller")
    void getCommuteTimes_googleDown() {
        when(googleMaps.computeRouteMatrix(any(), anyList(), any()))
                .thenThrow(new ExternalServiceUnavailableException("Google Routes", null));

        assertThatThrownBy(() -> directionsController.getCommuteTimes(HOME, List.of(tampines), TravelMode.DRIVE))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    /** The Route Google would return for each input name of TC-ROUTE.csv. */
    private static Route routeFor(String input) {
        List<RouteStep> threeSteps = List.of(new RouteStep(1, "Walk to Bishan Int", 250),
                new RouteStep(2, "Bus towards Tampines Int (69)", 10_000), new RouteStep(3, "Walk to school", 300));
        return switch (input) {
            case "no-route" -> Route.unavailable(TravelMode.TRANSIT);
            case "distance-0" -> new Route(TravelMode.TRANSIT, 0, 1500, "abc", threeSteps);
            case "distance-1" -> new Route(TravelMode.TRANSIT, 1, 1500, "abc", threeSteps);
            case "duration-0" -> new Route(TravelMode.TRANSIT, 10_550, 0, "abc", threeSteps);
            case "duration-1" -> new Route(TravelMode.TRANSIT, 10_550, 1, "abc", threeSteps);
            case "no-steps" -> new Route(TravelMode.TRANSIT, 10_550, 1500, "abc", List.of());
            case "step-gap" -> new Route(TravelMode.TRANSIT, 10_550, 1500, "abc",
                    List.of(new RouteStep(1, "Walk", 250), new RouteStep(3, "Bus", 10_300)));
            case "blank-step" -> new Route(TravelMode.TRANSIT, 10_550, 1500, "abc",
                    List.of(new RouteStep(1, "Walk", 250), new RouteStep(2, " ", 10_300)));
            default -> new Route(TravelMode.TRANSIT, 10_550, 1500, "abc", threeSteps);
        };
    }

    private static Route matrixRoute(int seconds) {
        return new Route(TravelMode.TRANSIT, 5000, seconds, null, List.of());
    }
}

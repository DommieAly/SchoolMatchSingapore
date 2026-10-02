package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.common.Place;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.school.SchoolDataCache;
import sg.schoolmatch.entity.search.AttributeCategory;
import sg.schoolmatch.entity.search.CurrentResultSet;
import sg.schoolmatch.entity.search.Filter;
import sg.schoolmatch.entity.search.ProximityFilter;
import sg.schoolmatch.entity.search.PsleScoreFilter;
import sg.schoolmatch.entity.search.SchoolAttributeFilter;
import sg.schoolmatch.entity.search.SortOrder;
import sg.schoolmatch.entity.search.TransportationFilter;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.support.TestSchools;

/**
 * Unit test of the control class FilterController (Mockito, no Spring): validation (AF-2), AND/OR through
 * CurrentResultSet, and the travel-time filter (prefilter by speed, cap, route failure → EX-1).
 */
@ExtendWith(MockitoExtension.class)
class FilterControllerTest {

    @Mock
    private LocationController locationController;

    @Mock
    private DirectionsController directionsController;

    @Mock
    private SchoolDataController schoolDataController;

    /** The default settings (app.transport-filter.*: WALK 6 / TRANSIT 45 / DRIVE 80 km/h, at most 150 schools). */
    private static final AppProperties PROPS =
            new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("app", AppProperties.class);

    private FilterController filterController;

    private final ReferenceLocation bishan =
            new ReferenceLocation(TestSchools.BISHAN, LocationSource.MANUAL_ENTRY, "BISHAN MRT");

    private final School catholic = TestSchools.school("catholic-high-school").name("CATHOLIC HIGH SCHOOL")
            .type("GOVERNMENT-AIDED SCH").planningArea("BISHAN").ccas("BOWLING").at(1.354525, 103.844901)
            .range(2025, 3, 6, 9).build();
    private final School peirce = TestSchools.school("peirce-secondary-school").name("PEIRCE SECONDARY SCHOOL")
            .type("GOVERNMENT SCHOOL").planningArea("BISHAN").ccas("RUGBY").at(1.366659, 103.830216)
            .range(2025, 3, 18, 21).build();
    private final School tampines = TestSchools.school("tampines-secondary-school").name("TAMPINES SECONDARY SCHOOL")
            .type("GOVERNMENT SCHOOL").planningArea("TAMPINES").ccas("CHOIR").at(1.349261, 103.944264)
            .range(2025, 3, 22, 25).build();
    private final School westwood = TestSchools.school("westwood-secondary-school").name("WESTWOOD SECONDARY SCHOOL")
            .type("GOVERNMENT SCHOOL").planningArea("JURONG WEST").ccas("BOWLING").at(1.353737, 103.701772).build();
    private final School noCoordinate = TestSchools.school("no-coordinate-school").name("NO COORDINATE SCHOOL")
            .type("GOVERNMENT SCHOOL").planningArea("BISHAN").noCoordinate().build();

    @BeforeEach
    void setUp() {
        filterController = new FilterController(locationController, directionsController, schoolDataController, PROPS);
        // Only the attribute-value check reads the dataset.
        lenient().when(schoolDataController.getActiveDataset()).thenReturn(dataset(
                catholic, peirce, tampines, westwood, noCoordinate));
    }

    private CurrentResultSet allSchools() {
        return new CurrentResultSet(null, List.of(catholic, noCoordinate, peirce, tampines, westwood));
    }

    @Test
    @Tag("FR-FILTER-07")
    @DisplayName("TC-FilterController-01: applyFilters returns a new filtered set (AND across, OR within) and leaves the input as it was")
    void applyFilters_andOr() {
        CurrentResultSet input = allSchools();
        input.setReferenceLocation(bishan);

        CurrentResultSet filtered = filterController.applyFilters(input, List.of(
                new SchoolAttributeFilter(AttributeCategory.CCA, List.of("BOWLING", "RUGBY")),
                new SchoolAttributeFilter(AttributeCategory.DISTRICT, List.of("BISHAN"))));

        assertThat(filtered.getSchools()).containsExactly(catholic, peirce);
        assertThat(filtered.getActiveFilters()).hasSize(2);
        assertThat(filtered.getReferenceLocation()).isSameAs(bishan);
        assertThat(input.getSchools()).hasSize(5);
        assertThat(input.getActiveFilters()).isEmpty();
        verifyNoInteractions(directionsController);   // no travel filter → no routing call
    }

    @Test
    @Tag("FR-FILTER-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FilterController-02: a value not in the active dataset is an error on its field (AF-2)")
    void applyFilters_unknownValue() {
        List<Filter> filters = List.of(
                new SchoolAttributeFilter(AttributeCategory.CCA, List.of("BOWLING", "UNDERWATER HOCKEY")),
                new SchoolAttributeFilter(AttributeCategory.SCHOOL_TYPE, List.of("GOVERNMENT SCHOOL")));

        assertThatThrownBy(() -> filterController.applyFilters(allSchools(), filters))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(((InvalidInputException) e).getFieldErrors())
                        .containsExactly(entry("cca", "Unknown CCA: UNDERWATER HOCKEY")));
    }

    @Test
    @Tag("FR-FILTER-02")
    @DisplayName("TC-FilterController-03: dataset values are accepted ignoring case")
    void applyFilters_valueIgnoringCase() {
        CurrentResultSet filtered = filterController.applyFilters(allSchools(),
                List.of(new SchoolAttributeFilter(AttributeCategory.DISTRICT, List.of("tampines"))));

        assertThat(filtered.getSchools()).containsExactly(tampines);
    }

    @Test
    @Tag("FR-FILTER-03")
    @Tag("FR-FILTER-05")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FilterController-04: every invalid filter is reported, each on its own field, before anything runs")
    void applyFilters_invalidFilters() {
        ReferenceLocation nowhere = new ReferenceLocation(null, LocationSource.MANUAL_ENTRY, "nowhere");
        List<Filter> filters = List.of(
                new PsleScoreFilter(33, 3, null),
                new ProximityFilter(bishan, 2),
                new TransportationFilter(nowhere, TravelMode.WALK, 30),
                new TransportationFilter(bishan, TravelMode.WALK, 20));

        assertThatThrownBy(() -> filterController.applyFilters(allSchools(), filters))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(((InvalidInputException) e).getFieldErrors()).containsOnly(
                        entry("psle", "Enter a whole number from 4 to 32"),
                        entry("radiusKm", "Choose 1, 3 or 5 km"),
                        entry("location", "Set a starting point to use distance or travel-time filters"),
                        entry("maxMin", "Choose 15, 30, 45 or 60 minutes")));
        verifyNoInteractions(directionsController);
    }

    @Test
    @Tag("FR-FILTER-03")
    @DisplayName("TC-FilterController-05: posting group out of range is reported on field pg")
    void applyFilters_badPostingGroup() {
        assertThatThrownBy(() -> filterController.applyFilters(allSchools(), List.of(new PsleScoreFilter(12, 4, null))))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(((InvalidInputException) e).getFieldErrors())
                        .containsExactly(entry("pg", "Choose posting group 1, 2 or 3")));
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-FilterController-06: travel filter asks for commute times only for schools that pass the other filters and are reachable at top speed")
    void travel_prefilterAndApply() {
        // Walking 30 min at 6 km/h reaches at most 3 km: Catholic (0.6 km) and Peirce (2.7 km) qualify; Tampines
        // (10.7 km), Westwood (16 km) and the school without a coordinate do not.
        when(directionsController.getCommuteTimes(eq(bishan), anyList(), eq(TravelMode.WALK)))
                .thenReturn(List.of(route(TravelMode.WALK, 9 * 60), route(TravelMode.WALK, 31 * 60)));
        TransportationFilter walk30 = new TransportationFilter(bishan, TravelMode.WALK, 30);

        CurrentResultSet filtered = filterController.applyFilters(allSchools(), List.of(walk30));

        ArgumentCaptor<List<Place>> asked = listCaptor();
        verify(directionsController).getCommuteTimes(eq(bishan), asked.capture(), eq(TravelMode.WALK));
        assertThat(asked.getValue()).containsExactly(catholic, peirce);   // nearest first
        assertThat(filtered.getSchools()).containsExactly(catholic);
        assertThat(filtered.getCommuteMinutes())
                .containsOnly(entry("catholic-high-school", 9), entry("peirce-secondary-school", 31));
        assertThat(walk30.getCommuteMinutesBySchool()).containsKeys("catholic-high-school", "peirce-secondary-school");
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-FilterController-07: other filters run first; minutes are rounded up; unavailable routes are left out")
    void travel_afterOtherFilters() {
        when(directionsController.getCommuteTimes(eq(bishan), anyList(), eq(TravelMode.TRANSIT)))
                .thenReturn(List.of(Route.unavailable(TravelMode.TRANSIT), route(TravelMode.TRANSIT, 14 * 60 + 1)));
        List<Filter> filters = List.of(
                new SchoolAttributeFilter(AttributeCategory.SCHOOL_TYPE, List.of("GOVERNMENT SCHOOL")),
                new TransportationFilter(bishan, TravelMode.TRANSIT, 15));

        CurrentResultSet filtered = filterController.applyFilters(allSchools(), filters);

        ArgumentCaptor<List<Place>> asked = listCaptor();
        verify(directionsController).getCommuteTimes(eq(bishan), asked.capture(), eq(TravelMode.TRANSIT));
        // government schools within 45 km/h × 15 min = 11.25 km: Peirce (2.7 km), Tampines (10.7 km); not Westwood
        assertThat(asked.getValue()).containsExactly(peirce, tampines);
        assertThat(filtered.getCommuteMinutes()).containsOnly(entry("tampines-secondary-school", 15));   // 14 min 1 s
        assertThat(filtered.getSchools()).containsExactly(tampines);
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-FilterController-08: no school in reach → no routing call and no results")
    void travel_nothingInReach() {
        School far = TestSchools.school("far-school").at(1.44, 103.78).build();   // Woodlands, ~11 km

        CurrentResultSet filtered = filterController.applyFilters(new CurrentResultSet(null, List.of(far)),
                List.of(new TransportationFilter(bishan, TravelMode.WALK, 15)));

        assertThat(filtered.isEmpty()).isTrue();
        verifyNoInteractions(directionsController);
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-FilterController-09: at most 150 schools are sent to the routing service, nearest first")
    void travel_capAt150() {
        List<School> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(TestSchools.school(String.format("school-%03d", i))
                    .at(TestSchools.BISHAN.getLatitude() + i * 0.0001, TestSchools.BISHAN.getLongitude()).build());
        }
        when(directionsController.getCommuteTimes(eq(bishan), anyList(), eq(TravelMode.DRIVE)))
                .thenAnswer(call -> ((List<?>) call.getArgument(1)).stream().map(s -> route(TravelMode.DRIVE, 60)).toList());

        CurrentResultSet filtered = filterController.applyFilters(new CurrentResultSet(null, many),
                List.of(new TransportationFilter(bishan, TravelMode.DRIVE, 15)));

        ArgumentCaptor<List<Place>> asked = listCaptor();
        verify(directionsController).getCommuteTimes(eq(bishan), asked.capture(), eq(TravelMode.DRIVE));
        assertThat(asked.getValue()).hasSize(PROPS.transportFilter().maxRoutedSchools());
        assertThat(asked.getValue().subList(0, 2)).containsExactly(many.get(0), many.get(1));   // nearest first
        assertThat(filtered.size()).isEqualTo(150);
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-FilterController-10: a routing failure is passed on as ExternalServiceUnavailableException (EX-1)")
    void travel_routeFailure() {
        when(directionsController.getCommuteTimes(any(), anyList(), any()))
                .thenThrow(new ExternalServiceUnavailableException("Google Routes", null));

        assertThatThrownBy(() -> filterController.applyFilters(allSchools(),
                List.of(new TransportationFilter(bishan, TravelMode.DRIVE, 60))))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    @Test
    @Tag("FR-FILTER-09")
    @DisplayName("TC-FilterController-11: clearFilters returns the unfiltered set for the same term")
    void clearFilters() {
        CurrentResultSet filtered = filterController.applyFilters(new CurrentResultSet("s", List.of(catholic, peirce)),
                List.of(new SchoolAttributeFilter(AttributeCategory.CCA, List.of("RUGBY"))));
        assertThat(filtered.getSchools()).containsExactly(peirce);

        CurrentResultSet cleared = filterController.clearFilters(filtered);

        assertThat(cleared.getSchools()).containsExactly(catholic, peirce);
        assertThat(cleared.getActiveFilters()).isEmpty();
        assertThat(cleared.getSearchTerm()).isEqualTo("s");
        assertThat(filtered.getSchools()).containsExactly(peirce);
    }

    @Test
    @Tag("FR-FILTER-07")
    @DisplayName("TC-FilterController-12: no filters → every school of the search, in the search order")
    void noFilters() {
        CurrentResultSet filtered = filterController.applyFilters(allSchools(), List.of());

        assertThat(filtered.getSchools()).containsExactly(catholic, noCoordinate, peirce, tampines, westwood);
        assertThat(filtered.getSortOrder()).isEqualTo(SortOrder.NAME_ASC);
    }

    @Test
    @Tag("NFR-PERF-02")
    @DisplayName("TC-FilterController-13: filtering 5,000 schools with attribute, PSLE and distance filters takes under 2 s")
    void performance() {
        List<School> many = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            many.add(TestSchools.school(String.format("school-%04d", i)).type(i % 2 == 0 ? "GOVERNMENT SCHOOL" : "X")
                    .planningArea("BISHAN").ccas("BOWLING", "CHOIR").range(2025, 3, 8, 8 + i % 20)
                    .at(1.30 + (i % 100) * 0.001, 103.80 + (i / 100) * 0.001).build());
        }
        when(schoolDataController.getActiveDataset()).thenReturn(dataset(many.toArray(School[]::new)));
        List<Filter> filters = List.of(
                new SchoolAttributeFilter(AttributeCategory.SCHOOL_TYPE, List.of("GOVERNMENT SCHOOL")),
                new SchoolAttributeFilter(AttributeCategory.CCA, List.of("BOWLING", "CHOIR")),
                new PsleScoreFilter(20, 3, null),
                new ProximityFilter(bishan, 5));

        long start = System.nanoTime();
        CurrentResultSet filtered = filterController.applyFilters(new CurrentResultSet(null, many), filters);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(filtered.size()).isPositive();
        assertThat(took).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @Tag("FR-FILTER-02")
    @DisplayName("TC-FilterController-14: getFilterOptions lists the values in the active dataset")
    void filterOptions() {
        assertThat(filterController.getFilterOptions(AttributeCategory.DISTRICT))
                .containsExactly("BISHAN", "JURONG WEST", "TAMPINES");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static Route route(TravelMode mode, int seconds) {
        return new Route(mode, 1000, seconds, null, List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<Place>> listCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private static SchoolDataCache dataset(School... schools) {
        return new SchoolDataCache("test", Instant.EPOCH, Instant.MAX, List.of(schools), List.of());
    }
}

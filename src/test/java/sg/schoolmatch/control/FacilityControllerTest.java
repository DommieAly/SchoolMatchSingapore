package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import sg.schoolmatch.boundary.external.GoogleMapsPlatformInterface;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.facility.FacilityFilterCriteria;
import sg.schoolmatch.entity.facility.FacilityType;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotFoundException;
import sg.schoolmatch.support.FixedClock;
import sg.schoolmatch.support.TestSchools;

/**
 * Unit test of the control class FacilityController (use cases View Nearby Facilities, View Facility Details;
 * FR-FACILITY-01..06, FR-FACFILTER-01..04, FR-FACDETAIL-01..03, DC-17, DC-24). Google is a Mockito mock that
 * returns facilities at known distances north of the school; the black-box cases come from
 * {@code testcases/TC-FACILITY.csv}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FacilityControllerTest {

    /** Degrees of latitude per km (1 degree ≈ 111.2 km). */
    private static final double DEG_PER_KM = 1 / 111.195;

    @Mock
    private GoogleMapsPlatformInterface googleMaps;

    private final FixedClock clock = FixedClock.atDefault();
    private FacilityController facilityController;

    private final School school = TestSchools.school("catholic-high-school").build();   // at Bishan

    // Names are the expected values in TC-FACILITY.csv; numbers are km from the school.
    private final Facility l1 = facility("L1", FacilityType.LIBRARY, 0.5);
    private final Facility l2 = facility("L2", FacilityType.LIBRARY, 1.5);
    private final Facility l3 = facility("L3", FacilityType.LIBRARY, 2.5);
    private final Facility farLibrary = facility("FAR", FacilityType.LIBRARY, 3.5);
    private final Facility t1 = facility("T1", FacilityType.TUITION_CENTRE, 0.8);
    private final Facility t2 = facility("T2", FacilityType.TUITION_CENTRE, 2.2);

    @BeforeEach
    void setUp() {
        AppProperties props = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("app", AppProperties.class);   // cache-ttl 24h
        facilityController = new FacilityController(googleMaps, props, clock);
        when(googleMaps.searchPlaces(FacilityType.LIBRARY, school.getCoordinate()))
                .thenReturn(List.of(l3, farLibrary, l1, l2));
        when(googleMaps.searchPlaces(FacilityType.TUITION_CENTRE, school.getCoordinate()))
                .thenReturn(List.of(t2, t1));
    }

    /**
     * One run per row of TC-FACILITY.csv. Input {@code radius;types} (types: ALL, empty, or names joined by
     * '+'); expected {@code error} or the facility names nearest first, joined by '|'.
     */
    @ParameterizedTest(name = "{0}: {2}")
    @CsvFileSource(resources = "/testcases/TC-FACILITY.csv", numLinesToSkip = 1)
    @Tag("FR-FACILITY-03")
    @Tag("FR-FACILITY-05")
    @Tag("FR-FACFILTER-01")
    @Tag("FR-FACFILTER-02")
    @Tag("FR-FACFILTER-04")
    @DisplayName("TC-FACILITY/FACFILTER-xx-yy: facility filter cases from TC-FACILITY.csv")
    void filterFacilities_followsTheTestCaseTable(String tcId, String requirement, String technique,
                                                  String input, String expected) {
        String[] parts = input.split(";", -1);
        int radius = Integer.parseInt(parts[0]);
        Set<FacilityType> types = EnumSet.noneOf(FacilityType.class);
        if (parts[1].equals("ALL")) {
            types = EnumSet.allOf(FacilityType.class);
        } else if (!parts[1].isEmpty()) {
            Arrays.stream(parts[1].split("\\+")).map(FacilityType::valueOf).forEach(types::add);
        }
        FacilityFilterCriteria criteria = new FacilityFilterCriteria(types, radius);

        if (expected.equals("error")) {
            assertThatThrownBy(() -> facilityController.filterFacilities(school, criteria))
                    .isInstanceOf(InvalidInputException.class)
                    .satisfies(e -> assertThat(((InvalidInputException) e).getFieldErrors())
                            .containsEntry("radiusKm", "Choose 1, 2 or 3 km"));
            verifyNoInteractions(googleMaps);
        } else {
            List<Facility> result = facilityController.filterFacilities(school, criteria);
            assertThat(result).extracting(Facility::getName).containsExactly(expected.split("\\|"));
        }
    }

    @Test
    @Tag("FR-FACILITY-01")
    @Tag("FR-FACILITY-05")
    @DisplayName("TC-Facility-01: nearby = both types within 3 km of the school, nearest first")
    void getNearbyFacilities_bothTypesNearestFirst() {
        List<Facility> nearby = facilityController.getNearbyFacilities(school);

        assertThat(nearby).extracting(Facility::getName).containsExactly("L1", "T1", "L2", "T2", "L3");
        verify(googleMaps).searchPlaces(FacilityType.LIBRARY, school.getCoordinate());
        verify(googleMaps).searchPlaces(FacilityType.TUITION_CENTRE, school.getCoordinate());
    }

    @Test
    @Tag("FR-FACMAP-05")
    @DisplayName("TC-Facility-02: facilities without a valid coordinate are left out; a place found twice is listed once")
    void getNearbyFacilities_invalidCoordinatesAndDuplicates() {
        Facility noLocation = new Facility("no-location", "NOLOC", FacilityType.LIBRARY);
        Facility outside = new Facility("outside", "OUTSIDE", FacilityType.LIBRARY);
        outside.setCoordinate(TestSchools.OUTSIDE_SINGAPORE);
        Facility l1AsTuition = new Facility(l1.getPlaceId(), "L1 again", FacilityType.TUITION_CENTRE);
        l1AsTuition.setCoordinate(l1.getCoordinate());
        when(googleMaps.searchPlaces(FacilityType.LIBRARY, school.getCoordinate()))
                .thenReturn(Arrays.asList(noLocation, l1, outside, null));
        when(googleMaps.searchPlaces(FacilityType.TUITION_CENTRE, school.getCoordinate()))
                .thenReturn(List.of(l1AsTuition, t1));

        List<Facility> nearby = facilityController.getNearbyFacilities(school);

        assertThat(nearby).extracting(Facility::getName).containsExactly("L1", "T1");
        assertThat(nearby.getFirst().getFacilityType()).as("the first search wins").isEqualTo(FacilityType.LIBRARY);
    }

    @Test
    @Tag("FR-FACILITY-06")
    @DisplayName("TC-Facility-03: nothing nearby → empty list; a school without a coordinate → empty, no Google call")
    void getNearbyFacilities_emptyCases() {
        when(googleMaps.searchPlaces(any(), any())).thenReturn(List.of());
        assertThat(facilityController.getNearbyFacilities(school)).isEmpty();

        School noLocation = TestSchools.school("no-location-school").noCoordinate().build();
        assertThat(facilityController.getNearbyFacilities(noLocation)).isEmpty();
        assertThat(facilityController.filterFacilities(noLocation, new FacilityFilterCriteria(List.of(), 3)))
                .isEmpty();
        verify(googleMaps, times(2)).searchPlaces(any(), any());   // only for the first school
    }

    @Test
    @Tag("FR-DATA-04")
    @DisplayName("TC-Facility-04: results are cached per school for 24 h (DC-17), then asked for again")
    void cachePerSchoolFor24Hours() {
        facilityController.getNearbyFacilities(school);
        facilityController.filterFacilities(school, new FacilityFilterCriteria(List.of(FacilityType.LIBRARY), 1));
        clock.advance(Duration.ofHours(23).plusMinutes(59));
        facilityController.getNearbyFacilities(school);
        verify(googleMaps, times(1)).searchPlaces(FacilityType.LIBRARY, school.getCoordinate());

        clock.advance(Duration.ofMinutes(1));   // exactly 24 h later: expired
        facilityController.getNearbyFacilities(school);
        verify(googleMaps, times(2)).searchPlaces(FacilityType.LIBRARY, school.getCoordinate());
    }

    @Test
    @Tag("FR-FACILITY-01")
    @Tag("NFR-USE-03")
    @DisplayName("TC-Facility-05: Google down → ExternalServiceUnavailableException; nothing is cached")
    void googleDown() {
        when(googleMaps.searchPlaces(any(), any()))
                .thenThrow(new ExternalServiceUnavailableException("Google Places", null))
                .thenReturn(List.of(l1));

        assertThatThrownBy(() -> facilityController.getNearbyFacilities(school))
                .isInstanceOf(ExternalServiceUnavailableException.class);
        assertThat(facilityController.getNearbyFacilities(school)).extracting(Facility::getName).containsExactly("L1");
    }

    @Test
    @Tag("FR-FACDETAIL-01")
    @Tag("FR-FACDETAIL-02")
    @DisplayName("TC-Facility-06: details = the cached summary (with its type) completed with Google's details")
    void getFacilityDetails_mergesSummaryAndDetails() {
        facilityController.getNearbyFacilities(school);
        Facility details = new Facility(l1.getPlaceId(), "L1 (full name)", null);
        details.setTelephone("6332 3255");
        details.setWebsite("https://www.nlb.gov.sg/");
        details.setOpeningHours("Monday: 10:00 AM – 9:00 PM");
        when(googleMaps.getPlaceDetails(l1.getPlaceId())).thenReturn(Optional.of(details));

        Facility merged = facilityController.getFacilityDetails(l1.getPlaceId());

        assertThat(merged.getName()).isEqualTo("L1 (full name)");
        assertThat(merged.getFacilityType()).isEqualTo(FacilityType.LIBRARY);
        assertThat(merged.getCoordinate()).isEqualTo(l1.getCoordinate());
        assertThat(merged.getAddress()).isEqualTo(l1.getAddress());
        assertThat(merged.getTelephone()).isEqualTo("6332 3255");
        assertThat(merged.getOpeningHours()).isEqualTo("Monday: 10:00 AM – 9:00 PM");
        assertThat(l1.getTelephone()).as("the cached summary is not changed").isNull();
    }

    @Test
    @Tag("FR-FACDETAIL-03")
    @DisplayName("TC-Facility-07: details not known to Google → the cached summary; missing fields stay null")
    void getFacilityDetails_summaryOnly() {
        facilityController.getNearbyFacilities(school);
        when(googleMaps.getPlaceDetails(t1.getPlaceId())).thenReturn(Optional.empty());

        Facility merged = facilityController.getFacilityDetails(t1.getPlaceId());

        assertThat(merged.getName()).isEqualTo("T1");
        assertThat(merged.getFacilityType()).isEqualTo(FacilityType.TUITION_CENTRE);
        assertThat(merged.getTelephone()).isNull();
        assertThat(merged.getWebsite()).isNull();
    }

    @Test
    @Tag("FR-FACDETAIL-01")
    @DisplayName("TC-Facility-08: not cached (e.g. after a restart) but known to Google → details still shown")
    void getFacilityDetails_notCachedButKnown() {
        Facility details = new Facility("ChIJ-only-in-google", "Some Library", null);
        when(googleMaps.getPlaceDetails("ChIJ-only-in-google")).thenReturn(Optional.of(details));

        Facility merged = facilityController.getFacilityDetails("ChIJ-only-in-google");

        assertThat(merged.getName()).isEqualTo("Some Library");
        assertThat(merged.getFacilityType()).isNull();
    }

    @Test
    @Tag("FR-FACDETAIL-01")
    @DisplayName("TC-Facility-09: unknown or malformed place id → NotFoundException (404); a malformed id never reaches Google")
    void getFacilityDetails_notFound() {
        when(googleMaps.getPlaceDetails(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facilityController.getFacilityDetails("ChIJ-unknown"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> facilityController.getFacilityDetails("../etc/passwd"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> facilityController.getFacilityDetails(null))
                .isInstanceOf(NotFoundException.class);
        verify(googleMaps, never()).getPlaceDetails("../etc/passwd");
    }

    /** A facility {@code km} north of Bishan (the school's coordinate). */
    private static Facility facility(String name, FacilityType type, double km) {
        Facility f = new Facility("place-" + name, name, type);
        Coordinate bishan = TestSchools.BISHAN;
        f.setCoordinate(new Coordinate(bishan.getLatitude() + km * DEG_PER_KM, bishan.getLongitude()));
        f.setAddress(name + " address");
        return f;
    }
}

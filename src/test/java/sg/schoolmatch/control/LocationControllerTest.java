package sg.schoolmatch.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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
import sg.schoolmatch.boundary.external.OneMapHit;
import sg.schoolmatch.boundary.external.OneMapInterface;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.support.TestSchools;

/**
 * Unit test of the control class LocationController (FR-FILTER-04, FR-ROUTE-02, NFR-SEC-06, DC-11).
 * OneMap is a mock, so every hit list is written out here; the address-length cases come from
 * {@code testcases/TC-LOCATION.csv}.
 */
@ExtendWith(MockitoExtension.class)
class LocationControllerTest {

    @Mock
    private SchoolDataController schoolDataController;

    @Mock
    private OneMapInterface oneMap;

    private LocationController locationController;

    @BeforeEach
    void setUp() {
        locationController = new LocationController(schoolDataController, oneMap);
    }

    // ---- getDeviceLocation ---------------------------------------------------------------------------------

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-SEC-06")
    @DisplayName("TC-LocationController-01: a device position inside Singapore becomes a DEVICE_LOCATION starting point")
    void getDeviceLocation_insideSingapore() {
        ReferenceLocation location = locationController.getDeviceLocation(TestSchools.BISHAN);

        assertThat(location.getSource()).isEqualTo(LocationSource.DEVICE_LOCATION);
        assertThat(location.getCoordinate()).isEqualTo(TestSchools.BISHAN);
        assertThat(location.isResolved()).isTrue();
        assertThat(location.getInputText()).isEqualTo(LocationController.DEVICE_LABEL);
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-LocationController-02: a device position outside Singapore is refused with a field error")
    void getDeviceLocation_outsideSingapore() {
        assertThatThrownBy(() -> locationController.getDeviceLocation(TestSchools.OUTSIDE_SINGAPORE))
                .isInstanceOf(InvalidInputException.class)
                .extracting(e -> ((InvalidInputException) e).getFieldErrors())
                .isEqualTo(java.util.Map.of(LocationController.LOCATION, "That location is outside Singapore"));
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-LocationController-03: a missing device position is refused")
    void getDeviceLocation_null() {
        assertThatThrownBy(() -> locationController.getDeviceLocation(null))
                .isInstanceOf(InvalidInputException.class);
    }

    // ---- findCandidates: input checks (TC-LOCATION.csv) ----------------------------------------------------

    /**
     * {@code expected} is "error" (InvalidInputException on field {@code address}, OneMap not called) or "search"
     * (OneMap is asked with the trimmed text). In the input column, {@code a*200} is the letter a 200 times.
     */
    @ParameterizedTest(name = "{0}: {2} → {4}")
    @CsvFileSource(resources = "/testcases/TC-LOCATION.csv", numLinesToSkip = 1)
    @Tag("FR-ROUTE-02")
    @Tag("FR-FILTER-04")
    @Tag("NFR-USE-03")
    @DisplayName("TC-LOCATION: an address has 1–200 characters after trimming")
    void findCandidates_addressLength(String tcId, String requirement, String technique, String input,
                                      String expected) {
        String address = expand(input);
        if ("error".equals(expected)) {
            assertThatThrownBy(() -> locationController.findCandidates(address))
                    .isInstanceOf(InvalidInputException.class)
                    .extracting(e -> ((InvalidInputException) e).getFieldErrors().keySet())
                    .isEqualTo(java.util.Set.of(LocationController.ADDRESS));
            verifyNoInteractions(oneMap);
        } else {
            assertThat(locationController.findCandidates(address)).isEmpty();
            verify(oneMap).search(address.strip());
        }
    }

    // ---- findCandidates: hits -------------------------------------------------------------------------------

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-LocationController-04: no OneMap hit gives no candidate")
    void findCandidates_noHit() {
        when(oneMap.search("nowhere")).thenReturn(List.of());

        assertThat(locationController.findCandidates("nowhere")).isEmpty();
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("FR-FILTER-04")
    @DisplayName("TC-LocationController-05: one hit becomes one MANUAL_ENTRY candidate labelled with the OneMap address")
    void findCandidates_oneHit() {
        when(oneMap.search("579767")).thenReturn(List.of(
                hit("CATHOLIC HIGH SCHOOL", "9 BISHAN STREET 22 CATHOLIC HIGH SCHOOL SINGAPORE 579767",
                        1.354525170657562, 103.8449008048039)));

        List<ReferenceLocation> candidates = locationController.findCandidates("579767");

        assertThat(candidates).hasSize(1);
        ReferenceLocation only = candidates.getFirst();
        assertThat(only.getSource()).isEqualTo(LocationSource.MANUAL_ENTRY);
        assertThat(only.getInputText()).isEqualTo("9 BISHAN STREET 22 CATHOLIC HIGH SCHOOL SINGAPORE 579767");
        assertThat(only.getCoordinate()).isEqualTo(new Coordinate(1.354525170657562, 103.8449008048039));
        assertThat(only.isResolved()).isTrue();
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-LocationController-06: at most 5 candidates, in OneMap's order")
    void findCandidates_atMostFive() {
        List<OneMapHit> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(hit("PLACE " + i, "ADDRESS " + i, 1.30 + i * 0.01, 103.80));
        }
        when(oneMap.search("bishan")).thenReturn(seven);

        List<ReferenceLocation> candidates = locationController.findCandidates("bishan");

        assertThat(candidates).extracting(ReferenceLocation::getInputText)
                .containsExactly("ADDRESS 0", "ADDRESS 1", "ADDRESS 2", "ADDRESS 3", "ADDRESS 4");
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-LocationController-07: hits at the same point (rounded to 5 decimal places) count once; the first is kept")
    void findCandidates_dedupeByCoordinate() {
        when(oneMap.search("bishan towers")).thenReturn(List.of(
                hit("BISHAN TOWERS", "156A BISHAN STREET 11", 1.344730771, 103.856592094),
                hit("BISHAN TOWERS", "156A BISHAN STREET 11 (again)", 1.344730774, 103.856592091),   // same at 5 dp
                hit("BISHAN TOWERS", "156B BISHAN STREET 11", 1.344281358, 103.856990983)));

        List<ReferenceLocation> candidates = locationController.findCandidates("bishan towers");

        assertThat(candidates).extracting(ReferenceLocation::getInputText)
                .containsExactly("156A BISHAN STREET 11", "156B BISHAN STREET 11");
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-DATA-02")
    @DisplayName("TC-LocationController-08: hits outside Singapore or with impossible coordinates are left out")
    void findCandidates_skipsInvalidPoints() {
        when(oneMap.search("x")).thenReturn(List.of(
                hit("FAR AWAY", "FAR AWAY ROAD", 40.0, 100.0),
                hit("BROKEN", "BROKEN ROAD", 999.0, 103.8),
                hit("ZERO", "ZERO ROAD", 0.0, 0.0),
                hit("BISHAN MRT", "200 BISHAN ROAD", 1.350838, 103.848143)));

        assertThat(locationController.findCandidates("x")).extracting(ReferenceLocation::getInputText)
                .containsExactly("200 BISHAN ROAD");
    }

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-LocationController-09: a hit without an address is labelled with its building, else its search value")
    void findCandidates_labelFallback() {
        when(oneMap.search("park")).thenReturn(List.of(
                new OneMapHit("BISHAN PARK", "BISHAN - ANG MO KIO PARK", "NIL", "NIL", 1.3651, 103.8362),
                new OneMapHit("POND GARDENS", "NIL", " ", null, 1.3661, 103.8372)));

        assertThat(locationController.findCandidates("park")).extracting(ReferenceLocation::getInputText)
                .containsExactly("BISHAN - ANG MO KIO PARK", "POND GARDENS");
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-LocationController-10: OneMap being down is passed on as ExternalServiceUnavailableException")
    void findCandidates_oneMapDown() {
        when(oneMap.search("bishan")).thenThrow(new ExternalServiceUnavailableException("OneMap", null));

        assertThatThrownBy(() -> locationController.findCandidates("bishan"))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    // ---- resolveAddress -------------------------------------------------------------------------------------

    @Test
    @Tag("FR-ROUTE-02")
    @DisplayName("TC-LocationController-11: resolveAddress returns the only match")
    void resolveAddress_oneMatch() {
        when(oneMap.search("579767")).thenReturn(List.of(hit("CHS", "9 BISHAN STREET 22", 1.3545, 103.8449)));

        assertThat(locationController.resolveAddress("579767").getInputText()).isEqualTo("9 BISHAN STREET 22");
    }

    @Test
    @Tag("FR-ROUTE-02")
    @Tag("NFR-USE-03")
    @DisplayName("TC-LocationController-12: resolveAddress refuses no match and several matches with different messages")
    void resolveAddress_noneOrSeveral() {
        when(oneMap.search("nowhere")).thenReturn(List.of());
        when(oneMap.search("bishan")).thenReturn(List.of(
                hit("A", "A ROAD", 1.35, 103.84), hit("B", "B ROAD", 1.36, 103.85)));

        assertThatThrownBy(() -> locationController.resolveAddress("nowhere"))
                .isInstanceOf(InvalidInputException.class)
                .extracting(e -> ((InvalidInputException) e).getFieldErrors().get(LocationController.ADDRESS))
                .isEqualTo(LocationController.NO_MATCH_MESSAGE);
        assertThatThrownBy(() -> locationController.resolveAddress("bishan"))
                .isInstanceOf(InvalidInputException.class)
                .extracting(e -> ((InvalidInputException) e).getFieldErrors().get(LocationController.ADDRESS))
                .isEqualTo(LocationController.SEVERAL_MATCHES_MESSAGE);
    }

    private static OneMapHit hit(String building, String address, double latitude, double longitude) {
        return new OneMapHit(building, building, address, "000000", latitude, longitude);
    }

    /** CSV shorthand: "a*200" is the letter a repeated 200 times; any other value is used as it is. */
    private static String expand(String input) {
        if (input != null && input.matches(".\\*\\d+")) {
            return input.substring(0, 1).repeat(Integer.parseInt(input.substring(2)));
        }
        return input;
    }
}

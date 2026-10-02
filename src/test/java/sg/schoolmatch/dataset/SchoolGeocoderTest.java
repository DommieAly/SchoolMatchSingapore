package sg.schoolmatch.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.boundary.external.OneMapHit;
import sg.schoolmatch.boundary.external.OneMapInterface;
import sg.schoolmatch.dataset.CuratedCsvReader.GeocodeOverride;
import sg.schoolmatch.dataset.SchoolGeocoder.GeocodeResult;
import sg.schoolmatch.dataset.SchoolGeocoder.Outcome;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.error.ExternalServiceUnavailableException;

/** SchoolGeocoder: which OneMap hit gives a school's coordinate (DC-11, DC-12, FR-DATA-06). Fake OneMap, no network. */
class SchoolGeocoderTest {

    private final Map<String, List<OneMapHit>> answers = new HashMap<>();
    private final List<String> searched = new ArrayList<>();
    private final OneMapInterface oneMap = text -> {
        searched.add(text);
        if ("999999".equals(text)) {
            throw new ExternalServiceUnavailableException("OneMap", null);
        }
        return answers.getOrDefault(text, List.of());
    };

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-Geocoder-01: the hit whose BUILDING is the school's name is used, not the first hit")
    void matchesBuilding() {
        answers.put("579767", List.of(hit("COMMIT LEARNING SCHOOLHOUSE @ CATHOLIC HIGH SCHOOL (PRIMARY)", 1.354388, 103.84421),
                hit("CATHOLIC HIGH SCHOOL", 1.354525, 103.844901)));

        GeocodeResult result = geocoder().locate("catholic-high-school", "CATHOLIC HIGH SCHOOL", "579767");

        assertThat(result.outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(result.coordinate()).isEqualTo(new Coordinate(1.354525, 103.844901));
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-Geocoder-02: 'ST.' in the dataset matches 'SAINT' in OneMap's BUILDING")
    void saintMatchesSt() {
        answers.put("528986", List.of(hit("TAMPINES BLK 1", 1.35, 103.94), hit("SAINT HILDA'S SECONDARY SCHOOL", 1.3501, 103.9401)));

        GeocodeResult result = geocoder().locate("st-hildas-secondary-school", "ST. HILDA'S SECONDARY SCHOOL", "528986");

        assertThat(result.outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(result.coordinate()).isEqualTo(new Coordinate(1.3501, 103.9401));
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-Geocoder-12: 'GOVT' in the dataset matches 'GOVERNMENT' in OneMap's BUILDING")
    void govtMatchesGovernment() {
        answers.put("689809", List.of(hit("BLK 1 OTHER", 1.37, 103.76),
                hit("BUKIT PANJANG GOVERNMENT HIGH SCHOOL", 1.3790, 103.7660)));

        GeocodeResult result = geocoder().locate("x", "BUKIT PANJANG GOVT. HIGH SCHOOL", "689809");

        assertThat(result.outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(result.coordinate()).isEqualTo(new Coordinate(1.3790, 103.7660));
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-Geocoder-03: a BUILDING that contains the school's name (e.g. '(SECONDARY)') still matches")
    void buildingContainsName() {
        answers.put("111111", List.of(hit("OTHER BUILDING", 1.30, 103.80),
                hit("METHODIST GIRLS' SCHOOL (SECONDARY)", 1.33, 103.78)));

        GeocodeResult result = geocoder().locate("x", "METHODIST GIRLS' SCHOOL", "111111");

        assertThat(result.outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(result.coordinate()).isEqualTo(new Coordinate(1.33, 103.78));
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-Geocoder-04: with one hit and no name match, the only hit is used")
    void singleHit() {
        answers.put("222222", List.of(hit("BLK 5 SOME ROAD", 1.31, 103.81)));

        GeocodeResult result = geocoder().locate("x", "SOME SECONDARY SCHOOL", "222222");

        assertThat(result.outcome()).isEqualTo(Outcome.SINGLE_HIT);
        assertThat(result.coordinate()).isEqualTo(new Coordinate(1.31, 103.81));
        assertThat(result.isLocated()).isTrue();
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-Geocoder-05: several unnamed hits within 100 m of each other count as one place")
    void samePlace() {
        answers.put("333333", List.of(hit("A", 1.3100, 103.8100), hit("B", 1.3105, 103.8100)));   // about 56 m apart

        GeocodeResult result = geocoder().locate("x", "SOME SECONDARY SCHOOL", "333333");

        assertThat(result.outcome()).isEqualTo(Outcome.SAME_PLACE);
        assertThat(result.coordinate()).isEqualTo(new Coordinate(1.3100, 103.8100));
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-Geocoder-06: several unnamed hits far apart are ambiguous: no coordinate is guessed")
    void ambiguous() {
        answers.put("444444", List.of(hit("A", 1.31, 103.81), hit("B", 1.35, 103.85)));

        GeocodeResult result = geocoder().locate("x", "SOME SECONDARY SCHOOL", "444444");

        assertThat(result.outcome()).isEqualTo(Outcome.AMBIGUOUS);
        assertThat(result.coordinate()).isNull();
        assertThat(result.isLocated()).isFalse();
        assertThat(result.detail()).contains("2 hits").contains(CuratedCsvReader.GEOCODE_OVERRIDES);
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-Geocoder-07: no hit, a OneMap failure or a hit outside Singapore gives no coordinate")
    void failed() {
        answers.put("555555", List.of(hit("SOME SECONDARY SCHOOL", 40.0, 100.0)));

        assertThat(geocoder().locate("x", "SOME SECONDARY SCHOOL", "666666").outcome()).isEqualTo(Outcome.FAILED);
        answers.put("888888", List.of());
        searched.clear();
        assertThat(geocoder().locate("x", "SOME SECONDARY SCHOOL", "888888").outcome()).isEqualTo(Outcome.FAILED);
        assertThat(searched).as("no hits is an answer, not an error: no retry").hasSize(1);
        searched.clear();
        GeocodeResult down = geocoder().locate("x", "SOME SECONDARY SCHOOL", "999999");
        assertThat(down.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(down.detail()).contains("OneMap");
        assertThat(searched).as("an error is retried once").hasSize(2);
        GeocodeResult outside = geocoder().locate("x", "SOME SECONDARY SCHOOL", "555555");
        assertThat(outside.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(outside.coordinate()).isNull();
    }

    @Test
    @Tag("NFR-MAIN-02")
    @DisplayName("TC-Geocoder-11: a OneMap error is retried once before the school counts as failed")
    void retriesOnce() {
        int[] calls = {0};
        OneMapInterface flaky = text -> {
            if (calls[0]++ == 0) {
                throw new ExternalServiceUnavailableException("OneMap", null);
            }
            return List.of(hit("SOME SECONDARY SCHOOL", 1.31, 103.81));
        };

        GeocodeResult result = new SchoolGeocoder(flaky, List.of()).locate("x", "SOME SECONDARY SCHOOL", "777777");

        assertThat(result.outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(calls[0]).isEqualTo(2);
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-Geocoder-08: a geocode-overrides.csv row (postal or school code) wins and OneMap is not called")
    void override() {
        SchoolGeocoder geocoder = new SchoolGeocoder(oneMap, List.of(
                new GeocodeOverride("099138", null, 1.2763, 103.8239, "wrong block"),
                new GeocodeOverride(null, "hua-yi-secondary-school", 1.3478, 103.6979, "test")));

        GeocodeResult byPostal = geocoder.locate("chij-st-theresas-convent", "CHIJ ST. THERESA'S CONVENT", "99138");
        GeocodeResult byCode = geocoder.locate("hua-yi-secondary-school", "HUA YI SECONDARY SCHOOL", "649371");

        assertThat(byPostal.outcome()).isEqualTo(Outcome.OVERRIDE);
        assertThat(byPostal.coordinate()).isEqualTo(new Coordinate(1.2763, 103.8239));
        assertThat(byCode.coordinate()).isEqualTo(new Coordinate(1.3478, 103.6979));
        assertThat(searched).isEmpty();
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-Geocoder-09: a 5-digit postal code gets its leading zero back before the OneMap search")
    void postalCodePadding() {
        geocoder().locate("x", "CHIJ ST. THERESA'S CONVENT", "99138");

        assertThat(searched).containsExactly("099138");
        assertThat(SchoolGeocoder.normalisePostalCode(" 99138 ")).isEqualTo("099138");
        assertThat(SchoolGeocoder.normalisePostalCode("579767")).isEqualTo("579767");
        assertThat(SchoolGeocoder.normalisePostalCode("na")).isNull();
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-Geocoder-10: a school without a postal code is not searched")
    void noPostalCode() {
        GeocodeResult result = geocoder().locate("x", "SOME SECONDARY SCHOOL", null);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(searched).isEmpty();
    }

    private SchoolGeocoder geocoder() {
        return new SchoolGeocoder(oneMap, List.of());
    }

    private static OneMapHit hit(String building, double latitude, double longitude) {
        return new OneMapHit(building, building, "ADDRESS OF " + building, null, latitude, longitude);
    }
}

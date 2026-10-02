package sg.schoolmatch.boundary.ui.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.location.LocationSource;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.search.AttributeCategory;
import sg.schoolmatch.entity.search.Filter;
import sg.schoolmatch.entity.search.ProximityFilter;
import sg.schoolmatch.entity.search.PsleScoreFilter;
import sg.schoolmatch.entity.search.SchoolAttributeFilter;
import sg.schoolmatch.entity.search.SortOrder;
import sg.schoolmatch.entity.search.TransportationFilter;

/**
 * {@link FilterParams}: reading the search/filter URL, building filters with input errors (NFR-USE-03),
 * and writing the URL back (chips, "View on map"). Plain JUnit, no Spring.
 */
class FilterParamsTest {

    private static final ReferenceLocation BISHAN =
            new ReferenceLocation(new Coordinate(1.3500, 103.8480), LocationSource.MANUAL_ENTRY, "Bishan");
    private static final Optional<ReferenceLocation> NO_LOCATION = Optional.empty();

    // ---- from(request) ------------------------------------------------------------------------------------

    @Test
    @Tag("FR-FILTER-01")
    @DisplayName("TC-FilterParams-01: from() reads every parameter, trims text, drops blanks and sorts repeated values")
    void from_readsAllParameters() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/schools");
        request.addParameter("q", "  st hilda ");
        request.addParameter("type", "GOVERNMENT SCHOOL", " ", "AUTONOMOUS");
        request.addParameter("cca", "BASKETBALL");
        request.addParameter("psle", " 12 ");
        request.addParameter("pg", "");
        request.addParameter("radiusKm", "3");
        request.addParameter("mode", "transit");
        request.addParameter("maxMin", "30");
        request.addParameter("travel", "1");
        request.addParameter("sort", "distance_asc");
        request.addParameter("page", "4");

        FilterParams params = FilterParams.from(request);

        assertThat(params.q()).isEqualTo("st hilda");
        assertThat(params.types()).containsExactly("AUTONOMOUS", "GOVERNMENT SCHOOL");
        assertThat(params.programmes()).isEmpty();
        assertThat(params.ccas()).containsExactly("BASKETBALL");
        assertThat(params.psle()).isEqualTo("12");
        assertThat(params.pg()).isNull();
        assertThat(params.radiusKm()).isEqualTo("3");
        assertThat(params.travel()).isTrue();
        assertThat(params.travelMode()).contains(TravelMode.TRANSIT);
        assertThat(params.sortOrder()).contains(SortOrder.DISTANCE_ASC);
        assertThat(params.toQueryString()).doesNotContain("page");
    }

    @Test
    @Tag("FR-FILTER-01")
    @DisplayName("TC-FilterParams-02: from() never throws: no parameters gives empty params with no filter")
    void from_noParameters() {
        FilterParams params = FilterParams.from(new MockHttpServletRequest("GET", "/schools"));

        assertThat(params).isEqualTo(FilterParams.empty());
        assertThat(params.hasAnyFilter()).isFalse();
        assertThat(params.activeFilterKeys()).isEmpty();
        assertThat(params.toQueryString()).isEmpty();
        assertThat(params.sortOrder()).isEmpty();
    }

    @ParameterizedTest(name = "TC-FilterParams-03 [{index}]: travel={0}")
    @ValueSource(strings = {"0", "yes", "", "2"})
    @Tag("FR-FILTER-06")
    @DisplayName("TC-FilterParams-03: only travel=1 (or true) asks for the travel-time filter")
    void from_travelOnlyForOne(String travel) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/schools");
        request.addParameter("travel", travel);
        assertThat(FilterParams.from(request).travel()).isFalse();
    }

    // ---- toFilters: attributes and PSLE ----------------------------------------------------------------------

    @Test
    @Tag("FR-FILTER-07")
    @DisplayName("TC-FilterParams-04: one attribute filter per category holding all its values (OR inside, AND across)")
    void toFilters_oneFilterPerCategory() {
        FilterParams params = params().types("A", "B").ccas("BASKETBALL", "CHOIR").districts("BISHAN").build();
        Map<String, String> errors = new LinkedHashMap<>();

        List<Filter> filters = params.toFilters(NO_LOCATION, null, errors);

        assertThat(errors).isEmpty();
        assertThat(filters).hasSize(3).allMatch(f -> f instanceof SchoolAttributeFilter);
        SchoolAttributeFilter type = (SchoolAttributeFilter) filters.get(0);
        SchoolAttributeFilter cca = (SchoolAttributeFilter) filters.get(1);
        assertThat(type.getCategory()).isEqualTo(AttributeCategory.SCHOOL_TYPE);
        assertThat(type.getSelectedValues()).containsExactly("A", "B");
        assertThat(cca.getCategory()).isEqualTo(AttributeCategory.CCA);
        assertThat(cca.getSelectedValues()).containsExactly("BASKETBALL", "CHOIR");
        assertThat(((SchoolAttributeFilter) filters.get(2)).getCategory()).isEqualTo(AttributeCategory.DISTRICT);
    }

    @ParameterizedTest(name = "TC-FilterParams-05 [{index}]: psle={0}")
    @ValueSource(strings = {"3", "33", "abc", "12.5", "-4", "+12", "１２"})
    @Tag("FR-FILTER-03")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FilterParams-05: a PSLE score that is not a whole number from 4 to 32 is an error on field psle")
    void toFilters_badPsle(String psle) {
        Map<String, String> errors = new LinkedHashMap<>();

        List<Filter> filters = params().psle(psle).build().toFilters(NO_LOCATION, null, errors);

        assertThat(filters).isEmpty();
        assertThat(errors).containsExactly(entry("psle", "Enter a whole number from 4 to 32"));
    }

    @ParameterizedTest(name = "TC-FilterParams-06 [{index}]: psle={0}")
    @ValueSource(strings = {"4", "32"})
    @Tag("FR-FILTER-03")
    @DisplayName("TC-FilterParams-06: PSLE 4 and 32 are accepted; a blank posting group means PG3, with a hint")
    void toFilters_psleBoundariesAndDefaultPg(String psle) {
        FilterParams params = params().psle(psle).build();
        Map<String, String> errors = new LinkedHashMap<>();

        List<Filter> filters = params.toFilters(NO_LOCATION, "ROSYTH SCHOOL", errors);

        assertThat(errors).isEmpty();
        assertThat(filters).singleElement().isInstanceOf(PsleScoreFilter.class);
        PsleScoreFilter filter = (PsleScoreFilter) filters.getFirst();
        assertThat(filter.getScore()).isEqualTo(Integer.parseInt(psle));
        assertThat(filter.getPostingGroup()).isEqualTo(3);
        assertThat(filter.getPrimarySchool()).isEqualTo("ROSYTH SCHOOL");
        assertThat(params.usesDefaultPostingGroup()).isTrue();
        assertThat(FilterParams.DEFAULT_PG_HINT).contains("posting group 3");
    }

    @ParameterizedTest(name = "TC-FilterParams-07 [{index}]: pg={0}")
    @ValueSource(strings = {"0", "4", "x"})
    @Tag("FR-FILTER-03")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FilterParams-07: a posting group other than 1, 2 or 3 is an error on field pg")
    void toFilters_badPostingGroup(String pg) {
        Map<String, String> errors = new LinkedHashMap<>();

        List<Filter> filters = params().psle("12").pg(pg).build().toFilters(NO_LOCATION, null, errors);

        assertThat(filters).isEmpty();
        assertThat(errors).containsExactly(entry("pg", "Choose posting group 1, 2 or 3"));
    }

    // ---- toFilters: distance and travel time ------------------------------------------------------------------

    @Test
    @Tag("FR-FILTER-05")
    @DisplayName("TC-FilterParams-08: radius 3 with a starting point gives a ProximityFilter")
    void toFilters_radiusWithLocation() {
        Map<String, String> errors = new LinkedHashMap<>();

        List<Filter> filters = params().radiusKm("3").build().toFilters(Optional.of(BISHAN), null, errors);

        assertThat(errors).isEmpty();
        assertThat(filters).singleElement().isInstanceOf(ProximityFilter.class);
        assertThat(((ProximityFilter) filters.getFirst()).getRadiusKm()).isEqualTo(3);
        assertThat(((ProximityFilter) filters.getFirst()).getReferenceLocation()).isSameAs(BISHAN);
    }

    @ParameterizedTest(name = "TC-FilterParams-09 [{index}]: radiusKm={0}")
    @ValueSource(strings = {"0", "2", "4", "10", "km"})
    @Tag("FR-FILTER-05")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FilterParams-09: a radius other than 1, 3 or 5 km is an error on field radiusKm")
    void toFilters_badRadius(String radius) {
        Map<String, String> errors = new LinkedHashMap<>();

        List<Filter> filters = params().radiusKm(radius).build().toFilters(Optional.of(BISHAN), null, errors);

        assertThat(filters).isEmpty();
        assertThat(errors).containsExactly(entry("radiusKm", "Choose 1, 3 or 5 km"));
    }

    @Test
    @Tag("FR-FILTER-04")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FilterParams-10: distance or travel time without a starting point is left out with one location error")
    void toFilters_noLocation() {
        FilterParams params = params().types("A").radiusKm("1").mode("WALK").maxMin("15").travel().build();
        Map<String, String> errors = new LinkedHashMap<>();

        List<Filter> filters = params.toFilters(NO_LOCATION, null, errors);

        assertThat(filters).singleElement().isInstanceOf(SchoolAttributeFilter.class);
        assertThat(errors).containsExactly(
                entry("location", "Set a starting point to use distance or travel-time filters"));
    }

    @Test
    @Tag("FR-FILTER-04")
    @DisplayName("TC-FilterParams-11: a starting point outside Singapore counts as no starting point")
    void toFilters_unresolvedLocation() {
        ReferenceLocation london = new ReferenceLocation(new Coordinate(51.5, -0.12), LocationSource.DEVICE_LOCATION, "x");
        Map<String, String> errors = new LinkedHashMap<>();

        List<Filter> filters = params().radiusKm("5").build().toFilters(Optional.of(london), null, errors);

        assertThat(filters).isEmpty();
        assertThat(errors).containsOnlyKeys("location");
    }

    @Test
    @Tag("FR-FILTER-06")
    @DisplayName("TC-FilterParams-12: travel=1 with mode and time gives a TransportationFilter; without travel=1 it does not")
    void toFilters_travelOnlyWhenRequested() {
        FilterParams withoutClick = params().mode("drive").maxMin("45").build();
        FilterParams clicked = params().mode("drive").maxMin("45").travel().build();
        Map<String, String> errors = new LinkedHashMap<>();

        assertThat(withoutClick.toFilters(Optional.of(BISHAN), null, errors)).isEmpty();
        List<Filter> filters = clicked.toFilters(Optional.of(BISHAN), null, errors);

        assertThat(errors).isEmpty();
        assertThat(filters).singleElement().isInstanceOf(TransportationFilter.class);
        TransportationFilter filter = (TransportationFilter) filters.getFirst();
        assertThat(filter.getTravelMode()).isEqualTo(TravelMode.DRIVE);
        assertThat(filter.getMaxDurationMin()).isEqualTo(45);
        assertThat(withoutClick.hasAnyFilter()).isFalse();
        assertThat(clicked.activeFilterKeys()).containsExactly("travel");
    }

    @Test
    @Tag("FR-FILTER-06")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FilterParams-13: travel time 20 min and mode BIKE are errors; travel=1 without them asks for both")
    void toFilters_badTravelValues() {
        Map<String, String> errors = new LinkedHashMap<>();
        params().mode("BIKE").maxMin("20").travel().build().toFilters(Optional.of(BISHAN), null, errors);
        assertThat(errors).containsExactly(
                entry("mode", "Choose walk, drive or public transport"),
                entry("maxMin", "Choose 15, 30, 45 or 60 minutes"));

        Map<String, String> missing = new LinkedHashMap<>();
        params().travel().build().toFilters(Optional.of(BISHAN), null, missing);
        assertThat(missing).containsOnlyKeys("mode", "maxMin");
    }

    @Test
    @Tag("FR-FILTER-07")
    @Tag("NFR-USE-03")
    @DisplayName("TC-FilterParams-14: all errors are reported at once and the valid filters are still built")
    void toFilters_errorsAndValidFiltersTogether() {
        FilterParams params = params().types("A").psle("abc").radiusKm("2").build();
        Map<String, String> errors = new LinkedHashMap<>();

        List<Filter> filters = params.toFilters(Optional.of(BISHAN), null, errors);

        assertThat(filters).singleElement().isInstanceOf(SchoolAttributeFilter.class);
        assertThat(errors).containsOnlyKeys("psle", "radiusKm");
    }

    // ---- query strings -----------------------------------------------------------------------------------------

    @Test
    @Tag("FR-FILTER-08")
    @DisplayName("TC-FilterParams-15: toQueryString has a fixed order and does not depend on the order values arrived in")
    void toQueryString_stableOrder() {
        FilterParams a = params().q("bishan").types("B", "A").ccas("CHOIR").psle("12").pg("2").radiusKm("3")
                .mode("WALK").maxMin("30").travel().sort("NAME_ASC").build();
        FilterParams b = params().sort("NAME_ASC").travel().maxMin("30").mode("WALK").radiusKm("3").pg("2")
                .psle("12").ccas("CHOIR").types("A", "B").q("bishan").build();

        assertThat(a.toQueryString()).isEqualTo(b.toQueryString()).isEqualTo(
                "q=bishan&type=A&type=B&cca=CHOIR&psle=12&pg=2&radiusKm=3&mode=WALK&maxMin=30&travel=1&sort=NAME_ASC");
        assertThat(a.toUrl("/schools/map")).isEqualTo("/schools/map?" + a.toQueryString());
        assertThat(FilterParams.empty().toUrl("/schools")).isEqualTo("/schools");
    }

    @Test
    @Tag("FR-FILTER-08")
    @DisplayName("TC-FilterParams-16: '+', '&', '=', '%', space and non-ASCII are percent-encoded and survive a round trip")
    void toQueryString_encodesReservedCharacters() {
        FilterParams params = params().q("a+b & c=d 100%").ccas("ART & CRAFT", "C++ CLUB").districts("ANG MO KIO")
                .types("ÉCOLE").build();

        String query = params.toQueryString();

        assertThat(query).isEqualTo("q=a%2Bb%20%26%20c%3Dd%20100%25&type=%C3%89COLE"
                + "&cca=ART%20%26%20CRAFT&cca=C%2B%2B%20CLUB&district=ANG%20MO%20KIO");
        assertThat(roundTrip(query)).isEqualTo(params);
    }

    @Test
    @Tag("FR-FILTER-08")
    @DisplayName("TC-FilterParams-17: toQueryStringWithout removes one chip value and keeps the rest")
    void toQueryStringWithout_removesOneValue() {
        FilterParams params = params().q("x").ccas("ART & CRAFT", "CHOIR").psle("12").pg("2").radiusKm("3")
                .mode("WALK").maxMin("15").travel().sort("COMMUTE_ASC").build();

        assertThat(params.toQueryStringWithout("cca", "ART & CRAFT"))
                .isEqualTo("q=x&cca=CHOIR&psle=12&pg=2&radiusKm=3&mode=WALK&maxMin=15&travel=1&sort=COMMUTE_ASC");
        assertThat(params.toQueryStringWithout("psle", "12"))
                .isEqualTo("q=x&cca=ART%20%26%20CRAFT&cca=CHOIR&radiusKm=3&mode=WALK&maxMin=15&travel=1&sort=COMMUTE_ASC");
        assertThat(params.toQueryStringWithout("travel", "1"))
                .isEqualTo("q=x&cca=ART%20%26%20CRAFT&cca=CHOIR&psle=12&pg=2&radiusKm=3");
        assertThat(params.toQueryStringWithout("radiusKm", null)).doesNotContain("radiusKm");
        assertThat(params.toQueryStringWithout("unknown", "v")).isEqualTo(params.toQueryString());
    }

    @Test
    @Tag("FR-FILTER-08")
    @DisplayName("TC-FilterParams-18: chips list every active value with a label and its remove link")
    void chips_labelsAndRemoveLinks() {
        FilterParams params = params().q("x").types("GOVERNMENT SCHOOL").psle("12").radiusKm("3")
                .mode("TRANSIT").maxMin("30").travel().build();

        assertThat(params.activeFilterKeys()).containsExactly("type", "psle", "radiusKm", "travel");
        assertThat(params.chips()).extracting(FilterParams.FilterChip::label).containsExactly(
                "Type: GOVERNMENT SCHOOL", "PSLE 12 (PG3)", "Within 3 km", "Within 30 min by public transport");
        assertThat(params.chips().getFirst().removeQuery())
                .isEqualTo("q=x&psle=12&radiusKm=3&mode=TRANSIT&maxMin=30&travel=1");
    }

    @Test
    @Tag("FR-SEARCH-06")
    @DisplayName("TC-FilterParams-19: an unknown sort value gives no sort order (the page uses NAME_ASC)")
    void sortOrder_unknownIsEmpty() {
        assertThat(params().sort("BY_RANK").build().sortOrder()).isEmpty();
        assertThat(params().sort("commute_asc").build().sortOrder()).contains(SortOrder.COMMUTE_ASC);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** Parses a query string the way a request would (percent-decoding) and reads it back with from(). */
    private static FilterParams roundTrip(String query) {
        MultiValueMap<String, String> raw = UriComponentsBuilder.fromUriString("/schools?" + query).build()
                .getQueryParams();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/schools");
        raw.forEach((name, values) -> values.forEach(v ->
                request.addParameter(UriUtils.decode(name, StandardCharsets.UTF_8),
                        UriUtils.decode(v, StandardCharsets.UTF_8))));
        return FilterParams.from(request);
    }

    private static Builder params() {
        return new Builder();
    }

    /** Small test builder so each test names only the parameters it cares about. */
    private static final class Builder {
        private String q;
        private Set<String> types = Set.of();
        private Set<String> ccas = Set.of();
        private Set<String> districts = Set.of();
        private String psle;
        private String pg;
        private String radiusKm;
        private String mode;
        private String maxMin;
        private boolean travel;
        private String sort;

        Builder q(String v) {
            q = v;
            return this;
        }

        Builder types(String... v) {
            types = Set.of(v);
            return this;
        }

        Builder ccas(String... v) {
            ccas = Set.of(v);
            return this;
        }

        Builder districts(String... v) {
            districts = Set.of(v);
            return this;
        }

        Builder psle(String v) {
            psle = v;
            return this;
        }

        Builder pg(String v) {
            pg = v;
            return this;
        }

        Builder radiusKm(String v) {
            radiusKm = v;
            return this;
        }

        Builder mode(String v) {
            mode = v;
            return this;
        }

        Builder maxMin(String v) {
            maxMin = v;
            return this;
        }

        Builder travel() {
            travel = true;
            return this;
        }

        Builder sort(String v) {
            sort = v;
            return this;
        }

        FilterParams build() {
            return new FilterParams(q, types, Set.of(), ccas, districts, psle, pg, radiusKm, mode, maxMin, travel, sort);
        }
    }
}

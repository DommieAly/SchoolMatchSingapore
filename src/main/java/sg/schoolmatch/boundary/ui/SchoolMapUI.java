package sg.schoolmatch.boundary.ui;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import sg.schoolmatch.boundary.ui.support.FilterParams;
import sg.schoolmatch.boundary.ui.support.MapMarker;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.PsleAvailability;
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.boundary.ui.support.SearchFilterPipeline;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.control.FilterController;
import sg.schoolmatch.control.MapController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.search.CurrentResultSet;
import sg.schoolmatch.error.InvalidInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Design class «boundary» SchoolMapUI — the current results as map markers (DM-11 SchoolMap; use cases View
 * Schools on Map and View School's Districts, FR-MAP-01..07). DC-15/DC-37: the results are rebuilt from the same
 * URL parameters as the result list ({@link FilterParams}), so the markers always match the list. DC-16:
 * MapController picks the schools with valid coordinates and decides whether the interactive map can load;
 * the browser draws (static/js/map.js) and loads the district layer from {@code GET /api/districts}.
 * <p>
 * Without a browser key (stub mode), or when today's map limit is used up, the page shows "Map unavailable" and
 * the list beside it still works (EX-1). The travel-time filter runs only after the explicit {@code travel=1}.
 * <p>
 * Model: {@code q}, {@code chips}, {@code notAppliedChips}, {@code hiddenFields}, {@code resultCount}, {@code schools} (with a map location),
 * {@code omittedCount}, {@code interactive}, {@code markersJson}, {@code resultsUrl}, {@code filterUrl},
 * {@code startLocation}, {@code usesStartingPoint}, {@code psleActive}, {@code psleNotApplied} (DC-74), and
 * {@code fieldErrors} / {@code notice} when something went wrong.
 */
@Controller
public class SchoolMapUI {

    /** RFC 7946 media type of {@code GET /api/districts}. */
    static final MediaType GEO_JSON = new MediaType("application", "geo+json", StandardCharsets.UTF_8);

    static final String TRAVEL_UNAVAILABLE_MESSAGE = "Travel-time filter is temporarily unavailable. "
            + "The map shows the schools that match your other filters.";

    private final SchoolController schoolController;     // DC-37: rebuilds the results from the URL
    private final SearchFilterPipeline filterPipeline;   // the same filter steps as the result list
    private final MapController mapController;
    private final ReferenceLocationStore locationStore;
    private final JsonMapper jsonMapper;

    public SchoolMapUI(SchoolController schoolController, FilterController filterController,
                       MapController mapController, ProfileController profileController,
                       SchoolDataController schoolDataController, ReferenceLocationStore locationStore,
                       SessionCookie sessionCookie, JsonMapper jsonMapper) {
        this.schoolController = schoolController;
        this.filterPipeline = new SearchFilterPipeline(filterController, profileController, sessionCookie,
                () -> PsleAvailability.available(schoolDataController));   // DC-74
        this.mapController = mapController;
        this.locationStore = locationStore;
        this.jsonMapper = jsonMapper;
    }

    /** An active filter on the map page; its remove link stays on the map (FR-FILTER-08). */
    public record Chip(String label, String removeUrl) {
    }

    /** A filter parameter carried by the name-search form, so a new name keeps the filters. */
    public record HiddenField(String name, String value) {
    }

    /**
     * GET /schools/map?&lt;same parameters as /schools&gt; — "View on map" from the results (selectViewOnMap,
     * T-23). Same steps as the result list: search by name, build the filters, apply them; then keep the
     * schools that have a map location (FR-MAP-03).
     */
    @GetMapping("/schools/map")
    public String displaySchoolMarkers(HttpServletRequest request, Model model) {
        FilterParams params = FilterParams.from(request);
        Map<String, String> errors = new LinkedHashMap<>();
        CurrentResultSet results = search(params.q(), errors);
        Optional<ReferenceLocation> start = locationStore.get(request);
        SearchFilterPipeline.Outcome filtered = filterPipeline.apply(params, results, start, request, errors);
        results = filtered.results();
        if (filtered.travelUnavailable()) {
            model.addAttribute("notice", TRAVEL_UNAVAILABLE_MESSAGE);
        }

        List<School> onMap = mapController.showSchoolsOnMap(results);
        model.addAttribute("q", params.q() == null ? "" : params.q());
        model.addAttribute("chips", chips(params, filtered.chips(params)));
        model.addAttribute("notAppliedChips", chips(params, filtered.notAppliedChips(params)));
        model.addAttribute("hiddenFields", hiddenFields(params));
        model.addAttribute("resultCount", results.size());
        model.addAttribute("schools", onMap);
        model.addAttribute("omittedCount", results.size() - onMap.size());
        model.addAttribute("interactive", mapController.displayInteractiveMap(onMap));
        model.addAttribute("markersJson", MapMarker.toJson(jsonMapper, onMap.stream().map(MapMarker::of).toList()));
        model.addAttribute("resultsUrl", params.toUrl("/schools"));
        model.addAttribute("filterUrl", params.toUrl("/schools/filter"));
        model.addAttribute("startLocation", start.orElse(null));
        model.addAttribute("usesStartingPoint", params.radiusKm() != null || params.travel());
        model.addAttribute("psleActive", params.psle() != null && !filtered.psleNotApplied());
        model.addAttribute("psleNotApplied", filtered.psleNotApplied());   // DC-74
        if (!errors.isEmpty()) {
            model.addAttribute(PageMessages.FIELD_ERRORS, errors);
        }
        return "school-map";
    }

    /**
     * GET /api/districts — the planning-area boundaries as GeoJSON for the district layer
     * (toggleDistrictLayer, FR-MAP-06, FR-MAP-07, DC-16). The browser hides and shows the layer itself, so the
     * markers stay where they are.
     */
    @GetMapping("/api/districts")
    @ResponseBody
    public ResponseEntity<String> toggleDistrictLayer() {
        return ResponseEntity.ok()
                .contentType(GEO_JSON)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .body(mapController.toggleDistricts(true));
    }

    /** The name search; a term the control rejects becomes a field error and the map shows all schools. */
    private CurrentResultSet search(String term, Map<String, String> errors) {
        try {
            return schoolController.searchSchools(term);
        } catch (InvalidInputException e) {
            errors.putAll(e.getFieldErrors());
            return schoolController.searchSchools(null);
        }
    }

    private static List<Chip> chips(FilterParams params, List<FilterParams.FilterChip> filterChips) {
        return filterChips.stream()
                .map(chip -> new Chip(chip.label(), params.without(chip.param(), chip.value()).toUrl("/schools/map")))
                .toList();
    }

    private static List<HiddenField> hiddenFields(FilterParams params) {
        List<HiddenField> fields = new ArrayList<>();
        params.types().forEach(v -> fields.add(new HiddenField(FilterParams.TYPE, v)));
        params.programmes().forEach(v -> fields.add(new HiddenField(FilterParams.PROGRAMME, v)));
        params.ccas().forEach(v -> fields.add(new HiddenField(FilterParams.CCA, v)));
        params.districts().forEach(v -> fields.add(new HiddenField(FilterParams.DISTRICT, v)));
        addIfSet(fields, FilterParams.PSLE, params.psle());
        addIfSet(fields, FilterParams.PG, params.pg());
        addIfSet(fields, FilterParams.RADIUS_KM, params.radiusKm());
        addIfSet(fields, FilterParams.MODE, params.mode());
        addIfSet(fields, FilterParams.MAX_MIN, params.maxMin());
        addIfSet(fields, FilterParams.TRAVEL, params.travel() ? "1" : null);
        addIfSet(fields, FilterParams.SORT, params.sort());
        return fields;
    }

    private static void addIfSet(List<HiddenField> fields, String name, String value) {
        if (value != null) {
            fields.add(new HiddenField(name, value));
        }
    }
}

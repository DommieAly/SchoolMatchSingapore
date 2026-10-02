package sg.schoolmatch.boundary.ui;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import sg.schoolmatch.boundary.ui.support.FilterParams;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.control.FilterController;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.search.AttributeCategory;
import sg.schoolmatch.entity.search.ProximityFilter;
import sg.schoolmatch.entity.search.SchoolAttributeFilter;
import sg.schoolmatch.entity.search.TransportationFilter;

/**
 * Design class «boundary» SchoolFilterUI — the filter panel for the current results (DM-10 SchoolFilter;
 * use case Filter Schools, FR-FILTER-01..09). The form is filled from the URL (DC-15: the URL is the state) and
 * submits GET /schools with the filter params (confirmFilters); "Clear all" links to /schools?q=… (clearAllFilters).
 * Values the URL holds but the form cannot accept are highlighted (highlightInvalidFields, NFR-USE-03).
 * The starting point comes from {@link ReferenceLocationStore}; D's location picker returns to this page.
 */
@Controller
public class SchoolFilterUI {

    private final FilterController filterController;
    private final ReferenceLocationStore locationStore;

    public SchoolFilterUI(FilterController filterController, ReferenceLocationStore locationStore) {
        this.filterController = filterController;
        this.locationStore = locationStore;
    }

    /** One group of checkboxes on the page: its label, request param name, values, selected values and error. */
    public record FilterGroup(String label, String param, Set<String> values, Set<String> selected, String error) {
    }

    /** One travel mode in the select: request value and label ("Public transport" for TRANSIT, DC-01). */
    public record ModeOption(String value, String label) {
    }

    /**
     * GET /schools/filter?&lt;current params&gt; — shows the filter categories (FR-FILTER-02, DC-04 district),
     * PSLE score and posting group (FR-FILTER-03), starting point (FR-FILTER-04), distance (FR-FILTER-05) and
     * travel time (FR-FILTER-06), filled from the URL.
     */
    @GetMapping("/schools/filter")
    public String displayFilterCategories(HttpServletRequest request, Model model) {
        FilterParams params = FilterParams.from(request);
        Optional<ReferenceLocation> start = locationStore.get(request).filter(ReferenceLocation::isResolved);

        Map<String, String> errors = new LinkedHashMap<>();
        params.toFilters(start, null, errors);   // only for the messages; /schools builds the real filters
        List<FilterGroup> groups = new ArrayList<>();
        for (AttributeCategory category : AttributeCategory.values()) {
            String param = param(category);
            Set<String> options = filterController.getFilterOptions(category);
            Set<String> selected = selected(params, category);
            String error = unknownValuesMessage(category, options, selected);
            if (error != null) {
                errors.put(param, error);
            }
            groups.add(new FilterGroup(label(category), param, options, selected, error));
        }

        model.addAttribute("params", params);
        model.addAttribute("q", params.q() == null ? "" : params.q());
        model.addAttribute("groups", groups);
        model.addAttribute("radiusOptions",
                ProximityFilter.RADIUS_OPTIONS_KM.stream().sorted().map(String::valueOf).toList());
        model.addAttribute("durationOptions",
                TransportationFilter.DURATION_OPTIONS_MIN.stream().sorted().map(String::valueOf).toList());
        model.addAttribute("travelModes", List.of(
                new ModeOption(TravelMode.TRANSIT.name(), "Public transport"),
                new ModeOption(TravelMode.WALK.name(), "Walk"),
                new ModeOption(TravelMode.DRIVE.name(), "Drive")));
        model.addAttribute("startLocation", start.orElse(null));
        model.addAttribute("returnTo", params.toUrl("/schools/filter"));
        model.addAttribute("clearAllUrl", new FilterParams(params.q(), null, null, null, null, null, null, null,
                null, null, false, null).toUrl("/schools"));
        if (params.usesDefaultPostingGroup()) {
            model.addAttribute("pgHint", FilterParams.DEFAULT_PG_HINT);
        }
        model.addAttribute("resultsUrl", params.toUrl("/schools"));
        model.addAttribute("errors", errors);   // field → message, for highlighting each field
        Map<String, String> banner = new LinkedHashMap<>(errors);
        banner.remove(FilterParams.LOCATION);   // shown next to the starting-point picker instead
        if (!banner.isEmpty()) {
            model.addAttribute(PageMessages.FIELD_ERRORS, banner);   // the list at the top (messages fragment)
        }
        return "school-filter";
    }

    /** "Unknown CCA: X, Y" when the URL holds values that are not in the active dataset; else null. */
    private static String unknownValuesMessage(AttributeCategory category, Set<String> options, Set<String> selected) {
        Set<String> known = options.stream().map(v -> v.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<String> unknown = selected.stream().filter(v -> !known.contains(v.toLowerCase(Locale.ROOT))).toList();
        if (unknown.isEmpty()) {
            return null;
        }
        String name = category == AttributeCategory.CCA
                ? "CCA" : SchoolAttributeFilter.label(category).toLowerCase(Locale.ROOT);
        return "Unknown " + name + ": " + String.join(", ", unknown);
    }

    private static Set<String> selected(FilterParams params, AttributeCategory category) {
        return switch (category) {
            case SCHOOL_TYPE -> params.types();
            case PROGRAMME -> params.programmes();
            case CCA -> params.ccas();
            case DISTRICT -> params.districts();
        };
    }

    private static String label(AttributeCategory category) {
        return switch (category) {
            case SCHOOL_TYPE -> "School type";
            case PROGRAMME -> "Programmes";
            case CCA -> "CCAs";
            case DISTRICT -> "District (planning area)";
        };
    }

    /** Request param names used by GET /schools (see docs/routes.md and FilterParams). */
    private static String param(AttributeCategory category) {
        return switch (category) {
            case SCHOOL_TYPE -> FilterParams.TYPE;
            case PROGRAMME -> FilterParams.PROGRAMME;
            case CCA -> FilterParams.CCA;
            case DISTRICT -> FilterParams.DISTRICT;
        };
    }
}

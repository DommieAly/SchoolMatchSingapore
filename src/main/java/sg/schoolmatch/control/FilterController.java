package sg.schoolmatch.control;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.search.AttributeCategory;
import sg.schoolmatch.entity.search.CurrentResultSet;
import sg.schoolmatch.entity.search.Filter;
import sg.schoolmatch.entity.search.ProximityFilter;
import sg.schoolmatch.entity.search.PsleScoreFilter;
import sg.schoolmatch.entity.search.SchoolAttributeFilter;
import sg.schoolmatch.entity.search.SortOrder;
import sg.schoolmatch.entity.search.TransportationFilter;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Design class «control» FilterController — filters the Current Result Set (use case Filter Schools;
 * FR-FILTER-01..09). DC-15: stateless. DC-14: depends on LocationController, DirectionsController
 * (commute times for the transport filter, DC-32) and SchoolDataController (filter options).
 * Called by SchoolFilterUI, SchoolSearchUI, SchoolMapUI; UserProfileUI and RecommendationUI use
 * getFilterOptions for their CCA and programme lists (DC-37).
 * <p>
 * Field names in {@link InvalidInputException} are the request parameter names of the search page
 * ({@code type, programme, cca, district, psle, pg, radiusKm, mode, maxMin}, and {@code location} for a missing
 * starting point), so a page can show each message next to its field (NFR-USE-03).
 */
@Service
public class FilterController {

    // Same texts as boundary.ui.support.FilterParams (a control cannot use the UI package).
    static final String PSLE_MESSAGE = "Enter a whole number from 4 to 32";
    static final String PG_MESSAGE = "Choose posting group 1, 2 or 3";
    static final String RADIUS_MESSAGE = "Choose 1, 3 or 5 km";
    static final String DURATION_MESSAGE = "Choose 15, 30, 45 or 60 minutes";
    static final String MODE_MESSAGE = "Choose walk, drive or public transport";
    static final String LOCATION_MESSAGE = "Set a starting point to use distance or travel-time filters";

    private final LocationController locationController;
    private final DirectionsController directionsController;
    private final SchoolDataController schoolDataController;
    private final AppProperties props;

    public FilterController(LocationController locationController, DirectionsController directionsController,
                            SchoolDataController schoolDataController, AppProperties props) {
        this.locationController = locationController;
        this.directionsController = directionsController;
        this.schoolDataController = schoolDataController;
        this.props = props;
    }

    /**
     * A new result set with {@code filters} applied: AND across categories, OR within one (FR-FILTER-07).
     * {@code results} is not changed. The schools keep the search order (A–Z); sort afterwards (DC-15:
     * search → filter → sort). The new set keeps the starting point of {@code results}.
     * <ol>
     *   <li>Every filter is checked first ({@link #validateFilter}); all problems are reported together.</li>
     *   <li>Attribute, PSLE and distance filters are applied (no outside call; NFR-PERF-02).</li>
     *   <li>Only when a TransportationFilter is present: the remaining schools that could be reached at the mode's
     *       top speed ({@code app.transport-filter.max-speed-kmh}; at most {@code app.transport-filter.max-routed-schools},
     *       150, nearest first) are sent to
     *       {@code DirectionsController.getCommuteTimes}; the minutes go into the filter and the result set, and
     *       the travel-time filter is applied.</li>
     * </ol>
     *
     * @throws InvalidInputException when a filter is not valid (AF-2): field → message
     * @throws sg.schoolmatch.error.ExternalServiceUnavailableException when the routing service fails (EX-1);
     *         the page then shows the results without the travel-time filter
     */
    public CurrentResultSet applyFilters(CurrentResultSet results, List<Filter> filters) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (Filter filter : filters) {
            if (!validateFilter(filter)) {
                problems(filter).forEach(errors::putIfAbsent);
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }

        CurrentResultSet filtered = results.copyWith(results.getUnfilteredSchools(), SortOrder.NAME_ASC);
        List<TransportationFilter> travelFilters = new ArrayList<>();
        for (Filter filter : filters) {
            if (filter instanceof TransportationFilter travel) {
                travelFilters.add(travel);
            } else {
                filtered.addFilter(filter);
            }
        }
        filtered.applyFilters();

        for (TransportationFilter travel : travelFilters) {
            Map<String, Integer> minutes = commuteMinutes(travel, filtered.getSchools());
            travel.setCommuteMinutesBySchool(minutes);
            Map<String, Integer> all = new HashMap<>(filtered.getCommuteMinutes());
            all.putAll(minutes);
            filtered.setCommuteMinutes(all);
            filtered.addFilter(travel);
            filtered.applyFilters();
        }
        return filtered;
    }

    /** The unfiltered result set for the same term (FR-FILTER-09). {@code results} is not changed. */
    public CurrentResultSet clearFilters(CurrentResultSet results) {
        CurrentResultSet cleared = results.copyWith(results.getUnfilteredSchools(), SortOrder.NAME_ASC);
        cleared.clearFilters();
        return cleared;
    }

    /** Values offered on the filter page for one category, sorted (FR-FILTER-02, DC-04). */
    public Set<String> getFilterOptions(AttributeCategory category) {
        return schoolDataController.getActiveDataset().getAttributeValues(category);
    }

    /**
     * True when the filter can be applied: its own {@code isValid()} (ranges, options, resolved starting point)
     * and, for an attribute filter, every value exists in the active dataset (data dictionary: values come only
     * from the active dataset; compared ignoring case).
     */
    private boolean validateFilter(Filter filter) {
        if (!filter.isValid()) {
            return false;
        }
        if (filter instanceof SchoolAttributeFilter attribute) {
            return unknownValues(attribute).isEmpty();
        }
        return true;
    }

    /** Field → message for an invalid filter (NFR-USE-03). */
    private Map<String, String> problems(Filter filter) {
        Map<String, String> problems = new LinkedHashMap<>();
        switch (filter) {
            case SchoolAttributeFilter attribute -> {
                String field = fieldName(attribute.getCategory());
                String label = SchoolAttributeFilter.label(attribute.getCategory());
                if (!attribute.isValid()) {
                    problems.put(field, "Choose at least one " + label.toLowerCase(Locale.ROOT));
                } else {
                    String unknownLabel = attribute.getCategory() == AttributeCategory.CCA
                            ? label : label.toLowerCase(Locale.ROOT);
                    problems.put(field, "Unknown " + unknownLabel + ": " + String.join(", ", unknownValues(attribute)));
                }
            }
            case PsleScoreFilter psle -> {
                if (psle.getScore() < 4 || psle.getScore() > 32) {
                    problems.put("psle", PSLE_MESSAGE);
                }
                if (psle.getPostingGroup() < 1 || psle.getPostingGroup() > 3) {
                    problems.put("pg", PG_MESSAGE);
                }
            }
            case ProximityFilter proximity -> {
                if (!ProximityFilter.RADIUS_OPTIONS_KM.contains(proximity.getRadiusKm())) {
                    problems.put("radiusKm", RADIUS_MESSAGE);
                }
                if (proximity.getReferenceLocation() == null || !proximity.getReferenceLocation().isResolved()) {
                    problems.put("location", LOCATION_MESSAGE);
                }
            }
            case TransportationFilter travel -> {
                if (travel.getTravelMode() == null) {
                    problems.put("mode", MODE_MESSAGE);
                }
                if (!TransportationFilter.DURATION_OPTIONS_MIN.contains(travel.getMaxDurationMin())) {
                    problems.put("maxMin", DURATION_MESSAGE);
                }
                if (travel.getReferenceLocation() == null || !travel.getReferenceLocation().isResolved()) {
                    problems.put("location", LOCATION_MESSAGE);
                }
            }
            default -> problems.put("filter", "This filter cannot be used: " + filter.describe());
        }
        return problems;
    }

    /** Selected values that are not in the active dataset (ignoring case), in the order given. */
    private List<String> unknownValues(SchoolAttributeFilter attribute) {
        Set<String> known = getFilterOptions(attribute.getCategory()).stream()
                .map(v -> v.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return attribute.getSelectedValues().stream()
                .filter(v -> !known.contains(v.strip().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** Request parameter name of a category on the search page. */
    private static String fieldName(AttributeCategory category) {
        if (category == null) {
            return "filter";
        }
        return switch (category) {
            case SCHOOL_TYPE -> "type";
            case PROGRAMME -> "programme";
            case CCA -> "cca";
            case DISTRICT -> "district";
        };
    }

    /**
     * Commute minutes (rounded up) from the filter's starting point to each school that could be reached at the
     * mode's top speed, nearest first, at most {@code app.transport-filter.max-routed-schools}. A school farther than
     * speed × time cannot be reached, so it is left out without asking the routing service. Schools with no route
     * get no entry.
     */
    private Map<String, Integer> commuteMinutes(TransportationFilter travel, List<School> schools) {
        AppProperties.TransportFilterSettings settings = props.transportFilter();   // DC-57
        Coordinate origin = travel.getReferenceLocation().getCoordinate();
        double reachKm = settings.maxSpeedKmh().get(travel.getTravelMode()) * travel.getMaxDurationMin() / 60.0;
        List<School> candidates = schools.stream()
                .filter(School::hasValidCoordinate)
                .filter(s -> s.distanceTo(origin) <= reachKm)
                .sorted(Comparator.comparingDouble((School s) -> s.distanceTo(origin))
                        .thenComparing(School::getSchoolCode))
                .limit(settings.maxRoutedSchools())
                .toList();
        if (candidates.isEmpty()) {
            return Map.of();
        }
        List<Route> routes = directionsController.getCommuteTimes(
                travel.getReferenceLocation(), candidates, travel.getTravelMode());
        Map<String, Integer> minutes = new HashMap<>();
        for (int i = 0; i < candidates.size() && routes != null && i < routes.size(); i++) {
            Route route = routes.get(i);
            if (route != null && route.isAvailable() && route.getDurationSeconds() != null) {
                minutes.put(candidates.get(i).getSchoolCode(), (route.getDurationSeconds() + 59) / 60);
            }
        }
        return minutes;
    }
}

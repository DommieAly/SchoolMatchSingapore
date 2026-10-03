package sg.schoolmatch.boundary.ui;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import sg.schoolmatch.boundary.ui.support.FilterParams;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.boundary.ui.support.PsleAvailability;
import sg.schoolmatch.boundary.ui.support.ReferenceLocationStore;
import sg.schoolmatch.boundary.ui.support.SearchFilterPipeline;
import sg.schoolmatch.boundary.ui.support.SessionCookie;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.FilterController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.search.CurrentResultSet;
import sg.schoolmatch.entity.search.Filter;
import sg.schoolmatch.entity.search.PsleScoreFilter;
import sg.schoolmatch.entity.search.SortOrder;
import sg.schoolmatch.entity.search.TransportationFilter;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Design class «boundary» SchoolSearchUI — search box and result list
 * (DM 07 SchoolSearch, DM 08 SearchResults; FR-SEARCH-01..06; use case Filter Schools FR-FILTER-01..09).
 * <p>
 * This class only turns the request into control calls and the answers into model attributes;
 * the rules live in {@link SchoolController} and {@link FilterController}. DC-15: every request rebuilds the
 * results from the URL: {@code searchSchools(q)} → {@code applyFilters(results, filters)} →
 * {@code sortResults(results, sort)}. Design display operations are {@code th:fragment} names in
 * {@code school-search.html}: {@code displaySearchField}, {@code highlightInvalidFields}, {@code displayResults},
 * {@code displayNoMatches}, {@code displayServiceUnavailable}.
 * <p>
 * Model attributes: {@code term}, {@code params}, and either {@code fieldErrors} for an invalid term (no results) or
 * {@code schools} (this page), {@code totalCount}, {@code page}, {@code totalPages}, {@code baseUrl}, {@code chips},
 * {@code notAppliedChips}, {@code clearAllUrl}, {@code filterUrl}, {@code mapUrl}, {@code sortOrder}, {@code sortOptions} and, when they
 * apply, {@code fieldErrors} (filter input), {@code sortMessage}, {@code flashError} (travel time unavailable),
 * {@code activeFilters}, {@code noPsleDataCount}, {@code pgHint}, {@code psleNotApplied} (DC-74: the URL has a PSLE
 * score but the dataset has no ranges), {@code startLocation} and the card extras
 * {@code cardDistanceKm}, {@code cardCommuteMin}, {@code cardCommuteMode}, {@code cardRanges}
 * (see {@code fragments/school-summary-card.html}).
 */
@Controller
public class SchoolSearchUI {

    static final String TRAVEL_UNAVAILABLE = "Travel-time filter is temporarily unavailable. "
            + "The other filters still apply; try again in a few minutes.";

    private final SchoolController schoolController;
    private final SearchFilterPipeline filterPipeline;   // FilterController + ProfileController (DC-22), shared with the map
    private final ReferenceLocationStore locationStore;
    private final int pageSize;

    public SchoolSearchUI(SchoolController schoolController, FilterController filterController,
                          ProfileController profileController, SchoolDataController schoolDataController,
                          ReferenceLocationStore locationStore, SessionCookie sessionCookie, AppProperties props) {
        this.schoolController = schoolController;
        this.filterPipeline = new SearchFilterPipeline(filterController, profileController, sessionCookie,
                () -> PsleAvailability.available(schoolDataController));   // DC-74
        this.locationStore = locationStore;
        this.pageSize = props.search().pageSize();
    }

    /** One entry of the sort selector: a link that keeps every other parameter. */
    public record SortOption(SortOrder order, String label, String url, boolean active) {
    }

    /**
     * GET /schools?q=&lt;filters&gt;&amp;sort=&amp;page= — search by name (no term lists all schools A–Z, DC-02),
     * then the filters in the URL (FilterParams), then the order. {@code page} is 1-based; out-of-range or
     * non-numeric pages show the nearest page. Invalid filter values are reported and left out; the other filters
     * still apply. The travel-time filter runs only with {@code travel=1}.
     */
    @GetMapping("/schools")
    public String submitSearch(@RequestParam(name = "page", defaultValue = "1") String page,
                               HttpServletRequest request, Model model) {
        FilterParams params = FilterParams.from(request);
        model.addAttribute("term", params.q() == null ? "" : params.q());   // refills the search field
        model.addAttribute("params", params);
        CurrentResultSet results;
        try {
            results = schoolController.searchSchools(params.q());
        } catch (InvalidInputException e) {
            model.addAttribute(PageMessages.FIELD_ERRORS, e.getFieldErrors());   // highlightInvalidFields, no results
            return "school-search";
        }

        Optional<ReferenceLocation> start = locationStore.get(request).filter(ReferenceLocation::isResolved);
        results.setReferenceLocation(start.orElse(null));

        // Filters: invalid values are reported and left out (AF-2); routing down keeps the rest (EX-1).
        Map<String, String> errors = new LinkedHashMap<>();
        SearchFilterPipeline.Outcome filtered = filterPipeline.apply(params, results, start, request, errors);
        results = filtered.results();
        if (filtered.travelUnavailable()) {
            model.addAttribute(PageMessages.FLASH_ERROR, TRAVEL_UNAVAILABLE);   // displayServiceUnavailable
        }
        if (params.sortOrder().isPresent()) {
            results = schoolController.sortResults(results, params.sortOrder().get());
            if (results.getSortOrder() != params.sortOrder().get()) {
                model.addAttribute("sortMessage", sortNotAvailable(params.sortOrder().get()));
            }
        }
        if (!errors.isEmpty()) {
            model.addAttribute(PageMessages.FIELD_ERRORS, errors);
        }

        displayResults(results, parsePage(page), model);
        displayFilters(params, filtered, results, start.orElse(null), model);
        return "school-search";
    }

    /** Puts one page of the results in the model. An empty result makes the page show displayNoMatches. */
    private void displayResults(CurrentResultSet results, int requestedPage, Model model) {
        List<School> schools = results.getSchools();
        int totalPages = Math.max(1, (schools.size() + pageSize - 1) / pageSize);
        int page = Math.max(1, Math.min(requestedPage, totalPages));
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, schools.size());

        model.addAttribute("schools", schools.subList(from, to));
        model.addAttribute("totalCount", schools.size());
        model.addAttribute("page", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("sortOrder", results.getSortOrder());
    }

    /**
     * Chips, links, sort options, notes and the card extras (FR-FILTER-08, DC-22). Chips and "match your filters"
     * describe only the filters that were applied; a rejected one is listed as "not applied" (step 9, AF-3).
     */
    private void displayFilters(FilterParams params, SearchFilterPipeline.Outcome filtered, CurrentResultSet results,
                                ReferenceLocation start, Model model) {
        model.addAttribute("baseUrl", params.toUrl("/schools"));   // the pager adds page=N
        model.addAttribute("chips", filtered.chips(params));
        model.addAttribute("notAppliedChips", filtered.notAppliedChips(params));
        model.addAttribute("hasFilters", filtered.anyApplied());
        model.addAttribute("searchCount", results.getUnfilteredSchools().size());
        model.addAttribute("clearAllUrl", onlyTerm(params).toUrl("/schools"));   // clearAllFilters (DC-15)
        model.addAttribute("filterUrl", params.toUrl("/schools/filter"));
        model.addAttribute("mapUrl", params.toUrl("/schools/map"));
        model.addAttribute("activeFilters", results.getActiveFilters().stream().map(Filter::describe).toList());
        model.addAttribute("sortOptions", sortOptions(params, results, start));
        model.addAttribute("startLocation", start);
        model.addAttribute("noPsleDataCount", results.getHiddenWithoutPsleData());
        model.addAttribute("psleNotApplied", filtered.psleNotApplied());   // DC-74: the reason under "Not applied"
        if (params.usesDefaultPostingGroup()) {
            model.addAttribute("pgHint", FilterParams.DEFAULT_PG_HINT);
        }

        // Card extras, only for the schools shown (fragments/school-summary-card.html).
        if (start != null) {
            Map<String, Double> distanceKm = new LinkedHashMap<>();
            for (School school : results.getSchools()) {
                if (school.hasValidCoordinate()) {
                    distanceKm.put(school.getSchoolCode(), school.distanceTo(start.getCoordinate()));
                }
            }
            model.addAttribute("cardDistanceKm", distanceKm);
        }
        if (!results.getCommuteMinutes().isEmpty()) {   // the travel-time filter ran
            model.addAttribute("cardCommuteMin", results.getCommuteMinutes());
            model.addAttribute("cardCommuteMode", TransportationFilter.modePhrase(params.travelMode().orElse(null)));
        }
        for (Filter filter : results.getActiveFilters()) {
            if (filter instanceof PsleScoreFilter psle) {
                // DC-22: with a PSLE filter the card shows the range that filter used (posting group, affiliation).
                Map<String, IndicativePsleScoreRange> ranges = new LinkedHashMap<>();
                for (School school : results.getSchools()) {
                    school.getScoreRange(psle.getPostingGroup(), psle.isAffiliatedWith(school))
                            .ifPresent(range -> ranges.put(school.getSchoolCode(), range));
                }
                model.addAttribute("cardRanges", ranges);
                model.addAttribute("pslePostingGroup", psle.getPostingGroup());   // "no PSLE range for PG3"
            }
        }
    }

    /**
     * Name A–Z always; distance only with a starting point; travel time only after the travel-time filter ran
     * (FR-SEARCH-06). Each option is a link with the same parameters (DC-15), back on page 1.
     */
    private static List<SortOption> sortOptions(FilterParams params, CurrentResultSet results, ReferenceLocation start) {
        List<SortOption> options = new ArrayList<>();
        options.add(sortOption(params, results, SortOrder.NAME_ASC, "Name (A–Z)"));
        if (start != null) {
            options.add(sortOption(params, results, SortOrder.DISTANCE_ASC, "Distance"));
        }
        if (!results.getCommuteMinutes().isEmpty()) {
            options.add(sortOption(params, results, SortOrder.COMMUTE_ASC, "Travel time"));
        }
        return options;
    }

    private static SortOption sortOption(FilterParams params, CurrentResultSet results, SortOrder order,
                                         String label) {
        String url = withSort(params, order == SortOrder.NAME_ASC ? null : order.name()).toUrl("/schools");
        return new SortOption(order, label, url, results.getSortOrder() == order);
    }

    /** Only the search term: the target of "Clear all" (FR-FILTER-09). */
    private static FilterParams onlyTerm(FilterParams params) {
        return new FilterParams(params.q(), null, null, null, null, null, null, null, null, null, false, null);
    }

    /** The same parameters with another {@code sort} (null = default order). */
    private static FilterParams withSort(FilterParams p, String sort) {
        return new FilterParams(p.q(), p.types(), p.programmes(), p.ccas(), p.districts(), p.psle(), p.pg(),
                p.radiusKm(), p.mode(), p.maxMin(), p.travel(), sort);
    }

    private static String sortNotAvailable(SortOrder wanted) {
        return switch (wanted) {
            case DISTANCE_ASC -> "Sorting by distance needs a starting point. Set one under Filters; "
                    + "the list is sorted by name instead.";
            case COMMUTE_ASC -> "Sorting by travel time needs the travel-time filter. Apply it under Filters; "
                    + "the list is sorted by name instead.";
            case NAME_ASC -> null;
        };
    }

    /** A hand-edited URL such as ?page=abc shows page 1 instead of an error page. */
    private static int parsePage(String page) {
        try {
            return Integer.parseInt(page.strip());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}

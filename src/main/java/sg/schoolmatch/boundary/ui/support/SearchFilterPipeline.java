package sg.schoolmatch.boundary.ui.support;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sg.schoolmatch.control.FilterController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.location.ReferenceLocation;
import sg.schoolmatch.entity.search.AttributeCategory;
import sg.schoolmatch.entity.search.CurrentResultSet;
import sg.schoolmatch.entity.search.Filter;
import sg.schoolmatch.entity.search.ProximityFilter;
import sg.schoolmatch.entity.search.PsleScoreFilter;
import sg.schoolmatch.entity.search.SchoolAttributeFilter;
import sg.schoolmatch.entity.search.TransportationFilter;
import sg.schoolmatch.error.ExternalFailureLog;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;
import sg.schoolmatch.error.NotAuthenticatedException;

/**
 * The filter steps shared by SchoolSearchUI (result list) and SchoolMapUI (map), so both pages show the same
 * schools for the same URL (DC-15, DC-37): build the filters from the URL parameters ({@link FilterParams}),
 * apply them with {@link FilterController}, drop a filter the control rejects (Filter Schools AF-2) and the
 * travel-time filter when routing is down (EX-1), and report which filters were really applied, so the chips
 * and "N of M schools match your filters" only describe those (FR-FILTER-08, Filter Schools step 9).
 * <p>
 * DC-74: when the active dataset has no PSLE score range at all, a {@code psle} (and {@code pg}) in the URL is not
 * turned into a filter (it would hide every school) and is not checked; its chip is listed as "not applied" and
 * {@link Outcome#psleNotApplied()} tells the page to give the reason ({@link PsleAvailability#NOT_AVAILABLE_MESSAGE}).
 * <p>
 * Not a Spring bean: each UI class builds one from the controls it already has.
 */
public final class SearchFilterPipeline {

    /**
     * What the filter step did.
     *
     * @param results           the filtered results (the input results when no filter could be applied)
     * @param applied           the filters FilterController applied; empty when none
     * @param travelUnavailable true when the travel-time filter was dropped because routing failed (EX-1)
     * @param psleNotApplied    true when the URL has a PSLE score but the dataset has no PSLE ranges (DC-74)
     */
    public record Outcome(CurrentResultSet results, List<Filter> applied, boolean travelUnavailable,
                          boolean psleNotApplied) {

        public Outcome {
            applied = List.copyOf(applied);
        }

        /** An outcome with PSLE data available (the usual case). */
        public Outcome(CurrentResultSet results, List<Filter> applied, boolean travelUnavailable) {
            this(results, applied, travelUnavailable, false);
        }

        /** True when at least one filter was applied. */
        public boolean anyApplied() {
            return !applied.isEmpty();
        }

        /** The chips of the applied filters (FR-FILTER-08). */
        public List<FilterParams.FilterChip> chips(FilterParams params) {
            Set<String> appliedParams = appliedParams();
            return params.chips().stream().filter(chip -> appliedParams.contains(chip.param())).toList();
        }

        /** The chips of filters in the URL that were rejected or dropped; the page shows them as "not applied". */
        public List<FilterParams.FilterChip> notAppliedChips(FilterParams params) {
            Set<String> appliedParams = appliedParams();
            return params.chips().stream().filter(chip -> !appliedParams.contains(chip.param())).toList();
        }

        private Set<String> appliedParams() {
            return applied.stream().map(SearchFilterPipeline::chipParam).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SearchFilterPipeline.class);

    private final FilterController filterController;
    private final ProfileController profileController;   // primary school for the affiliated PSLE range (DC-22)
    private final SessionCookie sessionCookie;
    private final BooleanSupplier psleDataAvailable;     // DC-74: SchoolDataController.hasPsleData, via PsleAvailability

    public SearchFilterPipeline(FilterController filterController, ProfileController profileController,
                                SessionCookie sessionCookie) {
        this(filterController, profileController, sessionCookie, () -> true);
    }

    /** @param psleDataAvailable asked once per {@link #apply}: false when the dataset has no PSLE ranges (DC-74) */
    public SearchFilterPipeline(FilterController filterController, ProfileController profileController,
                                SessionCookie sessionCookie, BooleanSupplier psleDataAvailable) {
        this.filterController = filterController;
        this.profileController = profileController;
        this.sessionCookie = sessionCookie;
        this.psleDataAvailable = psleDataAvailable;
    }

    /**
     * Builds the filters of {@code params} and applies them to {@code results}. Invalid parameters and filters
     * the control rejects are added to {@code errors} and left out; the other filters still apply. No filter
     * means no control call.
     *
     * @param start the starting point for distance and travel time (an unresolved one counts as none)
     */
    public Outcome apply(FilterParams params, CurrentResultSet results, Optional<ReferenceLocation> start,
                         HttpServletRequest request, Map<String, String> errors) {
        boolean psleNotApplied = false;
        FilterParams usable = params;
        if ((params.psle() != null || params.pg() != null) && !psleDataAvailable.getAsBoolean()) {
            usable = params.without(FilterParams.PSLE, null);   // DC-74: no PSLE filter and no psle/pg messages
            psleNotApplied = params.psle() != null;
        }
        String primarySchool = usable.psle() == null ? null : primarySchool(request);
        List<Filter> remaining = new ArrayList<>(usable.toFilters(start, primarySchool, errors));
        boolean travelUnavailable = false;
        while (!remaining.isEmpty()) {
            try {
                CurrentResultSet filtered = filterController.applyFilters(results, List.copyOf(remaining));
                return new Outcome(filtered, remaining, travelUnavailable, psleNotApplied);
            } catch (ExternalServiceUnavailableException e) {
                ExternalFailureLog.warn(log, "Travel-time filter", e);
                travelUnavailable = true;
                if (!remaining.removeIf(TransportationFilter.class::isInstance)) {
                    break;
                }
            } catch (InvalidInputException e) {
                errors.putAll(e.getFieldErrors());
                Set<String> rejected = e.getFieldErrors().keySet();
                if (!remaining.removeIf(filter -> fieldsOf(filter).stream().anyMatch(rejected::contains))) {
                    break;   // cannot tell which filter was rejected: show the unfiltered results
                }
            }
        }
        return new Outcome(results, List.of(), travelUnavailable, psleNotApplied);
    }

    /** The logged-in user's primary school (affiliated PSLE range, DC-22), or null for a guest or an ended login. */
    public String primarySchool(HttpServletRequest request) {
        Optional<String> sessionId = sessionCookie.read(request);
        if (sessionId.isEmpty()) {
            return null;
        }
        try {
            UserProfile profile = profileController.getProfile(sessionId.get());
            return profile == null ? null : profile.getPrimarySchool();
        } catch (NotAuthenticatedException e) {
            return null;   // these pages are public: an expired login just means "guest"
        }
    }

    /** The request fields a filter is built from (the keys FilterController uses for its messages). */
    static Set<String> fieldsOf(Filter filter) {
        return switch (filter) {
            case SchoolAttributeFilter a when a.getCategory() != null -> Set.of(param(a.getCategory()));
            case PsleScoreFilter p -> Set.of(FilterParams.PSLE, FilterParams.PG);
            case ProximityFilter p -> Set.of(FilterParams.RADIUS_KM, FilterParams.LOCATION);
            case TransportationFilter t -> Set.of(FilterParams.TRAVEL, FilterParams.MODE, FilterParams.MAX_MIN,
                    FilterParams.LOCATION);
            default -> Set.of();
        };
    }

    /** The parameter of the chip that shows {@code filter} ({@link FilterParams#chips()}), or null. */
    static String chipParam(Filter filter) {
        return switch (filter) {
            case SchoolAttributeFilter a when a.getCategory() != null -> param(a.getCategory());
            case PsleScoreFilter p -> FilterParams.PSLE;
            case ProximityFilter p -> FilterParams.RADIUS_KM;
            case TransportationFilter t -> FilterParams.TRAVEL;
            default -> null;
        };
    }

    private static String param(AttributeCategory category) {
        return switch (category) {
            case SCHOOL_TYPE -> FilterParams.TYPE;
            case PROGRAMME -> FilterParams.PROGRAMME;
            case CCA -> FilterParams.CCA;
            case DISTRICT -> FilterParams.DISTRICT;
        };
    }
}

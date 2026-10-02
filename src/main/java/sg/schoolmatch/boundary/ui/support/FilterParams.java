package sg.schoolmatch.boundary.ui.support;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
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
 * The search and filter parameters of {@code /schools}, {@code /schools/filter} and {@code /schools/map}
 * (use cases Search Schools and Filter Schools; FR-FILTER-01..09, DC-15: the URL is the state).
 * <p>
 * Parameters: {@code q}, {@code type}, {@code programme}, {@code cca}, {@code district} (each repeatable),
 * {@code psle} (4–32), {@code pg} (1–3), {@code radiusKm} (1|3|5), {@code mode} (WALK|DRIVE|TRANSIT),
 * {@code maxMin} (15|30|45|60), {@code travel=1} (the user clicked "apply travel-time filter"),
 * {@code sort} (NAME_ASC|DISTANCE_ASC|COMMUTE_ASC). {@code page} is read by the page itself and is never part
 * of these parameters.
 * <p>
 * The record keeps the raw text (trimmed; blank = not given; repeatable values sorted), so a page can refill
 * its form exactly. {@link #toFilters} turns it into Filter objects and collects the input errors (NFR-USE-03).
 */
public record FilterParams(String q, Set<String> types, Set<String> programmes, Set<String> ccas,
                           Set<String> districts, String psle, String pg, String radiusKm, String mode,
                           String maxMin, boolean travel, String sort) {

    // Request parameter names (also the field names in the errors map).
    public static final String Q = "q";
    public static final String TYPE = "type";
    public static final String PROGRAMME = "programme";
    public static final String CCA = "cca";
    public static final String DISTRICT = "district";
    public static final String PSLE = "psle";
    public static final String PG = "pg";
    public static final String RADIUS_KM = "radiusKm";
    public static final String MODE = "mode";
    public static final String MAX_MIN = "maxMin";
    public static final String TRAVEL = "travel";
    public static final String SORT = "sort";
    public static final String PAGE = "page";
    /** Errors-map key when a distance or travel-time filter has no starting point. */
    public static final String LOCATION = "location";

    // Messages (NFR-USE-03).
    public static final String PSLE_MESSAGE = "Enter a whole number from 4 to 32";
    public static final String PG_MESSAGE = "Choose posting group 1, 2 or 3";
    public static final String RADIUS_MESSAGE = "Choose 1, 3 or 5 km";
    public static final String DURATION_MESSAGE = "Choose 15, 30, 45 or 60 minutes";
    public static final String MODE_MESSAGE = "Choose walk, drive or public transport";
    public static final String LOCATION_MESSAGE = "Set a starting point to use distance or travel-time filters";

    /** Posting group used when a PSLE score is given without one. */
    public static final int DEFAULT_POSTING_GROUP = 3;
    /** Hint shown next to the PSLE filter when {@link #usesDefaultPostingGroup()} is true. */
    public static final String DEFAULT_PG_HINT = "No posting group chosen, so posting group 3 is used.";

    private static final Pattern WHOLE_NUMBER = Pattern.compile("\\d{1,9}");

    /** Trims every value; blank text becomes null and blank list items are dropped. Never throws. */
    public FilterParams {
        q = clean(q);
        types = cleanSet(types);
        programmes = cleanSet(programmes);
        ccas = cleanSet(ccas);
        districts = cleanSet(districts);
        psle = clean(psle);
        pg = clean(pg);
        radiusKm = clean(radiusKm);
        mode = clean(mode);
        maxMin = clean(maxMin);
        sort = clean(sort);
    }

    /** No term and no filters. */
    public static FilterParams empty() {
        return new FilterParams(null, null, null, null, null, null, null, null, null, null, false, null);
    }

    /** Reads the parameters of {@code request} as raw text. Never throws; {@code travel} is true for "1" or "true". */
    public static FilterParams from(HttpServletRequest request) {
        String travel = clean(request.getParameter(TRAVEL));
        return new FilterParams(
                request.getParameter(Q),
                values(request, TYPE), values(request, PROGRAMME), values(request, CCA), values(request, DISTRICT),
                request.getParameter(PSLE), request.getParameter(PG), request.getParameter(RADIUS_KM),
                request.getParameter(MODE), request.getParameter(MAX_MIN),
                "1".equals(travel) || "true".equalsIgnoreCase(travel),
                request.getParameter(SORT));
    }

    /** True when any filter category is set: attributes, PSLE, distance, or the travel-time filter (travel=1). */
    public boolean hasAnyFilter() {
        return !activeFilterKeys().isEmpty();
    }

    /**
     * The active filter categories as parameter names, in page order:
     * {@code type, programme, cca, district, psle, radiusKm, travel}. {@code mode}/{@code maxMin} alone
     * (without {@code travel=1}) only refill the form and are not active.
     */
    public List<String> activeFilterKeys() {
        List<String> keys = new ArrayList<>();
        if (!types.isEmpty()) {
            keys.add(TYPE);
        }
        if (!programmes.isEmpty()) {
            keys.add(PROGRAMME);
        }
        if (!ccas.isEmpty()) {
            keys.add(CCA);
        }
        if (!districts.isEmpty()) {
            keys.add(DISTRICT);
        }
        if (psle != null) {
            keys.add(PSLE);
        }
        if (radiusKm != null) {
            keys.add(RADIUS_KM);
        }
        if (travel) {
            keys.add(TRAVEL);
        }
        return keys;
    }

    /**
     * One removable chip per active value (FR-FILTER-08): one per selected type/programme/CCA/district, one for
     * PSLE, one for distance, one for travel time. {@code removeQuery} is {@link #toQueryStringWithout}.
     */
    public List<FilterChip> chips() {
        List<FilterChip> chips = new ArrayList<>();
        addChips(chips, TYPE, "Type", types);
        addChips(chips, PROGRAMME, "Programme", programmes);
        addChips(chips, CCA, "CCA", ccas);
        addChips(chips, DISTRICT, "District", districts);
        if (psle != null) {
            chips.add(chip(PSLE, psle, "PSLE " + psle + " (PG" + (pg == null ? DEFAULT_POSTING_GROUP : pg) + ")"));
        }
        if (radiusKm != null) {
            chips.add(chip(RADIUS_KM, radiusKm, "Within " + radiusKm + " km"));
        }
        if (travel) {
            String label = travelMode().isPresent() && maxMin != null
                    ? "Within " + maxMin + " min by " + modeLabel(travelMode().get())
                    : "Travel time";
            chips.add(chip(TRAVEL, "1", label));
        }
        return chips;
    }

    /** One active filter value with its label and the query string that removes it. */
    public record FilterChip(String param, String value, String label, String removeQuery) {
    }

    /**
     * The parameters as a query string without '?' and without {@code page}, e.g.
     * {@code q=st%20hilda&type=GOVERNMENT%20SCHOOL&psle=12}. Order is fixed (q, type, programme, cca, district,
     * psle, pg, radiusKm, mode, maxMin, travel, sort; repeated values sorted), so equal parameters always give the
     * same URL. Every reserved character is percent-encoded: space → %20, '+' → %2B, '&amp;' → %26.
     * Empty string when nothing is set.
     */
    public String toQueryString() {
        StringBuilder query = new StringBuilder();
        append(query, Q, q);
        types.forEach(v -> append(query, TYPE, v));
        programmes.forEach(v -> append(query, PROGRAMME, v));
        ccas.forEach(v -> append(query, CCA, v));
        districts.forEach(v -> append(query, DISTRICT, v));
        append(query, PSLE, psle);
        append(query, PG, pg);
        append(query, RADIUS_KM, radiusKm);
        append(query, MODE, mode);
        append(query, MAX_MIN, maxMin);
        append(query, TRAVEL, travel ? "1" : null);
        append(query, SORT, sort);
        return query.toString();
    }

    /** {@link #toQueryString()} without one value: see {@link #without(String, String)}. */
    public String toQueryStringWithout(String param, String value) {
        return without(param, value).toQueryString();
    }

    /** {@code path} plus {@code ?} and {@link #toQueryString()} when there is one, e.g. {@code toUrl("/schools/map")}. */
    public String toUrl(String path) {
        String query = toQueryString();
        return query.isEmpty() ? path : path + "?" + query;
    }

    /**
     * A copy without one parameter value, for the chip "remove" links:
     * <ul>
     *   <li>{@code type}, {@code programme}, {@code cca}, {@code district}: removes {@code value}
     *       (null removes the whole category)</li>
     *   <li>{@code psle}: removes psle and pg; {@code travel}: removes travel, mode, maxMin, and sort=COMMUTE_ASC</li>
     *   <li>any other name ({@code q}, {@code pg}, {@code radiusKm}, {@code mode}, {@code maxMin}, {@code sort}):
     *       removes that parameter ({@code value} is ignored)</li>
     * </ul>
     * An unknown name gives an equal copy.
     */
    public FilterParams without(String param, String value) {
        if (param == null) {
            return this;
        }
        String newSort = TRAVEL.equals(param) && sortOrder().orElse(null) == SortOrder.COMMUTE_ASC ? null : sort;
        if (SORT.equals(param)) {
            newSort = null;
        }
        return new FilterParams(
                Q.equals(param) ? null : q,
                TYPE.equals(param) ? minus(types, value) : types,
                PROGRAMME.equals(param) ? minus(programmes, value) : programmes,
                CCA.equals(param) ? minus(ccas, value) : ccas,
                DISTRICT.equals(param) ? minus(districts, value) : districts,
                PSLE.equals(param) ? null : psle,
                PSLE.equals(param) || PG.equals(param) ? null : pg,
                RADIUS_KM.equals(param) ? null : radiusKm,
                TRAVEL.equals(param) || MODE.equals(param) ? null : mode,
                TRAVEL.equals(param) || MAX_MIN.equals(param) ? null : maxMin,
                !TRAVEL.equals(param) && travel,
                newSort);
    }

    /**
     * Builds the filters, in this order: type, programme, CCA, district (one SchoolAttributeFilter per category,
     * values OR-ed), PSLE, distance, travel time. Input errors go into {@code errors} (field → message, see the
     * *_MESSAGE constants) and that filter is left out; the other filters are still returned.
     * <ul>
     *   <li>PSLE needs a whole number 4–32; a blank {@code pg} means posting group 3 ({@link #DEFAULT_PG_HINT}).</li>
     *   <li>Distance and travel time need a resolved {@code ref}; without one they are left out and
     *       {@code errors} gets {@link #LOCATION} → {@link #LOCATION_MESSAGE}.</li>
     *   <li>The travel-time filter is built only when {@code travel} is true (it calls a routing service), and then
     *       needs both {@code mode} and {@code maxMin}. Given values of mode/maxMin are always checked.</li>
     * </ul>
     * Attribute values are not checked against the dataset here; FilterController does that.
     *
     * @param primarySchool the logged-in user's primary school (for the affiliated PSLE range, DC-22), or null
     */
    public List<Filter> toFilters(Optional<ReferenceLocation> ref, String primarySchool, Map<String, String> errors) {
        Objects.requireNonNull(errors, "errors");
        List<Filter> filters = new ArrayList<>();
        addAttributeFilter(filters, AttributeCategory.SCHOOL_TYPE, types);
        addAttributeFilter(filters, AttributeCategory.PROGRAMME, programmes);
        addAttributeFilter(filters, AttributeCategory.CCA, ccas);
        addAttributeFilter(filters, AttributeCategory.DISTRICT, districts);

        Integer score = psle == null ? null : wholeNumberIn(psle, 4, 32);
        if (psle != null && score == null) {
            errors.put(PSLE, PSLE_MESSAGE);
        }
        Integer group = pg == null ? Integer.valueOf(DEFAULT_POSTING_GROUP) : wholeNumberIn(pg, 1, 3);
        if (group == null) {
            errors.put(PG, PG_MESSAGE);
        }
        if (score != null && group != null) {
            filters.add(new PsleScoreFilter(score, group, primarySchool));   // DC-40
        }

        ReferenceLocation start = ref == null ? null : ref.filter(ReferenceLocation::isResolved).orElse(null);
        boolean needsLocation = false;
        if (radiusKm != null) {
            Integer radius = wholeNumber(radiusKm);
            if (radius == null || !ProximityFilter.RADIUS_OPTIONS_KM.contains(radius)) {
                errors.put(RADIUS_KM, RADIUS_MESSAGE);
            } else if (start == null) {
                needsLocation = true;
            } else {
                filters.add(new ProximityFilter(start, radius));
            }
        }

        Optional<TravelMode> travelMode = travelMode();
        if (mode != null ? travelMode.isEmpty() : travel) {
            errors.put(MODE, MODE_MESSAGE);
        }
        Integer minutes = maxMin == null ? null : wholeNumber(maxMin);
        boolean minutesValid = minutes != null && TransportationFilter.DURATION_OPTIONS_MIN.contains(minutes);
        if (maxMin != null ? !minutesValid : travel) {
            errors.put(MAX_MIN, DURATION_MESSAGE);
        }
        if (travel && travelMode.isPresent() && minutesValid) {
            if (start == null) {
                needsLocation = true;
            } else {
                filters.add(new TransportationFilter(start, travelMode.get(), minutes));
            }
        }

        if (needsLocation) {
            errors.put(LOCATION, LOCATION_MESSAGE);
        }
        return filters;
    }

    /** The requested order, if {@code sort} names one (ignoring case); otherwise empty (use NAME_ASC). */
    public Optional<SortOrder> sortOrder() {
        return parseEnum(SortOrder.class, sort);
    }

    /** The travel mode, if {@code mode} names one (WALK, DRIVE, TRANSIT; ignoring case). */
    public Optional<TravelMode> travelMode() {
        return parseEnum(TravelMode.class, mode);
    }

    /** True when a PSLE score is given without a posting group, so posting group 3 is used ({@link #DEFAULT_PG_HINT}). */
    public boolean usesDefaultPostingGroup() {
        return psle != null && pg == null;
    }

    /** Label for a travel mode on pages and chips: walking, car, public transport. */
    public static String modeLabel(TravelMode mode) {
        return switch (mode) {
            case WALK -> "walking";
            case DRIVE -> "car";
            case TRANSIT -> "public transport";
        };
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static Set<String> cleanSet(Collection<String> values) {
        Set<String> cleaned = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                String c = clean(value);
                if (c != null) {
                    cleaned.add(c);
                }
            }
        }
        return Collections.unmodifiableSet(cleaned);
    }

    private static Set<String> values(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values == null ? Set.of() : cleanSet(Arrays.asList(values));
    }

    private static Set<String> minus(Set<String> values, String value) {
        if (value == null) {
            return Set.of();
        }
        Set<String> copy = new TreeSet<>(values);
        copy.remove(value.strip());
        return copy;
    }

    private void addChips(List<FilterChip> chips, String param, String label, Set<String> values) {
        values.forEach(v -> chips.add(chip(param, v, label + ": " + v)));
    }

    private FilterChip chip(String param, String value, String label) {
        return new FilterChip(param, value, label, toQueryStringWithout(param, value));
    }

    private static void addAttributeFilter(List<Filter> filters, AttributeCategory category, Set<String> values) {
        if (!values.isEmpty()) {
            filters.add(new SchoolAttributeFilter(category, values));
        }
    }

    private static Integer wholeNumber(String text) {
        return text != null && WHOLE_NUMBER.matcher(text).matches() ? Integer.valueOf(text) : null;
    }

    private static Integer wholeNumberIn(String text, int min, int max) {
        Integer n = wholeNumber(text);
        return n != null && n >= min && n <= max ? n : null;
    }

    private static <E extends Enum<E>> Optional<E> parseEnum(Class<E> type, String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, text.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static void append(StringBuilder query, String name, String value) {
        if (value == null) {
            return;
        }
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(name).append('=').append(encode(value));
    }

    /** Percent-encodes everything except letters, digits and {@code . - _ *}; space becomes %20 (not '+'). */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

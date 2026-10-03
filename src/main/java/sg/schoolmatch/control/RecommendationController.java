package sg.schoolmatch.control;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.recommend.MatchCriteria;
import sg.schoolmatch.entity.recommend.MatchFactor;
import sg.schoolmatch.entity.recommend.Recommendation;
import sg.schoolmatch.entity.recommend.ScoreComponent;
import sg.schoolmatch.entity.route.Route;
import sg.schoolmatch.entity.route.TravelMode;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.search.TransportationFilter;
import sg.schoolmatch.entity.shortlist.SchoolChoice;
import sg.schoolmatch.error.ExternalFailureLog;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Design class «control» RecommendationController — ranks schools for a student's criteria
 * (use case Get School Recommendations; FR-REC-01, proposed id). Scoring rules: DC-20,
 * docs/recommendation-scoring.md section 5. Called by RecommendationUI.
 * <ol>
 *   <li>Candidates: schools with a PSLE range for the posting group (DC-22; the affiliated range when the
 *       criteria's primary school is affiliated). PSLE_FIT: MATCH 1.0, SAFE 0.7, REACH within U + 2 0.3,
 *       any other school is excluded.</li>
 *   <li>CCA / PROGRAMME: the share of the preferred items the school offers; no preference → the factor drops out.</li>
 *   <li>The top {@code app.recommendation.commute-candidates} (20) by that score get travel times in one
 *       {@link DirectionsController#getCommuteTimes} call. COMMUTE = 1 − min(t / max, 1); over the limit → excluded;
 *       no route or service down → "Not available" and its weight is spread over the other factors.</li>
 *   <li>Total = Σ weight × score ÷ Σ weight over the factors that count; top {@code app.recommendation.top-n} (10),
 *       ties by shorter commute, then name.</li>
 * </ol>
 * DC-74: when the dataset has no PSLE ranges at all ({@link SchoolDataController#hasPsleData()}), PSLE_FIT is not
 * used: it gets weight 0 (the other weights are rescaled, as for a factor without a preference), every school is a
 * candidate, each result says {@link #PSLE_FIT_NOT_USED}, and the PSLE score and posting group are optional.
 */
@Service
public class RecommendationController {

    static final String PSLE_MESSAGE = "Enter a whole number from 4 to 32";
    static final String PG_MESSAGE = "Choose posting group 1, 2 or 3";
    static final String MODE_MESSAGE = "Choose walk, drive or public transport";
    static final String DURATION_MESSAGE = "Choose 15, 30, 45 or 60 minutes";
    static final String START_MESSAGE = "Set a starting point so commute times can be measured";
    static final String WEIGHTS_MESSAGE = "Weights must be 0 or more";
    /** DC-74: the PSLE_FIT reason of every result when the dataset has no PSLE ranges. */
    public static final String PSLE_FIT_NOT_USED = "PSLE fit not used: no score ranges in the current dataset";

    private static final Logger log = LoggerFactory.getLogger(RecommendationController.class);

    private final LocationController locationController;   // design arrow; the start location arrives resolved
    private final DirectionsController directionsController;
    private final SchoolDataController schoolDataController;
    private final AppProperties props;

    public RecommendationController(LocationController locationController, DirectionsController directionsController,
                                    SchoolDataController schoolDataController, AppProperties props) {
        this.locationController = locationController;
        this.directionsController = directionsController;
        this.schoolDataController = schoolDataController;
        this.props = props;
    }

    /**
     * The top recommendations, best first, with {@code rank} 1..n. Empty when no school fits (AF-2).
     *
     * @throws InvalidInputException when the criteria are not valid (field → message, NFR-USE-03)
     * @throws ExternalServiceUnavailableException when the school data cannot be read (EX-1)
     */
    public List<Recommendation> recommend(MatchCriteria criteria) {
        boolean psleData;
        try {
            psleData = schoolDataController.hasPsleData();   // DC-74
        } catch (RuntimeException e) {
            throw new ExternalServiceUnavailableException("School data", e);
        }
        Map<String, String> errors = validate(criteria, psleData);
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        List<School> schools;
        try {
            schools = schoolDataController.getSchools();
        } catch (RuntimeException e) {
            throw new ExternalServiceUnavailableException("School data", e);
        }

        List<Recommendation> scored = new ArrayList<>();
        for (School school : schools) {
            Recommendation rec = psleData ? scoreSchool(school, criteria) : scoreWithoutPsleFit(school, criteria);
            if (rec != null) {
                scored.add(rec);
            }
        }
        if (scored.isEmpty()) {
            return List.of();
        }
        Coordinate start = criteria.getStartLocation().getCoordinate();
        scored.sort(Comparator.comparingDouble((Recommendation r) -> -rounded(r.getTotalScore()))
                .thenComparingDouble(r -> r.getSchool().hasValidCoordinate()
                        ? r.getSchool().distanceTo(start) : Double.MAX_VALUE)
                .thenComparing(r -> r.getSchool(), BY_NAME));
        List<Recommendation> shortlist =
                new ArrayList<>(scored.subList(0, Math.min(scored.size(), props.recommendation().commuteCandidates())));
        addCommuteTimes(shortlist, criteria, psleData);
        rank(shortlist);
        return List.copyOf(shortlist);
    }

    /**
     * Field → message for every problem at once (AF-1 of the use case: the form shows them all).
     * DC-74: without PSLE data the score and posting group are optional (still checked when given).
     */
    private static Map<String, String> validate(MatchCriteria criteria, boolean psleData) {
        Map<String, String> errors = new LinkedHashMap<>();
        Integer score = criteria.getPsleScore();
        if (score == null ? psleData : score < 4 || score > 32) {
            errors.put("psleScore", PSLE_MESSAGE);
        }
        Integer pg = criteria.getPostingGroup();
        if (pg == null ? psleData : pg < 1 || pg > 3) {
            errors.put("postingGroup", PG_MESSAGE);
        }
        if (criteria.getTravelMode() == null) {
            errors.put("travelMode", MODE_MESSAGE);
        }
        if (criteria.getMaxCommuteMin() == null
                || !TransportationFilter.DURATION_OPTIONS_MIN.contains(criteria.getMaxCommuteMin())) {
            errors.put("maxCommuteMin", DURATION_MESSAGE);
        }
        if (criteria.getStartLocation() == null || !criteria.getStartLocation().isResolved()) {
            errors.put("startLocation", START_MESSAGE);
        }
        if (criteria.getWeights().values().stream().anyMatch(w -> w == null || w < 0)) {
            errors.put("weights", WEIGHTS_MESSAGE);
        }
        return errors;
    }

    /** PSLE_FIT, CCA and PROGRAMME for one school; null when the school is not a candidate. */
    private Recommendation scoreSchool(School school, MatchCriteria criteria) {
        int score = criteria.getPsleScore();
        Optional<IndicativePsleScoreRange> range =
                school.getScoreRange(criteria.getPostingGroup(), isAffiliated(school, criteria.getPrimarySchool()));
        if (range.isEmpty()) {
            return null;
        }
        ScoreComponent fit = psleFit(score, range.get());
        if (fit == null) {
            return null;
        }
        List<ScoreComponent> components = new ArrayList<>();
        components.add(fit);
        if (!criteria.getPreferredCCAs().isEmpty()) {
            components.add(share(MatchFactor.CCA, criteria.getPreferredCCAs(), school.getCcas(), "CCAs"));
        }
        if (!criteria.getPreferredProgrammes().isEmpty()) {
            components.add(share(MatchFactor.PROGRAMME, criteria.getPreferredProgrammes(), school.getProgrammes(),
                    "programmes"));
        }
        return new Recommendation(school, total(components, weights(criteria), false, true), components);
    }

    /**
     * DC-74: CCA and PROGRAMME for one school when the dataset has no PSLE ranges. Every school is a candidate;
     * PSLE_FIT is listed with {@link #PSLE_FIT_NOT_USED} and left out of the total (weight 0, the rest rescaled).
     */
    private Recommendation scoreWithoutPsleFit(School school, MatchCriteria criteria) {
        List<ScoreComponent> components = new ArrayList<>();
        components.add(new ScoreComponent(MatchFactor.PSLE_FIT, 0, PSLE_FIT_NOT_USED));
        if (!criteria.getPreferredCCAs().isEmpty()) {
            components.add(share(MatchFactor.CCA, criteria.getPreferredCCAs(), school.getCcas(), "CCAs"));
        }
        if (!criteria.getPreferredProgrammes().isEmpty()) {
            components.add(share(MatchFactor.PROGRAMME, criteria.getPreferredProgrammes(), school.getProgrammes(),
                    "programmes"));
        }
        return new Recommendation(school, total(components, weights(criteria), false, false), components);
    }

    /**
     * Adds COMMUTE to each recommendation (one route-matrix call for all of them) and removes the schools whose
     * travel time is over {@code maxCommuteMin}. DC-31: takes the criteria. DC-74: {@code psleFitUsed} is false when
     * the dataset has no PSLE ranges.
     */
    private void addCommuteTimes(List<Recommendation> recs, MatchCriteria criteria, boolean psleFitUsed) {
        TravelMode mode = criteria.getTravelMode();
        int max = criteria.getMaxCommuteMin();
        List<School> schools = recs.stream().map(Recommendation::getSchool).toList();
        List<Route> routes;
        try {
            routes = directionsController.getCommuteTimes(criteria.getStartLocation(), schools, mode);
            if (routes == null || routes.size() != schools.size()) {
                log.warn("Commute times: expected {} routes, got {}", schools.size(), routes == null ? null : routes.size());
                routes = null;
            }
        } catch (ExternalServiceUnavailableException e) {
            ExternalFailureLog.warn(log, "Commute times for recommendations", e);
            routes = null;
        }

        Map<MatchFactor, Double> weights = weights(criteria);
        List<Recommendation> result = new ArrayList<>();
        for (int i = 0; i < recs.size(); i++) {
            Recommendation rec = recs.get(i);
            List<ScoreComponent> components = new ArrayList<>(rec.getComponents());
            Route route = routes == null ? null : routes.get(i);
            Integer minutes = null;
            if (routes == null) {
                components.add(new ScoreComponent(MatchFactor.COMMUTE, 0, "Commute: Not available (the route service "
                        + "did not answer); its weight is shared by the other factors"));
            } else if (route == null || !route.isAvailable() || route.getDurationSeconds() == null) {
                components.add(new ScoreComponent(MatchFactor.COMMUTE, 0, "Commute: Not available (No route found "
                        + modeLabel(mode) + "); its weight is shared by the other factors"));
            } else {
                long seconds = route.getDurationSeconds();
                if (seconds > max * 60L) {
                    continue;   // over the student's limit (t > maxCommuteMin, decided on seconds): excluded
                }
                minutes = (int) ((seconds + 59) / 60);   // rounded up, like the travel-time filter (FilterController)
                double commute = 1 - Math.min((double) minutes / max, 1);
                components.add(new ScoreComponent(MatchFactor.COMMUTE, commute,
                        "About " + minutes + " min " + modeLabel(mode) + " (your limit is " + max + " min)"));
            }
            Recommendation withCommute =
                    new Recommendation(rec.getSchool(), total(components, weights, minutes != null, psleFitUsed),
                            components);
            withCommute.setCommuteMin(minutes);
            result.add(withCommute);
        }
        recs.clear();
        recs.addAll(result);
    }

    /** Best total first; ties by shorter commute (unknown last), then name; keeps the top N and numbers them 1..n. */
    private void rank(List<Recommendation> recs) {
        recs.sort(Comparator.comparingDouble((Recommendation r) -> -rounded(r.getTotalScore()))
                .thenComparing(Recommendation::getCommuteMin, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Recommendation::getSchool, BY_NAME));
        int topN = props.recommendation().topN();
        if (recs.size() > topN) {
            recs.subList(topN, recs.size()).clear();
        }
        for (int i = 0; i < recs.size(); i++) {
            recs.get(i).setRank(i + 1);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final Comparator<School> BY_NAME = Comparator
            .comparing(School::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(School::getSchoolCode, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * DC-20 labels with U = upperScore and m = SchoolChoice.SAFE_MARGIN: SAFE if score ≤ U − m, MATCH if
     * U − m &lt; score ≤ U, REACH if U &lt; score ≤ U + {@code app.recommendation.reach-near-margin} (2; still a
     * candidate), otherwise null (excluded). The scores come from {@code app.recommendation.psle-fit}.
     */
    private ScoreComponent psleFit(int score, IndicativePsleScoreRange range) {
        AppProperties.PsleFitSettings fit = props.recommendation().psleFit();   // DC-53
        int upper = range.getUpperScore();
        String rangeText = range.getAdmissionYear() + " PG" + range.getPostingGroup()
                + (range.isAffiliated() ? " affiliated" : "") + " range " + range.getRangeText();   // "IP 4–8" for IP (DC-77)
        if (score <= upper - SchoolChoice.SAFE_MARGIN) {
            return new ScoreComponent(MatchFactor.PSLE_FIT, fit.safe(), "PSLE " + score + " is "
                    + SchoolChoice.SAFE_MARGIN + " or more points below the cut-off of the " + rangeText + " → SAFE");
        }
        if (score <= upper) {
            return new ScoreComponent(MatchFactor.PSLE_FIT, fit.match(),
                    "PSLE " + score + " is within the " + rangeText + " → MATCH");
        }
        if (score <= upper + props.recommendation().reachNearMargin()) {
            int over = score - upper;
            return new ScoreComponent(MatchFactor.PSLE_FIT, fit.reachNear(), "PSLE " + score + " is " + over
                    + (over == 1 ? " point" : " points") + " above the cut-off of the " + rangeText + " → REACH");
        }
        return null;
    }

    /** Share of {@code preferred} that {@code offered} contains (case-insensitive), with the matches in the reason. */
    private static ScoreComponent share(MatchFactor factor, Set<String> preferred, Set<String> offered, String noun) {
        Set<String> offeredUpper = offered.stream().map(RecommendationController::upper).collect(Collectors.toSet());
        List<String> matched = preferred.stream().filter(p -> offeredUpper.contains(upper(p))).toList();
        String reason = matched.isEmpty() ? "Offers none of your " + preferred.size() + " " + noun
                : "Offers " + matched.size() + " of your " + preferred.size() + " " + noun + ": "
                        + String.join(", ", matched);
        return new ScoreComponent(factor, (double) matched.size() / preferred.size(), reason);
    }

    /**
     * Σ weight × score ÷ Σ weight; COMMUTE counts only when the travel time is known, PSLE_FIT only when the
     * dataset has PSLE ranges (DC-74). 0 when nothing counts.
     */
    private static double total(List<ScoreComponent> components, Map<MatchFactor, Double> weights, boolean commuteKnown,
                                boolean psleFitUsed) {
        double weighted = 0;
        double weightSum = 0;
        for (ScoreComponent c : components) {
            if (c.getFactor() == MatchFactor.COMMUTE && !commuteKnown) {
                continue;
            }
            if (c.getFactor() == MatchFactor.PSLE_FIT && !psleFitUsed) {
                continue;   // DC-74: weight 0, the other weights are rescaled
            }
            double w = weights.getOrDefault(c.getFactor(), 0.0);
            weighted += w * c.getScore();
            weightSum += w;
        }
        return weightSum == 0 ? 0 : weighted / weightSum;
    }

    /** The criteria's weights, or {@code app.recommendation.weights} when it has none. */
    private Map<MatchFactor, Double> weights(MatchCriteria criteria) {
        return criteria.getWeights().isEmpty() ? props.recommendation().weights() : criteria.getWeights();
    }

    /**
     * DC-22: affiliated when the student's primary school is one of the school's affiliated primary schools (name rule:
     * {@link School#hasAffiliatedPrimarySchool}, shared with search and the choice plan).
     */
    private static boolean isAffiliated(School school, String primarySchool) {
        return school.hasAffiliatedPrimarySchool(primarySchool);
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** "by public transport", "on foot", "by car". */
    static String modeLabel(TravelMode mode) {
        return switch (mode) {
            case WALK -> "on foot";
            case DRIVE -> "by car";
            case TRANSIT -> "by public transport";
        };
    }

    /** Scores compared to 6 decimals, so rounding noise never decides a tie. */
    private static double rounded(double score) {
        return Math.round(score * 1_000_000d) / 1_000_000d;
    }
}

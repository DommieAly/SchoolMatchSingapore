package sg.schoolmatch.control;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sg.schoolmatch.boundary.external.ExternalCallBudget;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.common.Place;
import sg.schoolmatch.entity.facility.Facility;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.entity.search.CurrentResultSet;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Design class «control» MapController — decides what the maps show (use cases View Schools on Map,
 * View School's Districts, View Facilities on Map; FR-MAP-01..07, FR-FACMAP-01..05). DC-16: returns data; the
 * browser (static/js/GoogleMapsPlatformInterface.js) draws. Called by SchoolMapUI, FacilityMapUI, DirectionsUI.
 * <p>
 * Spending limit (spec §2.5): in live Google mode every interactive map counts as one {@code map-loads} unit in
 * {@link ExternalCallBudget}; when today's limit is used up the page shows the list without the map.
 */
@Service
public class MapController {

    private static final Logger log = LoggerFactory.getLogger(MapController.class);

    private final SchoolDataController schoolDataController;
    private final AppProperties props;
    private final ExternalCallBudget budget;
    private final JsonMapper jsonMapper;

    public MapController(SchoolDataController schoolDataController, AppProperties props, ExternalCallBudget budget,
                         JsonMapper jsonMapper) {
        this.schoolDataController = schoolDataController;
        this.props = props;
        this.budget = budget;
        this.jsonMapper = jsonMapper;
    }

    /** DC-16: the schools that can get a marker — those with a valid coordinate (FR-MAP-03). */
    public List<School> showSchoolsOnMap(CurrentResultSet results) {
        return results.getSchools().stream().filter(Place::hasValidCoordinate).toList();
    }

    /** The facilities that can get a marker; others are left out without blocking the rest (FR-FACMAP-05). */
    public List<Facility> showFacilitiesOnMap(List<Facility> facilities) {
        return facilities.stream().filter(Place::hasValidCoordinate).toList();
    }

    /**
     * DC-16: the district (planning area) boundaries as a GeoJSON FeatureCollection string, or "" when not visible
     * (FR-MAP-06, FR-MAP-07). Each Feature has the district's boundary as geometry and properties
     * {@code code} and {@code name}. A district whose boundary is missing or is not a GeoJSON object is left out
     * (logged), so the others still show. Served by {@code GET /api/districts}.
     */
    public String toggleDistricts(boolean visible) {   // DC-16, DC-59
        if (!visible) {
            return "";
        }
        ObjectNode collection = jsonMapper.createObjectNode();
        collection.put("type", "FeatureCollection");
        ArrayNode features = collection.putArray("features");
        for (District district : schoolDataController.getDistricts()) {
            JsonNode geometry = boundary(district);
            if (geometry == null) {
                continue;
            }
            ObjectNode feature = features.addObject();
            feature.put("type", "Feature");
            feature.set("geometry", geometry);
            ObjectNode properties = feature.putObject("properties");
            properties.put("code", district.getPlanningAreaCode());
            properties.put("name", district.getPlanningAreaName());
        }
        return jsonMapper.writeValueAsString(collection);
    }

    /**
     * DC-16: true when at least one place has a valid coordinate AND a Google browser key is configured;
     * otherwise the page shows "Map unavailable" and its list still works (UC View Schools on Map EX-1).
     * In live Google mode this charges one map load; when today's limit is reached it returns false.
     */
    public boolean displayInteractiveMap(List<? extends Place> places) {
        if (!props.google().hasBrowserKey()
                || places == null || places.stream().noneMatch(Place::hasValidCoordinate)) {
            return false;
        }
        if (props.external().google().isLive()) {
            try {
                budget.charge(ExternalCallBudget.MAP_LOADS, 1);
            } catch (ExternalServiceUnavailableException e) {
                log.warn("Today's Google map-load limit is used up; pages show their lists without a map");
                return false;
            }
        }
        return true;
    }

    /** The district's GeoJSON geometry (a whole Feature is accepted too), or null when missing or unreadable. */
    private JsonNode boundary(District district) {
        String text = district.getBoundaryGeoJson();
        if (text == null || text.isBlank()) {
            log.warn("District {} has no boundary; it is left off the map", district.getPlanningAreaCode());
            return null;
        }
        try {
            JsonNode node = jsonMapper.readTree(text);
            if (node != null && node.isObject() && "Feature".equals(node.path("type").asString(""))) {
                node = node.get("geometry");
            }
            if (node == null || !node.isObject() || !node.path("type").isString()) {
                log.warn("District {} boundary is not a GeoJSON geometry; it is left off the map",
                        district.getPlanningAreaCode());
                return null;
            }
            return node;
        } catch (JacksonException e) {
            log.warn("District {} boundary is not valid JSON; it is left off the map: {}",
                    district.getPlanningAreaCode(), e.getOriginalMessage());
            return null;
        }
    }
}

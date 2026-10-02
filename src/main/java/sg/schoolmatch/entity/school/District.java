package sg.schoolmatch.entity.school;

import java.util.Objects;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import sg.schoolmatch.entity.common.Coordinate;

/**
 * Design class «entity» District — a URA planning area with its boundary (FR-MAP-06, DC-04).
 * {@code boundaryGeoJson} is the GeoJSON geometry of the area's Feature (Polygon or MultiPolygon), kept as a JSON
 * string. The importer uses {@link #contains} to find each school's planning area (DC-12).
 */
public class District {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private final String planningAreaCode;
    private final String planningAreaName;
    private final String boundaryGeoJson;

    /** {@code boundaryGeoJson} parsed by JTS on first use; never changes afterwards. */
    private transient volatile Geometry boundary;

    public District(String planningAreaCode, String planningAreaName, String boundaryGeoJson) {
        this.planningAreaCode = planningAreaCode;
        this.planningAreaName = planningAreaName;
        this.boundaryGeoJson = boundaryGeoJson;
    }

    /**
     * True when {@code point} lies inside this planning area's boundary or on its edge (JTS {@code covers}).
     * GeoJSON order is longitude, latitude, so longitude is x and latitude is y. A point in a hole is outside.
     * False for a null point or a district without a boundary.
     *
     * @throws IllegalStateException when the boundary is not valid GeoJSON
     */
    public boolean contains(Coordinate point) {   // DC-51
        if (point == null || boundaryGeoJson == null || boundaryGeoJson.isBlank()) {
            return false;
        }
        org.locationtech.jts.geom.Coordinate xy =
                new org.locationtech.jts.geom.Coordinate(point.getLongitude(), point.getLatitude());
        return boundary().covers(GEOMETRY_FACTORY.createPoint(xy));
    }

    private Geometry boundary() {
        Geometry parsed = boundary;
        if (parsed == null) {
            try {
                parsed = new GeoJsonReader(GEOMETRY_FACTORY).read(boundaryGeoJson);
            } catch (ParseException | RuntimeException e) {
                throw new IllegalStateException("District " + planningAreaCode + " has an invalid boundary: "
                        + e.getMessage(), e);
            }
            boundary = parsed;
        }
        return parsed;
    }

    public String getPlanningAreaCode() {
        return planningAreaCode;
    }

    public String getPlanningAreaName() {
        return planningAreaName;
    }

    public String getBoundaryGeoJson() {
        return boundaryGeoJson;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof District other && Objects.equals(planningAreaCode, other.planningAreaCode));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(planningAreaCode);
    }

    @Override
    public String toString() {
        return "District{" + planningAreaCode + ", " + planningAreaName + "}";
    }
}

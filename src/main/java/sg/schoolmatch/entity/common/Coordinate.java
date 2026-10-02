package sg.schoolmatch.entity.common;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Design class «entity» Coordinate — a latitude/longitude point (WGS84, degrees).
 * <p>
 * Reference example of a fully implemented entity: validation in the constructor,
 * small pure methods, and a plain JUnit test ({@code CoordinateTest}).
 * Used by FR-DATA-06, FR-MAP-03, FR-FILTER-05, FR-FACILITY-03, NFR-DATA-02.
 */
@Embeddable
public class Coordinate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Mean earth radius in km (IUGG value), used by the haversine formula. */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    // Bounding box of Singapore (main island and offshore islands).
    private static final double SG_MIN_LAT = 1.15;
    private static final double SG_MAX_LAT = 1.48;
    private static final double SG_MIN_LNG = 103.59;
    private static final double SG_MAX_LNG = 104.10;

    @Column(name = "latitude")
    private double latitude;

    @Column(name = "longitude")
    private double longitude;

    /** For JPA only. */
    protected Coordinate() {
    }

    /**
     * @throws IllegalArgumentException if latitude is outside -90..90 or longitude outside -180..180
     */
    public Coordinate(double latitude, double longitude) {
        if (Double.isNaN(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90, got " + latitude);
        }
        if (Double.isNaN(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180, got " + longitude);
        }
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    /** True when the point lies inside Singapore's bounding box (lat 1.15..1.48, lng 103.59..104.10). */
    public boolean isWithinSingapore() {
        return latitude >= SG_MIN_LAT && latitude <= SG_MAX_LAT
                && longitude >= SG_MIN_LNG && longitude <= SG_MAX_LNG;
    }

    /** Straight-line (great-circle) distance to {@code other} in kilometres, using the haversine formula. */
    public double distanceTo(Coordinate other) {
        Objects.requireNonNull(other, "other coordinate");
        double dLat = Math.toRadians(other.latitude - latitude);
        double dLng = Math.toRadians(other.longitude - longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Coordinate other)) {
            return false;
        }
        return Double.compare(latitude, other.latitude) == 0 && Double.compare(longitude, other.longitude) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(latitude, longitude);
    }

    @Override
    public String toString() {
        return "(" + latitude + ", " + longitude + ")";
    }
}

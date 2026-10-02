package sg.schoolmatch.entity.common;

/**
 * Design class «entity» Place — common fields of anything shown on a map (a School or a Facility).
 * Missing values are {@code null}; templates show "Not available" (FR-SCHOOL-03, FR-FACDETAIL-03).
 */
public abstract class Place {

    private String name;
    private String address;
    private Coordinate coordinate;   // may be null when the source has no location
    private String telephone;
    private String website;

    protected Place(String name) {
        this.name = name;
    }

    /** True when the coordinate is present and inside Singapore (FR-MAP-03, FR-FACMAP-05, NFR-DATA-02). */
    public boolean hasValidCoordinate() {
        return coordinate != null && coordinate.isWithinSingapore();
    }

    /**
     * Straight-line distance in km from this place to {@code point}.
     *
     * @throws IllegalStateException if this place has no coordinate
     */
    public double distanceTo(Coordinate point) {
        if (coordinate == null) {
            throw new IllegalStateException("Place '" + name + "' has no coordinate");
        }
        return coordinate.distanceTo(point);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Coordinate getCoordinate() {
        return coordinate;
    }

    public void setCoordinate(Coordinate coordinate) {
        this.coordinate = coordinate;
    }

    public String getTelephone() {
        return telephone;
    }

    public void setTelephone(String telephone) {
        this.telephone = telephone;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }
}

package sg.schoolmatch.entity.location;

import java.io.Serial;
import java.io.Serializable;
import sg.schoolmatch.entity.common.Coordinate;

/**
 * Design class «entity» ReferenceLocation — the user's starting point, from the device or typed in
 * (FR-FILTER-04, FR-ROUTE-02). Kept in the servlet session (page state only), hence Serializable.
 */
public class ReferenceLocation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Coordinate coordinate;   // null until resolved
    private final LocationSource source;
    private final String inputText;        // what the user typed, or a label such as "My location"

    public ReferenceLocation(Coordinate coordinate, LocationSource source, String inputText) {
        this.coordinate = coordinate;
        this.source = source;
        this.inputText = inputText;
    }

    /** Resolved = has a coordinate inside Singapore. */
    public boolean isResolved() {
        return coordinate != null && coordinate.isWithinSingapore();
    }

    public Coordinate getCoordinate() {
        return coordinate;
    }

    public LocationSource getSource() {
        return source;
    }

    public String getInputText() {
        return inputText;
    }

    @Override
    public String toString() {
        return "ReferenceLocation{" + source + ", " + inputText + ", " + coordinate + "}";
    }
}

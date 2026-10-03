package sg.schoolmatch.persistence.dataset;

/** One row of {@code school_mrt_station}: one station, {@code listPosition} 1, 2, 3 ... in published order. */
public record SchoolMrtStationRow(
        String schoolCode,
        String stationName,
        int listPosition) {
}

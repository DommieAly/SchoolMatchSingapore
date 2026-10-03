package sg.schoolmatch.persistence.dataset;

/** One row of {@code school_bus_service}: one bus service, {@code listPosition} 1, 2, 3 ... in published order. */
public record SchoolBusServiceRow(
        String schoolCode,
        String serviceNo,
        int listPosition) {
}

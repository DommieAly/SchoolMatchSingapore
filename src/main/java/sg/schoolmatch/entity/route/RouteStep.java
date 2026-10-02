package sg.schoolmatch.entity.route;

/** Design class «entity» RouteStep — one instruction of a Route (FR-ROUTE-07). {@code sequenceNo} starts at 1. */
public class RouteStep {

    private final int sequenceNo;
    private final String instruction;
    private final Integer distanceMetres;   // null when unknown

    public RouteStep(int sequenceNo, String instruction, Integer distanceMetres) {
        this.sequenceNo = sequenceNo;
        this.instruction = instruction;
        this.distanceMetres = distanceMetres;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public String getInstruction() {
        return instruction;
    }

    public Integer getDistanceMetres() {
        return distanceMetres;
    }
}

package sg.schoolmatch.boundary.external;

/** One OneMap search result (fields SEARCHVAL, BUILDING, ADDRESS, POSTAL, LATITUDE, LONGITUDE). */
public record OneMapHit(String searchVal, String building, String address, String postal,
                        double latitude, double longitude) {
}

package sg.schoolmatch.dataset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One school as stored in {@code schools.json} (JSON DTO; the app works with {@code School}).
 * Missing values are JSON {@code null}. Numbers are boxed so the validator can report missing ones.
 * <p>
 * DC-84 (snapshot format 2): {@code mrtStations} and {@code busServices} are lists in MOE's published order, made by
 * {@link TransportLists} from MOE's {@code mrt_desc} / {@code bus_desc} text. They replace the format-1 texts
 * {@code nearestMrt} and {@code busInfo}; {@link SnapshotReader} joins them back into {@code School.nearestMrt} and
 * {@code School.busInfo}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SchoolRecord(
        String schoolCode,
        String name,
        String address,
        String postalCode,
        Double latitude,
        Double longitude,
        String telephone,
        String website,
        String email,
        String schoolType,
        String planningAreaCode,
        String planningAreaName,
        List<String> mrtStations,
        List<String> busServices,
        String sessionType,
        String schoolNature,
        List<String> programmes,
        List<String> ccas,
        List<String> affiliatedPrimarySchools,
        String ipRangeNote,
        List<ScoreRangeRecord> scoreRanges) {

    public SchoolRecord {
        mrtStations = mrtStations == null ? List.of() : List.copyOf(mrtStations);
        busServices = busServices == null ? List.of() : List.copyOf(busServices);
        programmes = programmes == null ? List.of() : List.copyOf(programmes);
        ccas = ccas == null ? List.of() : List.copyOf(ccas);
        affiliatedPrimarySchools = affiliatedPrimarySchools == null ? List.of() : List.copyOf(affiliatedPrimarySchools);
        scoreRanges = scoreRanges == null ? List.of() : List.copyOf(scoreRanges);
    }
}

package sg.schoolmatch.dataset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One school as stored in {@code schools.json} (JSON DTO; the app works with {@code School}).
 * Missing values are JSON {@code null}. Numbers are boxed so the validator can report missing ones.
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
        String nearestMrt,
        String busInfo,
        String sessionType,
        String schoolNature,
        List<String> programmes,
        List<String> ccas,
        List<String> affiliatedPrimarySchools,
        String ipRangeNote,
        List<ScoreRangeRecord> scoreRanges) {

    public SchoolRecord {
        programmes = programmes == null ? List.of() : List.copyOf(programmes);
        ccas = ccas == null ? List.of() : List.copyOf(ccas);
        affiliatedPrimarySchools = affiliatedPrimarySchools == null ? List.of() : List.copyOf(affiliatedPrimarySchools);
        scoreRanges = scoreRanges == null ? List.of() : List.copyOf(scoreRanges);
    }
}

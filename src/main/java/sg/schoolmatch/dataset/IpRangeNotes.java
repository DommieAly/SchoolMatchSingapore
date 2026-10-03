package sg.schoolmatch.dataset;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;

/**
 * The text of {@code School.ipRangeNote} (DC-18): the school's Integrated Programme ranges as
 * {@code IP <year> PG<pg>[ affiliated]: <MOE text>}, joined by {@code "; "}, newest year first and non-affiliated
 * before affiliated. Without MOE text a range shows {@code lower–upper}. The note is computed, never stored
 * (docs/database-design.md, section 2.3). Used by the importer and, from step 5, by the database mapper.
 */
public final class IpRangeNotes {

    private IpRangeNotes() {
    }

    /** One IP range, whichever class it came from. */
    private record Ip(int year, int postingGroup, boolean affiliated, int lower, int upper, String moeText) {

        String text() {
            return "IP " + year + " PG" + postingGroup + (affiliated ? " affiliated" : "") + ": "
                    + (moeText != null ? moeText : lower + "–" + upper);
        }
    }

    private static final Comparator<Ip> NEWEST_FIRST_NON_AFFILIATED_FIRST =
            Comparator.comparingInt(Ip::year).reversed().thenComparing(Ip::affiliated);

    /** The note for the snapshot ranges of one school; null when it has no complete IP range. */
    public static String of(Collection<ScoreRangeRecord> ranges) {
        return join(ranges.stream()
                .filter(r -> r.isComplete() && r.integratedProgramme())
                .map(r -> new Ip(r.admissionYear(), r.postingGroup(), r.affiliated(), r.lowerScore(), r.upperScore(),
                        r.moeText()))
                .toList());
    }

    /** The note for the entity ranges of one school; null when it has no IP range. */
    public static String ofRanges(Collection<IndicativePsleScoreRange> ranges) {
        return join(ranges.stream()
                .filter(IndicativePsleScoreRange::isIntegratedProgramme)
                .map(r -> new Ip(r.getAdmissionYear(), r.getPostingGroup(), r.isAffiliated(), r.getLowerScore(),
                        r.getUpperScore(), r.getMoeText()))
                .toList());
    }

    private static String join(List<Ip> ips) {
        if (ips.isEmpty()) {
            return null;
        }
        return ips.stream().sorted(NEWEST_FIRST_NON_AFFILIATED_FIRST).map(Ip::text).collect(Collectors.joining("; "));
    }
}

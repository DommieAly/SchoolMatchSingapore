package sg.schoolmatch.dataset;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MOE's text of one PSLE range cell, e.g. {@code "8 - 12"}, {@code "26 - 30*"}, {@code "6(D) - 8(M)"} (DC-82, DC-85).
 * The database stores it as its parts (docs/database-design.md, section 5.2): the lower and upper score, the Higher
 * Chinese grade after each ({@code D} or {@code M}), and MOE's {@code *} ("places were left after posting").
 * {@link #format} rebuilds the exact text from the parts. Used by the validator ({@code moe-text-format}), the loader
 * and the mapper.
 */
public final class MoeRangeText {

    /** The only form MOE uses: {@code lower[(grade)] - upper[(grade)][*]}. */
    public static final Pattern FORM = Pattern.compile("^(\\d+)(?:\\(([DM])\\))? - (\\d+)(?:\\(([DM])\\))?(\\*)?$");

    private MoeRangeText() {
    }

    /**
     * The parts of one text. {@code placesLeft} null means MOE's text is not known (seed and test data); then both
     * grades are null too, and {@link #format} gives null.
     */
    public record Parts(int lowerScore, String lowerGrade, int upperScore, String upperGrade, Boolean placesLeft) {
    }

    /** The parts of {@code text}, or empty when it is not in MOE's form. */
    public static Optional<Parts> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = FORM.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Parts(Integer.parseInt(m.group(1)), m.group(2), Integer.parseInt(m.group(3)),
                    m.group(4), m.group(5) != null));
        } catch (NumberFormatException e) {
            return Optional.empty();   // more digits than an int holds
        }
    }

    /**
     * Why {@code text} cannot be stored as parts of a range with these scores, or null when it can (null text is
     * fine: "not known").
     */
    public static String problem(String text, int lowerScore, int upperScore) {
        if (text == null) {
            return null;
        }
        Optional<Parts> parts = parse(text);
        if (parts.isEmpty()) {
            return "'" + text + "' is not in MOE's form 'lower[(D|M)] - upper[(D|M)][*]'";
        }
        Parts p = parts.get();
        if (p.lowerScore() != lowerScore || p.upperScore() != upperScore) {
            return "'" + text + "' says " + p.lowerScore() + " - " + p.upperScore() + " but the range is "
                    + lowerScore + " - " + upperScore;
        }
        return null;
    }

    /**
     * The parts to store for a range: those of {@code text}, or only the scores (grades and {@code placesLeft} null)
     * when the text is null. Call {@link #problem} first; a text it refuses throws here.
     */
    public static Parts parts(String text, int lowerScore, int upperScore) {
        if (text == null) {
            return new Parts(lowerScore, null, upperScore, null, null);
        }
        String problem = problem(text, lowerScore, upperScore);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return parse(text).orElseThrow();
    }

    /** MOE's text rebuilt from the parts; null when {@code placesLeft} is null (text not known). */
    public static String format(int lowerScore, String lowerGrade, int upperScore, String upperGrade,
                                Boolean placesLeft) {
        if (placesLeft == null) {
            return null;
        }
        return lowerScore + (lowerGrade == null ? "" : "(" + lowerGrade + ")") + " - "
                + upperScore + (upperGrade == null ? "" : "(" + upperGrade + ")") + (placesLeft ? "*" : "");
    }
}

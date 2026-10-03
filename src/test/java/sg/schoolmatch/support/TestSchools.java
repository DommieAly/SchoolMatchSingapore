package sg.schoolmatch.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;

/**
 * Test helper: builds {@link School} objects in one line, so a test only states the fields it cares about.
 * <pre>
 * School s = TestSchools.school("catholic-high-school")
 *         .ccas("Basketball").range(2025, 3, 8, 12).build();
 * </pre>
 * Defaults: the name comes from the code ("catholic-high-school" → "Catholic High School") and the
 * coordinate is {@link #BISHAN}. Every other field stays {@code null} ("Not available"), as in real data.
 */
public final class TestSchools {

    /** Bishan MRT station. */
    public static final Coordinate BISHAN = new Coordinate(1.3510, 103.8484);

    /** Tampines MRT station, about 10.8 km east of Bishan. */
    public static final Coordinate TAMPINES = new Coordinate(1.3543, 103.9453);

    /** A real place outside Singapore (the same point as the bad-coordinate fixture). */
    public static final Coordinate OUTSIDE_SINGAPORE = new Coordinate(40.0, 100.0);

    private TestSchools() {
    }

    /** Starts a builder for a school with this code. */
    public static Builder school(String schoolCode) {
        return new Builder(schoolCode);
    }

    /** A school with only a code, a name and the default coordinate. */
    public static School named(String schoolCode, String name) {
        return school(schoolCode).name(name).build();
    }

    /** Schools keyed by code, in the given order: the map that {@code resolveSchools(...)} expects. */
    public static Map<String, School> byCode(School... schools) {
        Map<String, School> map = new LinkedHashMap<>();
        for (School s : schools) {
            map.put(s.getSchoolCode(), s);
        }
        return map;
    }

    /** Shorthand for {@code new IndicativePsleScoreRange(...)}. */
    public static IndicativePsleScoreRange range(int admissionYear, int postingGroup, boolean affiliated,
                                                 int lowerScore, int upperScore) {
        return new IndicativePsleScoreRange(admissionYear, postingGroup, affiliated, lowerScore, upperScore);
    }

    /** "catholic-high-school" → "Catholic High School". */
    static String nameFromCode(String code) {
        return Arrays.stream(code.split("-"))
                .filter(word -> !word.isEmpty())
                .map(word -> word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1))
                .collect(Collectors.joining(" "));
    }

    /** Fluent builder; call {@link #build()} last. */
    public static final class Builder {

        private final String schoolCode;
        private String name;
        private Coordinate coordinate = BISHAN;
        private String address;
        private String schoolType;
        private String planningArea;
        private String email;
        private final List<String> programmes = new ArrayList<>();
        private final List<String> ccas = new ArrayList<>();
        private final List<String> affiliatedPrimarySchools = new ArrayList<>();
        private final List<IndicativePsleScoreRange> ranges = new ArrayList<>();

        private Builder(String schoolCode) {
            this.schoolCode = schoolCode;
            this.name = nameFromCode(schoolCode);
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder at(Coordinate value) {
            this.coordinate = value;
            return this;
        }

        public Builder at(double latitude, double longitude) {
            return at(new Coordinate(latitude, longitude));
        }

        /** The source had no location for this school. */
        public Builder noCoordinate() {
            return at(null);
        }

        public Builder address(String value) {
            this.address = value;
            return this;
        }

        public Builder type(String value) {
            this.schoolType = value;
            return this;
        }

        /** Planning-area NAME, e.g. "BISHAN". */
        public Builder planningArea(String value) {
            this.planningArea = value;
            return this;
        }

        public Builder email(String value) {
            this.email = value;
            return this;
        }

        public Builder programmes(String... values) {
            programmes.addAll(List.of(values));
            return this;
        }

        public Builder ccas(String... values) {
            ccas.addAll(List.of(values));
            return this;
        }

        public Builder affiliatedPrimarySchools(String... values) {
            affiliatedPrimarySchools.addAll(List.of(values));
            return this;
        }

        /** Adds a non-affiliated range. */
        public Builder range(int admissionYear, int postingGroup, int lowerScore, int upperScore) {
            ranges.add(TestSchools.range(admissionYear, postingGroup, false, lowerScore, upperScore));
            return this;
        }

        /** Adds an affiliated range (DC-21). */
        public Builder affiliatedRange(int admissionYear, int postingGroup, int lowerScore, int upperScore) {
            ranges.add(TestSchools.range(admissionYear, postingGroup, true, lowerScore, upperScore));
            return this;
        }

        /** Adds an Integrated Programme range (DC-77: PG3, non-affiliated, IP). */
        public Builder ipRange(int admissionYear, int lowerScore, int upperScore) {
            ranges.add(new IndicativePsleScoreRange(admissionYear, School.IP_POSTING_GROUP, false,
                    lowerScore, upperScore, true));
            return this;
        }

        public School build() {
            School school = new School(schoolCode, name);
            school.setCoordinate(coordinate);
            school.setAddress(address);
            school.setSchoolType(schoolType);
            school.setPlanningArea(planningArea);
            school.setEmail(email);
            school.setProgrammes(programmes);
            school.setCcas(ccas);
            school.setAffiliatedPrimarySchools(affiliatedPrimarySchools);
            school.setScoreRanges(ranges);
            return school;
        }
    }
}

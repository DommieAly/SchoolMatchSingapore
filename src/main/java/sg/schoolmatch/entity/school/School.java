package sg.schoolmatch.entity.school;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sg.schoolmatch.entity.common.Place;

/**
 * Design class «entity» School — one secondary school from the active dataset (FR-SCHOOL-02, FR-DATA-03).
 * Built by the dataset reader. Missing values are {@code null}; collections are never null, and their getters
 * return read-only views so search/filter cannot change stored data (FR-DATA-07).
 * <p>
 * <b>Only SnapshotReader (and the test builder TestSchools) may call the setters.</b> SchoolDataCache hands the
 * same School objects to every request, so a control or page that called e.g. {@code setEmail} would change
 * the data every user sees. Treat a School as read-only everywhere else.
 */
public class School extends Place {

    private final String schoolCode;          // our own id: a slug of the name, frozen in data/curated/school-codes.csv
    private String schoolType;
    private String planningArea;              // planning-area NAME, e.g. "BISHAN"
    private String email;
    private String nearestMrt;
    private String busInfo;
    private String sessionType;
    private String schoolNature;
    private final Set<String> programmes = new LinkedHashSet<>();
    private final Set<String> ccas = new LinkedHashSet<>();
    private final List<IndicativePsleScoreRange> scoreRanges = new ArrayList<>();
    private final Set<String> affiliatedPrimarySchools = new LinkedHashSet<>();   // DC-21
    private String ipRangeNote;                                                   // DC-18 (MOE text of IP ranges)

    public School(String schoolCode, String name) {
        super(name);
        this.schoolCode = schoolCode;
    }

    /**
     * Case-insensitive partial match on the name only (FR-SEARCH-02). {@code term} is already trimmed.
     * <p>
     * DC-75: a plain substring match first; otherwise both sides are compared without punctuation
     * ({@link #plainName}) and every word of the term must appear in the name. So "st andrews" finds
     * "ST ANDREW'S SCHOOL (SECONDARY)", "St. Andrew" finds it too, "chij toa payoh" finds "CHIJ SECONDARY (TOA
     * PAYOH)", and "government" finds "BUKIT PANJANG GOVT. HIGH SCHOOL".
     */
    public boolean matchesName(String term) {
        if (term == null || getName() == null) {
            return false;
        }
        if (getName().toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String plainTerm = plainName(term);
        if (plainTerm.isEmpty()) {
            return false;
        }
        String plain = plainName(getName());
        for (String word : plainTerm.split(" ")) {
            if (!plain.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /**
     * DC-75: a name or search term for comparing: lower case, apostrophes and dots dropped ("ST. ANDREW'S" →
     * "st andrews"), every other run of non-letters and non-digits read as one space, and the word "govt" read as
     * "government" (the dataset writes "GOVT.").
     */
    static String plainName(String text) {
        String plain = text.toLowerCase(Locale.ROOT)
                .replaceAll("['\u2018\u2019`.]", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return plain.replaceAll("\\bgovt\\b", "government");
    }

    /** DC-77: MOE SchoolFinder files Integrated Programme ranges under posting group 3. */
    public static final int IP_POSTING_GROUP = 3;

    /**
     * The applicable range for a student (DC-21, DC-22): the latest admission year among the non-IP ranges of
     * {@code postingGroup} with the given affiliation. When {@code affiliated} is true but the school
     * has no affiliated range for that posting group, falls back to the non-affiliated range (DC-22).
     * DC-77: when {@code postingGroup} is 3 and the school has <em>no</em> non-IP PG3 range at all (the 8 IP-only
     * schools), the latest Integrated Programme range is used instead; a school with any non-IP PG3 range (even only
     * an affiliated one) never uses its IP range. DC-82: among the IP ranges the same affiliation rule applies (an
     * affiliated student gets the affiliated IP range when there is one).
     * Empty when no range applies ("Not available"; DC-29: Optional instead of null).
     */
    public Optional<IndicativePsleScoreRange> getScoreRange(int postingGroup, boolean affiliated) {
        Optional<IndicativePsleScoreRange> match = latestRange(postingGroup, affiliated, false);
        if (match.isEmpty() && affiliated) {
            match = latestRange(postingGroup, false, false);
        }
        boolean hasNonIpPg3Range = scoreRanges.stream()
                .anyMatch(r -> !r.isIntegratedProgramme() && r.getPostingGroup() == IP_POSTING_GROUP);
        if (match.isEmpty() && postingGroup == IP_POSTING_GROUP && !hasNonIpPg3Range) {
            match = latestRange(IP_POSTING_GROUP, affiliated, true);
            if (match.isEmpty() && affiliated) {
                match = latestRange(IP_POSTING_GROUP, false, true);
            }
        }
        return match;
    }

    /**
     * DC-22, DC-40: true when {@code primarySchool} (the member's profile field, free text) names one of this school's
     * affiliated primary schools. The one name rule for search, the choice plan and recommendations: names are
     * compared by {@link #plainName} (case, spaces, dots, apostrophes and brackets ignored) and a trailing
     * "(Primary)" is ignored, because data.gov.sg calls e.g. MOE's "Catholic High School (Primary)" just
     * "CATHOLIC HIGH SCHOOL". Words are never guessed: "Catholic High" does not match. Null or blank → false.
     */
    public boolean hasAffiliatedPrimarySchool(String primarySchool) {
        String wanted = primarySchoolKey(primarySchool);
        return !wanted.isEmpty()
                && affiliatedPrimarySchools.stream().anyMatch(name -> primarySchoolKey(name).equals(wanted));
    }

    /** "CHIJ St. Nicholas Girls' School (Primary)" → "chij st nicholas girls school"; null → "". */
    private static String primarySchoolKey(String name) {
        if (name == null) {
            return "";
        }
        return plainName(name).replaceAll("(^| )primary$", "").trim();
    }

    private Optional<IndicativePsleScoreRange> latestRange(int postingGroup, boolean affiliated, boolean ip) {
        return scoreRanges.stream()
                .filter(r -> r.isIntegratedProgramme() == ip)
                .filter(r -> r.getPostingGroup() == postingGroup && r.isAffiliated() == affiliated)
                .max(Comparator.comparingInt(IndicativePsleScoreRange::getAdmissionYear));
    }

    public String getSchoolCode() {
        return schoolCode;
    }

    public String getSchoolType() {
        return schoolType;
    }

    public void setSchoolType(String schoolType) {
        this.schoolType = schoolType;
    }

    public String getPlanningArea() {
        return planningArea;
    }

    public void setPlanningArea(String planningArea) {
        this.planningArea = planningArea;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNearestMrt() {
        return nearestMrt;
    }

    public void setNearestMrt(String nearestMrt) {
        this.nearestMrt = nearestMrt;
    }

    public String getBusInfo() {
        return busInfo;
    }

    public void setBusInfo(String busInfo) {
        this.busInfo = busInfo;
    }

    public String getSessionType() {
        return sessionType;
    }

    public void setSessionType(String sessionType) {
        this.sessionType = sessionType;
    }

    public String getSchoolNature() {
        return schoolNature;
    }

    public void setSchoolNature(String schoolNature) {
        this.schoolNature = schoolNature;
    }

    public Set<String> getProgrammes() {
        return Collections.unmodifiableSet(programmes);
    }

    public void setProgrammes(Collection<String> programmes) {
        replace(this.programmes, programmes);
    }

    public Set<String> getCcas() {
        return Collections.unmodifiableSet(ccas);
    }

    public void setCcas(Collection<String> ccas) {
        replace(this.ccas, ccas);
    }

    public List<IndicativePsleScoreRange> getScoreRanges() {
        return Collections.unmodifiableList(scoreRanges);
    }

    public void setScoreRanges(Collection<IndicativePsleScoreRange> scoreRanges) {
        replace(this.scoreRanges, scoreRanges);
    }

    public Set<String> getAffiliatedPrimarySchools() {
        return Collections.unmodifiableSet(affiliatedPrimarySchools);
    }

    public void setAffiliatedPrimarySchools(Collection<String> affiliatedPrimarySchools) {
        replace(this.affiliatedPrimarySchools, affiliatedPrimarySchools);
    }

    public String getIpRangeNote() {
        return ipRangeNote;
    }

    public void setIpRangeNote(String ipRangeNote) {
        this.ipRangeNote = ipRangeNote;
    }

    private static <T> void replace(Collection<T> target, Collection<T> source) {
        target.clear();
        if (source != null) {
            target.addAll(source);
        }
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof School other && Objects.equals(schoolCode, other.schoolCode));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(schoolCode);
    }

    @Override
    public String toString() {
        return "School{" + schoolCode + ", " + getName() + "}";
    }
}

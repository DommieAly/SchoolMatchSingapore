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

    private final String schoolCode;          // MOE SchoolFinder slug, e.g. "catholic-high-school"
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
    private String ipRangeNote;                                                   // DC-18

    public School(String schoolCode, String name) {
        super(name);
        this.schoolCode = schoolCode;
    }

    /** Case-insensitive substring match on the name only (FR-SEARCH-02). {@code term} is already trimmed. */
    public boolean matchesName(String term) {
        if (term == null || getName() == null) {
            return false;
        }
        return getName().toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    /**
     * The applicable range for a student (DC-21, DC-22): the latest admission year among ranges of
     * {@code postingGroup} with the given affiliation. When {@code affiliated} is true but the school
     * has no affiliated range for that posting group, falls back to the non-affiliated range (DC-22).
     * Empty when no range applies ("Not available"; DC-29: Optional instead of null).
     */
    public Optional<IndicativePsleScoreRange> getScoreRange(int postingGroup, boolean affiliated) {
        Optional<IndicativePsleScoreRange> match = latestRange(postingGroup, affiliated);
        if (match.isEmpty() && affiliated) {
            match = latestRange(postingGroup, false);
        }
        return match;
    }

    private Optional<IndicativePsleScoreRange> latestRange(int postingGroup, boolean affiliated) {
        return scoreRanges.stream()
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

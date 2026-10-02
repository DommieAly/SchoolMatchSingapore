package sg.schoolmatch.entity.shortlist;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import sg.schoolmatch.entity.account.Account;
import sg.schoolmatch.entity.school.School;

/**
 * Design class «entity» Shortlist — the schools an account has saved (FR-SHORTLIST-01..07, FR-DATA-05).
 * One per account, created at registration. Stores only school codes; the unique key
 * (account_id, school_code) enforces "no duplicates" (FR-SHORTLIST-02).
 * DC-21: {@code resolvedSchools} is filled by the controls from the dataset and not stored.
 */
@Entity
@Table(name = "shortlist")
public class Shortlist {

    @Id
    private String accountId;

    @MapsId
    @OneToOne(optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    private Instant updatedAt;

    // EAGER + separate select: small list, open-in-view is off, and Hibernate cannot join-fetch two lists at once.
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @CollectionTable(name = "shortlist_school",
            joinColumns = @JoinColumn(name = "account_id"),
            uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "school_code"}))
    @Column(name = "school_code", nullable = false, length = 100)
    @OrderBy   // DC-35: by school_code; a position column would clash with the unique key on reorder
    private List<String> schoolCodes = new ArrayList<>();

    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "choice_plan_id")
    private ChoicePlan choicePlan;

    @Transient
    private Map<String, School> resolvedSchools = new HashMap<>();

    /** For JPA only. */
    protected Shortlist() {
    }

    /** An empty shortlist for {@code account}. */
    public Shortlist(Account account) {
        // accountId stays null until saved: Hibernate copies it from the account (@MapsId), and a null id
        // makes Spring Data's save() call persist(). Setting it here would make save() call merge(), which fails.
        this.account = account;
    }

    /** Adds the school; false if it is already shortlisted (FR-SHORTLIST-02). */
    public boolean addSchool(School school) {
        Objects.requireNonNull(school, "school");
        if (contains(school.getSchoolCode())) {
            return false;
        }
        schoolCodes.add(school.getSchoolCode());
        resolvedSchools.put(school.getSchoolCode(), school);
        return true;
    }

    /** Removes the school, and also removes it from the choice plan (FR-SHORTLIST-06). */
    public void removeSchool(String schoolCode) {
        schoolCodes.remove(schoolCode);
        resolvedSchools.remove(schoolCode);
        if (choicePlan != null) {
            choicePlan.removeChoice(schoolCode);
        }
    }

    public boolean contains(String schoolCode) {
        return schoolCodes.contains(schoolCode);
    }

    /**
     * Resolved schools in stored order (adding order until reloaded; school-code order after loading from
     * the database). Codes no longer in the dataset are skipped. The UI may sort them by name.
     */
    public List<School> getSchools() {
        return schoolCodes.stream().map(resolvedSchools::get).filter(Objects::nonNull).toList();
    }

    /** Codes that could not be resolved ("School no longer in the dataset" + Remove button). */
    public List<String> getUnresolvedSchoolCodes() {   // DC-36 helper
        return schoolCodes.stream().filter(code -> !resolvedSchools.containsKey(code)).toList();
    }

    public boolean isEmpty() {
        return schoolCodes.isEmpty();
    }

    public List<String> getSchoolCodes() {   // DC-36 helper
        return Collections.unmodifiableList(schoolCodes);
    }

    /** Fills {@code resolvedSchools} (and the plan's choices) from {@code byCode}, schoolCode → School. */
    public void resolveSchools(Map<String, School> byCode) {   // DC-36 helper
        resolvedSchools.clear();
        for (String code : schoolCodes) {
            School school = byCode.get(code);
            if (school != null) {
                resolvedSchools.put(code, school);
            }
        }
        if (choicePlan != null) {
            choicePlan.resolveSchools(byCode);
        }
    }

    /** The owning account's id (also before the first save). */
    public String getAccountId() {
        return accountId != null ? accountId : account.getAccountId();
    }

    public Account getAccount() {
        return account;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** Null until the user starts a plan. */
    public ChoicePlan getChoicePlan() {
        return choicePlan;
    }

    public void setChoicePlan(ChoicePlan choicePlan) {
        this.choicePlan = choicePlan;
    }

    @Override
    public String toString() {
        return "Shortlist{accountId=" + getAccountId() + ", schoolCodes=" + schoolCodes + "}";
    }
}

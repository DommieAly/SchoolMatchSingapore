package sg.schoolmatch.entity.account;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.recommend.MatchCriteria;
import sg.schoolmatch.entity.route.TravelMode;

/**
 * Design class «entity» UserProfile — a member's PSLE score, home location and preferences
 * (FR-PROFILE-01, proposed id). Shares its primary key with {@link Account}.
 */
@Entity
@Table(name = "user_profile")
public class UserProfile {

    @Id
    private String accountId;

    @MapsId
    @OneToOne(optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    private String displayName;

    @Min(4)
    @Max(32)
    private Integer psleScore;

    @Min(1)
    @Max(3)
    private Integer postingGroup;

    private String primarySchool;

    private String homeAddress;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "latitude", column = @Column(name = "home_latitude")),
            @AttributeOverride(name = "longitude", column = @Column(name = "home_longitude"))
    })
    private Coordinate homeLocation;   // null until the home address is resolved

    // EAGER: small sets, and open-in-view is off (lazy loading in templates would fail).
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_profile_cca", joinColumns = @JoinColumn(name = "account_id"))
    @Column(name = "cca")
    private Set<String> preferredCCAs = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_profile_programme", joinColumns = @JoinColumn(name = "account_id"))
    @Column(name = "programme")
    private Set<String> preferredProgrammes = new LinkedHashSet<>();

    private Integer maxCommuteMin;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private TravelMode travelMode;

    /** For JPA only. */
    protected UserProfile() {
    }

    /** An empty profile for {@code account}. */
    public UserProfile(Account account) {
        // accountId stays null until saved: Hibernate copies it from the account (@MapsId), and a null id
        // makes Spring Data's save() call persist(). Setting it here would make save() call merge(), which fails.
        this.account = account;
    }

    /** Complete = enough data to get recommendations: PSLE score, posting group, home location, travel mode. */
    public boolean isComplete() {
        return psleScore != null && postingGroup != null && homeLocation != null && travelMode != null;
    }

    /**
     * Builds the recommendation criteria prefilled from this profile (DC-07, FR-REC-01). Owner B writes this method.
     * The home location becomes the start location (labelled with the home address, else "Home"); missing values
     * stay null, so an incomplete profile still prefills what it has. Weights stay empty: they come from
     * {@code app.recommendation.weights}, which an entity cannot read (DC-20).
     */
    public MatchCriteria toMatchCriteria() {
        MatchCriteria criteria = new MatchCriteria();
        criteria.setPsleScore(psleScore);
        criteria.setPostingGroup(postingGroup);
        criteria.setPrimarySchool(primarySchool);
        criteria.setTravelMode(travelMode);
        criteria.setMaxCommuteMin(maxCommuteMin);
        criteria.setPreferredCCAs(preferredCCAs);
        criteria.setPreferredProgrammes(preferredProgrammes);
        if (homeLocation != null) {
            String label = homeAddress == null || homeAddress.isBlank() ? "Home" : homeAddress.trim();
            criteria.setStartLocation(new sg.schoolmatch.entity.location.ReferenceLocation(homeLocation,
                    sg.schoolmatch.entity.location.LocationSource.MANUAL_ENTRY, label));
        }
        return criteria;
    }

    /** The owning account's id (also before the first save). */
    public String getAccountId() {
        return accountId != null ? accountId : account.getAccountId();
    }

    public Account getAccount() {
        return account;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Integer getPsleScore() {
        return psleScore;
    }

    public void setPsleScore(Integer psleScore) {
        this.psleScore = psleScore;
    }

    public Integer getPostingGroup() {
        return postingGroup;
    }

    public void setPostingGroup(Integer postingGroup) {
        this.postingGroup = postingGroup;
    }

    public String getPrimarySchool() {
        return primarySchool;
    }

    public void setPrimarySchool(String primarySchool) {
        this.primarySchool = primarySchool;
    }

    public String getHomeAddress() {
        return homeAddress;
    }

    public void setHomeAddress(String homeAddress) {
        this.homeAddress = homeAddress;
    }

    public Coordinate getHomeLocation() {
        return homeLocation;
    }

    public void setHomeLocation(Coordinate homeLocation) {
        this.homeLocation = homeLocation;
    }

    public Set<String> getPreferredCCAs() {
        return Collections.unmodifiableSet(preferredCCAs);
    }

    /** Replaces the CCAs (a copy is taken first, so passing this profile's own set is safe). */
    public void setPreferredCCAs(Collection<String> preferredCCAs) {
        List<String> copy = preferredCCAs == null ? List.of() : new ArrayList<>(preferredCCAs);
        this.preferredCCAs.clear();
        this.preferredCCAs.addAll(copy);
    }

    public Set<String> getPreferredProgrammes() {
        return Collections.unmodifiableSet(preferredProgrammes);
    }

    /** Replaces the programmes (a copy is taken first, so passing this profile's own set is safe). */
    public void setPreferredProgrammes(Collection<String> preferredProgrammes) {
        List<String> copy = preferredProgrammes == null ? List.of() : new ArrayList<>(preferredProgrammes);
        this.preferredProgrammes.clear();
        this.preferredProgrammes.addAll(copy);
    }

    public Integer getMaxCommuteMin() {
        return maxCommuteMin;
    }

    public void setMaxCommuteMin(Integer maxCommuteMin) {
        this.maxCommuteMin = maxCommuteMin;
    }

    public TravelMode getTravelMode() {
        return travelMode;
    }

    public void setTravelMode(TravelMode travelMode) {
        this.travelMode = travelMode;
    }

    @Override
    public String toString() {
        return "UserProfile{accountId=" + getAccountId() + ", complete=" + isComplete() + "}";
    }
}

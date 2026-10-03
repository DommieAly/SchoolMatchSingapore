package sg.schoolmatch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * How many units of one Google SKU were used on one budget day (the date in {@code app.external.budget.zone},
 * Pacific Time by default, DC-80). Written only by
 * {@code boundary.external.ExternalCallBudget}. Not a design entity (it is bookkeeping for the spending
 * limit), so it lives here and not in {@code entity}.
 * <p>
 * Table {@code external_usage}, primary key ({@code usage_day}, {@code sku}). The column is {@code usage_day},
 * not {@code day}: DAY is a reserved word in H2.
 */
@Entity
@Table(name = "external_usage")
@IdClass(ExternalUsage.Key.class)
public class ExternalUsage {

    @Id
    @Column(name = "usage_day", nullable = false)
    private LocalDate day;

    @Id
    @Column(name = "sku", nullable = false, length = 50)
    private String sku;

    @Column(name = "used", nullable = false)
    private int used;

    /** For JPA only. */
    protected ExternalUsage() {
    }

    /** A counter at 0 for {@code sku} on {@code day}. */
    public ExternalUsage(LocalDate day, String sku) {
        this.day = Objects.requireNonNull(day, "day");
        this.sku = Objects.requireNonNull(sku, "sku");
    }

    public LocalDate getDay() {
        return day;
    }

    public String getSku() {
        return sku;
    }

    public int getUsed() {
        return used;
    }

    /** Adds {@code units} to the counter. */
    public void add(int units) {
        used += units;
    }

    @Override
    public String toString() {
        return "ExternalUsage{" + day + ", " + sku + ", used=" + used + "}";
    }

    /** Composite primary key (day, sku). */
    public static class Key implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private LocalDate day;
        private String sku;

        /** For JPA only. */
        protected Key() {
        }

        public Key(LocalDate day, String sku) {
            this.day = day;
            this.sku = sku;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && Objects.equals(day, other.day) && Objects.equals(sku, other.sku);
        }

        @Override
        public int hashCode() {
            return Objects.hash(day, sku);
        }
    }
}

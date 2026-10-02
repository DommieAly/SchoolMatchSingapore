package sg.schoolmatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Stores the daily Google usage counters ({@link ExternalUsage}) for {@code ExternalCallBudget}.
 * Look a counter up with {@code findById(new ExternalUsage.Key(day, sku))}.
 */
public interface ExternalUsageRepository extends JpaRepository<ExternalUsage, ExternalUsage.Key> {
}

package sg.schoolmatch.dataset;

import java.util.List;
import sg.schoolmatch.entity.school.ValidationStatus;

/**
 * Result of {@link SnapshotValidator#validate(LoadedSnapshot)} (DC-09). Every message starts with its rule id,
 * e.g. {@code "duplicate-code: 'x' appears 2 times"}. Any error → FAILED, and the app refuses to start.
 */
public class ValidationReport {

    private final List<String> errors;
    private final List<String> warnings;

    public ValidationReport(List<String> errors, List<String> warnings) {
        this.errors = List.copyOf(errors);
        this.warnings = List.copyOf(warnings);
    }

    /** FAILED when there is an error, PASSED_WITH_WARNINGS when there is a warning, else PASSED. */
    public ValidationStatus getStatus() {
        if (!errors.isEmpty()) {
            return ValidationStatus.FAILED;
        }
        return warnings.isEmpty() ? ValidationStatus.PASSED : ValidationStatus.PASSED_WITH_WARNINGS;
    }

    /** True unless FAILED: the snapshot may be served. */
    public boolean isUsable() {
        return errors.isEmpty();
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    /** True when an error of rule {@code ruleId} (e.g. "bad-coordinate") was reported. */
    public boolean hasError(String ruleId) {
        return errors.stream().anyMatch(e -> e.startsWith(ruleId + ":"));
    }

    /** Multi-line summary for logs and the start-up failure message. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getStatus().name())
                .append(" (").append(errors.size()).append(" errors, ").append(warnings.size()).append(" warnings)");
        errors.forEach(e -> sb.append("\n  ERROR   ").append(e));
        warnings.forEach(w -> sb.append("\n  WARNING ").append(w));
        return sb.toString();
    }
}

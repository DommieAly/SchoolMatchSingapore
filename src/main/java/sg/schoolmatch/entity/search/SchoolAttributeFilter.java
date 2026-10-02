package sg.schoolmatch.entity.search;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import sg.schoolmatch.entity.school.School;

/**
 * Design class «entity» SchoolAttributeFilter — keeps schools having any of the selected values of one
 * attribute category: type, programme, CCA or district (FR-FILTER-02, DC-04).
 * Values are compared ignoring case. Whether a value exists in the active dataset is checked by
 * {@code FilterController.validateFilter} (an entity cannot read the dataset).
 */
public class SchoolAttributeFilter extends Filter {

    private final AttributeCategory category;
    private final Set<String> selectedValues;

    public SchoolAttributeFilter(AttributeCategory category, Collection<String> selectedValues) {
        this.category = category;
        this.selectedValues = selectedValues == null ? Set.of() : new LinkedHashSet<>(selectedValues);
    }

    public AttributeCategory getCategory() {
        return category;
    }

    public Set<String> getSelectedValues() {
        return Collections.unmodifiableSet(selectedValues);
    }

    /**
     * OR within the category: the school has at least one selected value (FR-FILTER-07).
     * SCHOOL_TYPE → its type; PROGRAMME → any of its programmes; CCA → any of its CCAs; DISTRICT → its planning area.
     */
    @Override
    public boolean matches(School school) {
        if (category == null) {
            return false;
        }
        Stream<String> schoolValues = switch (category) {
            case SCHOOL_TYPE -> Stream.of(school.getSchoolType());
            case PROGRAMME -> school.getProgrammes().stream();
            case CCA -> school.getCcas().stream();
            case DISTRICT -> Stream.of(school.getPlanningArea());
        };
        return schoolValues.anyMatch(this::isSelected);
    }

    /** Valid: a category and at least one value, none of them blank. */
    @Override
    public boolean isValid() {
        return category != null && !selectedValues.isEmpty()
                && selectedValues.stream().allMatch(v -> v != null && !v.isBlank());
    }

    /** e.g. "CCA: BOWLING or RUGBY" (FR-FILTER-08). */
    @Override
    public String describe() {
        return label(category) + ": " + String.join(" or ", selectedValues);
    }

    /** Name of a category on pages and in messages: "School type", "Programme", "CCA", "District". */
    public static String label(AttributeCategory category) {   // DC-36 helper
        if (category == null) {
            return "Attribute";
        }
        return switch (category) {
            case SCHOOL_TYPE -> "School type";
            case PROGRAMME -> "Programme";
            case CCA -> "CCA";
            case DISTRICT -> "District";
        };
    }

    private boolean isSelected(String schoolValue) {
        if (schoolValue == null) {
            return false;
        }
        String wanted = schoolValue.strip().toLowerCase(Locale.ROOT);
        return selectedValues.stream().anyMatch(v -> v != null && v.strip().toLowerCase(Locale.ROOT).equals(wanted));
    }
}

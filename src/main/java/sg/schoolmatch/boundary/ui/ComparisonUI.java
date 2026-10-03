package sg.schoolmatch.boundary.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import sg.schoolmatch.boundary.ui.support.AuthInterceptor;
import sg.schoolmatch.boundary.ui.support.PageMessages;
import sg.schoolmatch.control.ShortlistController;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;
import sg.schoolmatch.error.InvalidInputException;

/**
 * Design class «boundary» ComparisonUI — shortlisted schools side by side (DM-18 Compare Schools;
 * use case Compare Schools, FR-COMPARE-01; DC-07 login required, DC-28).
 * <p>
 * Model attributes: {@code schools} (the columns, in the order chosen) and {@code rows} (one {@link CompareRow}
 * per field). {@code highlightDifferences} is the {@code differs} flag of each row, shown as a highlighted row.
 * Missing values are null and show "Not available" (AF-2).
 */
@Controller
public class ComparisonUI {

    /**
     * One table row: a field label, one value per school (null = not available), whether they differ, and whether
     * it is a PSLE range row (DC-74: the page leaves those out when the dataset has no PSLE ranges at all).
     */
    public record CompareRow(String label, List<String> values, boolean differs, boolean psleRange) {

        public CompareRow(String label, List<String> values, boolean differs) {
            this(label, values, differs, false);
        }
    }

    private final ShortlistController shortlistController;

    public ComparisonUI(ShortlistController shortlistController) {
        this.shortlistController = shortlistController;
    }

    /**
     * GET /compare?codes=a,b,c (or codes=a&amp;codes=b). Fewer than 2, more than 4, or a school that is not on the
     * member's shortlist → back to the shortlist with the message (AF-1).
     */
    @GetMapping("/compare")
    public String displayComparison(@RequestAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) String sessionId,
                                    @RequestParam(name = "codes", required = false) List<String> codes,
                                    Model model, RedirectAttributes redirect) {
        Set<String> chosen = new LinkedHashSet<>();
        if (codes != null) {
            codes.stream().filter(Objects::nonNull).map(String::strip).filter(c -> !c.isEmpty()).forEach(chosen::add);
        }
        List<School> schools;
        try {
            schools = shortlistController.compareSchools(sessionId, chosen);
        } catch (InvalidInputException e) {
            redirect.addFlashAttribute(PageMessages.FLASH_ERROR, String.join(" ", e.getFieldErrors().values()));
            return "redirect:/shortlist";
        }
        model.addAttribute("schools", schools);
        model.addAttribute("rows", highlightDifferences(schools));
        return "compare";
    }

    /** The comparison rows (FR-COMPARE-01); a row is highlighted when its values are not all the same. */
    static List<CompareRow> highlightDifferences(List<School> schools) {
        List<CompareRow> rows = new ArrayList<>();
        rows.add(row("School type", schools, School::getSchoolType));
        rows.add(row("District", schools, School::getPlanningArea));
        rows.add(row("Address", schools, School::getAddress));
        rows.add(row("Nearest MRT", schools, School::getNearestMrt));
        rows.add(row("Session", schools, School::getSessionType));
        rows.add(row("School nature", schools, School::getSchoolNature));
        for (int postingGroup = 3; postingGroup >= 1; postingGroup--) {
            int pg = postingGroup;
            CompareRow range = row("PSLE AL range PG" + pg, schools, s -> latestRange(s, pg));
            rows.add(new CompareRow(range.label(), range.values(), range.differs(), true));
        }
        rows.add(row("Number of CCAs", schools, s -> count(s.getCcas())));
        rows.add(row("CCA list", schools, s -> join(s.getCcas())));
        rows.add(row("Number of programmes", schools, s -> count(s.getProgrammes())));
        rows.add(row("Programme list", schools, s -> join(s.getProgrammes())));
        return rows;
    }

    /** True when the values are not all equal; a missing value differs from a present one. */
    static boolean differs(List<String> values) {
        return new HashSet<>(values).size() > 1;
    }

    private static CompareRow row(String label, List<School> schools, Function<School, String> value) {
        List<String> values = schools.stream().map(value).map(ComparisonUI::blankToNull).toList();
        return new CompareRow(label, values, differs(values));
    }

    /** Latest non-affiliated range for the posting group, e.g. "8–12 (2025)" (DC-22); null when none. */
    private static String latestRange(School school, int postingGroup) {
        return school.getScoreRange(postingGroup, false)
                .map(ComparisonUI::rangeText)
                .orElse(null);
    }

    private static String rangeText(IndicativePsleScoreRange range) {
        return range.getRangeText() + " (" + range.getAdmissionYear() + ")";   // "IP 4–8 (2025)" for IP (DC-77)
    }

    /** An empty list means the source had no data for the school, so it is "Not available", not 0. */
    private static String count(Collection<String> items) {
        return items.isEmpty() ? null : String.valueOf(items.size());
    }

    /** A–Z, so two schools with the same items in a different order count as equal. */
    private static String join(Collection<String> items) {
        return items.isEmpty() ? null : items.stream().sorted().collect(Collectors.joining(", "));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

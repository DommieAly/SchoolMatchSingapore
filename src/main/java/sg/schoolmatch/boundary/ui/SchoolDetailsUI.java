package sg.schoolmatch.boundary.ui;

import java.util.Comparator;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;

/**
 * Design class «boundary» SchoolDetailsUI — one school's details (DM 09 SchoolDetails; FR-SCHOOL-01..03,
 * NFR-DATA-03).
 * <p>
 * {@code displaySchoolDetails} is a {@code th:fragment} in {@code school-details.html};
 * {@code displayUnavailable} is the shared {@code value} fragment ("Not available").
 * The buttons are the design's inputs: {@code selectAddToShortlist} (POST /schools/{code}/shortlist, handled by
 * ShortlistUI, DC-38),
 * {@code selectViewNearbyFacilities}, {@code selectGetDirections}, and View on map.
 */
@Controller
public class SchoolDetailsUI {

    /** Ranges table order: newest year first, then PG3, PG2, PG1, non-affiliated before affiliated. */
    private static final Comparator<IndicativePsleScoreRange> NEWEST_FIRST = Comparator
            .comparingInt(IndicativePsleScoreRange::getAdmissionYear).reversed()
            .thenComparing(Comparator.comparingInt(IndicativePsleScoreRange::getPostingGroup).reversed())
            .thenComparing(IndicativePsleScoreRange::isAffiliated);

    private final SchoolController schoolController;

    public SchoolDetailsUI(SchoolController schoolController) {
        this.schoolController = schoolController;
    }

    /**
     * GET /schools/{code} — the details page. An unknown code throws NotFoundException,
     * which {@code ErrorPageAdvice} turns into the 404 page.
     */
    @GetMapping("/schools/{code}")
    public String selectSchool(@PathVariable("code") String code, Model model) {
        School school = schoolController.getSchoolDetails(code);
        model.addAttribute("school", school);
        model.addAttribute("scoreRanges", school.getScoreRanges().stream().sorted(NEWEST_FIRST).toList());
        return "school-details";
    }
}

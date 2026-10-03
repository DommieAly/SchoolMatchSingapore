package sg.schoolmatch.boundary.ui;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import sg.schoolmatch.control.SchoolController;
import sg.schoolmatch.control.SchoolDataController;
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

    /**
     * Ranges table order: newest year first, then PG3, PG2, PG1, the ordinary ranges before the IP ones, and
     * non-affiliated before affiliated.
     */
    private static final Comparator<IndicativePsleScoreRange> NEWEST_FIRST = Comparator
            .comparingInt(IndicativePsleScoreRange::getAdmissionYear).reversed()
            .thenComparing(Comparator.comparingInt(IndicativePsleScoreRange::getPostingGroup).reversed())
            .thenComparing(IndicativePsleScoreRange::isIntegratedProgramme)
            .thenComparing(IndicativePsleScoreRange::isAffiliated);

    private final SchoolController schoolController;
    private final SchoolDataController schoolDataController;

    public SchoolDetailsUI(SchoolController schoolController, SchoolDataController schoolDataController) {
        this.schoolController = schoolController;
        this.schoolDataController = schoolDataController;
    }

    /**
     * GET /schools/{code} — the details page. An unknown code throws NotFoundException,
     * which {@code ErrorPageAdvice} turns into the 404 page.
     */
    @GetMapping("/schools/{code}")
    public String selectSchool(@PathVariable("code") String code, Model model) {
        School school = schoolController.getSchoolDetails(code);
        model.addAttribute("school", school);
        List<IndicativePsleScoreRange> ranges = school.getScoreRanges().stream().sorted(NEWEST_FIRST).toList();
        model.addAttribute("scoreRanges", ranges);
        // DC-82: MOE's notes for "6(D) - 8(M)" (Higher Chinese grades) and "26 - 30*" (places left), when shown.
        model.addAttribute("higherChineseNote", ranges.stream().anyMatch(IndicativePsleScoreRange::hasHigherChineseGrades));
        model.addAttribute("placesLeftNote", ranges.stream().anyMatch(IndicativePsleScoreRange::hadPlacesLeft));
        model.addAttribute("affiliationsCurated", affiliationsCurated());
        return "school-details";
    }

    /** True when the dataset has curated affiliations, so an empty list means "None"; unknown → false. */
    private boolean affiliationsCurated() {
        try {
            return schoolDataController.hasAffiliationData();
        } catch (RuntimeException e) {
            return false;   // the dataset cannot be read: keep "Not available"
        }
    }
}

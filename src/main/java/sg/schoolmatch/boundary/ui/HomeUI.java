package sg.schoolmatch.boundary.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.dataset.SnapshotManifest;

/**
 * Design class «boundary» HomeUI — the home page (DM 01 HomePage (Guest), DM 04 HomePage (Authenticated)).
 * <p>
 * The design's input operations ({@code selectSearchSchools}, {@code selectLogin}, {@code selectRegister},
 * {@code selectGetRecommendations}, {@code selectProfile}) are links and the search form in {@code home.html}.
 * Which block is shown depends on {@code loggedIn}, added to every page by {@code LayoutModelAdvice}.
 * <p>
 * Also {@code GET /about/data}: where the school data comes from (NFR-DATA-01, DC-09, data.gov.sg licence notice;
 * owner B). Not a dialog-map state.
 */
@Controller
public class HomeUI {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    /** Dataset id prefix of the OneMap source in a manifest (e.g. {@code onemap-elastic-search}). */
    static final String ONEMAP_SOURCE_PREFIX = "onemap";

    private final SchoolDataController schoolDataController;

    public HomeUI(SchoolDataController schoolDataController) {
        this.schoolDataController = schoolDataController;
    }

    /**
     * GET / — shows the search box; guests also see log in / register (DM 01), members see links to
     * recommendations, shortlist, plan and profile (DM 04).
     */
    @GetMapping("/")
    public String displayHomePage() {
        return "home";
    }

    /**
     * GET /about/data — the active snapshot: version, kind, effective and import dates, validation status, sources
     * with dataset ids and download dates, counts, warnings, and the Singapore Open Data Licence notices: one per
     * data.gov.sg dataset and one for the OneMap coordinates ({@code oneMapAccessedOn}, data/README.md "Licences").
     */
    @GetMapping("/about/data")
    public String displayDataSources(Model model) {
        SnapshotManifest manifest = schoolDataController.getActiveManifest();
        model.addAttribute("dataset", schoolDataController.getActiveDataset());
        model.addAttribute("manifest", manifest);
        model.addAttribute("fullSnapshot", manifest.isFull());
        model.addAttribute("effectiveDate", manifest.effectiveDate() == null ? null : DATE.format(manifest.effectiveDate()));
        model.addAttribute("importedAt", format(manifest.importedAt(), DATE_TIME));
        model.addAttribute("counts", new TreeMap<>(manifest.counts()));
        model.addAttribute("sources", manifest.sources().stream().map(HomeUI::toRow).toList());
        model.addAttribute("oneMapAccessedOn", manifest.sources().stream()
                .filter(source -> source.datasetId() != null && source.datasetId().startsWith(ONEMAP_SOURCE_PREFIX))
                .map(source -> format(source.downloadedAt(), DATE))
                .filter(Objects::nonNull)
                .findFirst().orElse(null));
        return "about-data";
    }

    /** One source line of the about page; {@code licensed} = a data.gov.sg dataset (needs the licence notice). */
    public record DataSourceRow(String name, String datasetId, String downloadedOn, String downloadedAt,
                                boolean licensed) {
    }

    private static DataSourceRow toRow(SnapshotManifest.Source source) {
        String name = source.name() == null ? source.datasetId() : source.name().replaceFirst("^data\\.gov\\.sg ", "");
        boolean dataGovSg = source.datasetId() != null && source.datasetId().startsWith("d_");
        return new DataSourceRow(name, source.datasetId(), format(source.downloadedAt(), DATE),
                format(source.downloadedAt(), DATE_TIME), dataGovSg);
    }

    private static String format(Instant instant, DateTimeFormatter formatter) {
        return instant == null ? null : formatter.format(instant.atZone(SINGAPORE));
    }
}

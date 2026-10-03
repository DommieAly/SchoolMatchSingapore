package sg.schoolmatch.boundary.ui.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.control.AuthController;
import sg.schoolmatch.control.ProfileController;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.account.UserProfile;
import sg.schoolmatch.entity.school.SchoolDataCache;

/**
 * Adds the values that {@code layout.html} (navbar, footer) needs to every page model:
 * <ul>
 *   <li>{@code loggedIn} — the session cookie belongs to a valid session (same check as AuthInterceptor).
 *       A stale cookie is cleared when a page is viewed (GET).</li>
 *   <li>{@code userName} — for "Hi, …" in the navbar: the profile's display name, else the username
 *       (only when logged in)</li>
 *   <li>{@code datasetLabel} — version and effective date of the active snapshot, plus
 *       "Seed data (test values)" for the seed snapshot (DC-09, NFR-DATA-01)</li>
 *   <li>{@code seedData} — true while the seed snapshot is active: pages that show PSLE ranges must say the
 *       ranges are test values (DC-34)</li>
 *   <li>{@code psleDataAvailable} — false when the active dataset has no PSLE score range at all: pages then say
 *       "PSLE score ranges: not available yet" once instead of showing ranges, labels or PSLE inputs (DC-74);
 *       {@code noPsleDataMessage} is the reason text</li>
 *   <li>{@code dataAccessedOn} — the snapshot's import date for the Singapore Open Data Licence notice
 *       (absent when unknown)</li>
 *   <li>{@code googleStub} — Google Maps Platform runs in stub mode (footer badge "Demo data (stub)")</li>
 *   <li>{@code mapsBrowserKey}, {@code mapId} — only when configured (DC-16)</li>
 *   <li>{@code currentPath} — for the active navbar link</li>
 * </ul>
 * Only for page controllers ({@code @Controller}), so {@code /actuator/*} requests skip it.
 */
@ControllerAdvice(annotations = Controller.class)
public class LayoutModelAdvice {   // DC-49

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    private final SchoolDataController schoolDataController;
    private final AuthController authController;
    private final SessionCookie sessionCookie;
    private final AppProperties props;
    // Optional on purpose: the @WebMvcTest of another page has no ProfileController bean and must still start.
    private final ObjectProvider<ProfileController> profileController;

    public LayoutModelAdvice(SchoolDataController schoolDataController, AuthController authController,
                             SessionCookie sessionCookie, AppProperties props,
                             ObjectProvider<ProfileController> profileController) {
        this.schoolDataController = schoolDataController;
        this.authController = authController;
        this.sessionCookie = sessionCookie;
        this.props = props;
        this.profileController = profileController;
    }

    @ModelAttribute
    public void addLayoutAttributes(HttpServletRequest request, HttpServletResponse response, Model model) {
        Map<String, Object> attributes = layoutAttributes(request);
        // Expired, logged out or unknown session: act as a guest and drop the cookie. Only on page views (GET):
        // a POST handler may set a new cookie itself (POST /login), and two Set-Cookie headers would be confusing.
        if (!(Boolean) attributes.get("loggedIn") && HttpMethod.GET.matches(request.getMethod())
                && sessionCookie.read(request).isPresent()) {
            sessionCookie.clear(response);
        }
        model.addAllAttributes(attributes);
    }

    /** The layout attributes as a map. ErrorPageAdvice uses it too, because exception views skip @ModelAttribute. */
    public Map<String, Object> layoutAttributes(HttpServletRequest request) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        boolean loggedIn = isLoggedIn(request);
        attributes.put("loggedIn", loggedIn);
        if (loggedIn) {
            attributes.put("userName", userName(request));
        }
        SchoolDataCache dataset = activeDataset();
        attributes.put("datasetLabel", datasetLabel(dataset));
        attributes.put("seedData", dataset != null && dataset.isSeedData());
        attributes.put("psleDataAvailable", PsleAvailability.available(dataset));   // DC-74
        attributes.put("noPsleDataMessage", PsleAvailability.NOT_AVAILABLE_MESSAGE);
        if (dataset != null && dataset.getImportedAt() != null) {
            attributes.put("dataAccessedOn", DATE.format(dataset.getImportedAt().atZone(SINGAPORE)));
        }
        attributes.put("googleStub", props.external().google().isStub());
        if (props.google().hasBrowserKey()) {
            attributes.put("mapsBrowserKey", props.google().browserKey());
        }
        if (!props.google().mapId().isBlank()) {
            attributes.put("mapId", props.google().mapId());
        }
        attributes.put("currentPath", AuthInterceptor.pathWithinApp(request));
        return attributes;
    }

    /** AuthInterceptor already checked protected pages; elsewhere ask AuthController the same way it does. */
    private boolean isLoggedIn(HttpServletRequest request) {
        if (request.getAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) != null) {
            return true;
        }
        Optional<String> sessionId = sessionCookie.read(request);
        if (sessionId.isEmpty()) {
            return false;
        }
        return authController.verifySession(sessionId.get());
    }

    /** Display name, else username; null when it cannot be read (e.g. the session ended during this request). */
    private String userName(HttpServletRequest request) {
        String sessionId = request.getAttribute(AuthInterceptor.SESSION_ID_ATTRIBUTE) instanceof String id
                ? id : sessionCookie.read(request).orElse(null);
        try {
            ProfileController profiles = profileController.getIfAvailable();
            if (profiles == null) {
                return authController.getAccount(sessionId).getUsername();
            }
            UserProfile profile = profiles.getProfile(sessionId);
            String displayName = profile.getDisplayName();
            return displayName != null && !displayName.isBlank() ? displayName : profile.getAccount().getUsername();
        } catch (RuntimeException e) {
            return null;   // only the greeting is missing; the page itself still works
        }
    }

    private SchoolDataCache activeDataset() {
        try {
            return schoolDataController.getActiveDataset();
        } catch (RuntimeException e) {
            return null;   // pages still render while the data layer is unfinished
        }
    }

    private static String datasetLabel(SchoolDataCache dataset) {
        if (dataset == null) {
            return "Dataset not loaded";
        }
        StringBuilder label = new StringBuilder("Dataset ");
        label.append(dataset.getDatasetVersion() == null ? "(unknown version)" : dataset.getDatasetVersion());
        if (dataset.getEffectiveDate() != null) {
            label.append(" · effective ").append(DATE.format(dataset.getEffectiveDate()));
        }
        if (dataset.isSeedData()) {
            label.append(" · Seed data (test values)");
        }
        return label.toString();
    }
}

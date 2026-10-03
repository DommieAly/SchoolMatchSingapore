package sg.schoolmatch.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toCollection;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * Package and naming rules of the design (NFR-MAIN-01 modular components, NFR-MAIN-02 external
 * services behind integration components). A failure message names the class and the broken rule.
 * <ol>
 *   <li>Design control classes ({@code XxxController}) are {@code @Service}s in {@code control}.</li>
 *   <li>Web handlers are design UI classes ({@code XxxUI}, {@code @Controller}) in {@code boundary.ui};
 *       no {@code @RestController}.</li>
 *   <li>{@code boundary.ui} never uses {@code persistence} or {@code boundary.external}.</li>
 *   <li>{@code entity} depends on no other layer of the app.</li>
 *   <li>{@code control} never uses {@code boundary.ui}.</li>
 *   <li>Control → control dependencies are only those in {@code docs/design/control-deps.csv}.</li>
 *   <li>{@code entity} holds exactly the Lab 2 entity classes and enumerations.</li>
 *   <li>Only {@code boundary.external} (and {@code config}, which builds the shared {@code RestClient.Builder})
 *       uses Spring's HTTP client ({@code org.springframework.web.client}).</li>
 *   <li>Only {@code control.SchoolDataController} (and {@code persistence} itself) uses {@code persistence.dataset}:
 *       every other class reads schools through SchoolDataController (docs/database-design.md, section 7.3).</li>
 * </ol>
 * ArchUnit runs this class with its own JUnit engine: {@code @ArchTag} is the tag (so
 * {@code ./mvnw test -Dgroups=NFR-MAIN-01} works) and the member name is the display name
 * ({@code @DisplayName} is not supported; underscores become spaces via {@code archunit.properties}).
 */
@AnalyzeClasses(packages = "sg.schoolmatch", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String CONTROL = "sg.schoolmatch.control..";
    private static final String UI = "sg.schoolmatch.boundary.ui..";
    private static final String UI_TOP = "sg.schoolmatch.boundary.ui";   // without sub-packages such as support
    private static final String ENTITY_PACKAGE = "sg.schoolmatch.entity";

    /** Allowed control → control arrows (design + DC-14). Path relative to the project root (Maven's working dir). */
    static final Path CONTROL_DEPS_CSV = Path.of("docs", "design", "control-deps.csv");

    // ---- Rule 1: XxxController = design control class = @Service in control --------------------------------

    @ArchTest
    @ArchTag("NFR-MAIN-01")
    static final ArchRule TC_Arch_1a_classes_named_Controller_are_Services_in_control =
            classes().that().haveSimpleNameEndingWith("Controller")
                    .should().resideInAPackage(CONTROL)
                    .andShould().beAnnotatedWith(Service.class)
                    .because("design control classes keep their Lab 2 names and are Spring @Service beans; "
                            + "web handlers are XxxUI classes in boundary.ui (README, naming rule)");

    @ArchTest
    @ArchTag("NFR-MAIN-01")
    static final ArchRule TC_Arch_1b_control_package_holds_only_Controller_services =
            classes().that().resideInAPackage(CONTROL).and().areTopLevelClasses()
                    .should().haveSimpleNameEndingWith("Controller")
                    .andShould().beAnnotatedWith(Service.class)
                    .because("control/ contains only the 13 design control classes; helpers go elsewhere");

    // ---- Rule 2: web handlers = design UI classes -------------------------------------------------------------

    @ArchTest
    @ArchTag("NFR-MAIN-01")
    static final ArchRule TC_Arch_2a_ui_classes_are_Controllers_named_UI =
            classes().that().resideInAPackage(UI_TOP).and().areTopLevelClasses()
                    .should().haveSimpleNameEndingWith("UI")
                    .andShould().beAnnotatedWith(Controller.class)
                    .because("each page is handled by its design «boundary» XxxUI class; helpers and form "
                            + "objects go in boundary.ui.support or inside the UI class");

    @ArchTest
    @ArchTag("NFR-MAIN-01")
    static final ArchRule TC_Arch_2b_Controller_annotation_only_on_UI_classes_in_boundary_ui =
            classes().that().areAnnotatedWith(Controller.class)
                    .should().resideInAPackage(UI_TOP)
                    .andShould().haveSimpleNameEndingWith("UI");

    @ArchTest
    @ArchTag("NFR-MAIN-01")
    static final ArchRule TC_Arch_2c_no_RestController =
            noClasses().should().beAnnotatedWith(RestController.class)
                    .because("pages are server-rendered Thymeleaf views. A JSON endpoint is a @GetMapping + "
                            + "@ResponseBody method inside the owning XxxUI @Controller (e.g. SchoolMapUI for "
                            + "GET /api/districts), never a separate @RestController");

    // ---- Rules 3–5: layer dependencies ------------------------------------------------------------------------

    @ArchTest
    @ArchTag("NFR-MAIN-01")
    @ArchTag("NFR-MAIN-02")
    static final ArchRule TC_Arch_3_ui_does_not_use_persistence_or_external_services =
            noClasses().that().resideInAPackage(UI)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "sg.schoolmatch.persistence..", "sg.schoolmatch.boundary.external..")
                    .because("pages call control classes; only controls use repositories and external interfaces");

    @ArchTest
    @ArchTag("NFR-MAIN-01")
    static final ArchRule TC_Arch_4_entities_do_not_depend_on_other_layers =
            noClasses().that().resideInAPackage(ENTITY_PACKAGE + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            CONTROL, "sg.schoolmatch.boundary..", "sg.schoolmatch.persistence..",
                            "sg.schoolmatch.config..", "sg.schoolmatch.dataset..", "sg.schoolmatch.error..")
                    .because("entities are plain domain classes (JPA/validation annotations and libraries are fine)");

    @ArchTest
    @ArchTag("NFR-MAIN-01")
    static final ArchRule TC_Arch_5_controls_do_not_use_ui =
            noClasses().that().resideInAPackage(CONTROL)
                    .should().dependOnClassesThat().resideInAPackage(UI)
                    .because("controls must work without the web layer");

    // ---- Rule 6: control → control arrows from docs/design/control-deps.csv -----------------------------------

    @ArchTest
    @ArchTag("NFR-MAIN-01")
    static void TC_Arch_6_control_dependencies_are_listed_in_control_deps_csv(JavaClasses classes) {
        Map<String, Set<String>> allowed = readControlDeps(CONTROL_DEPS_CSV);

        Set<String> controls = classes.that(resideInAPackage(CONTROL)).stream()
                .filter(JavaClass::isTopLevelClass)
                .map(JavaClass::getSimpleName)
                .collect(toCollection(TreeSet::new));
        Set<String> namedInCsv = allowed.entrySet().stream()
                .flatMap(e -> Stream.concat(Stream.of(e.getKey()), e.getValue().stream()))
                .collect(toCollection(TreeSet::new));
        assertThat(controls)
                .as("every name in %s must be a class in control/ (check the spelling)", CONTROL_DEPS_CSV)
                .containsAll(namedInCsv);

        classes().that().resideInAPackage(CONTROL)
                .should(onlyDependOnControlsListedIn(allowed))
                .check(classes);
    }

    private static ArchCondition<JavaClass> onlyDependOnControlsListedIn(Map<String, Set<String>> allowed) {
        return new ArchCondition<>("only depend on the controls listed for them in " + CONTROL_DEPS_CSV) {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                String from = designName(source);
                Set<String> mayUse = allowed.getOrDefault(from, Set.of());
                for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String to = designName(target);
                    if (resideInAPackage(CONTROL).test(target) && !to.equals(from) && !mayUse.contains(to)) {
                        events.add(SimpleConditionEvent.violated(dependency, from + " -> " + to
                                + " is not an allowed arrow in " + CONTROL_DEPS_CSV + ": " + dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** Top-level class name, so a nested class counts as its design class ({@code A$1} → {@code A}). */
    private static String designName(JavaClass javaClass) {
        String name = javaClass.getName();
        int nested = name.indexOf('$');
        if (nested >= 0) {
            name = name.substring(0, nested);
        }
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** Reads {@code control,may_depend_on} rows into control → allowed controls. Blank and {@code #} lines are skipped. */
    static Map<String, Set<String>> readControlDeps(Path csv) {
        assertThat(csv)
                .as("%s is missing; run the tests from the project root (./mvnw test)", csv.toAbsolutePath())
                .isRegularFile();
        List<String> lines;
        try {
            lines = new ArrayList<>(Files.readAllLines(csv, UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(lines).as("%s is empty", csv).isNotEmpty();
        assertThat(lines.getFirst().replace("﻿", "").trim())
                .as("header of %s", csv).isEqualTo("control,may_depend_on");

        Map<String, Set<String>> allowed = new TreeMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).replace("\"", "").trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split(",", -1);
            assertThat(cols.length == 2 && !cols[0].isBlank() && !cols[1].isBlank())
                    .as("%s line %d should be 'Control,OtherControl' but is '%s'", csv, i + 1, line)
                    .isTrue();
            allowed.computeIfAbsent(cols[0].trim(), k -> new TreeSet<>()).add(cols[1].trim());
        }
        return allowed;
    }

    // ---- Rule 7: entity/ holds exactly the design entity classes and enumerations ----------------------------

    /** The 27 «entity» classes of the Lab 2 entity class diagram (+ DCs), relative to sg.schoolmatch.entity. */
    static final Set<String> DESIGN_ENTITY_CLASSES = Set.of(
            "account.Account", "account.AuthenticatedSession", "account.UserProfile",
            "common.Coordinate", "common.Place",
            "facility.Facility", "facility.FacilityDataCache", "facility.FacilityFilterCriteria",
            "location.ReferenceLocation",
            "recommend.MatchCriteria", "recommend.Recommendation", "recommend.ScoreComponent",
            "route.Route", "route.RouteStep",
            "school.District", "school.IndicativePsleScoreRange", "school.School", "school.SchoolDataCache",
            "search.CurrentResultSet", "search.Filter", "search.ProximityFilter", "search.PsleScoreFilter",
            "search.SchoolAttributeFilter", "search.TransportationFilter",
            "shortlist.ChoicePlan", "shortlist.SchoolChoice", "shortlist.Shortlist");

    /** The 9 «enumeration» types, relative to sg.schoolmatch.entity. */
    static final Set<String> DESIGN_ENUMS = Set.of(
            "account.AccountStatus", "facility.FacilityType", "location.LocationSource", "recommend.MatchFactor",
            "route.TravelMode", "school.ValidationStatus", "search.AttributeCategory", "search.SortOrder",
            "shortlist.AdmissionChance");

    @ArchTest
    @ArchTag("NFR-MAIN-01")
    static void TC_Arch_7_entity_package_holds_exactly_the_design_classes(JavaClasses classes) {
        List<JavaClass> entityTypes = classes.stream()
                .filter(c -> c.getPackageName().equals(ENTITY_PACKAGE)
                        || c.getPackageName().startsWith(ENTITY_PACKAGE + "."))
                .filter(JavaClass::isTopLevelClass)
                .filter(c -> !c.getSimpleName().equals("package-info"))
                .toList();
        String hint = "entity/ holds only Lab 2 design classes. A new design class needs a DC row in "
                + "docs/design-changes.md and an entry in ArchitectureTest; any other helper belongs outside entity/";

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(relativeNames(entityTypes.stream().filter(c -> !c.isEnum()).toList()))
                    .as("entity classes. " + hint)
                    .containsExactlyInAnyOrderElementsOf(DESIGN_ENTITY_CLASSES);
            softly.assertThat(relativeNames(entityTypes.stream().filter(JavaClass::isEnum).toList()))
                    .as("entity enumerations. " + hint)
                    .containsExactlyInAnyOrderElementsOf(DESIGN_ENUMS);
        });
    }

    // ---- Rule 8: outside HTTP calls only in boundary.external ----------------------------------------------------

    @ArchTest
    @ArchTag("NFR-MAIN-02")
    static final ArchRule TC_Arch_8_only_boundary_external_uses_the_HTTP_client =
            noClasses().that().resideOutsideOfPackages("sg.schoolmatch.boundary.external..", "sg.schoolmatch.config..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.web.client..")
                    .because("outside services are reached only through the integration components in "
                            + "boundary.external (NFR-MAIN-02); config only builds the shared RestClient.Builder "
                            + "with the timeouts (spec §2.6)");

    // ---- Rule 9: only SchoolDataController uses the school dataset store and mapper -----------------------------

    private static final String SCHOOL_DATA_CONTROLLER = "sg.schoolmatch.control.SchoolDataController";

    /** SchoolDataController and its nested classes (records, lambdas). */
    private static final DescribedPredicate<JavaClass> SCHOOL_DATA_CONTROLLER_ITSELF = DescribedPredicate.describe(
            "are SchoolDataController or nested in it", c -> c.getName().equals(SCHOOL_DATA_CONTROLLER)
                    || c.getName().startsWith(SCHOOL_DATA_CONTROLLER + "$"));

    @ArchTest
    @ArchTag("NFR-MAIN-01")
    @ArchTag("FR-DATA-03")
    static final ArchRule TC_Arch_9_only_SchoolDataController_uses_persistence_dataset =
            noClasses().that().resideOutsideOfPackage("sg.schoolmatch.persistence..")
                    .and(DescribedPredicate.not(SCHOOL_DATA_CONTROLLER_ITSELF))
                    .should().dependOnClassesThat().resideInAPackage("sg.schoolmatch.persistence.dataset..")
                    .because("school data is read and written only through SchoolDataController, which loads the "
                            + "snapshot with SchoolDatasetStore and builds the cache with SchoolDatasetMapper "
                            + "(docs/database-design.md, section 7.3; DC-83)");

    @ArchTest
    @ArchTag("NFR-MAIN-01")
    static final ArchRule TC_Arch_9b_SchoolDataController_uses_persistence_dataset =
            classes().that().haveFullyQualifiedName(SCHOOL_DATA_CONTROLLER)
                    .should().dependOnClassesThat().resideInAPackage("sg.schoolmatch.persistence.dataset..")
                    .because("rule 9 must not pass only because nothing uses persistence.dataset");

    private static Set<String> relativeNames(List<JavaClass> types) {
        return types.stream()
                .map(c -> c.getName().substring(ENTITY_PACKAGE.length() + 1))
                .collect(toCollection(TreeSet::new));
    }
}

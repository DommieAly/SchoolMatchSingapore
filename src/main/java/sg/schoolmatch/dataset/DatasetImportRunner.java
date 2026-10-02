package sg.schoolmatch.dataset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import sg.schoolmatch.control.SchoolDataController;

/**
 * Runs the dataset import once and stops the app (DC-12). Exists only under the {@code import} profile
 * (application-import.yml: no web server, live data.gov.sg and OneMap):
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=import
 * </pre>
 * Exit code 0 when the new snapshot is usable (PASSED or PASSED_WITH_WARNINGS), 1 when it FAILED validation or the
 * import stopped with an error. Output folder and activation: see {@link SchoolDataController#importDataset()}.
 */
@Component
@Profile("import")
public class DatasetImportRunner implements ApplicationRunner, ExitCodeGenerator {   // DC-52

    private static final Logger log = LoggerFactory.getLogger(DatasetImportRunner.class);

    private final SchoolDataController schoolDataController;
    private final ConfigurableApplicationContext context;
    private int exitCode;

    public DatasetImportRunner(SchoolDataController schoolDataController, ConfigurableApplicationContext context) {
        this.schoolDataController = schoolDataController;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        importAndReport();
        System.exit(SpringApplication.exit(context));   // uses getExitCode()
    }

    /** Runs the import and returns (and keeps) the exit code. */
    int importAndReport() {
        try {
            ValidationReport report = schoolDataController.importDataset();
            log.info("Dataset import finished: {}", report.getStatus());
            report.getErrors().forEach(e -> log.error("Validation error: {}", e));
            exitCode = report.isUsable() ? 0 : 1;
        } catch (RuntimeException e) {
            log.error("Dataset import stopped: {}", e.getMessage(), e);
            exitCode = 1;
        }
        return exitCode;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}

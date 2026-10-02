package sg.schoolmatch.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.error.ExternalServiceUnavailableException;

/** DatasetImportRunner: runs importDataset() only under the import profile and turns the result into an exit code. */
class DatasetImportRunnerTest {

    private final SchoolDataController controller = mock(SchoolDataController.class);
    private final DatasetImportRunner runner = new DatasetImportRunner(controller, null);

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-ImportRunner-01: the runner exists only under the 'import' profile")
    void onlyImportProfile() {
        Profile profile = DatasetImportRunner.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("import");
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-ImportRunner-02: exit code 0 when the snapshot is usable (PASSED or PASSED_WITH_WARNINGS)")
    void passed() {
        when(controller.importDataset()).thenReturn(new ValidationReport(List.of(), List.of("no-ccas: x has no CCAs")));

        assertThat(runner.importAndReport()).isZero();
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-ImportRunner-03: exit code 1 when the snapshot FAILED validation")
    void failed() {
        when(controller.importDataset()).thenReturn(new ValidationReport(List.of("school-count: 4 schools"), List.of()));

        assertThat(runner.importAndReport()).isEqualTo(1);
        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    @Tag("NFR-DATA-01")
    @DisplayName("TC-ImportRunner-04: exit code 1 when the import stops with an error (e.g. data.gov.sg down)")
    void error() {
        when(controller.importDataset()).thenThrow(new ExternalServiceUnavailableException("data.gov.sg", null));

        assertThat(runner.importAndReport()).isEqualTo(1);
    }
}

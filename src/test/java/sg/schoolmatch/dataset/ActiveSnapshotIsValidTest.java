package sg.schoolmatch.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static sg.schoolmatch.dataset.DatasetTestSupport.reader;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guards the committed data: the snapshot named in data/snapshots/ACTIVE (read from the project folder,
 * exactly as the app reads it with the default {@code app.dataset.dir}) must pass validation.
 * If this fails, the app would refuse to start. Fix the snapshot or point ACTIVE back to a good one.
 */
class ActiveSnapshotIsValidTest {

    @Test
    @Tag("NFR-DATA-01")
    @Tag("FR-DATA-03")
    @Tag("FR-DATA-06")
    @DisplayName("TC-ActiveSnapshot-01: the ACTIVE snapshot in data/snapshots passes validation")
    void activeSnapshotIsValid() {
        SnapshotReader reader = reader(Map.of());   // defaults: app.dataset.dir = data/snapshots
        LoadedSnapshot active = reader.read();

        ValidationReport report = new SnapshotValidator().validate(active);

        assertThat(report.isUsable()).as(report.toString()).isTrue();
        assertThat(active.schools()).isNotEmpty();
        assertThat(active.version()).as("manifest version = folder named in ACTIVE")
                .isEqualTo(SnapshotReader.readActiveVersion(Path.of("data/snapshots")));
    }
}

package sg.schoolmatch.boundary.ui.support;

import sg.schoolmatch.control.SchoolDataController;
import sg.schoolmatch.entity.school.SchoolDataCache;

/**
 * DC-74: whether the pages can use PSLE score ranges, read from {@link SchoolDataController} (the one source).
 * <p>
 * "Unknown" counts as available, so a page behaves as before when the dataset cannot be read: the same rule as
 * the layout ("Dataset not loaded"), and the reason a {@code @WebMvcTest} whose SchoolDataController is an
 * unstubbed mock sees the normal pages.
 */
public final class PsleAvailability {

    /** Shown wherever a PSLE input or filter cannot be used (filter page, "Not applied" note, map). */
    public static final String NOT_AVAILABLE_MESSAGE = SchoolDataController.NO_PSLE_DATA_MESSAGE;

    private PsleAvailability() {
    }

    /** True unless the active dataset is known and has no PSLE score range at all. */
    public static boolean available(SchoolDataController schoolDataController) {
        try {
            return available(schoolDataController.getActiveDataset());
        } catch (RuntimeException e) {
            return true;   // the page itself reports a data problem; do not hide the PSLE features for it
        }
    }

    /** Same rule for a dataset already read; null (not loaded) counts as available. */
    public static boolean available(SchoolDataCache dataset) {
        return dataset == null || dataset.hasPsleData();
    }
}

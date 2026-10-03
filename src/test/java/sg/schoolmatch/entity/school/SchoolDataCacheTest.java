package sg.schoolmatch.entity.school;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.support.TestSchools;

/** SchoolDataCache.hasPsleData (DC-74): does the active dataset have any PSLE score range at all? */
class SchoolDataCacheTest {

    @Test
    @Tag("FR-DATA-03")
    @Tag("DC-74")
    @DisplayName("TC-SchoolDataCache-01: one school with a range is enough for hasPsleData")
    void hasPsleData_oneSchoolWithRange() {
        School withRange = TestSchools.school("with-range").range(2025, 3, 8, 12).build();
        School noRange = TestSchools.school("no-range").build();

        assertThat(cache(withRange, noRange).hasPsleData()).isTrue();
        assertThat(SchoolDataCache.hasPsleData(List.of(noRange, withRange))).isTrue();
    }

    @Test
    @Tag("FR-DATA-03")
    @Tag("DC-74")
    @DisplayName("TC-SchoolDataCache-02: a dataset where no school has a range (or no school at all) has no PSLE data")
    void hasPsleData_noRanges() {
        assertThat(cache(TestSchools.school("a").build(), TestSchools.school("b").build()).hasPsleData()).isFalse();
        assertThat(cache().hasPsleData()).isFalse();
    }

    private static SchoolDataCache cache(School... schools) {
        return new SchoolDataCache("test", Instant.EPOCH, null, List.of(schools), List.of());
    }
}

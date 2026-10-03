package sg.schoolmatch.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static sg.schoolmatch.dataset.DatasetTestSupport.reader;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sg.schoolmatch.entity.school.School;

/**
 * IpRangeNotes: {@code School.ipRangeNote} is computed from the school's Integrated Programme ranges, not stored
 * (docs/database-design.md, sections 2.3 and 5.2; DC-18, DC-77, DC-82).
 */
class IpRangeNotesTest {

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-IpRangeNotes-01: one IP range gives 'IP <year> PG<pg>: <MOE text>'; no IP range gives null")
    void oneRange() {
        assertThat(IpRangeNotes.of(List.of(new ScoreRangeRecord(2025, 3, false, 4, 7, true, "4 - 7"))))
                .isEqualTo("IP 2025 PG3: 4 - 7");
        assertThat(IpRangeNotes.of(List.of(new ScoreRangeRecord(2025, 3, false, 6, 8)))).isNull();
        assertThat(IpRangeNotes.of(List.of())).isNull();
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-IpRangeNotes-02: notes are joined by '; ', newest year first, non-affiliated before affiliated")
    void order() {
        List<ScoreRangeRecord> ranges = List.of(
                new ScoreRangeRecord(2024, 3, true, 4, 9, true, "4(D) - 9(M)"),
                new ScoreRangeRecord(2025, 3, true, 4, 8, true, "4(D) - 8(M)"),
                new ScoreRangeRecord(2025, 3, false, 6, 8),   // not IP: ignored
                new ScoreRangeRecord(2025, 3, false, 4, 6, true, "4(D) - 6(D)"));

        assertThat(IpRangeNotes.of(ranges)).isEqualTo(
                "IP 2025 PG3: 4(D) - 6(D); IP 2025 PG3 affiliated: 4(D) - 8(M); IP 2024 PG3 affiliated: 4(D) - 9(M)");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-IpRangeNotes-03: without MOE text the note shows 'lower–upper'")
    void withoutMoeText() {
        assertThat(IpRangeNotes.of(List.of(new ScoreRangeRecord(2025, 3, false, 4, 7, true))))
                .isEqualTo("IP 2025 PG3: 4–7");
    }

    @Test
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-IpRangeNotes-04: the note rebuilt from the ranges equals ipRangeNote for every school in ACTIVE (16 notes)")
    void everyRealNoteIsRebuilt() {
        LoadedSnapshot active = reader(Map.of()).read();
        int notes = 0;
        for (int i = 0; i < active.records().size(); i++) {
            SchoolRecord r = active.records().get(i);
            School school = active.schools().get(i);
            assertThat(IpRangeNotes.of(r.scoreRanges())).as(r.schoolCode()).isEqualTo(r.ipRangeNote());
            assertThat(IpRangeNotes.ofRanges(school.getScoreRanges())).as(r.schoolCode())
                    .isEqualTo(school.getIpRangeNote());
            if (r.ipRangeNote() != null) {
                notes++;
            }
        }
        assertThat(notes).isEqualTo(16);
    }
}

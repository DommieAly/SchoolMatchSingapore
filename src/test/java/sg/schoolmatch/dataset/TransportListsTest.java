package sg.schoolmatch.dataset;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sg.schoolmatch.dataset.CuratedCsvReader.TransportOverride;
import sg.schoolmatch.dataset.TransportLists.Kind;

/**
 * TransportLists (DC-84, database design 6.1): MOE's bus and MRT texts → lists, with the curated overrides.
 * The real texts are pinned in {@code fixtures/transport/published-texts-2026-10-03.5.csv} (all 147 schools of the
 * last format-1 snapshot; the lists were computed by a separate Python script during the database design).
 */
class TransportListsTest {

    private static final String PINNED = "/fixtures/transport/published-texts-2026-10-03.5.csv";

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-TransportLists-01: split on commas, trim, drop empty and repeated pieces; no text → empty list")
    void splits() {
        assertThat(TransportLists.split("13, 52, 54, 162M")).containsExactly("13", "52", "54", "162M");
        assertThat(TransportLists.split("KATONG PARK MRT,ALJUNIED MRT, KALLANG MRT, MOUNTBATTEN MRT,"))
                .containsExactly("KATONG PARK MRT", "ALJUNIED MRT", "KALLANG MRT", "MOUNTBATTEN MRT");
        assertThat(TransportLists.split("913, 913M, 913, 913T, 178")).containsExactly("913", "913M", "913T", "178");
        assertThat(TransportLists.split(" ,  , ")).isEmpty();
        assertThat(TransportLists.split(null)).isEmpty();
    }

    @ParameterizedTest(name = "TC-TransportLists-02 [{index}]: bus service ''{0}'' is accepted")
    @ValueSource(strings = {"904", "70M", "74e", "CT18", "853M", "12e", "5", "243G"})
    @Tag("FR-DATA-03")
    @DisplayName("TC-TransportLists-02: a bus service matches ^[A-Z]{0,2}[0-9]{1,3}[A-Za-z]?$")
    void acceptsBusServices(String service) {
        assertThat(TransportLists.problem(Kind.BUS, service)).isNull();
    }

    @ParameterizedTest(name = "TC-TransportLists-03 [{index}]: bus service ''{0}'' is refused")
    @ValueSource(strings = {"243G/W", "14E 16", "SBS Transit No 170", "962 (Bus Stop IDs: 47549 or 47541)",
            "293 and 518.", "", " ", "ABC1", "1234", "12ab"})
    @Tag("FR-DATA-03")
    @DisplayName("TC-TransportLists-03: anything else is not one bus service")
    void refusesBadBusServices(String service) {
        assertThat(TransportLists.problem(Kind.BUS, service)).isNotNull();
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-TransportLists-04: an MRT station must not contain ':', ' - ', ' and ', '&', 'campus' (any case) or a comma")
    void mrtRule() {
        assertThat(TransportLists.problem(Kind.MRT, "BISHAN MRT")).isNull();
        assertThat(TransportLists.problem(Kind.MRT, "OUTRAM PARK MRT (EW16)")).isNull();
        assertThat(TransportLists.problem(Kind.MRT, "Clementi")).isNull();
        assertThat(TransportLists.problem(Kind.MRT, "Sandy Land MRT")).as("'and' inside a word is fine").isNull();
        assertThat(TransportLists.problem(Kind.MRT, "Nearest MRT Stations - Clementi and Dover")).isNotNull();
        assertThat(TransportLists.problem(Kind.MRT, "Clementi and Dover")).isNotNull();
        assertThat(TransportLists.problem(Kind.MRT, "York Hill Campus")).isNotNull();
        assertThat(TransportLists.problem(Kind.MRT, "Bishan & Braddell")).isNotNull();
        assertThat(TransportLists.problem(Kind.MRT, "Stations: Bishan")).isNotNull();
        assertThat(TransportLists.problem(Kind.MRT, "BISHAN MRT, BRADDELL MRT")).isNotNull();
        assertThat(TransportLists.problem(Kind.MRT, " ")).isNotNull();
    }

    @Test
    @Tag("FR-SCHOOL-03")
    @DisplayName("TC-TransportLists-05: join() rebuilds the display text with \", \"; an empty list gives null ('Not available')")
    void joins() {
        assertThat(TransportLists.join(List.of("13", "52", "162M"))).isEqualTo("13, 52, 162M");
        assertThat(TransportLists.join(List.of("BISHAN MRT"))).isEqualTo("BISHAN MRT");
        assertThat(TransportLists.join(List.of())).isNull();
        assertThat(TransportLists.join(null)).isNull();
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-TransportLists-06: an override is used while MOE's text is exactly its published_text")
    void overrideApplies() {
        TransportLists lists = new TransportLists(List.of(
                new TransportOverride("spectra-secondary-school", Kind.BUS,
                        "901M, 962 (Bus Stop IDs: 47549 or 47541), 904", List.of("901M", "962", "904"), "labels")));
        ImportLog log = new ImportLog();

        assertThat(lists.elements(Kind.BUS, "spectra-secondary-school", "901M, 962 (Bus Stop IDs: 47549 or 47541), 904",
                log)).containsExactly("901M", "962", "904");
        assertThat(lists.elements(Kind.MRT, "spectra-secondary-school", "JURONG EAST MRT", log))
                .as("an override is per kind").containsExactly("JURONG EAST MRT");
        assertThat(lists.elements(Kind.BUS, "other-school", "901M, 962", log)).containsExactly("901M", "962");
        assertThat(log.warnings()).isEmpty();
        assertThat(log.count(TransportLists.OVERRIDES_APPLIED)).isEqualTo(1);
    }

    @Test
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-TransportLists-07: a stale override (MOE's text changed) is reported and the plain split is used")
    void staleOverride() {
        TransportLists lists = new TransportLists(List.of(
                new TransportOverride("spectra-secondary-school", Kind.BUS, "901M, 962 (Bus Stop IDs: 47549)",
                        List.of("901M", "962"), "labels")));
        ImportLog log = new ImportLog();

        List<String> buses = lists.elements(Kind.BUS, "spectra-secondary-school",
                "901M, 962 (Bus Stop IDs: 47549 or 47541), 904", log);

        assertThat(buses).containsExactly("901M", "962 (Bus Stop IDs: 47549 or 47541)", "904");
        assertThat(log.warnings(ImportLog.TRANSPORT_OVERRIDE_STALE)).singleElement().asString()
                .startsWith(ImportLog.TRANSPORT_OVERRIDE_STALE + ": spectra-secondary-school bus")
                .contains(CuratedCsvReader.TRANSPORT_OVERRIDES);
        assertThat(log.count(TransportLists.OVERRIDES_APPLIED)).isZero();
        assertThat(TransportLists.problem(Kind.BUS, buses.get(1))).as("the validator then fails it").isNotNull();
    }

    @Test
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-TransportLists-08: all 147 real bus and MRT texts give the pinned lists (1,492 bus and 243 MRT rows) with the repository's overrides")
    void realTextsGivePinnedLists() {
        TransportLists lists = new TransportLists(repositoryOverrides());
        ImportLog log = new ImportLog();
        int bus = 0;
        int mrt = 0;
        List<String[]> pinned = pinned();
        for (String[] row : pinned) {
            Kind kind = Kind.fromCsv(row[1]);
            List<String> expected = List.of(row[3].split(";"));

            List<String> actual = lists.elements(kind, row[0], row[2], log);

            assertThat(actual).as(row[0] + " " + row[1]).isEqualTo(expected);
            assertThat(actual).as(row[0] + " " + row[1]).allMatch(e -> TransportLists.problem(kind, e) == null);
            if (kind == Kind.BUS) {
                bus += actual.size();
            } else {
                mrt += actual.size();
            }
        }
        assertThat(pinned).hasSize(2 * 147);
        assertThat(bus).isEqualTo(1492);
        assertThat(mrt).isEqualTo(243);
        assertThat(log.warnings()).as("no override is stale").isEmpty();
        assertThat(log.count(TransportLists.OVERRIDES_APPLIED)).isEqualTo(9);
    }

    @Test
    @Tag("FR-SCHOOL-03")
    @DisplayName("TC-TransportLists-09: 138 bus texts and 144 MRT texts display exactly as before; the other 12 lose only labels, a repeat or a stray comma")
    void displayTextMostlyUnchanged() {
        TransportLists lists = new TransportLists(repositoryOverrides());
        int sameBus = 0;
        int sameMrt = 0;
        for (String[] row : pinned()) {
            Kind kind = Kind.fromCsv(row[1]);
            if (row[2].equals(TransportLists.join(lists.elements(kind, row[0], row[2], new ImportLog())))) {
                if (kind == Kind.BUS) {
                    sameBus++;
                } else {
                    sameMrt++;
                }
            }
        }
        assertThat(sameBus).isEqualTo(138);
        assertThat(sameMrt).isEqualTo(144);
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-TransportLists-10: every row of data/curated/transport-overrides.csv is needed, and its elements pass the rules")
    void everyOverrideIsNeeded() {
        List<TransportOverride> overrides = repositoryOverrides();

        assertThat(overrides).hasSize(9);
        for (TransportOverride o : overrides) {
            assertThat(TransportLists.split(o.publishedText()))
                    .as(o.schoolCode() + " " + o.kind() + ": the plain split has an element that fails")
                    .anyMatch(e -> TransportLists.problem(o.kind(), e) != null);
            assertThat(o.elements()).as(o.schoolCode()).isNotEmpty()
                    .allMatch(e -> TransportLists.problem(o.kind(), e) == null)
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    @Tag("FR-DATA-03")
    @Tag("NFR-DATA-01")
    @DisplayName("TC-TransportLists-11: an element with leading or trailing white space is refused (the loader writes it trimmed, so 'X' and 'X ' would clash in the database key)")
    void untrimmedElementRefused() {
        for (String station : List.of("TAMPINES MRT ", " TAMPINES MRT", "TAMPINES MRT\n", "\tTAMPINES MRT",
                "TAMPINES MRT ")) {
            assertThat(TransportLists.problem(Kind.MRT, station)).as("[" + station + "]")
                    .isEqualTo("has leading or trailing white space");
        }
        for (String service : List.of("13 ", " 13", "13\n")) {
            assertThat(TransportLists.problem(Kind.BUS, service)).as("[" + service + "]")
                    .isEqualTo("has leading or trailing white space");
        }
        assertThat(TransportLists.problem(Kind.MRT, "TAMPINES MRT")).isNull();
        assertThat(TransportLists.problem(Kind.BUS, "13")).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private static List<TransportOverride> repositoryOverrides() {
        CuratedCsvReader.CuratedData data = new CuratedCsvReader("data/curated").read();
        assertThat(data.problems()).isEmpty();
        return data.transportOverrides();
    }

    /** Rows of the pinned CSV: school_code, kind, published_text, elements (";"-separated). */
    private static List<String[]> pinned() {
        String text;
        try (InputStream in = TransportListsTest.class.getResourceAsStream(PINNED)) {
            text = new String(in.readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String[]> rows = new ArrayList<>();
        List<CuratedCsvReader.CsvLine> lines = CuratedCsvReader.parse(text);
        for (CuratedCsvReader.CsvLine line : lines.subList(1, lines.size())) {
            rows.add(line.values().toArray(String[]::new));
        }
        return rows;
    }
}

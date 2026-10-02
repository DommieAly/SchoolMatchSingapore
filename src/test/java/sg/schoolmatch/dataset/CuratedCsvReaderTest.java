package sg.schoolmatch.dataset;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sg.schoolmatch.dataset.CuratedCsvReader.CuratedData;
import sg.schoolmatch.dataset.CuratedCsvReader.GeocodeOverride;
import sg.schoolmatch.dataset.CuratedCsvReader.PsleRangeRow;
import sg.schoolmatch.dataset.CuratedCsvReader.SchoolCodeRow;

/** CuratedCsvReader: the five hand-maintained CSV files in data/curated (data/README.md, curation rules). */
class CuratedCsvReaderTest {

    @TempDir
    Path folder;

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-CuratedCsv-01: the repository's data/curated files (header rows only) read as empty")
    void repositoryFilesAreEmpty() {
        CuratedData data = new CuratedCsvReader("data/curated").read();

        assertThat(data.schoolCodes()).isEmpty();
        assertThat(data.aliases()).isEmpty();
        assertThat(data.psleRanges()).isEmpty();
        assertThat(data.geocodeOverrides()).isEmpty();
        assertThat(data.affiliations()).isEmpty();
        assertThat(data.problems()).isEmpty();
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-CuratedCsv-02: quoted values may hold commas and quotes; BOM, CRLF and blank lines are fine")
    void quotedValues() throws IOException {
        writeAllHeaders();
        write(CuratedCsvReader.SCHOOL_CODES, "﻿school_name,school_code,source_url\r\n"
                + "\"SCHOOL OF THE ARTS, SINGAPORE\",school-of-the-arts,https://example.test/a\r\n"
                + "\r\n"
                + "\"THE \"\"QUOTED\"\" SCHOOL\",quoted-school,\r\n");

        CuratedData data = new CuratedCsvReader(folder.toString()).read();

        assertThat(data.schoolCodes()).containsExactly(
                new SchoolCodeRow("SCHOOL OF THE ARTS, SINGAPORE", "school-of-the-arts", "https://example.test/a"),
                new SchoolCodeRow("THE \"QUOTED\" SCHOOL", "quoted-school", null));
        assertThat(data.problems()).isEmpty();
    }

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-CuratedCsv-03: PSLE rows are parsed; a row with a bad number is skipped and reported")
    void psleRows() throws IOException {
        writeAllHeaders();
        write(CuratedCsvReader.PSLE_RANGES,
                "school_code,admission_year,posting_group,track,lower,upper,raw_text,source_url,entered_by,checked_by\n"
                        + "catholic-high-school,2025,3,non_affiliated,8,12,8 - 12,https://example.test,Ann,Ben\n"
                        + "catholic-high-school,2025,2,NON_AFFILIATED,x,14,? - 14,https://example.test,Ann,Ben\n");

        CuratedData data = new CuratedCsvReader(folder.toString()).read();

        assertThat(data.psleRanges()).containsExactly(new PsleRangeRow("catholic-high-school", 2025, 3,
                "NON_AFFILIATED", 8, 12, "8 - 12", "https://example.test", "Ann", "Ben"));
        assertThat(data.problems()).singleElement().asString()
                .contains(CuratedCsvReader.PSLE_RANGES).contains("line 3").contains("lower");
    }

    @Test
    @Tag("NFR-DATA-03")
    @DisplayName("TC-CuratedCsv-04: a PSLE row counts as checked only when checked_by is a second person")
    void checkedRule() {
        PsleRangeRow checked = new PsleRangeRow("a", 2025, 3, "NON_AFFILIATED", 8, 12, null, null, "Ann", "Ben");
        PsleRangeRow unchecked = new PsleRangeRow("a", 2025, 3, "NON_AFFILIATED", 8, 12, null, null, "Ann", null);
        PsleRangeRow selfChecked = new PsleRangeRow("a", 2025, 3, "NON_AFFILIATED", 8, 12, null, null, "Ann", "ann");

        assertThat(checked.isChecked()).isTrue();
        assertThat(unchecked.isChecked()).isFalse();
        assertThat(selfChecked.isChecked()).isFalse();
    }

    @Test
    @Tag("FR-DATA-06")
    @DisplayName("TC-CuratedCsv-05: geocode overrides keep their coordinates; a bad coordinate is reported")
    void geocodeOverrides() throws IOException {
        writeAllHeaders();
        write(CuratedCsvReader.GEOCODE_OVERRIDES, "postal_code,school_code,latitude,longitude,reason\n"
                + "099138,,1.2763,103.8239,OneMap returns the wrong block\n"
                + ",some-school,north,103.8,typo\n");

        CuratedData data = new CuratedCsvReader(folder.toString()).read();

        assertThat(data.geocodeOverrides()).containsExactly(
                new GeocodeOverride("099138", null, 1.2763, 103.8239, "OneMap returns the wrong block"));
        assertThat(data.problems()).singleElement().asString().contains("latitude");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-CuratedCsv-06: a missing file is reported and read as empty; a missing folder stops the import")
    void missingFiles() throws IOException {
        Files.writeString(folder.resolve(CuratedCsvReader.NAME_ALIASES), "dataset,raw_name,canonical_name\n", UTF_8);

        CuratedData data = new CuratedCsvReader(folder.toString()).read();

        assertThat(data.problems()).hasSize(4).allMatch(p -> p.contains("not found"));
        assertThatThrownBy(() -> new CuratedCsvReader(folder.resolve("nope").toString()).read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");
    }

    @Test
    @Tag("FR-DATA-03")
    @DisplayName("TC-CuratedCsv-07: a file whose header lacks a column is reported and its rows are not used")
    void wrongHeader() throws IOException {
        writeAllHeaders();
        write(CuratedCsvReader.AFFILIATIONS, "school_code,primary\nx,y\n");

        CuratedData data = new CuratedCsvReader(folder.toString()).read();

        assertThat(data.affiliations()).isEmpty();
        assertThat(data.problems()).singleElement().asString()
                .contains(CuratedCsvReader.AFFILIATIONS).contains("primary_school");
    }

    private void writeAllHeaders() throws IOException {
        write(CuratedCsvReader.SCHOOL_CODES, "school_name,school_code,source_url\n");
        write(CuratedCsvReader.NAME_ALIASES, "dataset,raw_name,canonical_name\n");
        write(CuratedCsvReader.PSLE_RANGES,
                "school_code,admission_year,posting_group,track,lower,upper,raw_text,source_url,entered_by,checked_by\n");
        write(CuratedCsvReader.GEOCODE_OVERRIDES, "postal_code,school_code,latitude,longitude,reason\n");
        write(CuratedCsvReader.AFFILIATIONS, "school_code,primary_school,source_url\n");
    }

    private void write(String file, String content) throws IOException {
        Files.writeString(folder.resolve(file), content, UTF_8);
    }
}

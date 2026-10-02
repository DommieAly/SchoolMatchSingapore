package sg.schoolmatch.boundary.external;

import java.util.List;

/**
 * Design class «boundary» DataGovSgInterface — reads MOE school datasets from data.gov.sg
 * (FR-DATA-03, NFR-MAIN-02). Used only by SchoolDataController.importDataset() (import profile).
 * Not wired yet: owner B adds an {@code @Profile("import")} ApplicationRunner that calls importDataset().
 * DC-12: fetchScoreRanges removed (no API provides it); fetchSchoolCcas and fetchSchoolSubjects added.
 * DC-33: returns raw rows (field name → value); the importer joins them and adds codes, coordinates, ranges.
 * Implementations: StubDataGovSg (app.external.datagovsg.mode=stub, default) and DataGovSgClient (live).
 */
public interface DataGovSgInterface {

    /** General information of schools (one record per school). */
    List<DataGovSgRecord> fetchSchools();

    /** CCAs offered (one record per school and CCA). */
    List<DataGovSgRecord> fetchSchoolCcas();

    /** Subjects offered (one record per school and subject). */
    List<DataGovSgRecord> fetchSchoolSubjects();

    /** URA planning-area boundaries as a GeoJSON FeatureCollection string. */
    String fetchDistrictsGeoJson();
}

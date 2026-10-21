package sg.schoolmatch.boundary.external;

import java.util.List;

/**
 * Design class «boundary» DataGovSgInterface — reads MOE school datasets from data.gov.sg
 * (FR-DATA-03, NFR-MAIN-02). Used only by SchoolDataController.importDataset() (import profile).
 * {@code DatasetImportRunner} ({@code @Profile("import")}) calls importDataset().
 * DC-12: fetchScoreRanges removed (no API provides it); fetchSchoolCcas and fetchSchoolSubjects added.
 * DC-33: returns raw rows (field name → value); the importer joins them and adds codes, coordinates, ranges.
 * Implementations: StubDataGovSg (app.external.datagovsg.mode=stub, default) and DataGovSgClient (live).
 */
public interface DataGovSgInterface {

    /** data.gov.sg dataset ids the importer reads (data/README.md) and lists as manifest sources. */
    String SCHOOLS_DATASET = "d_688b934f82c1059ed0a6993d2a829089";
    String CCAS_DATASET = "d_9aba12b5527843afb0b2e8e4ed6ac6bd";
    String SUBJECTS_DATASET = "d_f1d144e423570c9d84dbc5102c2e664d";
    String PLANNING_AREAS_DATASET = "d_4765db0e87b9c86336792efe8a1f7a66";

    /** General information of schools (one record per school). */
    List<DataGovSgRecord> fetchSchools();

    /** CCAs offered (one record per school and CCA). */
    List<DataGovSgRecord> fetchSchoolCcas();

    /** Subjects offered (one record per school and subject). */
    List<DataGovSgRecord> fetchSchoolSubjects();

    /** URA planning-area boundaries as a GeoJSON FeatureCollection string. */
    String fetchDistrictsGeoJson();
}

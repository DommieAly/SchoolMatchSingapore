package sg.schoolmatch.persistence.dataset;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import sg.schoolmatch.dataset.LoadedSnapshot;
import sg.schoolmatch.dataset.MoeRangeText;
import sg.schoolmatch.dataset.SchoolRecord;
import sg.schoolmatch.dataset.SnapshotManifest;
import sg.schoolmatch.dataset.ValidationReport;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;

/**
 * One snapshot as table rows (docs/database-design.md, sections 4.2 and 7.2). Nothing is parsed except MOE's range
 * text, which {@link MoeRangeText} splits into its parts.
 * <p>
 * The values are those {@code SnapshotReader} puts into {@code School} and {@code District} (trimmed, blank as
 * null), so the database holds exactly what the app shows today. {@code School} has no postal code, planning-area
 * code or bus and MRT lists, so those come from the school's {@link SchoolRecord} (same index).
 * <p>
 * Step 5: {@link SchoolDatasetStore#readActive()} returns the active dataset in this shape too (withdrawn schools and
 * areas left out, rows in no particular order), and {@link SchoolDatasetMapper} turns it into the entities.
 */
public record SnapshotRows(
        DatasetVersionRow version,
        List<DatasetSourceRow> sources,
        List<DatasetWarningRow> warnings,
        List<DistrictRow> districts,
        List<SchoolRow> schools,
        List<ScoreRangeRow> scoreRanges,
        List<SchoolCcaRow> ccas,
        List<SchoolProgrammeRow> programmes,
        List<SchoolAffiliatedPrimaryRow> affiliations,
        List<SchoolBusServiceRow> busServices,
        List<SchoolMrtStationRow> mrtStations) {

    /**
     * Rows of {@code snapshot}. Timestamps are cut to microseconds, the precision of
     * {@code TIMESTAMP(6) WITH TIME ZONE}, so what is compared later is what is stored.
     */
    static SnapshotRows of(LoadedSnapshot snapshot, ValidationReport report, String sha256, int loaderFormat,
                           Instant loadedAt) {
        SnapshotManifest m = snapshot.manifest();
        String v = m.version();
        DatasetVersionRow version = new DatasetVersionRow(v, m.kind().toLowerCase(Locale.ROOT), m.effectiveDate(),
                micros(m.importedAt()), m.validationStatus() == null ? null : m.validationStatus().name(),
                report.getStatus().name(), m.notes(), sha256, loaderFormat, micros(loadedAt));

        List<DatasetSourceRow> sources = new ArrayList<>();
        for (int i = 0; i < m.sources().size(); i++) {
            SnapshotManifest.Source s = m.sources().get(i);
            sources.add(new DatasetSourceRow(v, i + 1, s.datasetId(), s.name(), micros(s.downloadedAt())));
        }
        List<DatasetWarningRow> warnings = new ArrayList<>();
        for (int i = 0; i < m.warnings().size(); i++) {
            warnings.add(new DatasetWarningRow(v, i + 1, m.warnings().get(i)));
        }
        List<DistrictRow> districts = new ArrayList<>();
        for (District d : snapshot.districts()) {
            districts.add(new DistrictRow(d.getPlanningAreaCode(), d.getPlanningAreaName(), d.getBoundaryGeoJson(),
                    null));
        }

        List<SchoolRow> schools = new ArrayList<>();
        List<ScoreRangeRow> ranges = new ArrayList<>();
        List<SchoolCcaRow> ccas = new ArrayList<>();
        List<SchoolProgrammeRow> programmes = new ArrayList<>();
        List<SchoolAffiliatedPrimaryRow> affiliations = new ArrayList<>();
        List<SchoolBusServiceRow> buses = new ArrayList<>();
        List<SchoolMrtStationRow> stations = new ArrayList<>();
        for (int i = 0; i < snapshot.records().size(); i++) {
            SchoolRecord r = snapshot.records().get(i);
            School s = snapshot.schools().get(i);
            String code = s.getSchoolCode();
            if (s.getCoordinate() == null) {   // the validator refuses this (bad-coordinate)
                throw new IllegalArgumentException(code + " has no coordinate; validate the snapshot first");
            }
            schools.add(new SchoolRow(code, s.getName(), s.getAddress(), clean(r.postalCode()),
                    s.getCoordinate().getLatitude(), s.getCoordinate().getLongitude(), s.getTelephone(),
                    s.getWebsite(), s.getEmail(), s.getSchoolType(), s.getSessionType(), s.getSchoolNature(),
                    clean(r.planningAreaCode()), null));
            for (IndicativePsleScoreRange x : s.getScoreRanges()) {
                MoeRangeText.Parts p = MoeRangeText.parts(x.getMoeText(), x.getLowerScore(), x.getUpperScore());
                ranges.add(new ScoreRangeRow(code, x.getAdmissionYear(), x.getPostingGroup(), x.isAffiliated(),
                        x.isIntegratedProgramme(), x.getLowerScore(), x.getUpperScore(), p.lowerGrade(),
                        p.upperGrade(), p.placesLeft()));
            }
            s.getCcas().forEach(name -> ccas.add(new SchoolCcaRow(code, name)));
            s.getProgrammes().forEach(name -> programmes.add(new SchoolProgrammeRow(code, name)));
            s.getAffiliatedPrimarySchools().forEach(name -> affiliations.add(new SchoolAffiliatedPrimaryRow(code, name)));
            int position = 0;
            for (String bus : r.busServices()) {
                if (clean(bus) != null) {
                    buses.add(new SchoolBusServiceRow(code, clean(bus), ++position));
                }
            }
            position = 0;
            for (String station : r.mrtStations()) {
                if (clean(station) != null) {
                    stations.add(new SchoolMrtStationRow(code, clean(station), ++position));
                }
            }
        }
        return new SnapshotRows(version, sources, warnings, districts, schools, ranges, ccas, programmes,
                affiliations, buses, stations);
    }

    /** Design section 6.2 step 5: the manifest counts must equal the snapshot's rows. */
    void checkCounts(Map<String, Integer> counts) {
        check(counts, "schools", schools.size());
        check(counts, "districts", districts.size());
        check(counts, "scoreRanges", scoreRanges.size());
    }

    private void check(Map<String, Integer> counts, String key, int rows) {
        Integer expected = counts.get(key);
        if (expected != null && expected != rows) {
            throw new IllegalStateException("manifest.json of " + version.datasetVersion() + " counts " + expected
                    + " " + key + ", but the snapshot has " + rows);
        }
    }

    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

package sg.schoolmatch.dataset;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.entity.common.Coordinate;
import sg.schoolmatch.entity.school.District;
import sg.schoolmatch.entity.school.IndicativePsleScoreRange;
import sg.schoolmatch.entity.school.School;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one school snapshot folder ({@code manifest.json}, {@code schools.json}, {@code districts.geojson})
 * and maps it to entities (FR-DATA-03, NFR-DATA-01). It does not validate: see {@link SnapshotValidator}.
 * <p>
 * Which folder: {@code app.dataset.snapshot-location} when set (tests use
 * {@code classpath:fixtures/snapshot-mini/}); otherwise the folder named in {@code <app.dataset.dir>/ACTIVE}.
 */
@Component
public class SnapshotReader {

    public static final String ACTIVE_FILE = "ACTIVE";
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String SCHOOLS_FILE = "schools.json";
    public static final String DISTRICTS_FILE = "districts.geojson";

    /** Allowed folder names in ACTIVE; no slashes, so ACTIVE cannot point outside the dataset dir. */
    private static final Pattern VERSION_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AppProperties.DatasetSettings settings;
    private final ResourceLoader resourceLoader;

    public SnapshotReader(AppProperties props, ResourceLoader resourceLoader) {
        this.settings = props.dataset();
        this.resourceLoader = resourceLoader;
    }

    /** Reads the configured snapshot (see class comment). */
    public LoadedSnapshot read() {
        return read(resolveLocation());
    }

    /** The folder to read, as a Spring resource location ending in "/". */
    public String resolveLocation() {
        if (settings.hasSnapshotLocation()) {
            return withSlash(settings.snapshotLocation().trim());
        }
        Path dir = Path.of(settings.dir());
        Path folder = dir.resolve(readActiveVersion(dir)).toAbsolutePath().normalize();
        return withSlash(folder.toUri().toString());
    }

    /** Reads the snapshot in {@code folderLocation} (a {@code file:} or {@code classpath:} folder). */
    public LoadedSnapshot read(String folderLocation) {
        String base = withSlash(folderLocation);
        SnapshotManifest manifest = readJson(base, MANIFEST_FILE, new TypeReference<>() { });
        List<SchoolRecord> records = readJson(base, SCHOOLS_FILE, new TypeReference<>() { });
        List<District> districts = readDistricts(base);
        List<School> schools = records.stream().map(SnapshotReader::toSchool).toList();
        return new LoadedSnapshot(base, manifest, records, schools, districts);
    }

    /** The version named in {@code dir/ACTIVE} (its first non-blank line). */
    public static String readActiveVersion(Path dir) {
        Path active = dir.resolve(ACTIVE_FILE);
        List<String> lines;
        try {
            lines = Files.readAllLines(active, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + active.toAbsolutePath()
                    + " (start the app from the repository root, or set app.dataset.dir)", e);
        }
        String version = lines.stream().map(String::trim).filter(l -> !l.isEmpty()).findFirst()
                .orElseThrow(() -> new IllegalStateException(active.toAbsolutePath() + " is empty"));
        if (!VERSION_NAME.matcher(version).matches()) {
            throw new IllegalStateException(active.toAbsolutePath() + " names an invalid folder: '" + version + "'");
        }
        return version;
    }

    // ------------------------------------------------------------------ JSON → records / entities

    private <T> T readJson(String base, String file, TypeReference<T> type) {
        try (InputStream in = open(base, file)) {
            T value = JSON.readValue(in, type);
            if (value == null) {
                throw new IllegalStateException(base + file + " is empty (JSON null)");
            }
            return value;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + base + file, e);
        } catch (JacksonException e) {
            throw new IllegalStateException("Invalid JSON in " + base + file + ": " + e.getOriginalMessage(), e);
        }
    }

    /** Each GeoJSON Feature → District; the geometry is kept as a JSON string (drawn by the browser). */
    private List<District> readDistricts(String base) {
        JsonNode root;
        try (InputStream in = open(base, DISTRICTS_FILE)) {
            root = JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + base + DISTRICTS_FILE, e);
        } catch (JacksonException e) {
            throw new IllegalStateException("Invalid JSON in " + base + DISTRICTS_FILE + ": " + e.getOriginalMessage(), e);
        }
        List<District> districts = new ArrayList<>();
        for (JsonNode feature : root.path("features").values()) {
            JsonNode properties = feature.path("properties");
            JsonNode geometry = feature.get("geometry");
            districts.add(new District(text(properties, "planningAreaCode"), text(properties, "planningAreaName"),
                    geometry == null || geometry.isNull() ? null : JSON.writeValueAsString(geometry)));
        }
        return districts;
    }

    private InputStream open(String base, String file) throws IOException {
        Resource resource = resourceLoader.getResource(base + file);
        if (!resource.exists()) {
            throw new IllegalStateException("Snapshot file not found: " + base + file);
        }
        return resource.getInputStream();
    }

    /**
     * Record → School. Blank strings become null; a coordinate that cannot exist becomes null.
     * Public for the importer, which validates its records before writing them (DC-12).
     */
    public static School toSchool(SchoolRecord r) {
        School school = new School(blankToNull(r.schoolCode()), blankToNull(r.name()));
        school.setAddress(blankToNull(r.address()));
        school.setCoordinate(toCoordinate(r.latitude(), r.longitude()));
        school.setTelephone(blankToNull(r.telephone()));
        school.setWebsite(blankToNull(r.website()));
        school.setEmail(blankToNull(r.email()));
        school.setSchoolType(blankToNull(r.schoolType()));
        school.setPlanningArea(blankToNull(r.planningAreaName()));
        school.setNearestMrt(blankToNull(r.nearestMrt()));
        school.setBusInfo(blankToNull(r.busInfo()));
        school.setSessionType(blankToNull(r.sessionType()));
        school.setSchoolNature(blankToNull(r.schoolNature()));
        school.setProgrammes(nonBlank(r.programmes()));
        school.setCcas(nonBlank(r.ccas()));
        school.setAffiliatedPrimarySchools(nonBlank(r.affiliatedPrimarySchools()));
        school.setIpRangeNote(blankToNull(r.ipRangeNote()));
        school.setScoreRanges(r.scoreRanges().stream()
                .filter(ScoreRangeRecord::isComplete)   // incomplete ranges are reported by the validator
                .map(x -> new IndicativePsleScoreRange(x.admissionYear(), x.postingGroup(), x.affiliated(),
                        x.lowerScore(), x.upperScore()))
                .toList());
        return school;
    }

    private static Coordinate toCoordinate(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return null;
        }
        try {
            return new Coordinate(latitude, longitude);
        } catch (IllegalArgumentException e) {
            return null;   // impossible value; SnapshotValidator reports it as bad-coordinate
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : blankToNull(value.asString());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> nonBlank(Collection<String> values) {
        return values.stream().map(SnapshotReader::blankToNull).filter(Objects::nonNull).toList();
    }

    private static String withSlash(String location) {
        return location.endsWith("/") ? location : location + "/";
    }
}

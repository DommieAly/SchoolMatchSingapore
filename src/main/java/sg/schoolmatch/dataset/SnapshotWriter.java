package sg.schoolmatch.dataset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.springframework.stereotype.Component;
import sg.schoolmatch.entity.school.District;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes one importer snapshot folder {@code <outputDir>/<yyyy-MM-dd>.<n>/} (DC-12): {@code manifest.json},
 * {@code schools.json} (sorted by schoolCode, missing values as JSON null), {@code districts.geojson} (simplified),
 * {@code validation-report.md} and {@code import-log.txt}. An existing folder is never overwritten.
 * {@link #activate} updates {@code ACTIVE}; the importer calls it only when allowed (see SchoolDataController).
 */
@Component
public class SnapshotWriter {

    public static final String VALIDATION_REPORT_FILE = "validation-report.md";
    public static final String IMPORT_LOG_FILE = "import-log.txt";

    /** Keep districts.geojson small enough for the map page and the repo (spec: under 300 KB). */
    public static final int MAX_DISTRICTS_BYTES = 300_000;

    /** Simplification tolerances tried in order (degrees; 0.0001° is about 11 m), until the file is small enough. */
    static final double[] SIMPLIFY_TOLERANCES_DEG = {0.0001, 0.0002, 0.0005, 0.001, 0.002};

    private static final int COORDINATE_DECIMALS = 6;   // about 0.1 m
    private static final Pattern VERSION = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)");

    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final JsonMapper COMPACT = JsonMapper.builder().build();

    /** {@code <date>.<n>} with n one more than the highest existing folder for that date (1 for the first). */
    public String nextVersion(Path outputDir, LocalDate date) {
        int highest = 0;
        if (Files.isDirectory(outputDir)) {
            try (Stream<Path> children = Files.list(outputDir)) {
                for (Path child : children.filter(Files::isDirectory).toList()) {
                    Matcher m = VERSION.matcher(child.getFileName().toString());
                    if (m.matches() && m.group(1).equals(date.toString())) {
                        highest = Math.max(highest, Integer.parseInt(m.group(2)));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list " + outputDir, e);
            }
        }
        return date + "." + (highest + 1);
    }

    /**
     * Writes the snapshot folder {@code outputDir/version} and returns it.
     *
     * @throws IllegalStateException when that folder already exists
     */
    public Path write(Path outputDir, String version, SnapshotManifest manifest, List<SchoolRecord> schools,
                      String districtsGeoJson, String validationReport, String importLog) {
        Path folder = outputDir.resolve(version);
        if (Files.exists(folder)) {
            throw new IllegalStateException("Snapshot folder " + folder.toAbsolutePath() + " already exists");
        }
        List<SchoolRecord> sorted = schools.stream()
                .sorted(Comparator.comparing(SchoolRecord::schoolCode, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(SnapshotReader.MANIFEST_FILE),
                    PRETTY.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
            Files.writeString(folder.resolve(SnapshotReader.SCHOOLS_FILE),
                    PRETTY.writeValueAsString(sorted) + "\n", StandardCharsets.UTF_8);
            Files.writeString(folder.resolve(SnapshotReader.DISTRICTS_FILE), districtsGeoJson + "\n",
                    StandardCharsets.UTF_8);
            Files.writeString(folder.resolve(VALIDATION_REPORT_FILE), validationReport, StandardCharsets.UTF_8);
            Files.writeString(folder.resolve(IMPORT_LOG_FILE), importLog, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write snapshot " + folder.toAbsolutePath(), e);
        }
        return folder;
    }

    /** Points {@code outputDir/ACTIVE} at {@code version} (written to a temp file first, then moved). */
    public void activate(Path outputDir, String version) {
        Path active = outputDir.resolve(SnapshotReader.ACTIVE_FILE);
        try {
            Path temp = Files.createTempFile(outputDir, "ACTIVE", ".tmp");
            Files.writeString(temp, version + "\n", StandardCharsets.UTF_8);
            Files.move(temp, active, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot update " + active.toAbsolutePath(), e);
        }
    }

    /**
     * The districts as a compact GeoJSON FeatureCollection with {@code planningAreaCode} / {@code planningAreaName}
     * properties. Boundaries are simplified with JTS (topology kept, so areas stay valid); the smallest tolerance
     * whose output fits in {@code maxBytes} is used, else the largest one.
     */
    public String districtsGeoJson(List<District> districts, int maxBytes) {
        String json = null;
        for (double tolerance : SIMPLIFY_TOLERANCES_DEG) {
            json = featureCollection(districts, tolerance);
            if (json.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
                return json;
            }
        }
        return json;
    }

    private static String featureCollection(List<District> districts, double tolerance) {
        GeoJsonReader reader = new GeoJsonReader();
        GeoJsonWriter writer = new GeoJsonWriter(COORDINATE_DECIMALS);
        writer.setEncodeCRS(false);
        ObjectNode root = COMPACT.createObjectNode();
        root.put("type", "FeatureCollection");
        ArrayNode features = root.putArray("features");
        for (District district : districts) {
            ObjectNode feature = features.addObject();
            feature.put("type", "Feature");
            ObjectNode properties = feature.putObject("properties");
            properties.put("planningAreaCode", district.getPlanningAreaCode());
            properties.put("planningAreaName", district.getPlanningAreaName());
            if (district.getBoundaryGeoJson() == null) {
                feature.putNull("geometry");
                continue;
            }
            try {
                Geometry geometry = reader.read(district.getBoundaryGeoJson());
                Geometry simple = TopologyPreservingSimplifier.simplify(geometry, tolerance);
                feature.set("geometry", COMPACT.readTree(writer.write(simple)));
            } catch (ParseException e) {
                throw new IllegalStateException("District " + district.getPlanningAreaCode()
                        + " has an invalid boundary: " + e.getMessage(), e);
            }
        }
        return COMPACT.writeValueAsString(root);
    }

    /** The {@code validation-report.md} text: status, counts, every error and warning. */
    public static String validationReportMarkdown(String version, ValidationReport report, Map<String, Integer> counts) {
        StringBuilder md = new StringBuilder("# Validation report — ").append(version).append("\n\n");
        md.append("- Status: **").append(report.getStatus()).append("**\n");
        md.append("- Errors: ").append(report.getErrors().size()).append("\n");
        md.append("- Warnings: ").append(report.getWarnings().size()).append("\n");
        counts.forEach((key, value) -> md.append("- ").append(key).append(": ").append(value).append("\n"));
        md.append("\n## Errors\n\n");
        appendList(md, report.getErrors());
        md.append("\n## Warnings\n\n");
        appendList(md, report.getWarnings());
        return md.toString();
    }

    private static void appendList(StringBuilder md, List<String> items) {
        if (items.isEmpty()) {
            md.append("None.\n");
        }
        items.forEach(item -> md.append("- ").append(item).append("\n"));
    }
}

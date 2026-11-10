package sg.schoolmatch.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Lab 4, section 3.2.4: records the input, expected output and actual output of each designed test case in
 * {@code target/test-results/<file>.csv}, so that docs/testing/test-results.md is built from a real run and never
 * copied from the expected column. Call it with the actual value before asserting, so a failing case is recorded
 * too. A file is emptied the first time this JVM writes to it.
 */
public final class TestResultLog {

    private static final Path DIR = Path.of("target", "test-results");
    private static final String HEADER = "tc_id,input,expected,actual\n";
    private static final Set<String> STARTED = new HashSet<>();

    private TestResultLog() {
    }

    public static synchronized void record(String file, String tcId, String input, String expected, String actual) {
        Path path = DIR.resolve(file + ".csv");
        try {
            Files.createDirectories(DIR);
            if (STARTED.add(file)) {
                Files.writeString(path, HEADER, StandardCharsets.UTF_8);
            }
            Files.writeString(path, String.join(",", csv(tcId), csv(input), csv(expected), csv(actual)) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String csv(String value) {
        String text = value == null ? "null" : value.replace("\r", " ").replace("\n", " ");
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}

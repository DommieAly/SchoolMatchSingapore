package sg.schoolmatch.control;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test helper: reads the account test-case tables {@code testcases/TC-REGISTER.csv} and {@code TC-LOGIN.csv}.
 * <p>
 * The {@code input} column lists only the fields a row changes, e.g. {@code username=ab;email=alice}; every other
 * field keeps its valid default. {@code {a*30}} means the letter a repeated 30 times. The {@code expected} column
 * is {@code ok} / {@code session}, or {@code error:} followed by the fields that must have an error, e.g.
 * {@code error:username|email}.
 */
public final class AccountTestCases {

    /** Valid registration values used when a row does not set the field. */
    public static final Map<String, String> VALID_REGISTRATION = Map.of(
            "username", "alice_01",
            "email", "alice@example.com",
            "password", "Passw0rd");

    private static final Pattern REPEAT = Pattern.compile("\\{(.)\\*(\\d+)}");

    private AccountTestCases() {
    }

    /** One row of a TC-*.csv table. */
    public record Row(String tcId, String requirement, String technique, String input, String expected) {

        /** The fields this row sets, with {@code {c*n}} expanded. Values are not trimmed. */
        public Map<String, String> fields() {
            Map<String, String> fields = new LinkedHashMap<>();
            if (input.isEmpty()) {
                return fields;
            }
            for (String pair : input.split(";", -1)) {
                int eq = pair.indexOf('=');
                fields.put(pair.substring(0, eq), expand(pair.substring(eq + 1)));
            }
            return fields;
        }

        /** The registration form: the valid defaults with this row's fields on top; confirm = password unless set. */
        public Map<String, String> registration() {
            Map<String, String> form = new LinkedHashMap<>(VALID_REGISTRATION);
            form.putAll(fields());
            form.putIfAbsent("confirm", form.get("password"));
            return form;
        }

        public boolean expectsError() {
            return expected.startsWith("error:");
        }

        /** The fields named after {@code error:}, in order. Empty when the row expects success. */
        public Set<String> errorFields() {
            return expectsError() ? new LinkedHashSet<>(List.of(expected.substring(6).split("\\|"))) : Set.of();
        }

        @Override
        public String toString() {
            return tcId + ": " + technique;
        }
    }

    /** The rows of {@code /testcases/<file>} that pass {@code filter}. */
    public static List<Row> rows(String file, Predicate<Row> filter) {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = AccountTestCases.class.getResourceAsStream("/testcases/" + file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8))) {
            reader.readLine();   // header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split(",", -1);   // the tables use no commas or quotes inside a column
                Row row = new Row(cols[0], cols[1], cols[2], cols[3], cols[4]);
                if (filter.test(row)) {
                    rows.add(row);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    /** "Aa1{a*3}" → "Aa1aaa". */
    static String expand(String value) {
        Matcher m = REPEAT.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1).repeat(Integer.parseInt(m.group(2)))));
        }
        m.appendTail(out);
        return out.toString();
    }
}

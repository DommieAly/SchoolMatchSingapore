"""Build docs/testing/test-results.md from a real test run (Lab 4, section 3.2.4).

Run it from anywhere after the tests, with Python 3 and the standard library only:

    python3 -I docs/testing/make_results.py [--command "<the Maven command you ran>"]

It reads three sources and never copies one into another:

- the test case tables of the three design documents in docs/testing (test case ids, requirement ids, JUnit method);
- target/test-results/<document>.csv, written by TestResultLog during the run (input, expected, actual);
- target/surefire-reports/TEST-*.xml, written by Maven Surefire (pass or fail of each JUnit test).

Surefire names a test by its JUnit method, not by its display name. The script therefore reads the @DisplayName of
each method from the test source, and for a @ParameterizedTest with @CsvFileSource it takes the first column of the
n-th data row as the id of invocation [n]. The id of a test is the id its display name starts with.

The script stops with exit code 1, and writes nothing, when an id is missing on any side, appears twice, or when the
sources disagree (a JUnit method that differs from the document, a recorded row that matches its expected output but
failed in JUnit, and the reverse).
"""

import argparse
import csv
import datetime
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TESTING = ROOT / "docs" / "testing"
OUT = TESTING / "test-results.md"
RESULTS_DIR = ROOT / "target" / "test-results"
SUREFIRE_DIR = ROOT / "target" / "surefire-reports"
TEST_JAVA = ROOT / "src" / "test" / "java"
TEST_RESOURCES = ROOT / "src" / "test" / "resources"
PACKAGE = "sg.schoolmatch.control"

DEFAULT_COMMAND = "JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B -o clean verify"

# (document, JUnit class, Lab 4 section). The results file has the same name as the document.
DOCUMENTS = [
    ("black-box-recommendation.md", "RecommendationControllerBlackBoxTest", "3.2.1"),
    ("basis-path-recommend.md", "RecommendationControllerBasisPathTest", "3.2.2"),
    ("basis-path-login.md", "AuthControllerBasisPathTest", "3.2.2"),
]

ID = re.compile(r"TC-(?:BB|BP)-[A-Z]+-\d+")
ID_AT_START = re.compile(r"^(TC-(?:BB|BP)-[A-Z]+-\d+)(?![\d.])")
WILDCARD = re.compile(r"=\\\*(?=,|\\})")  # "=*" as a whole message, in an re.escape()d expected line
REPORT_WINDOW_S = 600  # a results CSV and its surefire report must be written within 10 minutes of each other

problems = []


def problem(text):
    problems.append(text)


def ids_key(tc_id):
    prefix, number = tc_id.rsplit("-", 1)
    return prefix, int(number)


# ---------------------------------------------------------------- design documents


def split_row(line):
    """Cells of one Markdown table row; an escaped pipe (\\|) stays inside its cell."""
    body = line.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|") and not body.endswith("\\|"):
        body = body[:-1]
    return [cell.strip().replace("\\|", "|") for cell in re.split(r"(?<!\\)\|", body)]


def plain(cell):
    return cell.replace("`", "").strip()


def read_document(name):
    """Test case rows of every table that has a 'Requirement' column, in document order."""
    path = TESTING / name
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    title = next((l[2:].strip() for l in lines if l.startswith("# ")), name)
    rows = {}
    i = 0
    while i < len(lines):
        if not lines[i].lstrip().startswith("|"):
            i += 1
            continue
        block = []
        while i < len(lines) and lines[i].lstrip().startswith("|"):
            block.append(lines[i])
            i += 1
        header = [plain(c).lower() for c in split_row(block[0])]
        req_col = next((k for k, h in enumerate(header) if h.startswith("requirement")), None)
        method_col = next((k for k, h in enumerate(header) if h.startswith("junit method")), None)
        if req_col is None:
            continue
        for line in block[2:]:
            cells = split_row(line)
            match = ID_AT_START.match(plain(cells[0]))
            if not match:
                continue
            tc_id = match.group(1)
            if tc_id in rows:
                problem(f"{name}: {tc_id} is in more than one test case row")
                continue
            requirement = ", ".join(r.strip() for r in re.split(r"[;,]", plain(cells[req_col])) if r.strip())
            method = plain(cells[method_col]) if method_col is not None and method_col < len(cells) else None
            rows[tc_id] = {"requirement": requirement, "method": method}
    if not rows:
        problem(f"{name}: no test case table with a 'Requirement' column found")
    # A document may name the cases of another document (another id prefix); its own ids must all have a row.
    own = {ids_key(tc_id)[0] for tc_id in rows}
    for mentioned in sorted(set(ID.findall(text)) - set(rows), key=ids_key):
        if ids_key(mentioned)[0] in own:
            problem(f"{name}: {mentioned} is mentioned but has no test case row")
    return title, rows


# ---------------------------------------------------------------- results CSV written by TestResultLog


def read_results(stem):
    path = RESULTS_DIR / f"{stem}.csv"
    if not path.exists():
        problem(f"{path.relative_to(ROOT)} is missing: run the tests first")
        return {}, None
    rows = {}
    with path.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            if row["tc_id"] in rows:
                problem(f"{path.name}: {row['tc_id']} was recorded twice")
            rows[row["tc_id"]] = row
    return rows, path.stat().st_mtime


# ---------------------------------------------------------------- JUnit source: method -> display name


def java_string(literal):
    return literal.replace('\\"', '"').replace("\\\\", "\\")


def read_test_source(junit_class):
    """{method: {'display': str, 'tags': [...], 'invocations': [ids] or None}} for @Test and @ParameterizedTest."""
    path = TEST_JAVA / Path(*PACKAGE.split(".")) / f"{junit_class}.java"
    methods = {}
    pending = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith(("*", "/*", "//")):
            continue
        if line.startswith("@Test"):
            pending["test"] = True
        elif line.startswith("@ParameterizedTest"):
            pending["test"] = True
            name = re.search(r'name\s*=\s*"((?:[^"\\]|\\.)*)"', line)
            pending["pattern"] = java_string(name.group(1)) if name else "[{index}] {argumentsWithNames}"
        elif line.startswith("@CsvFileSource"):
            resource = re.search(r'resources\s*=\s*"([^"]+)"', line)
            skip = re.search(r"numLinesToSkip\s*=\s*(\d+)", line)
            pending["csv"] = (resource.group(1) if resource else None, int(skip.group(1)) if skip else 0)
        elif line.startswith("@DisplayName"):
            display = re.search(r'@DisplayName\("((?:[^"\\]|\\.)*)"\)', line)
            pending["display"] = java_string(display.group(1)) if display else None
        elif line.startswith("@Tag"):
            tag = re.search(r'@Tag\("([^"]+)"\)', line)
            if tag:
                pending.setdefault("tags", []).append(tag.group(1))
        declaration = re.search(r"\bvoid\s+(\w+)\s*\(", line)
        if declaration and not line.startswith("@"):
            if pending.get("test"):
                methods[declaration.group(1)] = pending
            pending = {}
    for method, info in methods.items():
        info["invocations"] = None
        if "pattern" in info:
            if not info["pattern"].startswith("{0}"):
                problem(f"{junit_class}.{method}: the invocation name '{info['pattern']}' does not start with "
                        "{0}, so its display name does not start with the test case id")
                continue
            resource, skip = info.get("csv", (None, 0))
            if not resource:
                problem(f"{junit_class}.{method}: a parameterized test without @CsvFileSource is not supported")
                continue
            csv_path = TEST_RESOURCES / resource.lstrip("/")
            with csv_path.open(encoding="utf-8", newline="") as f:
                data = list(csv.reader(f))[skip:]
            data = [r for r in data if r and r[0].strip() and not r[0].lstrip().startswith("#")]
            info["invocations"] = [r[0].strip() for r in data]
            info["csv_rows"] = {r[0].strip(): r for r in data}
            info["csv_path"] = csv_path
        elif not info.get("display"):
            problem(f"{junit_class}.{method}: no single-line @DisplayName found")
    return methods


# ---------------------------------------------------------------- surefire reports


def surefire_name(name):
    match = re.match(r"^(\w+)(?:\(.*\))?(?:\[(\d+)\])?$", name)
    if not match:
        return name, None
    return match.group(1), int(match.group(2)) if match.group(2) else None


def read_surefire(junit_class, methods):
    path = SUREFIRE_DIR / f"TEST-{PACKAGE}.{junit_class}.xml"
    if not path.exists():
        problem(f"{path.relative_to(ROOT)} is missing: run the tests first")
        return {}, None, None
    suite = ET.parse(path).getroot()
    props = {p.get("name"): p.get("value") for p in suite.iter("property")}
    results = {}
    for case in suite.iter("testcase"):
        method, index = surefire_name(case.get("name"))
        info = methods.get(method)
        if info is None:
            problem(f"{path.name}: test {case.get('name')} has no @Test or @ParameterizedTest method in the source")
            continue
        if index is not None:
            invocations = info.get("invocations") or []
            if not 1 <= index <= len(invocations):
                problem(f"{path.name}: {case.get('name')} has no data row {index} in its CSV source")
                continue
            tc_id = invocations[index - 1]
            display = info["pattern"].replace("{0}", tc_id, 1)
        else:
            display = info.get("display") or ""
        match = ID_AT_START.match(display)
        if not match:
            problem(f"{path.name}: the display name of {case.get('name')} does not start with a test case id")
            continue
        tc_id = match.group(1)
        if tc_id in results:
            problem(f"{path.name}: more than one test has a display name that starts with {tc_id}")
            continue
        failed = case.find("failure") if case.find("failure") is not None else case.find("error")
        if failed is not None:
            status = "Fail"
            message = (failed.get("message") or failed.get("type") or "").strip().splitlines()
            message = message[0].strip() if message else ""
        elif case.find("skipped") is not None:
            status, message = "Skipped", ""
        else:
            status, message = "Pass", ""
        results[tc_id] = {"status": status, "message": message, "method": method, "tags": info.get("tags", [])}
    jdk = " ".join(filter(None, [props.get("java.runtime.name"), props.get("java.runtime.version")]))
    if props.get("java.vendor"):
        jdk += f" ({props['java.vendor']})"
    return results, jdk, path.stat().st_mtime


def whole_run():
    reports = sorted(SUREFIRE_DIR.glob("TEST-*.xml"))
    totals = {"classes": len(reports), "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in reports:
        suite = ET.parse(path).getroot()
        for key in ("tests", "failures", "errors", "skipped"):
            totals[key] += int(suite.get(key, "0"))
    times = [p.stat().st_mtime for p in reports]
    return totals, (min(times), max(times)) if times else (None, None)


# ---------------------------------------------------------------- git (read only)


def git(*args):
    done = subprocess.run(["git", "--no-optional-locks", *args], cwd=ROOT, capture_output=True, text=True)
    if done.returncode != 0:
        problem(f"git {' '.join(args)} failed: {done.stderr.strip()}")
        return ""
    return done.stdout


# ---------------------------------------------------------------- output


def matches(expected, actual):
    """True when the actual line equals the expected line; '=*' in the expected line is any non-blank message."""
    pattern = WILDCARD.sub(lambda _: r"=[^{}]*[^\s{}][^{}]*", re.escape(expected))
    return re.fullmatch(pattern, actual) is not None


def code(text):
    # Line breaks become one space; runs of spaces stay, so an input of three spaces still shows three.
    text = re.sub(r"\s*[\r\n]+\s*", " ", text).strip().replace("|", "\\|")
    return f"`{text}`" if text else ""


def plural(n, word):
    return f"{n} {word}" if n == 1 else f"{n} {word}s"


def local(ts):
    return datetime.datetime.fromtimestamp(ts)


def day(ts):
    when = local(ts)
    return f"{when.day} {when.strftime('%b %Y')}"


def main():
    parser = argparse.ArgumentParser(description="Build docs/testing/test-results.md from the last test run.")
    parser.add_argument("--command", default=DEFAULT_COMMAND,
                        help="the Maven command that produced target/ (printed in the header)")
    args = parser.parse_args()

    sections = []
    jdks = set()
    report_times = []
    for doc, junit_class, lab_section in DOCUMENTS:
        title, designed = read_document(doc)
        recorded, csv_time = read_results(Path(doc).stem)
        methods = read_test_source(junit_class)
        verdicts, jdk, xml_time = read_surefire(junit_class, methods)
        if jdk:
            jdks.add(jdk)
        if xml_time:
            report_times.append(xml_time)
        if csv_time and xml_time and abs(xml_time - csv_time) > REPORT_WINDOW_S:
            problem(f"{Path(doc).stem}.csv and the surefire report of {junit_class} were written "
                    f"{abs(xml_time - csv_time) / 60:.0f} min apart: they come from different runs")

        for side, ids in (("the results CSV", recorded), ("the surefire report", verdicts)):
            for tc_id in sorted(set(designed) - set(ids), key=ids_key):
                problem(f"{doc}: {tc_id} is designed but missing from {side}")
            for tc_id in sorted(set(ids) - set(designed), key=ids_key):
                problem(f"{doc}: {tc_id} is in {side} but has no test case row in the document")

        rows = []
        for tc_id in sorted(designed, key=ids_key):
            if tc_id not in recorded or tc_id not in verdicts:
                continue
            design, record, verdict = designed[tc_id], recorded[tc_id], verdicts[tc_id]
            if design["method"] and design["method"] != verdict["method"]:
                problem(f"{doc}: {tc_id} names JUnit method {design['method']}, "
                        f"but the test that runs it is {verdict['method']}")
            doc_reqs = set(design["requirement"].split(", "))
            for tag in verdict["tags"]:
                if tag not in doc_reqs:
                    problem(f"{doc}: {tc_id} has @Tag {tag}, which its test case row does not list")
            info = methods.get(verdict["method"], {})
            if tc_id in info.get("csv_rows", {}):
                csv_reqs = ", ".join(r.strip() for r in re.split(r"[;,]", info["csv_rows"][tc_id][1]) if r.strip())
                if csv_reqs != design["requirement"]:
                    problem(f"{doc}: {tc_id} lists '{design['requirement']}', "
                            f"but {info['csv_path'].name} lists '{csv_reqs}'")
            same = matches(record["expected"], record["actual"])
            if verdict["status"] == "Pass" and not same:
                problem(f"{tc_id}: JUnit passed, but the recorded actual output does not match the expected output")
            if verdict["status"] == "Fail" and same:
                problem(f"{tc_id}: JUnit failed, but the recorded actual output matches the expected output")
            rows.append((tc_id, design["requirement"], record, verdict))
        sections.append((doc, junit_class, lab_section, title, rows))

    if len(jdks) > 1:
        problem(f"the three test classes ran on different JDKs: {sorted(jdks)}")
    totals, _ = whole_run()
    branch = git("rev-parse", "--abbrev-ref", "HEAD").strip()
    head = git("rev-parse", "--short", "HEAD").strip()
    status = [line[3:] for line in git("status", "--porcelain").splitlines() if line.strip()]

    if problems:
        print("make_results.py: test-results.md was NOT written:", file=sys.stderr)
        for text in problems:
            print("  - " + text, file=sys.stderr)
        sys.exit(1)

    all_rows = [r for s in sections for r in s[4]]
    passed = sum(1 for r in all_rows if r[3]["status"] == "Pass")
    failed = [r for r in all_rows if r[3]["status"] == "Fail"]
    skipped = sum(1 for r in all_rows if r[3]["status"] == "Skipped")
    run_day = day(max(report_times))

    out = []
    out.append("# Test results (Lab 4, section 3.2.4)")
    out.append("")
    out.append("| Item | Value |")
    out.append("|:--:|:--:|")
    out.append(f"| Date of the run | {run_day} |")
    out.append("| Author | Lingyi Liu |")
    out.append(f"| Code version | branch `{branch}`, based on commit `{head}`"
               + (", plus the Lab 4 changes" if status else "") + " |")
    out.append(f"| Command | `{args.command}`, run from the project root |")
    out.append(f"| JDK | {', '.join(sorted(jdks))} |")
    out.append(f"| Lab 4 test cases | {len(all_rows)} run: {passed} passed, {len(failed)} failed"
               + (f", {skipped} skipped" if skipped else "") + " |")
    out.append(f"| Whole run | {totals['tests']} JUnit tests in {totals['classes']} test classes: "
               f"{plural(totals['failures'], 'failure')}, {plural(totals['errors'], 'error')}, "
               f"{totals['skipped']} skipped |")
    out.append("")
    out.append("The tables below come straight from the test run.")
    out.append("")
    out.append("Where each column comes from:")
    out.append("")
    out.append("- **Test case** and **Requirement**: the test case tables of the design documents.")
    out.append("- **Test input**, **Expected output** and **Actual output**: what each test recorded with "
               "`TestResultLog` in `target/test-results/`. In an expected output, `=*` means any message that is "
               "not blank.")
    out.append("- **Result**: the Surefire report in `target/surefire-reports/` of the JUnit test whose display "
               "name starts with the test case id.")
    out.append("")
    out.append("How the outputs are written:")
    out.append("")
    out.append("- **Table 1** (black box): `6 results | #1 fit-u13 total 0.786, 15 min: FIT 1.0 MATCH; COMMUTE 0.5 "
               "| ...` gives the number of results, then for each result its rank, school code, total score, travel "
               "time (`commute n/a` when it is not known) and the score of each factor. `FIT` is PSLE fit with its "
               "label, `PROG` is programme, and `FIT not used` is the DC-74 reason. "
               "`black-box-recommendation.md` Table 5 has the full key.")
    out.append("- **Table 2** (basis paths of `recommend`): `#1 alpha MATCH 1.0, 15 min, total 0.786` is rank, "
               "school, PSLE fit label and score, travel time and total; ` / ` separates results. The calls that "
               "follow show the path, for example `getSchools 1x` (called once). `basis-path-recommend.md` "
               "section 6.3 has the full key.")
    out.append("- **Table 3** (basis paths of `login`): the result, then `lookups` (account lookups), `checks` "
               "(password and status checks) and `saved` (sessions saved). `basis-path-login.md` section 7 has "
               "the full key.")
    out.append("")

    table_no = 0
    for doc, junit_class, lab_section, title, rows in sections:
        table_no += 1
        ok = sum(1 for r in rows if r[3]["status"] == "Pass")
        out.append(f"## {table_no}. {title}")
        out.append("")
        out.append(f"Lab 4 section {lab_section}. Design: [`{doc}`]({doc}). JUnit class: `{junit_class}`. "
                   f"{len(rows)} test cases: {ok} passed, {len(rows) - ok} failed.")
        out.append("")
        out.append(f"**Table {table_no}. Results of `{junit_class}`**")
        out.append("")
        out.append("| Test case | Requirement | Test input | Expected output | Actual output | Result |")
        out.append("|:--:|:--:|:--:|:--:|:--:|:--:|")
        for tc_id, requirement, record, verdict in rows:
            result = verdict["status"] if verdict["status"] != "Fail" else "**Fail**"
            out.append(f"| {tc_id} | {requirement} | {code(record['input'])} | {code(record['expected'])} | "
                       f"{code(record['actual'])} | {result} |")
        out.append("")

    out.append("## Failed test cases")
    out.append("")
    if failed:
        out.append("Section 3 of [`README.md`](README.md) explains each failed case.")
        out.append("")
        out.append(f"**Table {table_no + 1}. Failed test cases and their Surefire messages**")
        out.append("")
        out.append("| Test case | JUnit method | Surefire message |")
        out.append("|:--:|:--:|:--:|")
        for tc_id, _, _, verdict in failed:
            out.append(f"| {tc_id} | `{verdict['method']}` | {code(verdict['message'])} |")
    else:
        out.append("None in this run. TC-BB-REC-26 failed in the first run on 6 Nov 2026 because of a bug in the code. "
                   "We fixed the bug; section 3 of [`README.md`](README.md) explains it.")
    out.append("")

    ids = [r[0] for r in all_rows]
    twice = sorted({i for i in ids if ids.count(i) > 1}, key=ids_key)
    if twice:
        print(f"make_results.py: test-results.md was NOT written: ids in more than one document: {twice}",
              file=sys.stderr)
        sys.exit(1)
    OUT.write_text("\n".join(out), encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(all_rows)} test cases, {passed} passed, {len(failed)} failed; "
          f"whole run {totals['tests']} tests, {plural(totals['failures'], 'failure')}, "
          f"{plural(totals['errors'], 'error')}")


if __name__ == "__main__":
    main()

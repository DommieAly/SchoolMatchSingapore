#!/usr/bin/env python3
"""
build_seed.py - builds the small SEED snapshot of SchoolMatch SG. For the seed only.

The real importer is Java (SchoolDataController.importDataset(), import profile, Sprint 1, owner B).
This script exists so the app can start and the tests can run on day one.

What it fetches (no API keys needed):
  1. data.gov.sg d_688b934f82c1059ed0a6993d2a829089  General information of schools -> the 10 seed schools
  2. data.gov.sg d_9aba12b5527843afb0b2e8e4ed6ac6bd  CCAs          -> School.ccas       (joined by upper-case name)
  3. data.gov.sg d_f1d144e423570c9d84dbc5102c2e664d  Subjects      -> School.programmes (joined by upper-case name)
  4. data.gov.sg d_4765db0e87b9c86336792efe8a1f7a66  Master Plan 2019 planning-area boundaries (GeoJSON)
  5. OneMap elastic search by postal code (<= 1 request/second) -> coordinates
     (the hit whose BUILDING matches the school name)

Then it computes each school's planning area (ray casting), keeps and simplifies the 3 planning areas,
adds TEST PSLE ranges (not MOE data!) and writes:
  data/snapshots/0000-seed/{manifest.json, schools.json, districts.geojson}
  data/snapshots/ACTIVE                               (only if missing, or with --activate)
  src/test/resources/fixtures/snapshot-mini/          (identical copy of 0000-seed)
  src/test/resources/fixtures/snapshot-broken/<rule>/ (copy of snapshot-mini with exactly one defect)
  src/main/resources/stub/onemap/*.json               (recorded OneMap responses for StubOneMap)

Every network call is retried once. If a source is still unreachable the script stops:
it never invents school details or coordinates.

Usage (from the repository root, Python 3.9+, standard library only):
  python3 data/tools/build_seed.py [--date 2026-09-30] [--activate] [--cache DIR]
"""
import argparse
import copy
import json
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

DATAGOV_SEARCH = "https://data.gov.sg/api/action/datastore_search"
DATAGOV_POLL = "https://api-open.data.gov.sg/v1/public/api/datasets/{}/poll-download"
ONEMAP_SEARCH = "https://www.onemap.gov.sg/api/common/elastic/search"

DS_SCHOOLS = "d_688b934f82c1059ed0a6993d2a829089"
DS_CCAS = "d_9aba12b5527843afb0b2e8e4ed6ac6bd"
DS_SUBJECTS = "d_f1d144e423570c9d84dbc5102c2e664d"
DS_AREAS = "d_4765db0e87b9c86336792efe8a1f7a66"

# The 10 seed schools, names exactly as data.gov.sg publishes them, in exactly 3 planning areas.
# ST. HILDA'S has an apostrophe and a full stop, to exercise search.
SEED_SCHOOLS = {
    "BISHAN": ["CATHOLIC HIGH SCHOOL", "GUANGYANG SECONDARY SCHOOL",
               "KUO CHUAN PRESBYTERIAN SECONDARY SCHOOL", "PEIRCE SECONDARY SCHOOL"],
    "TAMPINES": ["NGEE ANN SECONDARY SCHOOL", "ST. HILDA'S SECONDARY SCHOOL", "TAMPINES SECONDARY SCHOOL"],
    "JURONG WEST": ["HUA YI SECONDARY SCHOOL", "JURONG WEST SECONDARY SCHOOL", "WESTWOOD SECONDARY SCHOOL"],
}
NO_RANGES_SCHOOL = "westwood-secondary-school"      # no PSLE ranges -> page shows "Not available"
NULL_EMAIL_SCHOOL = "jurong-west-secondary-school"  # email removed on purpose -> "Not available"
AFFILIATED_RANGE_SCHOOL = "kuo-chuan-presbyterian-secondary-school"
OLDER_YEAR_SCHOOL = "catholic-high-school"          # also gets 2024 ranges, so "latest year" is testable

# Recorded OneMap searches for StubOneMap: single hit, two hits, a full page of hits.
STUB_ONEMAP_SEARCHES = ["579767", "catholic high school", "bishan"]

VERSION = "0000-seed"
SIMPLIFY_TOLERANCE_DEG = 0.0001   # about 11 m
MAX_DISTRICTS_BYTES = 300_000
BROKEN_RULES = ["duplicate-code", "bad-coordinate", "bad-psle-range", "missing-name", "unknown-planning-area"]

_last_call = {}   # host -> time of last request (rate limiting)


# ---------------------------------------------------------------- network

def fetch_json(url, host_key, min_interval):
    """GET url as JSON; waits min_interval seconds between calls to the same host; retries once."""
    for attempt in (1, 2):
        wait = _last_call.get(host_key, 0) + min_interval - time.time()
        if wait > 0:
            time.sleep(wait)
        _last_call[host_key] = time.time()
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "SchoolMatchSG-seed-builder/1.0"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.load(resp)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            if attempt == 2:
                sys.exit(f"STOP: {host_key} unreachable after one retry ({e}). URL: {url}\n"
                         "No data was written. Do not invent school details or coordinates.")
            print(f"  retrying after error: {e}", file=sys.stderr)
            time.sleep(15 if getattr(e, "code", None) == 429 else 5)   # 429 = rate limit (no API key)


def datagov_search(resource_id, filters=None):
    params = {"resource_id": resource_id, "limit": 1000}
    if filters:
        params["filters"] = json.dumps(filters)
    data = fetch_json(DATAGOV_SEARCH + "?" + urllib.parse.urlencode(params), "data.gov.sg", 3.0)
    if not data.get("success"):
        sys.exit(f"STOP: data.gov.sg returned an error for {resource_id}: {data}")
    result = data["result"]
    if result.get("total", 0) > len(result["records"]):
        sys.exit(f"STOP: {resource_id} has more than 1000 matching rows; add paging.")
    return result["records"]


def onemap_search(text):
    params = {"searchVal": text, "returnGeom": "Y", "getAddrDetails": "Y", "pageNum": 1}
    return fetch_json(ONEMAP_SEARCH + "?" + urllib.parse.urlencode(params), "onemap", 1.1)


# ---------------------------------------------------------------- helpers

def clean(value):
    """Published value, trimmed with single spaces; None for missing / 'na'."""
    if value is None:
        return None
    value = re.sub(r"\s+", " ", str(value)).strip()
    return None if value == "" or value.lower() in ("na", "n/a", "nil", "-") else value


def norm_name(name):
    return re.sub(r"\s+", " ", (name or "").upper().replace("’", "'")).strip()


def match_key(name):
    """Name for matching OneMap BUILDING values: OneMap writes "SAINT" where data.gov.sg writes "ST."."""
    return re.sub(r"\bST\.?\s", "SAINT ", norm_name(name))


def slug(name):
    """schoolCode = kebab-case slug of the name: lower-case, punctuation dropped, spaces -> '-'."""
    s = re.sub(r"[^a-z0-9\s-]", "", name.lower())
    return re.sub(r"-+", "-", re.sub(r"\s+", "-", s.strip())).strip("-")


def stub_key(text):
    """File name of a recorded OneMap search; same rule as StubOneMap.key() in Java."""
    return re.sub(r"[^a-z0-9]+", "-", text.strip().lower()).strip("-")


def point_in_ring(lng, lat, ring):
    inside = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i][0], ring[i][1]
        xj, yj = ring[j][0], ring[j][1]
        if (yi > lat) != (yj > lat) and lng < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def polygons_of(geometry):
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiPolygon":
        return geometry["coordinates"]
    raise ValueError("unsupported geometry " + geometry["type"])


def point_in_geometry(lng, lat, geometry):
    for rings in polygons_of(geometry):
        if point_in_ring(lng, lat, rings[0]) and not any(point_in_ring(lng, lat, h) for h in rings[1:]):
            return True
    return False


def _perp_dist(p, a, b):
    (x, y), (x1, y1), (x2, y2) = p, a, b
    dx, dy = x2 - x1, y2 - y1
    if dx == 0 and dy == 0:
        return ((x - x1) ** 2 + (y - y1) ** 2) ** 0.5
    t = max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)))
    return ((x - (x1 + t * dx)) ** 2 + (y - (y1 + t * dy)) ** 2) ** 0.5


def douglas_peucker(points, tol):
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        s, e = stack.pop()
        best, idx = 0.0, None
        for i in range(s + 1, e):
            d = _perp_dist(points[i], points[s], points[e])
            if d > best:
                best, idx = d, i
        if idx is not None and best > tol:
            keep[idx] = True
            stack += [(s, idx), (idx, e)]
    return [p for p, k in zip(points, keep) if k]


def simplify_ring(ring, tol):
    out = douglas_peucker([[round(x, 6), round(y, 6)] for x, y, *_ in ring], tol)
    if len(out) < 4:   # a closed ring needs 4 points; keep the original if simplification collapsed it
        out = [[round(x, 6), round(y, 6)] for x, y, *_ in ring]
    if out[0] != out[-1]:
        out.append(out[0])
    return out


def simplify_geometry(geometry, tol):
    polys = [[simplify_ring(r, tol) for r in rings] for rings in polygons_of(geometry)]
    if geometry["type"] == "Polygon":
        return {"type": "Polygon", "coordinates": polys[0]}
    return {"type": "MultiPolygon", "coordinates": polys}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def test_ranges(index, code):
    """TEST VALUES ONLY (not MOE data): deterministic PG3/PG2/PG1 non-affiliated ranges for 2025."""
    if code == NO_RANGES_SCHOOL:
        return []
    base = 6 + 2 * index
    ranges = []
    if code == OLDER_YEAR_SCHOOL:
        ranges.append({"admissionYear": 2024, "postingGroup": 3, "affiliated": False,
                       "lowerScore": base + 1, "upperScore": base + 4})
    ranges += [
        {"admissionYear": 2025, "postingGroup": 3, "affiliated": False, "lowerScore": base, "upperScore": base + 3},
        {"admissionYear": 2025, "postingGroup": 2, "affiliated": False, "lowerScore": base + 3, "upperScore": base + 6},
        {"admissionYear": 2025, "postingGroup": 1, "affiliated": False, "lowerScore": base + 6, "upperScore": base + 9},
    ]
    if code == AFFILIATED_RANGE_SCHOOL:
        ranges.append({"admissionYear": 2025, "postingGroup": 3, "affiliated": True,
                       "lowerScore": base, "upperScore": base + 5})
    return ranges


# ---------------------------------------------------------------- main steps

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--date", default=datetime.now(timezone.utc).date().isoformat(), help="effectiveDate")
    ap.add_argument("--activate", action="store_true", help="overwrite data/snapshots/ACTIVE with 0000-seed")
    ap.add_argument("--cache", help="folder to save the raw downloads in (optional, e.g. data/raw/seed)")
    args = ap.parse_args()

    root = Path.cwd()
    if not (root / "pom.xml").exists():
        sys.exit("Run this from the repository root (the folder with pom.xml).")
    cache = Path(args.cache) if args.cache else None
    downloaded = {}

    def now_iso():
        return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    # 1. General information of schools
    print("data.gov.sg: general information of schools")
    wanted = {name: area for area, names in SEED_SCHOOLS.items() for name in names}
    rows = datagov_search(DS_SCHOOLS)
    downloaded[DS_SCHOOLS] = now_iso()
    by_name = {norm_name(r["school_name"]): r for r in rows}
    missing = [n for n in wanted if n not in by_name]
    if missing:
        sys.exit(f"STOP: not found in {DS_SCHOOLS}: {missing}. Edit SEED_SCHOOLS.")
    if cache:
        write_json(cache / "schools.json", rows)

    # 2. CCAs and subjects (one filtered query per school)
    ccas, subjects = {}, {}
    for name, row in ((n, by_name[n]) for n in wanted):
        print("data.gov.sg: CCAs and subjects of", name)
        cca_rows = datagov_search(DS_CCAS, {"School_name": row["school_name"]})
        ccas[name] = sorted({clean(r["cca_grouping_desc"]) for r in cca_rows
                             if clean(r.get("cca_grouping_desc")) and r.get("school_section") != "PRIMARY"})
        subject_rows = datagov_search(DS_SUBJECTS, {"School_Name": row["school_name"]})
        subjects[name] = sorted({clean(r["Subject_Desc"]) for r in subject_rows if clean(r.get("Subject_Desc"))})
        if cache:
            write_json(cache / f"ccas-{slug(name)}.json", cca_rows)
            write_json(cache / f"subjects-{slug(name)}.json", subject_rows)
    downloaded[DS_CCAS] = downloaded[DS_SUBJECTS] = now_iso()

    # 3. Planning-area boundaries
    print("data.gov.sg: planning-area boundaries")
    poll = fetch_json(DATAGOV_POLL.format(DS_AREAS), "data.gov.sg", 3.0)
    areas = fetch_json(poll["data"]["url"], "s3", 0)
    downloaded[DS_AREAS] = now_iso()
    if cache:
        write_json(cache / "planning-areas.geojson", areas)

    # 4. OneMap coordinates by postal code
    coords = {}
    for name in wanted:
        row = by_name[name]
        postal = clean(row["postal_code"])
        print("OneMap:", postal, name)
        hits = onemap_search(postal).get("results", [])
        match = [h for h in hits if match_key(h.get("BUILDING")) == match_key(name)] or \
                [h for h in hits if match_key(name) in match_key(h.get("SEARCHVAL"))]
        if not match:
            sys.exit(f"STOP: OneMap has no hit for {postal} whose BUILDING matches {name}: {hits}")
        coords[name] = (round(float(match[0]["LATITUDE"]), 6), round(float(match[0]["LONGITUDE"]), 6))
    onemap_downloaded = now_iso()

    # 5. Planning area of each school (ray casting on the full-detail polygons)
    warnings = [
        "schoolCode slugs derived from names; verify against MOE SchoolFinder URLs",
        "scoreRanges are TEST VALUES, not MOE data",
        f"{NO_RANGES_SCHOOL} has no PSLE ranges on purpose (exercises 'Not available')",
        f"email of {NULL_EMAIL_SCHOOL} set to null on purpose (exercises 'Not available'); data.gov.sg has one",
        "affiliatedPrimarySchools and ipRangeNote left empty; curated later (data/curated/)",
        "programmes = subjects offered (data.gov.sg " + DS_SUBJECTS + ")",
    ]
    area_of = {}
    for name, (lat, lng) in coords.items():
        hits = [f for f in areas["features"] if point_in_geometry(lng, lat, f["geometry"])]
        if len(hits) != 1:
            sys.exit(f"STOP: {name} at {lat},{lng} lies in {len(hits)} planning areas")
        area_of[name] = hits[0]["properties"]
        if area_of[name]["PLN_AREA_N"] != wanted[name]:
            sys.exit(f"STOP: {name} lies in {area_of[name]['PLN_AREA_N']}, expected {wanted[name]}. Edit SEED_SCHOOLS.")
        dgp = clean(by_name[name].get("dgp_code"))
        if dgp and dgp != area_of[name]["PLN_AREA_N"]:
            warnings.append(f"{slug(name)}: dgp_code {dgp} differs from planning area {area_of[name]['PLN_AREA_N']}")

    # 6. Districts: the 3 planning areas, simplified
    features = []
    for area in sorted(SEED_SCHOOLS):
        f = next(f for f in areas["features"] if f["properties"]["PLN_AREA_N"] == area)
        features.append({"type": "Feature",
                         "properties": {"planningAreaCode": f["properties"]["PLN_AREA_C"],
                                        "planningAreaName": f["properties"]["PLN_AREA_N"]},
                         "geometry": simplify_geometry(f["geometry"], SIMPLIFY_TOLERANCE_DEG)})
    for name, (lat, lng) in coords.items():   # a school near a boundary could fall outside after simplifying
        area = next(f for f in features if f["properties"]["planningAreaName"] == wanted[name])
        if not point_in_geometry(lng, lat, area["geometry"]):
            warnings.append(f"{slug(name)} lies outside its simplified planning-area boundary")
    districts = {"type": "FeatureCollection", "features": features}

    # 7. Schools
    schools = []
    for name in sorted(wanted, key=slug):
        row, code = by_name[name], slug(name)
        lat, lng = coords[name]
        schools.append({
            "schoolCode": code,
            "name": clean(row["school_name"]),
            "address": clean(row["address"]),
            "postalCode": clean(row["postal_code"]),
            "latitude": lat,
            "longitude": lng,
            "telephone": clean(row["telephone_no"]),
            "website": clean(row["url_address"]),
            "email": None if code == NULL_EMAIL_SCHOOL else clean(row["email_address"]),
            "schoolType": clean(row["type_code"]),
            "planningAreaCode": area_of[name]["PLN_AREA_C"],
            "planningAreaName": area_of[name]["PLN_AREA_N"],
            "nearestMrt": clean(row["mrt_desc"]),
            "busInfo": clean(row["bus_desc"]),
            "sessionType": clean(row["session_code"]),
            "schoolNature": clean(row["nature_code"]),
            "programmes": subjects[name],
            "ccas": ccas[name],
            "affiliatedPrimarySchools": [],
            "ipRangeNote": None,
            "scoreRanges": [],
        })
    for i, s in enumerate(schools):
        s["scoreRanges"] = test_ranges(i, s["schoolCode"])

    manifest = {
        "kind": "seed",
        "version": VERSION,
        "effectiveDate": args.date,
        "importedAt": now_iso(),
        "sources": [
            {"name": "data.gov.sg General information of schools", "datasetId": DS_SCHOOLS,
             "downloadedAt": downloaded[DS_SCHOOLS]},
            {"name": "data.gov.sg Co-curricular activities (CCAs)", "datasetId": DS_CCAS,
             "downloadedAt": downloaded[DS_CCAS]},
            {"name": "data.gov.sg Subjects offered", "datasetId": DS_SUBJECTS,
             "downloadedAt": downloaded[DS_SUBJECTS]},
            {"name": "data.gov.sg Master Plan 2019 Planning Area Boundary", "datasetId": DS_AREAS,
             "downloadedAt": downloaded[DS_AREAS]},
            {"name": "OneMap search (coordinates by postal code)", "datasetId": "onemap-elastic-search",
             "downloadedAt": onemap_downloaded},
        ],
        "validationStatus": "PASSED_WITH_WARNINGS",
        "warnings": warnings,
        "counts": {"schools": len(schools), "districts": len(features),
                   "scoreRanges": sum(len(s["scoreRanges"]) for s in schools)},
        "notes": "PSLE ranges in this seed are TEST VALUES, not MOE data.",
    }

    # 8. Write the seed snapshot and its identical test fixture
    seed = root / "data" / "snapshots" / VERSION
    write_json(seed / "manifest.json", manifest)
    write_json(seed / "schools.json", schools)
    (seed / "districts.geojson").write_text(json.dumps(districts, separators=(",", ":")) + "\n", encoding="utf-8")
    size = (seed / "districts.geojson").stat().st_size
    if size > MAX_DISTRICTS_BYTES:
        sys.exit(f"STOP: districts.geojson is {size} bytes; raise SIMPLIFY_TOLERANCE_DEG")
    active = root / "data" / "snapshots" / "ACTIVE"
    if args.activate or not active.exists():
        active.write_text(VERSION + "\n", encoding="utf-8")

    fixtures = root / "src" / "test" / "resources" / "fixtures"
    mini = fixtures / "snapshot-mini"
    shutil.rmtree(mini, ignore_errors=True)
    shutil.copytree(seed, mini)

    # 9. Broken fixtures: a copy of snapshot-mini with exactly one defect each
    for rule in BROKEN_RULES:
        folder = fixtures / "snapshot-broken" / rule
        shutil.rmtree(folder, ignore_errors=True)
        shutil.copytree(mini, folder)
        broken = copy.deepcopy(schools)
        target = broken[0]
        if rule == "duplicate-code":
            broken[1]["schoolCode"] = target["schoolCode"]
        elif rule == "bad-coordinate":
            target["latitude"], target["longitude"] = 40.0, 100.0   # a real place, but not in Singapore
        elif rule == "bad-psle-range":
            r = target["scoreRanges"][0]
            r["lowerScore"], r["upperScore"] = r["upperScore"] + 2, r["lowerScore"]   # lower > upper
        elif rule == "missing-name":
            target["name"] = None
        elif rule == "unknown-planning-area":
            target["planningAreaCode"] = "XX"
        write_json(folder / "schools.json", broken)

    # 10. Recorded OneMap responses for StubOneMap (file name = normalised search text)
    stub_dir = root / "src" / "main" / "resources" / "stub" / "onemap"
    for text in STUB_ONEMAP_SEARCHES:
        response = onemap_search(text)
        response.pop("error", None)   # "token missing" notice; search works without a token
        write_json(stub_dir / (stub_key(text) + ".json"), response)

    print(f"Wrote {seed} ({len(schools)} schools, {len(features)} districts, "
          f"districts.geojson {size} bytes), snapshot-mini, {len(BROKEN_RULES)} broken fixtures, "
          f"{len(STUB_ONEMAP_SEARCHES)} OneMap stubs.")


if __name__ == "__main__":
    main()

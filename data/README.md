# School data

The app does not call data.gov.sg while it runs. It loads one **snapshot**: a folder of JSON files committed to this repo, checked at startup, and kept in memory. This keeps the app fast, lets it work offline, and means nobody except the data owner (B) needs network access to get school data.

```
data/
├─ snapshots/
│  ├─ ACTIVE              one line: the folder name of the snapshot the app loads (now 0000-seed)
│  └─ 0000-seed/          manifest.json, schools.json, districts.geojson
├─ curated/               hand-maintained CSV files, changed only by PR (rules below)
├─ tools/build_seed.py    Python script that built the seed snapshot (seed only)
└─ raw/                   gitignored; for your own downloads (the importer keeps nothing there)
```

## Sources

| Data | Source | Dataset id | Publisher | Used for |
|:--:|:--:|:--:|:--:|:--:|
| General information of schools | data.gov.sg | `d_688b934f82c1059ed0a6993d2a829089` | Ministry of Education | name, address, postal code, phone, website, email, MRT, bus, type, nature, session |
| Co-curricular activities (CCAs) | data.gov.sg | `d_9aba12b5527843afb0b2e8e4ed6ac6bd` | Ministry of Education | `School.ccas` |
| Subjects Offered | data.gov.sg | `d_f1d144e423570c9d84dbc5102c2e664d` | Ministry of Education | `School.programmes` |
| Master Plan 2019 Planning Area Boundary (No Sea) | data.gov.sg | `d_4765db0e87b9c86336792efe8a1f7a66` | Urban Redevelopment Authority | districts (planning areas) and each school's planning area |
| OneMap search | onemap.gov.sg (no key for search) | – | Singapore Land Authority | school coordinates by postal code; address lookup in the app |
| PSLE ranges, affiliated primary schools | MOE SchoolFinder, typed into `curated/*.csv` by hand | – | Ministry of Education | `School.scoreRanges`, `School.affiliatedPrimarySchools` |
| Libraries, tuition centres | Google Places | – | Google | nearby facilities; kept in memory only, never in this folder (DC-17) |

The CCA and subject datasets have no school code, so they are joined to schools by upper-cased school name (plus `curated/name-aliases.csv` for names that differ).

## Licences and credits

- **data.gov.sg** datasets are under the [Singapore Open Data Licence v1.0](https://data.gov.sg/open-data-licence). The licence asks for this notice, shown prominently in the app (the footer, and per dataset with its download date on `/about/data`): *"Contains information from {dataset name} accessed on {date} from data.gov.sg which is made available under the terms of the Singapore Open Data Licence version 1.0 (https://data.gov.sg/open-data-licence)"*. The dataset names and dates are in each snapshot's `manifest.json`; the footer (`layout.html`) names the MOE school datasets and URA's Master Plan 2019 Planning Area Boundary. Update the footer when a snapshot adds a source.
- **OneMap:** credit "OneMap, Singapore Land Authority" in the footer. OneMap has two documents that disagree (checked 2026-09-30): its [Open Data Licence page](https://www.onemap.gov.sg/legal/opendatalicence.html) is the Singapore Open Data Licence v1.0, which allows copying and distributing with attribution, but the site [Terms of Use](https://www.onemap.gov.sg/legal/termsofuse.html) clause 3 says you shall not store, reproduce or distribute SLA Data beyond clause 3(a). This repo stores some OneMap output: the seed and fixture coordinates and the recorded answers in `src/main/resources/stub/onemap/*.json`. The lead decides (or asks the TA / geoworks@sla.gov.sg) before the repo stores coordinates for all schools in Sprint 1, and records the answer here.
- **MOE SchoolFinder:** MOE's terms of use forbid commercial reuse and modification. Do not copy SchoolFinder data into this repo until the TA has approved it in writing (owner: lead; see the curation rules).
- **Google:** the map shows Google's own attribution. Anywhere Places data is shown without a Google map, show "Google Maps" as the source. Do not store Places content beyond the in-memory cache.

## The seed snapshot `0000-seed`

- 10 real secondary schools in 3 planning areas: Bishan (4), Tampines (3) and Jurong West (3), with names, addresses and contact details as published on data.gov.sg and coordinates from OneMap.
- **The PSLE ranges are TEST VALUES, not MOE data.** The manifest says so (`"kind": "seed"` and `notes`), and the footer shows "Seed data (test values)". Never quote them as real cut-off scores.
- `schoolCode` values are slugs made from the names (e.g. `catholic-high-school`). They still need checking against MOE SchoolFinder URLs.
- Some gaps are on purpose, to test "Not available": one school has no PSLE ranges, and one school's email is null.
- It is identical to the test fixture `src/test/resources/fixtures/snapshot-mini/`. Broken copies (one defect each) live in `src/test/resources/fixtures/snapshot-broken/<rule>/`.
- Rebuild it only if the seed must change: `python3 data/tools/build_seed.py` (Mac) or `py data\tools\build_seed.py` (Windows), from the repo root, Python 3.9+, no extra packages. It rewrites the seed, the test fixtures and the OneMap stub files, so review the diff.

## Snapshot format

Each snapshot folder `data/snapshots/<version>/` has:

- `manifest.json`: `kind` (`seed` or `full`), `version`, `effectiveDate`, `importedAt`, `sources` (name, dataset id, download time), `validationStatus`, `warnings`, `counts`, `notes`.
- `schools.json`: an array sorted by `schoolCode`, one object per school, with its `scoreRanges`. A missing value is JSON `null`, never `0`, `""`, `"NA"` or `"-"`.
- `districts.geojson`: a FeatureCollection with `planningAreaCode` and `planningAreaName` on each feature, simplified to keep the file small.

At startup `SchoolDataController` reads the folder named in `ACTIVE` (tests read `snapshot-mini` instead) and checks it with `SnapshotValidator`:

- **Errors** stop the app from starting: a duplicate or badly formed `schoolCode` (must match `^[a-z0-9-]+$`), a missing name, a coordinate missing or outside Singapore, a planning area not in `districts.geojson`, a range with lower > upper, outside 4–32 or a posting group other than 1–3, a duplicate range, and (full snapshots only) a school count outside 140–160.
- **Warnings** are allowed: a school with no CCAs, a school with no PSLE ranges.

`ActiveSnapshotIsValidTest` checks the `ACTIVE` snapshot on every build, so a broken snapshot fails CI.

## The real snapshot: the importer (owner B)

The Java importer is `SchoolDataController.importDataset()` (DC-12). The `import` profile runs it once with no web server (`DatasetImportRunner`) and then stops; the exit code is 0 when the new snapshot is usable and 1 when it failed. Run it from the repository root (it reads `data/curated/` and writes next to `data/snapshots/`):

- Mac: `./mvnw spring-boot:run -Dspring-boot.run.profiles=import`
- Windows (PowerShell): `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=import"`
- To try it without touching `data/snapshots/`, add an output folder, e.g. `-Dspring-boot.run.arguments="--app.dataset.import-output-dir=/tmp/import-test"`.

What it does, in order:

1. **Download** the four data.gov.sg datasets (`DataGovSgClient`, `datastore_search` 1000 rows per page, one request every 3 s, one retry after HTTP 429). Schools: only rows whose `mainlevel_code` contains `SECONDARY` or starts with `MIXED LEVEL`. On 2 Oct 2026 that was **147 of 337** schools: 117 `SECONDARY (S1-S5)`, 16 `SECONDARY (S1-S4)`, 10 `MIXED LEVEL (S1-JC2)`, 3 `MIXED LEVEL (P1-S4)`, 1 `MIXED LEVEL (S1-S5, JC1-JC2)`. Planning areas: `poll-download` gives a signed download URL for the GeoJSON (55 areas).
2. **Join** CCAs (`cca_grouping_desc`, rows whose `school_section` is not `PRIMARY` or `JUNIOR COLLEGE`) and subjects (`Subject_Desc`, stored as `programmes`) to the schools by name: upper case, single spaces, curly apostrophes made straight, plus `curated/name-aliases.csv`. A school with no rows is kept and reported (`cca-join-miss`, `subject-join-miss`).
3. **School code** from `curated/school-codes.csv`; without a row, a slug of the name (e.g. `st-hildas-secondary-school`) and a `school-code-slug` warning.
4. **Coordinate** by postal code with OneMap (`OneMapClient`, at most 1 request per second, one retry on an error; 5-digit postal codes get their leading 0 back). The hit whose BUILDING is the school's name wins (`ST.` = `SAINT`); else the only hit, or the first of several hits within 100 m of each other (both reported). Hits far apart (`geocode-ambiguous`) or no hit (`geocode-failed`) leave the school without a coordinate, which fails validation: add a row to `curated/geocode-overrides.csv`. An override row always wins.
5. **Planning area** by point-in-polygon on the full-detail boundaries (`District.contains`, JTS). A planning area that disagrees with the dataset's `dgp_code` is reported (`district-mismatch`; names are compared on letters only, so `SENG KANG` = `SENGKANG`).
6. **Curated data**: checked rows of `psle-ranges.csv` (`IP` rows become the school's `ipRangeNote`), `affiliations.csv`. Rows that are not checked by a second person, or that name an unknown school code, are reported (`curated`).
7. **Validate** with `SnapshotValidator` as kind `full` (140–160 schools, every coordinate in Singapore, every planning area known, ranges sane).
8. **Write** `<output>/<yyyy-MM-dd>.<n>/`: `manifest.json`, `schools.json` (sorted by code), `districts.geojson` (simplified with JTS until it is under 300 KB), `validation-report.md` (every error and warning) and `import-log.txt`. A FAILED snapshot is written too, so you can read why; an existing folder is never overwritten.
9. **ACTIVE** changes only when `app.dataset.activate-on-import=true` and the snapshot is not FAILED. The default is false: B reads the report, then edits `ACTIVE` in the PR titled "data: snapshot <version>".

Settings: `app.dataset.import-output-dir` (empty = `app.dataset.dir`), `app.dataset.activate-on-import` (false), `app.dataset.curated-dir` (`data/curated`), `DATAGOVSG_API_KEY` (optional, sent as the `x-api-key` header).

Run it again whenever a curated CSV changes, and once more when the PSLE CSV is complete. Never on a schedule and never during the demo.

**Dry run on 2 Oct 2026** (live data.gov.sg and OneMap, written outside the repo, not committed): about 4 minutes, exit code 0, **PASSED_WITH_WARNINGS** with 0 errors. 147 schools, 55 planning areas (`districts.geojson` 123 KB). OneMap located all 147: 145 by BUILDING name, 2 by the only hit (Bukit Panjang Govt. High School, School of the Arts), none ambiguous or failed. 2 district mismatches (School of Science and Technology: QUEENSTOWN by coordinate, CLEMENTI in `dgp_code`; School of the Arts: MUSEUM vs CENTRAL). 2 schools have no CCA rows (NUS High School of Mathematics and Science, School of the Arts); every school has subjects. Warnings that remain until the curated CSVs are filled: 147 `school-code-slug`, 147 `no-psle-ranges`. With no PSLE ranges, a full snapshot gives no PSLE filter results and no recommendations, so `ACTIVE` stays `0000-seed` for now.

## Curation rules for `data/curated/*.csv`

| File | Columns | What it holds |
|:--:|:--:|:--:|
| `school-codes.csv` | `school_name,school_code,source_url` | data.gov.sg name → MOE SchoolFinder slug (the part after `schoolname=` in the URL). Without a row the importer uses a slug of the name and warns (`school-code-slug`). Codes never change once used, because saved shortlists refer to them. |
| `name-aliases.csv` | `dataset,raw_name,canonical_name` | fixes for names that differ between datasets (CCA / subject joins); `dataset` is `schools`, `ccas`, `subjects` or `*` (all) |
| `psle-ranges.csv` | `school_code,admission_year,posting_group,track,lower,upper,raw_text,source_url,entered_by,checked_by` | PSLE ranges; `track` is `NON_AFFILIATED`, `AFFILIATED` or `IP` |
| `geocode-overrides.csv` | `postal_code,school_code,latitude,longitude,reason` | fixes for postal codes where OneMap returns the wrong building; fill `postal_code` or `school_code` |
| `affiliations.csv` | `school_code,primary_school,source_url` | affiliated primary schools (DC-21); stored upper-case |

- **TA approval first.** Do not enter MOE SchoolFinder data (ranges, affiliations, codes) until the TA has approved it in writing.
- Every row has a `source_url` pointing at the exact page the value came from.
- PSLE rows: `raw_text` is copied exactly as shown (e.g. `4(D) - 8(M)`); `entered_by` and `checked_by` are two different team members, and a row without `checked_by` is not used.
- One row per (school, year, posting group, track). Never merge or average ranges.
- Leave a cell empty when the value is unknown. Never type `0`, `NA` or `-`.
- Change these files only through a PR. Save as UTF-8 CSV with a header row; if you edit in Excel, check the diff for changed quotes or dates before committing.

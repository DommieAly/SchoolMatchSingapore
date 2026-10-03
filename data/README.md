# School data

The app does not call data.gov.sg while it runs. It loads one **snapshot**: a folder of JSON files committed to this repo, checked at start-up and copied into the database (DC-83), from where the app builds its in-memory cache. The JSON stays the reviewed record of every data change: what a pull request shows is exactly what reaches the database. The app still starts offline, and nobody except the data owner (B) needs network access to get school data. How the copy works, and how to roll back, is in [Loading into the database](#loading-into-the-database) and [Rollback](#rollback).

```
data/
├─ snapshots/
│  ├─ ACTIVE              one line: the folder name of the snapshot the app loads (2026-10-04.1)
│  ├─ 2026-10-04.1/       the real snapshot: 147 secondary schools from data.gov.sg + OneMap, 2025 PSLE ranges from MOE SchoolFinder
│  └─ 0000-seed/          historical: the 10-school seed with TEST PSLE values (same as the test fixture)
├─ curated/               curated CSV files (codes, aliases, overrides, MOE SchoolFinder ranges and affiliations), changed only by PR (rules below)
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
| PSLE ranges (2025 S1 posting), affiliated primary schools | MOE SchoolFinder school pages, collected into `curated/psle-ranges.csv` and `curated/affiliations.csv` on 3 Oct 2026 (see [MOE SchoolFinder data](#moe-schoolfinder-data)) | – | Ministry of Education | `School.scoreRanges`, `School.ipRangeNote`, `School.affiliatedPrimarySchools` |
| Libraries, tuition centres | Google Places | – | Google | nearby facilities; kept in memory only, never in this folder (DC-17) |

The CCA and subject datasets have no school code, so they are joined to schools by upper-cased school name (plus `curated/name-aliases.csv` for names that differ).

## Licences and credits

- **data.gov.sg** datasets are under the [Singapore Open Data Licence v1.0](https://data.gov.sg/open-data-licence). The licence asks for this notice, shown prominently in the app (the footer, and per dataset with its download date on `/about/data`): *"Contains information from {dataset name} accessed on {date} from data.gov.sg which is made available under the terms of the Singapore Open Data Licence version 1.0 (https://data.gov.sg/open-data-licence)"*. The dataset names and dates are in each snapshot's `manifest.json`; the footer (`layout.html`) names the MOE school datasets and URA's Master Plan 2019 Planning Area Boundary. Update the footer when a snapshot adds a source.
- **OneMap:** the active snapshot stores a OneMap coordinate for every one of its 147 schools (`latitude`, `longitude` in `schools.json`; one of them comes from `curated/geocode-overrides.csv`, which was also read from OneMap). OneMap's [Open Data Licence page](https://www.onemap.gov.sg/legal/opendatalicence.html) is the Singapore Open Data Licence v1.0: it allows copying and distributing, on condition that the app shows **a conspicuous notice that names the source and links to the most recent version of the licence**. The notice for this snapshot (footer or `/about/data`, next to the data.gov.sg notices):
  *"Contains information from OneMap search accessed on 3 October 2026 from OneMap (onemap.gov.sg, Singapore Land Authority) which is made available under the terms of the Singapore Open Data Licence version 1.0 (https://www.onemap.gov.sg/legal/opendatalicence.html)"*.
  The date is the `downloadedAt` of the source `onemap-elastic-search` in `manifest.json`; change it when a new snapshot is activated. Keep the short footer credit "OneMap, Singapore Land Authority" as well. Still open: the site [Terms of Use](https://www.onemap.gov.sg/legal/termsofuse.html) clause 3 says you shall not store, reproduce or distribute SLA Data beyond clause 3(a), which disagrees with the licence page (checked 2026-09-30). The lead asks the TA or geoworks@sla.gov.sg and records the answer here. The repo also stores OneMap output in the seed, the test fixtures and `src/main/resources/stub/onemap/*.json`.
- **MOE SchoolFinder:** MOE's terms of use forbid commercial reuse and modification. The lead approved using SchoolFinder data for this non-commercial course project on 3 Oct 2026; the TA has not been asked yet and is to be informed (owner: lead). We copy the published values as they are (`raw_text` keeps MOE's text) and never change them; pages name MOE SchoolFinder as the source and say the ranges are historical. The school codes stay our own (see [School codes](#school-codes)).
- **Google:** the map shows Google's own attribution. Anywhere Places data is shown without a Google map, show "Google Maps" as the source. Do not store Places content beyond the in-memory cache.

## The active snapshot `2026-10-04.1`

Built on 4 October 2026 (01:17–01:20 Singapore time) by the importer from live data.gov.sg and OneMap, with the MOE SchoolFinder rows of `curated/`: snapshot format 2, `kind` `full`, **PASSED_WITH_WARNINGS**, 0 errors, 14 warnings. Its data is the same as the previous snapshot `2026-10-03.5` (see the runs below).

| What | Count |
|:--:|:--:|
| Schools | 147 (of 337 schools in the dataset) |
| Planning areas in `districts.geojson` | 55 (all of Singapore) |
| Planning areas that have at least one school | 27 |
| Schools with CCAs / with subjects | 145 / 147 |
| CCA entries / subject entries | 3,774 / 9,103 |
| Distinct subject names (the subject filter's options) | 803 |
| Schools with PSLE ranges | 139 (8 of them with only an IP range) |
| PSLE ranges | 444 (427 ordinary, 17 IP: 16 non-affiliated, 1 affiliated), all admission year 2025 |
| Schools with affiliated primary schools / affiliations | 27 / 39 |

**Which schools.** Every row whose `mainlevel_code` contains `SECONDARY` or starts with `MIXED LEVEL`: 117 `SECONDARY (S1-S5)`, 16 `SECONDARY (S1-S4)`, 10 `MIXED LEVEL (S1-JC2)`, 3 `MIXED LEVEL (P1-S4)` (Catholic High, CHIJ St. Nicholas Girls', Maris Stella High), 1 `MIXED LEVEL (S1-S5, JC1-JC2)` (Singapore Sports School). By `schoolType`: 100 government, 31 government-aided, 8 independent, 4 specialised (NUS High School of Mathematics and Science, School of Science and Technology, School of the Arts, Singapore Sports School) and 4 specialised independent (Assumption Pathway, Crest Secondary, NorthLight, Spectra Secondary). These 8 schools admit students through their own application process rather than the usual S1 posting, and SchoolFinder shows no PSLE range for any of them (see [MOE SchoolFinder data](#moe-schoolfinder-data)). National Junior College and Temasek Junior College are in the list because they run secondary years (S1-JC2).

**Coordinates.** 146 schools by the OneMap hit whose BUILDING is the school's name (Bukit Panjang Govt. High School matches "BUKIT PANJANG GOVERNMENT HIGH SCHOOL" through the GOVT = GOVERNMENT rule). 1 school by `curated/geocode-overrides.csv`: School of the Arts, whose only OneMap hit for 227968 is "SCHOOL OF THE ARTS (SOTA)", which the name rule cannot match; the override holds that same OneMap coordinate, checked by hand. None by "only hit" or "same place", none ambiguous, none failed.

**Warnings that remain, and why**

| Rule | Count | Schools | Why it stays |
|:--:|:--:|:--:|:--:|
| `no-psle-ranges` | 8 | Assumption Pathway, Crest Secondary, NorthLight, Spectra Secondary, NUS High School of Mathematics and Science, School of Science and Technology, School of the Arts, Singapore Sports School | SchoolFinder shows no PSLE range for them: they admit students through their own process (see [MOE SchoolFinder data](#moe-schoolfinder-data)). Pages show "Not available" for their ranges. |
| `cca-join-miss` and `no-ccas` | 2 each | NUS High School of Mathematics and Science; School of the Arts, Singapore | Neither school is in the CCA dataset under any spelling (all 335 school names in it were checked on 3 Oct 2026), so a name alias cannot fix it. Pages show "Not available". |
| `district-mismatch` | 2 | School of Science and Technology, Singapore (1 Technology Drive, 138572): QUEENSTOWN by coordinate, CLEMENTI in `dgp_code`. School of the Arts, Singapore (1 Zubir Said Drive, 227968): MUSEUM by coordinate, CENTRAL in `dgp_code`. | `dgp_code` is MOE's own grouping, not a URA planning area (there is no URA area called CENTRAL). The snapshot keeps the URA planning area that contains the coordinate. |

**Subjects cleaned by the importer** (DC-76): 4 placeholder rows of Subjects Offered (`G1 Test`, `Test rebase A`, `Test Subject`, `test`) are left out through `curated/subject-exclusions.csv`, and 32 subject names that the dataset spells in two letter cases (e.g. `BIOLOGY` and `Biology`, `IP ART` and `IP Art`) are kept in one spelling, so 5 schools (Hwa Chong, Methodist Girls' (Secondary), Nanyang Girls', NorthLight, Victoria) no longer list a subject twice. The other subject names are as published; some still differ in more than case (e.g. `Character & Citizenship Education` and `Character and Citizenship Education`).

**Values published as-is** (the importer only trims spaces; nothing here breaks validation):

- Telephone is not 8 plain digits for 5 schools: Catholic High `64582177 (Secondary)`, CHIJ St. Nicholas Girls' `63541839 (Secondary)`, Maris Stella High `62803880 (Sec)` (the 3 schools with a primary section), NorthLight `+65 69296290`, School of the Arts `6338 9663`. A `tel:` link should keep only the digits.
- NUS High's website is `www.nushigh.edu.sg`, with no `https://`; used as-is in an `href` it becomes a link inside our own site.
- School of the Arts' address is `1 Zubir Said Drive 05 01` (the unit `#05-01` lost its `#` and dash in the dataset).
- 12 addresses are in mixed case (e.g. `1 Technology Drive`); the rest are upper case. All names are upper case; `ST ANDREW'S SCHOOL (SECONDARY)` has no dot after `ST`, the other saints do.
- No school is missing a phone number, email, website, MRT or bus value, and every school has subjects.

**Files**

| File | Size |
|:--:|:--:|
| `schools.json` | 493,746 bytes |
| `districts.geojson` | 123,231 bytes (simplified from the full boundaries to stay under 300 KB) |
| `validation-report.md` | 1,477 bytes |
| `import-log.txt` | 17,327 bytes |
| `manifest.json` | 2,083 bytes |

## School codes

`curated/school-codes.csv` freezes every school's `schoolCode`: 147 rows, `school_name` exactly as data.gov.sg spells it, `school_code` the slug the importer made from that name (`NameNormaliser.slug`, the same rule as the seed, e.g. `st-hildas-secondary-school`), `source_url` the school's MOE SchoolFinder page (added 3 Oct 2026). **Codes are our own internal ids, frozen on 3 October 2026. They are not MOE SchoolFinder slugs** (decision 2026-10-03): for 11 schools the SchoolFinder page name differs (e.g. `catholic-high-school` and `...?schoolname=catholic-high-school-secondary`), and the codes stay as they are. The 10 seed codes are unchanged, so shortlists and choice plans saved against the seed still find their schools.

- Never change a code once it is used: saved shortlists and choice plans refer to it, through a foreign key since V3 (DC-86). A changed code would withdraw the old one and leave members' saved entries on the withdrawn school; the loader log says how many.
- If data.gov.sg renames a school, change `school_name` in its row and keep the `school_code`.
- A new school gets a new row (the importer warns `school-code-slug` until it has one).

## The seed snapshot `0000-seed` (historical)

The app no longer loads it; it stays because the test fixture `src/test/resources/fixtures/snapshot-mini/` is identical to it and `build_seed.py` rebuilds both.

- 10 real secondary schools in 3 planning areas: Bishan (4), Tampines (3) and Jurong West (3), with names, addresses and contact details as published on data.gov.sg and coordinates from OneMap.
- **The PSLE ranges are TEST VALUES, not MOE data.** The manifest says so (`"kind": "seed"` and `notes`), and the footer shows "Seed data (test values)" while the seed is active. Never quote them as real cut-off scores.
- `schoolCode` values are slugs made from the names (e.g. `catholic-high-school`); they are the same codes as in the active snapshot.
- Some gaps are on purpose, to test "Not available": one school has no PSLE ranges, and one school's email is null.
- Broken copies (one defect each) live in `src/test/resources/fixtures/snapshot-broken/<rule>/`.
- Rebuild it only if the seed must change: `python3 data/tools/build_seed.py` (Mac) or `py data\tools\build_seed.py` (Windows), from the repo root, Python 3.9+, no extra packages. It rewrites the seed, the test fixtures and the OneMap stub files, so review the diff. **Never pass `--activate`**: it would point `ACTIVE` back at the seed.

## Snapshot format

Each snapshot folder `data/snapshots/<version>/` has:

- `manifest.json`: `formatVersion` (2; DC-84), `kind` (`seed` or `full`), `version`, `effectiveDate`, `importedAt`, `sources` (name, dataset id, download time), `validationStatus`, `warnings`, `counts`, `notes`.
- `schools.json`: an array sorted by `schoolCode`, one object per school, with `busServices` and `mrtStations` (lists in MOE's published order, one bus service or one station per element; the app joins each list with `", "` for display) and its `scoreRanges` (`admissionYear`, `postingGroup`, `affiliated`, `lowerScore`, `upperScore`, `integratedProgramme`, `moeText`; a range without `integratedProgramme`, as in older snapshots, reads as `false`, and one without `moeText` (MOE's text of the cell, DC-82) as `null`). A missing value is JSON `null`, never `0`, `""`, `"NA"` or `"-"`.
- `districts.geojson`: a FeatureCollection with `planningAreaCode` and `planningAreaName` on each feature, simplified to keep the file small.
- Full snapshots also have `validation-report.md` (every error and warning) and `import-log.txt` (what the importer did for each school).

At startup `SchoolDataController` reads the folder named in `ACTIVE` (tests read `snapshot-mini` instead) and checks it with `SnapshotValidator`:

- **Errors** stop the app from starting: a `formatVersion` other than 2 (a manifest without one is format 1, which had the `busInfo` and `nearestMrt` texts instead of the lists; `manifest`), a bus element that does not match `^[A-Z]{0,2}[0-9]{1,3}[A-Za-z]?$` or an MRT element that contains `:`, ` - `, ` and `, `&`, `campus` or a comma, or one listed twice or with leading or trailing white space (`transport-list`), a duplicate or badly formed `schoolCode` (must match `^[a-z0-9-]+$`), a missing name, a coordinate missing or outside Singapore, a planning area not in `districts.geojson`, a range with lower > upper, outside 4–32 or a posting group other than 1–3, an IP range that is not PG3, a duplicate range (same year, posting group, affiliation and IP flag), and (full snapshots only) a school count outside 140–160. DC-85 adds the rules that keep a valid snapshot loadable into the database: an admission year outside 2022–2100 (`bad-psle-range`), a `moeText` not in MOE's form `lower[(D|M)] - upper[(D|M)][*]` or with other numbers than the range (`moe-text-format`), a text longer than its database column (`too-long`), a postal code that is not 6 digits (`bad-postal-code`), two schools with the same name (`duplicate-name`), a planning area without a code or name or with one used twice (`bad-district`), a CCA, programme or affiliation listed twice for one school (`duplicate-element`), and a manifest without `effectiveDate` or `importedAt`, with a version other than letters, digits, `.`, `_` and `-`, or with a source lacking a `datasetId` or name or listed twice (`manifest`).
- **Warnings** are allowed: a school with no CCAs, a school with no PSLE ranges.

`ActiveSnapshotIsValidTest` checks the `ACTIVE` snapshot on every build, so a broken snapshot fails CI.

`schools.json` may also carry `planningAreaName` and `ipRangeNote` for reviewers. The database does not store them: the app computes the name from the planning-area code and the note from the IP ranges (DC-85), and the round-trip test checks that both equal what the JSON says.

## Loading into the database

At start-up, after the checks above, `SchoolDataController` hands the snapshot to `persistence.dataset.SchoolDatasetStore.ensureLoaded`, which runs in one database transaction (DC-83; [`docs/database-design.md`](../docs/database-design.md), section 6.2):

1. It locks the one row of `active_dataset`, so a second app instance starting at the same moment waits.
2. **Nothing to do** when the database's active version has the same name, the same SHA-256 of `manifest.json` + `schools.json` + `districts.geojson`, and the same loader format. This is the normal start-up and takes milliseconds.
3. **An older snapshot** (earlier `importedAt` than the active one) is not loaded when `app.dataset.allow-rollback` is false, which is the case only in the `prod` profile. The app keeps serving the newer version and logs a WARN.
4. Otherwise it writes the version row (with its sources and warnings), updates or inserts every planning area and school, and **withdraws** the ones missing from the snapshot: their row stays, marked with `withdrawn_in_version`. It logs each withdrawn school code with how many shortlists and plans saved it. Then it replaces all PSLE ranges, CCAs, programmes, affiliations, bus services and MRT stations, and makes the version active.
5. Any failure rolls the whole load back, and the previous version stays active.

After that the app reads the active dataset from the database (about 10 SELECTs) into `SchoolDataCache`. Every hour (`app.dataset.recheck-after`) it compares the database's active version and hash with what it serves, and rebuilds the cache when another instance loaded a different version. It never reads the files after start-up, so **a changed `ACTIVE` is picked up on the next restart**.

**Withdrawn schools.** Schools and planning areas are never deleted (DC-86): shortlists and plans hold foreign keys to `school`. A withdrawn school is left out of every page; a member who saved it sees its last-known name and code with "School no longer in the dataset" and a Remove button, and the plan warns about it. If the school comes back in a later snapshot, the loader clears `withdrawn_in_version` and the saved entries work again.

**Settings** (`app.dataset.*`): `load-on-startup` (true; false = read no snapshot file and build the cache from the database on first use, as the `import` profile does, so a new validator rule that rejects the current `ACTIVE` cannot stop the importer that would fix it), `allow-rollback` (true; `${DATASET_ALLOW_ROLLBACK:false}` in `prod`), `recheck-after` (1h).

**Look at the loaded data.** With the H2 console (README, "Look inside the dev database") or, on PostgreSQL, `docker compose exec db psql -U schoolmatch`:

```sql
SELECT a.dataset_version, v.load_status, v.loaded_at FROM active_dataset a JOIN dataset_version v USING (dataset_version);
SELECT school_code, school_name, withdrawn_in_version FROM school WHERE withdrawn_in_version IS NOT NULL;
```

## Rollback

To go back to an older snapshot:

1. In a PR, point `ACTIVE` back at the older folder. If the folder was removed, restore it from git history first: `git checkout <commit> -- data/snapshots/<version>`.
2. Restart the app. In `prod`, set `DATASET_ALLOW_ROLLBACK=true` for that one deploy and remove it afterwards; without it, `prod` keeps the newer version (so an old image restarting during a rolling deploy cannot switch every instance back).
3. The loader takes the normal path above: the old version's row already exists, so it is updated and made active; schools that are only in the newer version are withdrawn; schools that came back are reactivated. No history row is deleted.

Only format-2 snapshots can be loaded. `2026-10-03.5` and older are format 1, so they are no longer rollback targets; `2026-10-04.1` is the oldest real snapshot that is.

To start over on a local database instead (dev only): stop the app and delete `.local/h2/`; the next start creates the tables and loads `ACTIVE` again.

## The real snapshot: the importer (owner B)

The Java importer is `SchoolDataController.importDataset()` (DC-12). The `import` profile runs it once with no web server (`DatasetImportRunner`) and then stops; the exit code is 0 when the new snapshot is usable and 1 when it failed. Run it from the repository root (it reads `data/curated/` and writes next to `data/snapshots/`):

- Mac: `./mvnw spring-boot:run -Dspring-boot.run.profiles=import`
- Windows (PowerShell): `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=import"`
- To try it without touching `data/snapshots/`, add an output folder, e.g. `-Dspring-boot.run.arguments="--app.dataset.import-output-dir=/tmp/import-test"`.

What it does, in order:

1. **Download** the four data.gov.sg datasets (`DataGovSgClient`, `datastore_search` 1000 rows per page, one request every 3 s, one retry after HTTP 429). Schools: only rows whose `mainlevel_code` contains `SECONDARY` or starts with `MIXED LEVEL` (147 of 337 on 3 Oct 2026). Planning areas: `poll-download` gives a signed download URL for the GeoJSON (55 areas).
2. **Join** CCAs (`cca_grouping_desc`, rows whose `school_section` is not `PRIMARY` or `JUNIOR COLLEGE`) and subjects (`Subject_Desc`, stored as `programmes`) to the schools by name: upper case, single spaces, curly apostrophes made straight, plus `curated/name-aliases.csv`. A school with no rows is kept and reported (`cca-join-miss`, `subject-join-miss`). Two subject clean-ups (DC-76): rows listed in `curated/subject-exclusions.csv` are left out (`subjects-excluded` in the report; a row that matches nothing is a `curated` warning), and subject names that differ only in letter case get one spelling for all schools: a spelling that is not all upper case first, then the one most schools use, then alphabetical (`subject-spellings-merged`; e.g. `BIOLOGY` becomes `Biology`).
3. **School code** from `curated/school-codes.csv`; without a row, a slug of the name (e.g. `st-hildas-secondary-school`) and a `school-code-slug` warning.
4. **Coordinate** by postal code with OneMap (`OneMapClient`, at most 1 request per second, one retry on an error; 5-digit postal codes get their leading 0 back). The hit whose BUILDING is the school's name wins (`ST.` = `SAINT`, `GOVT` = `GOVERNMENT`); else the only hit, or the first of several hits within 100 m of each other (both reported). Hits far apart (`geocode-ambiguous`) or no hit (`geocode-failed`) leave the school without a coordinate, which fails validation: add a row to `curated/geocode-overrides.csv`. An override row always wins.
5. **Bus and MRT lists** (DC-84, `TransportLists`): MOE's `bus_desc` and `mrt_desc` texts are split on commas, each piece trimmed, empty and repeated pieces dropped. A text the split gets wrong has a row in `curated/transport-overrides.csv`, used only while MOE's text is still exactly its `published_text`; a stale row is reported (`transport-override-stale`) and the plain split is used, which the validator then refuses (`transport-list`).
6. **Planning area** by point-in-polygon on the full-detail boundaries (`District.contains`, JTS). A planning area that disagrees with the dataset's `dgp_code` is reported (`district-mismatch`; names are compared on letters only, so `SENG KANG` = `SENGKANG`).
7. **Curated data**: checked rows of `psle-ranges.csv` and `affiliations.csv`. An `IP` row becomes a range with `integratedProgramme: true` (posting group 3, non-affiliated, listed after the school's other ranges) and also the school's `ipRangeNote` with MOE's text (DC-77, see [MOE SchoolFinder data](#moe-schoolfinder-data)); an `IP_AFFILIATED` row is the same with `affiliated: true` (DC-82). Every range keeps the row's `raw_text` as `moeText` (DC-82), so the details page can show MOE's Higher Chinese grades and `30*`. Rows that are not checked by a second person, or that name an unknown school code, are reported (`curated`).
8. **Validate** with `SnapshotValidator` as kind `full` (140–160 schools, every coordinate in Singapore, every planning area known, ranges sane).
9. **Write** `<output>/<yyyy-MM-dd>.<n>/`: `manifest.json`, `schools.json` (sorted by code), `districts.geojson` (simplified with JTS until it is under 300 KB), `validation-report.md` (every error and warning) and `import-log.txt`. A FAILED snapshot is written too, so you can read why; an existing folder is never overwritten.
10. **ACTIVE** changes only when `app.dataset.activate-on-import=true` and the snapshot is not FAILED. The default is false: B reads the report, then edits `ACTIVE` in the PR titled "data: snapshot <version>".

Settings: `app.dataset.import-output-dir` (empty = `app.dataset.dir`), `app.dataset.activate-on-import` (false), `app.dataset.curated-dir` (`data/curated`, seven CSV files), `DATAGOVSG_API_KEY` (optional, sent as the `x-api-key` header).

Run it again whenever a curated CSV changes. Never on a schedule and never during the demo. Each run takes about 4 minutes. Delete the folders of runs you do not keep, so only the active snapshot (and the seed) stay in the repo.

**Runs on 3 Oct 2026.** Run 1 (curated CSVs empty) passed with 301 warnings: the same data as run 2, plus 147 `school-code-slug` and 1 `geocode-single-hit` (School of the Arts). Its codes were then frozen in `school-codes.csv` and the School of the Arts coordinate added to `geocode-overrides.csv`. Run 2 (`2026-10-03.2`, 153 warnings) had the same `schools.json` and `districts.geojson` as run 1, byte for byte. Run 3 is `2026-10-03.3` (153 warnings), made after the DC-76 subject clean-up: compared with run 2, only the `programmes` of 12 schools changed (4 placeholders left out, 32 names given one spelling), and `districts.geojson` is the same byte for byte; the manifest now names "Subjects Offered" as data.gov.sg does. Run 4 is `2026-10-03.4` (14 warnings), made after the MOE SchoolFinder rows were added to `curated/` (DC-77, DC-78): compared with run 3, only `scoreRanges`, `affiliatedPrimarySchools` and `ipRangeNote` changed (no other field of any school), `districts.geojson` is the same byte for byte, and the 139 `no-psle-ranges` warnings of the schools that now have ranges are gone. Run 5 is `2026-10-03.5` (14 warnings), made after the review fixes of DC-82: compared with run 4, every range gains `moeText` (MOE's `raw_text`), Nanyang Girls' High gains its affiliated IP range `4(D) - 8(M)` and the matching `ipRangeNote` part, and nothing else changed (`districts.geojson` the same byte for byte). The folders of runs 1 to 4 were removed from the repo. A dry run on 2 Oct 2026 (outside the repo) gave the same 147 schools.

**Run on 4 Oct 2026.** `2026-10-04.1` (14 warnings, the same 14 as run 5) is the first snapshot in format 2 (DC-84), made after `TransportLists` and `curated/transport-overrides.csv` were added. Compared with `2026-10-03.5`, a script checked that every field of every school is the same except `busInfo` / `nearestMrt`, which became `busServices` / `mrtStations` (1,492 bus services and 243 MRT stations; 9 texts through `transport-overrides.csv`); `districts.geojson` is the same byte for byte; the manifest differs only in `formatVersion`, the version, the dates and the curated-files source name (which now lists the transport overrides). Joined with `", "` for display, 138 of 147 bus texts and 144 of 147 MRT texts are exactly as before; the other 12 lose only labels, a repeated service or a stray comma. `2026-10-03.5` (format 1) was removed from the repo; the app can no longer load it.

**OneMap token notice.** Since at least 3 Oct 2026, OneMap's search answer carries `"error": "Authentication token missing. Please create an account and generate or renew your API Token."` next to normal results. Search still works without a token. If OneMap starts refusing searches without one, the importer (and live address search) will need a OneMap account token.

## Curation rules for `data/curated/*.csv`

| File | Columns | What it holds |
|:--:|:--:|:--:|
| `school-codes.csv` | `school_name,school_code,source_url` | data.gov.sg name → the app's school code. All 147 codes are name slugs frozen on 3 Oct 2026 (our own ids, not SchoolFinder slugs). `source_url` is the school's SchoolFinder page. Without a row the importer uses a slug of the name and warns (`school-code-slug`). Codes never change once used, because saved shortlists refer to them. |
| `name-aliases.csv` | `dataset,raw_name,canonical_name` | fixes for names that differ between datasets (CCA / subject joins); `dataset` is `schools`, `ccas`, `subjects` or `*` (all). Empty: no name differs in the 3 Oct 2026 data. |
| `psle-ranges.csv` | `school_code,admission_year,posting_group,track,lower,upper,raw_text,source_url,entered_by,checked_by` | PSLE ranges; `track` is `NON_AFFILIATED`, `AFFILIATED`, `IP` or `IP_AFFILIATED` (an IP row has `posting_group` 3). 444 rows for 139 schools, admission year 2025. |
| `geocode-overrides.csv` | `postal_code,school_code,latitude,longitude,reason` | coordinates for schools OneMap's hits cannot place by name; fill `postal_code` or `school_code`. One row: School of the Arts (227968). |
| `affiliations.csv` | `school_code,primary_school,source_url` | affiliated primary schools (DC-21), names as SchoolFinder writes them (the importer upper-cases them). The app compares a member's primary school with these names ignoring case, spaces, dots, apostrophes, brackets and a trailing "(Primary)" (DC-82), so the data.gov.sg spelling (e.g. `CATHOLIC HIGH SCHOOL` for MOE's `Catholic High School (Primary)`) also counts. 39 rows for 27 schools. |
| `transport-overrides.csv` | `school_code,kind,published_text,elements,reason` | DC-84: the bus services (`kind` `bus`) or MRT stations (`mrt`) of a school whose MOE text the plain comma split gets wrong; `elements` separated by `;`, in published order. Used only while MOE's (cleaned) text equals `published_text`. Nine rows (4 Oct 2026): 7 bus texts with operator names, `&`, `243G/W`, missing commas, stop or road labels, and the MRT texts of NUS High and Outram. |
| `subject-exclusions.csv` | `school_name,subject_desc,reason` | rows of the Subjects Offered dataset that are not real subjects, left out by the importer (DC-76). `school_name` and `subject_desc` as published (the subject is matched ignoring case). Four rows: the placeholders `G1 Test` (Boon Lay Secondary), `Test rebase A` (Cedar Girls'), `Test Subject` (Methodist Girls' (Secondary)) and `test` (Singapore Chinese Girls'), seen in the live dataset on 3 Oct 2026. |

- **Approval.** The lead approved using MOE SchoolFinder data (ranges, affiliations, page links) on 3 Oct 2026. The TA was not consulted and is to be informed (owner: lead). If the TA says no, empty `psle-ranges.csv` and `affiliations.csv` (header rows only) and import again: every page then works without ranges (DC-74).
- Every row of `psle-ranges.csv` and `affiliations.csv` has a `source_url` pointing at the exact page the value came from; `school-codes.csv` has each school's SchoolFinder page. A `geocode-overrides.csv` row says in `reason` where the coordinate came from and when it was checked; never type a coordinate you have not looked up. A `subject-exclusions.csv` row says in `reason` why the value is not a subject and when it was seen; exclude only values that are clearly not subjects (placeholders, test rows), never a real subject you would rather not show.
- PSLE rows: `raw_text` is copied exactly as shown (e.g. `4(D) - 8(M)`, `25 - 30*`); `lower` and `upper` are the numbers only. `entered_by` and `checked_by` name two different, independent sources of the value (two team members, or for the 3 Oct 2026 data two independent scripts, see below), and a row without `checked_by` is not used.
- One row per (school, year, posting group, track). Never merge or average ranges.
- Leave a cell empty when the value is unknown. Never type `0`, `NA` or `-`.
- Change these files only through a PR. Save as UTF-8 CSV with a header row; if you edit in Excel, check the diff for changed quotes or dates before committing.

## MOE SchoolFinder data

`curated/psle-ranges.csv`, `curated/affiliations.csv` and the `source_url` column of `curated/school-codes.csv` come from the MOE SchoolFinder page of each of the 147 schools (`https://www.moe.gov.sg/schoolfinder/schooldetail?schoolname=...`), read on 3 Oct 2026. The lead approved this on 3 Oct 2026; the TA is to be informed.

**How the values were collected.** By script, not typed by hand. One script (`entered_by` = `script:schoolfinder-extract`) read the "PSLE score range of 2025" table and the affiliated primary schools of every page. A second, independent extraction (`checked_by` = `script:independent-recheck`) read the values again to check them. Then 18 schools were compared by hand with the live SchoolFinder pages (a manual spot-check). So `entered_by` and `checked_by` name scripts, not team members. The collected rows were keyed by the data.gov.sg school name and were matched to `school_code` through `school-codes.csv` by exact name; all 147 names matched.

**What the cells mean.**

- `lower` and `upper` are the AL scores of the first and last student posted to the school in the 2025 S1 posting, by posting group (PG1, PG2, PG3) and by affiliation (`AFFILIATED` for students from an affiliated primary school, `NON_AFFILIATED` for everyone else). A lower score is better. They are historical, not a guarantee.
- `30*` (28 PG1 non-affiliated cells, e.g. `25 - 30*`): the `*` means the school still had places after posting. PG1 covers AL 25 to 30, so `upper` is 30, the worst PG1 score; `raw_text` keeps the `*`, and the details page shows `26–30*` with MOE's note (DC-82).
- `(D)` and `(M)` (14 cells at 10 Special Assistance Plan (SAP) schools, e.g. `4(D) - 8(M)` at Dunman High): the student at that score had a Distinction (D) or Merit (M) in Higher Chinese Language, which SAP schools use to order students with the same score. The app uses only the numbers (`lower` 4, `upper` 8) for search, labels and recommendations; `raw_text` keeps the grades, and the details page shows them ("MOE: 4(D) - 8(M)") with MOE's meaning (DC-82).
- `IP` (16 schools): the range of the school's Integrated Programme, which usually has no affiliated / non-affiliated split. MOE's own page data files IP under posting group 3, so an `IP` row has `posting_group` 3.
- `IP_AFFILIATED` (1 row, DC-82): Nanyang Girls' High shows a second IP value, `4(D) - 8(M)`, in SchoolFinder's "Affiliated" column (its `IP` row holds the non-affiliated `4(D) - 6(M)`). An affiliated student (Nanyang Primary) gets this range for PG3. `checked_by` is `manual:moe-page-text-recheck`: the value was checked against the saved SchoolFinder page text on 3 Oct 2026.

**Schools with no range (8).** Assumption Pathway, Crest Secondary, NorthLight, Spectra Secondary, NUS High School of Mathematics and Science, School of Science and Technology, School of the Arts and Singapore Sports School. They admit students through their own application process (Direct School Admission or the school's own selection), so SchoolFinder shows no PSLE range for them. They keep the `no-psle-ranges` warning, and pages show "Not available" for their ranges.

**IP schools (16) and the PG3 fallback (DC-77).** 8 schools have only an IP range: Dunman High, Hwa Chong Institution, Nanyang Girls' High, National Junior College, Raffles Girls' School (Secondary), Raffles Institution, River Valley High and Temasek Junior College. 8 have an IP range and ordinary PG3 ranges: Anglo-Chinese School (Independent), Catholic High, Cedar Girls' Secondary, CHIJ St. Nicholas Girls', Methodist Girls' (Secondary), Singapore Chinese Girls', St. Joseph's Institution and Victoria School. The importer keeps each IP row as a range with `integratedProgramme: true` (PG3; non-affiliated, or affiliated for `IP_AFFILIATED`) and as the details-page note `ipRangeNote` (MOE's text, e.g. `IP 2025 PG3: 4(D) - 8(M)`). `School.getScoreRange(pg, affiliated)` first picks among the non-IP ranges as before; only when the school has no non-IP PG3 range at all does it use the IP range for PG3 (the affiliated IP range for an affiliated student when there is one, DC-82). So the 8 IP-only schools get a PG3 range for search, the choice plan and recommendations, the other 8 keep their ordinary PG3 range, and PG1 and PG2 of the IP-only schools stay "Not available". Pages mark such a range "IP", e.g. "PG3 IP 4–8 (2025)".

**Values left out on purpose (1).** Kept outside the repo with the reason (Nanyang Girls' affiliated IP value, first left out, is now the `IP_AFFILIATED` row, DC-82):

- Kuo Chuan Presbyterian Secondary School, PG3 non-affiliated: the 2025 table cell is `-`. A footnote says no non-affiliated PG3 student was posted in 2025 (those places were all taken through DSA-Sec) and gives the 2024 range `6 - 9`, but only in prose and for an earlier year, so it is not used. The school's other five 2025 ranges are in the CSV, and its PG3 non-affiliated range shows "Not available".

**Checks on every build.** `CuratedCsvReaderTest` (TC-CuratedCsv-01) checks that the files read without problems, that every `school_code` is in `school-codes.csv`, that there is one row per (school, year, posting group, track), that 4 ≤ `lower` ≤ `upper` ≤ 32, that every row is checked and links to SchoolFinder, that every IP row is posting group 3, and that the one `IP_AFFILIATED` row is Nanyang Girls' `4(D) - 8(M)`.


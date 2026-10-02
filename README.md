# SchoolMatch SG

SchoolMatch SG helps Primary 6 students and their parents in Singapore choose secondary schools. You can search and filter the 140-plus secondary schools, see them on a map with planning-area boundaries, read each school's details and past PSLE score ranges, find libraries and tuition centres nearby, and get directions. After logging in you can keep a shortlist, compare schools, plan your 6 school choices with SAFE / MATCH / REACH labels, and get recommendations. It is our SC2006 (Software Engineering) team project, built from the Lab 2 design in [`docs/design/`](docs/design/).

Milestones: prototype in week 9 (12–16 Oct 2026), demo in week 11 (26–30 Oct 2026).

## Current state (2 Oct 2026)

Every page of the dialog map is built, and `./mvnw verify` runs about 960 tests. The app runs on the 10-school seed dataset.

**What works now:**

- Register, log in (username or email), log out, and a profile with home-address lookup. The navbar greets the member, and a login lasts 30 minutes after the last page view.
- Search, filters (school type, programme, CCA, district, PSLE score, distance, travel time) with filter chips and Clear all, sorting by name, distance or travel time, and school details.
- The school map with filters and planning-area boundaries, and `GET /api/districts`.
- Nearby libraries and tuition centres (list, filter, details, map) and directions to a school or a facility (walk, drive, public transport).
- Shortlist, compare 2–4 schools, the 6-choice plan with SAFE / MATCH / REACH labels and warnings, and recommendations with a reason for every factor.
- "About the data" (`/about/data`, footer link) and a health check at `/actuator/health`.
- The dataset importer (`import` profile). A dry run on 2 Oct 2026 built 147 schools with 0 errors (see [`data/README.md`](data/README.md)).

**What is still stub or test data:**

- **PSLE ranges are TEST VALUES.** The seed's ranges are made up, and every page that shows them says so. Real ranges wait for the TA to approve using MOE SchoolFinder data (`data/curated/psle-ranges.csv` is empty).
- **Only the 10-school seed is active** (`data/snapshots/ACTIVE` = `0000-seed`). No full snapshot is committed yet: the OneMap terms, the MOE school codes and the PSLE ranges are still open. A full snapshot without ranges would hide every school from the PSLE filter and from recommendations.
- **Google runs in stub mode.** Routes are straight lines with three "(stub)" steps, and facilities are made-up "(stub)" places. The live Google code follows Google's REST reference and is tested against a mock server, but it has never run with a real key. Without a browser key, map pages show "Map unavailable"; the lists beside the maps still work.
- **OneMap runs in stub mode by default.** The stub answers only `579767`, `catholic high school` and `bishan`; set `ONEMAP_MODE=live` for real address search.
- **The Google free-tier numbers are not confirmed.** The daily limits in `app.external.budget.monthly-free` still need a check in Google Cloud Console.

## Stack

| Part | Choice |
|:--:|:--:|
| Language | Java 21 (Temurin JDK) |
| Framework | Spring Boot 4.1.1 (Spring MVC, Spring Data JPA, Bean Validation, Cache) |
| Build | Maven, run through the wrapper `./mvnw` / `mvnw.cmd` (a script that downloads the right Maven for you) |
| Pages | Thymeleaf (server-side HTML templates) + Bootstrap 5 (CSS library) + a little plain JavaScript |
| Database | H2, a Java database that runs inside the app: a file in `.local/h2/` for dev and demo, in memory for tests |
| School data | JSON snapshot in `data/snapshots/`, loaded into memory at startup (see [`data/README.md`](data/README.md)) |
| Login | our own session table + `SM_SESSION` cookie; passwords hashed with BCrypt (`spring-security-crypto`) |
| External services | Google Maps Platform (map, routes, places), OneMap (address search), data.gov.sg (school data import) |
| Tests | JUnit 5, Mockito, MockMvc (calls pages inside a test without a server), ArchUnit (checks package and naming rules) |
| CI | GitHub Actions on Ubuntu and Windows: `verify` on every push and pull request |

## Setup

You need Git, IntelliJ IDEA and JDK 21. You do **not** need to install Maven, Node, Docker or a database.

Clone the repo into a normal folder, **not** one synced by iCloud, OneDrive or Dropbox: syncing corrupts `.git` and `target/`. On Windows, `Desktop` and `Documents` are often OneDrive folders.

### Mac (Terminal, zsh)

1. Install [Git](https://git-scm.com/downloads) (or run `xcode-select --install`), [IntelliJ IDEA](https://www.jetbrains.com/idea/download/) (NTU students can get the paid edition free through the [JetBrains student licence](https://www.jetbrains.com/community/education/)), and the **Temurin JDK 21** `.pkg` from [adoptium.net](https://adoptium.net/temurin/releases/) (aarch64 for Apple silicon, x64 for Intel Macs).
2. Check Java. This must print version 21:
   ```zsh
   java -version
   ```
   If it prints another version, make JDK 21 the default, then check again:
   ```zsh
   echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' >> ~/.zshrc
   source ~/.zshrc
   java -version
   ```
3. Clone the repo:
   ```zsh
   mkdir -p ~/dev
   cd ~/dev
   git clone https://github.com/DommieAly/SchoolMatchSingapore.git
   cd SchoolMatchSingapore
   ```
4. Create your local settings file (it will hold keys, so git ignores it):
   ```zsh
   cp .env.example .env
   ```
5. Start the app. The first run downloads Maven and the libraries, which takes a few minutes.
   ```zsh
   ./mvnw spring-boot:run
   ```
   Open http://localhost:8080. Stop the app with `Ctrl+C`.
6. Run all tests. It must end with `BUILD SUCCESS`:
   ```zsh
   ./mvnw verify
   ```

### Windows (PowerShell)

1. Install [Git for Windows](https://git-scm.com/download/win), [IntelliJ IDEA](https://www.jetbrains.com/idea/download/), and the **Temurin JDK 21** `.msi` (x64) from [adoptium.net](https://adoptium.net/temurin/releases/). In the installer's *Custom Setup* screen, set **"Set JAVA_HOME variable"** to *"Will be installed on local hard drive"*.
2. Open a **new** PowerShell window and check Java. Both must mention 21:
   ```powershell
   java -version
   echo $env:JAVA_HOME
   ```
   The build uses `JAVA_HOME`. If it is empty or points at an older JDK (often left over from earlier courses), set it, then open a new PowerShell window:
   ```powershell
   $jdk = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
   [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
   ```
   If `java -version` still shows an older version, open *System Properties → Environment Variables* and, in `Path`, move the `Eclipse Adoptium\jdk-21…\bin` entry above the other Java entries.
3. Clone the repo:
   ```powershell
   New-Item -ItemType Directory -Force "$HOME\dev" | Out-Null
   cd "$HOME\dev"
   git clone https://github.com/DommieAly/SchoolMatchSingapore.git
   cd SchoolMatchSingapore
   ```
4. Create your local settings file:
   ```powershell
   Copy-Item .env.example .env
   ```
5. Start the app (the first run takes a few minutes):
   ```powershell
   .\mvnw.cmd spring-boot:run
   ```
   Open http://localhost:8080. Stop with `Ctrl+C`, then answer `Y` to "Terminate batch job". If Windows Firewall asks about Java, you can press Cancel; `localhost` still works.
6. Run all tests. It must end with `BUILD SUCCESS`:
   ```powershell
   .\mvnw.cmd verify
   ```

### IntelliJ IDEA (both)

- *File → Open*, pick the `SchoolMatchSingapore` folder, and trust the project. IntelliJ reads `pom.xml` by itself.
- *File → Project Structure → Project → SDK*: choose the Temurin 21 JDK.
- Run the app with the green arrow next to `main` in `SchoolMatchApplication`. Keep the run configuration's working directory as the project folder (the default), so the app finds `.env` and `data/snapshots/`.
- `.editorconfig` sets the indentation for you (4 spaces for Java; 2 for YAML, HTML, JS, CSS and JSON).
- `.gitattributes` makes git store and check out LF line endings (CRLF only for `*.cmd`), whatever your `core.autocrlf` setting is. Leave it as it is, so Windows and Mac commits do not show whole-file diffs.

## Everyday commands

| Task | Mac | Windows (PowerShell) |
|:--:|:--:|:--:|
| Start the app (dev profile) | `./mvnw spring-boot:run` | `.\mvnw.cmd spring-boot:run` |
| All tests + architecture checks (**before every PR**) | `./mvnw verify` | `.\mvnw.cmd verify` |
| One test class | `./mvnw test -Dtest=SchoolControllerTest` | `.\mvnw.cmd test "-Dtest=SchoolControllerTest"` |
| Tests for one requirement (by `@Tag`) | `./mvnw test -Dgroups=FR-SEARCH-04` | `.\mvnw.cmd test "-Dgroups=FR-SEARCH-04"` |
| Start with the demo profile | `./mvnw spring-boot:run -Dspring-boot.run.profiles=demo` | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"` |
| Reset your local database (app stopped) | `rm -rf .local/h2` | `Remove-Item -Recurse -Force .local\h2` |
| Is the app up? (while it runs) | open http://localhost:8080/actuator/health | same |

In PowerShell, keep the quotes around `-D…` arguments: without them PowerShell can split the argument at the dot.

**Look inside the dev database:** the H2 console is off by default. Start the app with `H2_CONSOLE=true` (for example `H2_CONSOLE=true ./mvnw spring-boot:run`, or a line `H2_CONSOLE=true` in `.env`), open http://localhost:8080/h2-console and enter JDBC URL `jdbc:h2:file:./.local/h2/dev`, user `sa`, and an empty password. Turn it off again afterwards: the console runs any SQL, and it does not check the Host header, so a web page open in your browser could reach it by DNS rebinding. If an entity change breaks your local database, stop the app and delete `.local/h2/` (command above); the tables are created again on the next start. The column `account.username_key` (DC-70) was added on 2 Oct 2026, so a dev database created before then needs this reset.

**Profiles** (sets of settings in `src/main/resources/application-<profile>.yml`):

| Profile | Used for | Database | Google |
|:--:|:--:|:--:|:--:|
| `dev` (default) | daily work | H2 file `.local/h2/dev` | stub |
| `test` | automated tests (`@ActiveProfiles("test")`) | H2 in memory | stub (OneMap and data.gov.sg too) |
| `demo` | rehearsals and the demo | H2 file `.local/h2/demo` | whatever `GOOGLE_MODE` in `.env` says: set `GOOGLE_MODE=live` and the keys on the demo laptop (with no `.env` at all it is live, and stops at start-up without a server key) |
| `import` | building a new school snapshot (owner B; no web server) | – | – |

## Project structure

```
src/main/java/sg/schoolmatch/
├─ SchoolMatchApplication   starts the app
├─ boundary/
│  ├─ ui/                   the 16 design UI classes (XxxUI, Spring @Controller): handle page requests
│  │  └─ support/           helpers for the UI layer: login check, layout data, error pages, session cookie,
│  │                        search/filter URL parameters (FilterParams), starting point in the session, map markers
│  └─ external/             interfaces to outside services (DataGovSgInterface, GoogleMapsPlatformInterface, OneMapInterface)
│     ├─ ExternalCallBudget daily spending limit for live Google calls (DC-41)
│     ├─ stub/              offline versions (the default): made-up Google routes and facilities, a few recorded OneMap answers, empty data.gov.sg
│     └─ datagovsg/ google/ onemap/   live versions that call the real APIs
├─ control/                 the 13 design control classes (XxxController, Spring @Service): all the logic
├─ entity/                  the design entity classes and enums, one sub-package per area (account, school, search, …)
├─ persistence/             database access for Account, AuthenticatedSession, UserProfile, Shortlist (ChoicePlan is saved with its Shortlist), and the Google usage counters
├─ dataset/                 reads and checks the school data snapshot; the importer that builds a new one
├─ error/                   exceptions used across layers (InvalidInputException, NotFoundException, …)
└─ config/                  settings (AppProperties), clock, password hashing, caches, the HTTP client for outside calls, login-check registration
src/main/resources/
├─ application*.yml         settings, one file per profile
├─ templates/               Thymeleaf pages and layout.html (head, navbar, footer); fragments/ holds shared parts ("Not available", cards, pager)
├─ static/                  CSS and browser JavaScript: map.js, loading.js, and the browser-side design classes DeviceLocationInterface.js and GoogleMapsPlatformInterface.js (loadMap, DC-39)
└─ stub/                    recorded OneMap responses used by StubOneMap
src/test/java/sg/schoolmatch/   tests in the same packages, plus architecture/, flow/ (whole-app tests) and support/
src/test/resources/         application-test.yml, fixtures/ (test snapshots), testcases/ (Lab 4 test tables as CSV)
data/                       school data snapshots and hand-curated CSV files
docs/                       design diagrams and the documents listed below
```

## Naming rule: design class → Java class

Every design class keeps its **exact Lab 2 name**. Spring calls its web classes "controllers", but in our design the web layer is the UI classes, and our `XxxController` classes hold the logic. So:

| Lab 2 design | Java | Package | Spring annotation |
|:--:|:--:|:--:|:--:|
| «control» `XxxController` (e.g. `FilterController`) | same name | `control` | `@Service` |
| «boundary» `XxxUI` (e.g. `SchoolSearchUI`) | same name | `boundary.ui` | `@Controller` (never `@RestController`) |
| «boundary» external interfaces | same name, a Java `interface`; the browser-side parts (`DeviceLocationInterface`, `GoogleMapsPlatformInterface.loadMap`) are JavaScript files in `static/js` (DC-39) | `boundary.external` | – |
| «entity» classes and enums | same name | `entity.<area>` | none, or `@Entity` for the stored ones |

- Handler methods in a UI class use the design's input operation names (`submitSearch`, `selectAddToShortlist`, `reorderChoice`). Display operations become template fragments. See [`docs/routes.md`](docs/routes.md).
- A JSON endpoint (only `GET /api/districts` so far) is a `@GetMapping` + `@ResponseBody` method inside the UI class that owns the page (`SchoolMapUI`), never a separate `@RestController`.
- If you add a method you have not written yet, make it throw `UnsupportedOperationException("TODO <FR ids> (owner <letter>)")`: the page then shows "Not built yet" instead of failing silently.
- A change to the design is marked in code with `// DC-xx` and recorded in [`docs/design-changes.md`](docs/design-changes.md).
- A missing value is `null` in Java and shows as "Not available" on the page. Never use `""`, `0` or `"NA"` for missing data.
- `School` objects are shared by every request (they live in `SchoolDataCache`). Only `SnapshotReader` calls their setters; everywhere else treat a `School` as read-only.
- `ArchitectureTest` (ArchUnit) fails the build when a class breaks these rules: a `*Controller` outside `control`, a UI class that is not a `@Controller` named `*UI`, a UI class that uses the database or an external service directly, an entity that depends on another layer, a control calling another control that [`docs/design/control-deps.csv`](docs/design/control-deps.csv) does not allow, or a class outside `boundary.external` making HTTP calls. If an IDE or AI assistant suggests `@RestController SchoolController`, it is wrong for this project.

## Where to find things

| File | What it is |
|:--:|:--:|
| [`docs/design/`](docs/design/) | Lab 2 diagrams (draw.io sources + PNGs), edited here from now on, and `control-deps.csv` |
| [`docs/design-changes.md`](docs/design-changes.md) | every change to the Lab 2 design (DC-01, DC-02, …) and its status |
| [`docs/requirements.csv`](docs/requirements.csv) | every FR/NFR id with its use case, dialog-map states, UI class, control method and owner |
| [`docs/routes.md`](docs/routes.md) | dialog-map state → URL → UI class → template, and every transition |
| [`docs/recommendation-scoring.md`](docs/recommendation-scoring.md) | PSLE range rules, SAFE / MATCH / REACH, plan warnings, recommendation scores |
| [`data/README.md`](data/README.md) | data sources, licences, the seed snapshot, curation rules |
| `src/test/resources/testcases/TC-*.csv` | Lab 4 test-case tables (header `tc_id,requirement,technique,input,expected`). Each one is read by the tests of its area, e.g. `TC-SEARCH.csv` by `SchoolControllerTest`, `TC-FILTER.csv` by `FilterFlowTest` |
| Example tests to copy | `CoordinateTest` (plain JUnit), `SchoolControllerTest` (Mockito), `SchoolSearchUITest` (MockMvc), `SearchFlowTest` (whole app), `ShortlistRepositoryTest` (`@DataJpaTest`, save and reload), `GoogleRoutesApiTest` (`MockRestServiceServer`), `PagesSmokeTest` (every GET route, guest and member; log in with `support/TestMembers`), `ArchitectureTest` |

## Team workflow

- **`main` always builds.** Never push to `main` directly; every change goes through a pull request (PR).
- **One branch per feature**, named `feat/…`, `fix/…`, `docs/…`, `data/…` or `chore/…`. Keep a branch at most about 2 days old.
  ```
  git switch main
  git pull --rebase
  git switch -c feat/school-filter
  ```
- **Every day**, update `main` (`git switch main`, then `git pull --rebase`). To bring the new `main` into your branch, `git switch feat/school-filter`, then `git merge main`.
- **Tests first** (Lecture 9): add your rows to `src/test/resources/testcases/TC-*.csv`, write the tests with `@Tag("FR-…")` and `@DisplayName("TC-…: …")`, see them fail against the TODO, then write the code.
- **Test ids:** `TC-<AREA>-<nn>-<seq>` when the test is about one requirement `FR-<AREA>-<nn>` (its first `@Tag`), e.g. `TC-SEARCH-02-08`; `TC-<Class>-<seq>` for tests of one class that cover several requirements (entities, dataset, architecture, security), e.g. `TC-Coordinate-06`. The `@Tag` values are the link to `docs/requirements.csv`.
- **Before opening a PR**, run `./mvnw verify` (Windows: `.\mvnw.cmd verify`) and fill in the PR template (FR ids, test tags, design change, screenshots).
- **One approving review** is needed. Reviews rotate A → B → C → D → E → F → A. CI must be green on both Ubuntu and Windows.
- PRs are **squash-merged**, and the branch is deleted afterwards.
- If you change a route, update `docs/routes.md`. If you change the design, add a DC row and update the diagram in the same PR.

## Google keys and external services

- **Stub mode is the default.** `GOOGLE_MODE=stub` in `.env` (or an empty value) makes the app use made-up straight-line routes and "(stub)" libraries and tuition centres, with no Google calls, and pages show a "Demo data (stub)" badge. Most teammates never need a key.
- **Key owner: E (backup A).** The server key (Routes + Places) is held only by E, A and the demo laptop. The browser key (Maps JavaScript) is held by E, D and the demo laptop. Without a browser key, map pages show "Map unavailable" and the list views still work; that is expected.
- To use live Google (key holders only): put the keys in `.env`, set `GOOGLE_MODE=live`, and restart the app. Live mode without `GOOGLE_MAPS_SERVER_KEY` stops at start-up with a message saying so. Write values without quotes: `.env` is read as a `.properties` file, so quotes become part of the value.
- **Never commit a key**, and never paste one into an issue, chat, screenshot or code. `.env` is gitignored for this reason. If a key leaks, tell E at once; E deletes it in Google Cloud Console and issues a new one.
- **Spending limit:** every live Google call first takes from a daily allowance (`ExternalCallBudget`): the monthly free amount × 0.8 ÷ 30 per kind of call, counted per Singapore day in the database. When today's allowance is used up, the page says the service is temporarily unavailable. Stub mode never counts. The free amounts in `application.yml` are still to be confirmed in Cloud Console (owner E).
- **OneMap** (address search) is free and needs no key. `OneMapClient` works: set `ONEMAP_MODE=live` in `.env` for real address search. It sends at most one request per second and caches answers for 24 hours. Stub mode answers only a few recorded searches (`src/main/resources/stub/onemap`: `579767`, `catholic high school`, `bishan`). Still open: the OneMap terms on storing coordinates (see [`data/README.md`](data/README.md)).
- **data.gov.sg** is used only by the dataset importer (`import` profile, see [`data/README.md`](data/README.md)). The running app reads the committed snapshot.

## Login and sessions

Registration, login and logout work. How it is built:

- There is **no Spring Security filter chain**. The `AuthenticatedSession` table is the session: logging in creates a row and an `SM_SESSION` cookie (HttpOnly, SameSite=Lax), and each request with a valid session moves its expiry 30 minutes forward. Logout marks the row invalid, clears the cookie and ends the browser's HTTP session (starting point, address list, stored results; DC-71). Logging in gives the HTTP session a new id.
- Passwords are hashed with BCrypt from `spring-security-crypto` (a small library, not the full Spring Security). They are never stored or logged in plain text.
- A wrong password, an unknown user and an inactive account all get the same message, "Incorrect username/email or password", so the page never reveals which accounts exist.
- Logged-out and expired session rows are deleted every hour (`app.session.cleanup-interval`, `AuthController.removeEndedSessions`, DC-73).
- Usernames are unique ignoring case in the database itself: the column `account.username_key` holds the lower-case username with a unique key, and login looks the username up there (DC-70). Emails are stored lower-case with a unique key.
- `AuthInterceptor` guards the login-required pages listed in `WebConfig` and sends a guest to `/login?next=<page>`.
- Pages never take an account id from the URL. Controls always get the account from the session id, so one user cannot see another user's shortlist (NFR-SEC-05).
- **No CSRF tokens, but an Origin check.** Without Spring Security there are no hidden CSRF tokens in forms. Two things protect form posts: `SameSite=Lax` on `SM_SESSION` (browsers do not send it with a POST from another site), and `OriginCheckInterceptor` (DC-72): every POST (any method except GET/HEAD/OPTIONS/TRACE) whose `Origin` header, or `Referer` when there is no Origin, names another host or port gets 403. This also stops login CSRF (another site logging the visitor into the attacker's account). A request with neither header (curl, tests) is allowed. Keep every change a POST, never a GET.
- `app.session.cookie-secure` is false for `http://localhost`. Anywhere the app is served over HTTPS, set it to true (NFR-SEC-03).

## Who owns what

Owners write their own classes and tests; everyone reviews. Fill in the names at the first team meeting.

| Letter | Name | Use cases | Control classes | UI classes and other files |
|:--:|:--:|:--:|:--:|:--:|
| A (tech lead) | _(fill in)_ | Register, Log In, Log Out, Manage Profile | `AccountController`, `AuthController`, `ProfileController` | `HomeUI`, `RegisterUI`, `LoginUI`, `UserProfileUI`, layout, `AuthInterceptor`, pom, CI, GitHub settings, `ArchitectureTest`, `entity.common`, `entity.account`; backup key owner |
| B (data, then recommendations) | _(fill in)_ | school dataset for all discovery; Get School Recommendations | `SchoolDataController`, `RecommendationController` | importer, `DataGovSgClient`, `OneMapClient`, snapshots, PSLE curation lead, `RecommendationUI`, `entity.school`, `entity.recommend` |
| C (discovery) | _(fill in)_ | Search for Schools, Filter Schools, View School Details; transport filter with D | `SchoolController`, `FilterController` | `SchoolSearchUI`, `SchoolFilterUI`, `SchoolDetailsUI`, `fragments/value.html`, performance tests, `entity.search` |
| D (map, location, directions pages) | _(fill in)_ | View Schools on Map, Display Interactive Map, reference location, Get Directions (pages) | `MapController`, `LocationController` | `SchoolMapUI`, `DirectionsUI` (including `/location*`), `map.js`, `GoogleMapsPlatformInterface.js`, `DeviceLocationInterface.js`, `entity.location` |
| E (facilities, routes, Google key owner) | _(fill in)_ | View Nearby Facilities, View Facility Details, View Facilities on Map, Calculate Route | `FacilityController`, `DirectionsController` | `NearbyFacilitiesUI`, `FacilityDetailsUI`, `FacilityMapUI`, `GoogleMapsPlatformClient`, Google stub data, Cloud Console, `entity.route`, `entity.facility` |
| F (shortlist, plan, design sync) | _(fill in)_ | Add / Remove / View Shortlist, Compare Schools, Plan School Choices | `ShortlistController`, `ChoicePlanController` | `ShortlistUI`, `ComparisonUI`, `ChoicePlanUI`, `docs/recommendation-scoring.md`, `docs/design-changes.md`, `docs/design/`, `entity.shortlist` |

## Spring Boot 4 note

Most tutorials and AI answers online are for Spring Boot 3. This project uses **Spring Boot 4.1**, and some names changed:

- Use `@MockitoBean` (`org.springframework.test.context.bean.override.mockito.MockitoBean`); `@MockBean` no longer exists.
- The web starter is `spring-boot-starter-webmvc` (Boot 3: `spring-boot-starter-web`).
- Test annotations moved: `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`.
- JSON is Jackson 3: `tools.jackson.databind.ObjectMapper` / `JsonMapper`; the annotations stay `com.fasterxml.jackson.annotation.*`; Jackson exceptions are unchecked.
- The H2 console is a separate module (`spring-boot-h2console`, already in `pom.xml`).
- A `@WebMvcTest` of a UI class needs the same setup as `SchoolSearchUITest`: `@EnableConfigurationProperties(AppProperties.class)`, `@Import(SessionCookie.class)`, and `@MockitoBean` for `SchoolDataController` and `AuthController` (the shared layout advice and `AuthInterceptor` use them), plus a `@MockitoBean` for the control your page calls.

When in doubt, copy the imports from the example tests listed above, and check the [Spring Boot 4.0 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).

## Troubleshooting

| Problem | Fix |
|:--:|:--:|
| `release version 21 not supported` or `UnsupportedClassVersionError` | Maven is using an older JDK. Fix `JAVA_HOME` (Setup, step 2) and open a new terminal. |
| `./mvnw: Permission denied` (Mac) | `chmod +x mvnw` |
| `Port 8080 was already in use` | Another copy of the app is running. Stop it (`Ctrl+C` in its window, or the red square in IntelliJ). |
| A setting from `.env` seems ignored | Check there are no quotes around the value, and that the app runs from the project folder. Restart after editing `.env`. |
| The app refuses to start with a snapshot validation error | The active school snapshot is broken. Run `git status` to see whether you changed `data/snapshots/`, and ask B. |

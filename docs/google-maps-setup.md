# Google Maps Platform setup

This guide sets up the Google Cloud project and the two API keys that SchoolMatch SG needs for real maps, routes and nearby places. Only the key owner (E, backup A) does steps 1 to 7. Everyone else keeps `GOOGLE_MODE=stub` in `.env` and needs no key.

Google facts in this guide were checked against Google's documentation on 3 Oct 2026. Google sometimes renames console menus; if a button name differs slightly, pick the closest match.

## Quick checklist

1. Key owner E (backup A) does steps 1 to 7. Everyone else stays in stub mode, with no keys.
2. Create the Google Cloud project `school-match-sg`.
3. Link a billing account (a card is required even for free usage).
4. Enable exactly three APIs: Maps JavaScript API, Routes API, Places API (New).
5. Create the browser key: Websites `http://localhost:8080/*` and `http://127.0.0.1:8080/*`, Maps JavaScript API only.
6. Create the server key: no application restriction on laptops, Routes API + Places API (New) only.
7. Optional: create a JavaScript Map ID for Advanced Markers.
8. Set the daily caps in Google Maps Platform → Quotas (322 / 322 / 322 / 161 / 161 / 32, unused rows 1).
9. Add a budget alert (it only sends emails; it does not stop charges).
10. Put the keys in `.env`, test in stub mode, then in live mode, and check the usage numbers.

## What the app uses Google for

| Feature | Pages | Google API → method | Key | Google SKU (tier) | Free per month |
|:--:|:--:|:--:|:--:|:--:|:--:|
| Map, markers, planning-area layer, route line | `/schools/map`, `/schools/{code}/facilities/map`, `/directions` | Maps JavaScript API (runs in the browser) | browser | Dynamic Maps (Essentials) | 10,000 map loads |
| Directions: walk, drive, public transport | `/directions` | Routes API → `computeRoutes` | server | Compute Routes Essentials | 10,000 requests |
| Travel-time filter; commute times in recommendations | `/schools`, `/schools/map` (travel-time filter), `/recommendations` | Routes API → `computeRouteMatrix` | server | Compute Route Matrix Essentials | 10,000 elements |
| Nearby libraries | `/schools/{code}/facilities` | Places API (New) → `searchNearby` (type `library`) | server | Nearby Search Pro | 5,000 requests |
| Nearby tuition centres | `/schools/{code}/facilities` | Places API (New) → `searchText` ("tuition centre") | server | Text Search Pro | 5,000 requests |
| Facility phone, website, opening hours | `/facilities/{placeId}` | Places API (New) → Place Details | server | Place Details Enterprise | 1,000 requests |

- A **SKU** is Google's name for one billable item. **Essentials, Pro and Enterprise** are Google's price tiers. Each SKU gets its own free amount every month: 10,000 for Essentials, 5,000 for Pro, 1,000 for Enterprise.
- Free amounts **do not pool**: unused map loads do not pay for Places calls. They are counted **per billing account**, so every Maps project on the same billing account shares them.
- An **element** is one start point × one destination. A travel-time filter from one start point to 147 schools needs up to 147 elements.
- Google bills a request at the tier of the most expensive field it asks for. The app chooses the fields with a **field mask** (the list of fields sent in the `X-Goog-FieldMask` header). Searches ask for `id, displayName, formattedAddress, location` (Pro). Details also ask for phone, website and opening hours (Enterprise). Routes requests do not ask for traffic-aware routing, so they stay in Essentials.
- If anyone changes a field mask in `GooglePlacesApi` or `GoogleRoutesApi`, check the SKU again before merging. A higher tier has a smaller free amount, and the daily caps in step 6 would no longer keep us free.
- **Address search uses OneMap** (free, no key), so the Geocoding API is not needed. Do not enable it.
- Google public-transport routing works in Singapore (tested 3 Oct 2026: Catholic High → Ngee Ann Secondary via the CC and DT lines).

## Who does this

| Person | Job | Server key | Browser key |
|:--:|:--:|:--:|:--:|
| E (key owner) | Steps 1 to 7, key rotation, watching usage | yes | yes |
| A (backup owner) | Takes over if E is away | yes | – |
| D (map pages) | Works on the map pages | – | yes |
| Demo laptop | Rehearsals and the demo | yes | yes |
| Everyone else | Develops in stub mode | – | – |

- This matches the README section [Google keys and external services](../README.md#google-keys-and-external-services).
- So that A can act as backup, E adds A to the project: ☰ menu → **IAM & Admin → IAM → Grant access**, enter A's Google account, role **Owner**.
- Hand keys over in person or through a password manager's share feature. If you must use a direct message, delete it after the other person has copied the key. Never put a key in the team group chat, a GitHub issue or pull request, a commit or a screenshot.

## Step 1: Create the Google Cloud project

A **Google Cloud project** is the container that holds our enabled APIs, keys, quotas and billing link.

1. Open https://console.cloud.google.com and sign in with E's Google account.
2. Click the **project picker** at the top of the page (it shows "Select a project" or the current project name), then **New project**.
3. Project name: `school-match-sg`. "No organisation" is fine for a personal account. Click **Create**.
4. Check that the project picker now shows `school-match-sg`. Every later step applies to the project selected there.

## Step 2: Link a billing account

Google Maps Platform refuses every request from a project without billing, even inside the free amounts (the browser console then shows `BillingNotEnabledMapError`). Google asks for a card to confirm who you are and to pay for any usage above the free amounts. The daily caps in step 6 keep us below the free amounts.

1. ☰ menu → **Billing**. Google shows that the project has no billing account; choose **Link a billing account**.
2. Pick an existing billing account, or **Create billing account** (country Singapore, then the card details). Only the key owner types the card details.
3. Check: with `school-match-sg` selected, ☰ menu → **Billing** shows the billing account's overview and no longer says the project has no billing account.

If E's billing account already has other Google Maps projects, they share the same free amounts. Lower the daily caps in step 6 in that case.

## Step 3: Enable exactly three APIs

1. ☰ menu → **APIs & Services → Library**.
2. Search for each API below, open it, and click **Enable**.

| Enable this | Do NOT enable (legacy versions) |
|:--:|:--:|
| Maps JavaScript API | – |
| Routes API | Directions API, Distance Matrix API |
| Places API (New) | Places API |

- The legacy APIs are older versions with different addresses. Our code calls `routes.googleapis.com` and `places.googleapis.com/v1`, which belong to Routes API and Places API (New) only.
- Check: **APIs & Services → Enabled APIs & services** lists all three.
- Do this before step 4. The **API restrictions** list on a key shows only APIs that are already enabled in the project.
- If a Google Maps Platform welcome screen already created a key for you, restrict it as in step 4 or delete it.

## Step 4: Create the two API keys

An **API key** is a long string starting with `AIza` that tells Google which project a request belongs to. We use two keys so that the key visible in the browser cannot call the more expensive APIs.

Open **Google Maps Platform → Keys & Credentials → Create credentials → API key** (or **APIs & Services → Credentials → Create credentials → API key**). Create one key with the settings in the table, then repeat for the second key. If the create dialog does not show every setting, open the new key from the list afterwards, set the rest and click **Save**.

| Setting | Browser key | Server key |
|:--:|:--:|:--:|
| Name (only a label for the key) | `schoolmatch-browser` | `schoolmatch-server` |
| Application restrictions | **Websites** | **None** on laptops; **IP addresses** on a server with a fixed IP |
| Entries | `http://localhost:8080/*` and `http://127.0.0.1:8080/*` (add the deployed site later, e.g. `https://your-domain.example/*`) | – (on a server: its fixed public IP address) |
| API restrictions | **Restrict key** → Maps JavaScript API | **Restrict key** → Routes API, Places API (New) |
| `.env` variable | `GOOGLE_MAPS_BROWSER_KEY` | `GOOGLE_MAPS_SERVER_KEY` |
| Who can see it | Anyone who opens a map page | Only the key holders; the app never sends it to the browser |

- **Why the browser key is restricted:** the app writes it into the page (the `data-maps-key` attribute of the map), so anyone can read it in the page source. The website restriction makes Google refuse it on other sites. The API restriction stops it from being used for Routes or Places.
- **Why the server key has no application restriction on laptops:** an IP restriction accepts only listed public IP addresses, and a laptop's address changes between home, NTU Wi-Fi and a phone hotspot. The API restriction and the daily caps limit the damage if it leaks. The app sends it only in the `X-Goog-Api-Key` header, never to the browser, in a URL or in a log line.
- **Ignore the service account box.** The key page may show a box about service accounts for Gemini API and Agent Platform ("Service account required" or "Authenticate API calls through a service account"). Leave it unticked; Maps keys do not use it.
- After **Create**, Google shows the key string. Keep it in your password manager. You can open it again later on the key's page (**Show key**).
- Changes to restrictions can take a few minutes to apply.

## Step 5 (optional): Create a Map ID

A **Map ID** names a map configuration in Google Cloud. The app needs it only for **Advanced Markers**, Google's newer marker type. Without a Map ID, the app draws classic markers with the same colours and letters, and the browser console shows a warning that `google.maps.Marker` is deprecated. Both work.

1. **Google Maps Platform → Map management → Create Map ID**.
2. Name `schoolmatch-map`, map type **JavaScript**. Keep the default rendering option (raster or vector both work for markers). Click **Save**.
3. Copy the Map ID into `GOOGLE_MAPS_MAP_ID`.

For a quick test you can also use Google's `DEMO_MAP_ID`; Google says it is for testing only. Google lists no separate charge for Map IDs or Advanced Markers.

## Step 6: Set hard daily caps (the real spending stop)

A **quota** is a limit that Google enforces on one project. A "per day" quota is the only setting that really stops spending: once it is reached, Google refuses further requests of that kind instead of charging for them. The app then shows "Map unavailable" or "temporarily unavailable".

Google resets daily quotas at **midnight Pacific Time**: 3 pm Singapore time in October 2026 (US daylight saving time), 4 pm from November.

Each value is the monthly free amount ÷ 31, rounded down, so even a 31-day month stays inside the free amount.

1. Open **Google Maps Platform → Quotas**.
2. Choose the API in the dropdown at the top.
3. On each row below, click **⋮ → Edit quota**, enter the value, and submit.
4. Leave the "per minute" rows as they are.

| API (dropdown) | Quota row | Value | Reason |
|:--:|:--:|:--:|:--:|
| Maps JavaScript API | Map loads per day | 322 | 10,000 ÷ 31 |
| Routes API | Directions - ComputeRoutes per request quota per day | 322 | 10,000 ÷ 31 |
| Routes API | DistanceMatrix - ComputeRouteMatrix per-element quota per day | 322 | 10,000 elements ÷ 31 |
| Places API (New) | SearchNearbyRequest per day | 161 | 5,000 ÷ 31 |
| Places API (New) | SearchTextRequest per day | 161 | 5,000 ÷ 31 |
| Places API (New) | GetPlaceRequest per day | 32 | 1,000 ÷ 31 |
| Places API (New) | AutocompletePlacesRequest per day | 1 | not used |
| Places API (New) | GetPhotoMediaRequest per day | 1 | not used |
| Places API (New) | SearchMediaRequest per day | 1 | not used |

- Google also counts requests that end in a Google server error against the quota.
- These caps keep us free only while the field masks stay as they are (see [What the app uses Google for](#what-the-app-uses-google-for)).

## Step 7: Add a budget alert

1. ☰ menu → **Billing → Budgets & alerts → Create budget**.
2. Scope: project `school-match-sg`. Amount: a small sum in the billing account's currency, e.g. 5.
3. Alert thresholds: 50%, 90% and 100% (Google's defaults). Send emails to the billing admins. **Finish**.

A budget **only sends emails. It does not stop any charges.** Google Cloud's "spend cap budgets" (Preview) support only Gemini API, Agent Platform, Cloud Run and Cloud Run functions, not Google Maps Platform. The daily caps in step 6 are the real stop. Charges in Billing reports can take up to 48 hours to appear.

## The app's own daily limiter (`ExternalCallBudget`)

On top of Google's caps, the app keeps its own daily allowance. Before every **live** Google call, `ExternalCallBudget.charge` takes units from a counter. When the counter would go over today's limit, nothing is sent. Stub mode never counts.

**Daily limit = floor(monthly-free × safety ÷ 30), at least 1**, with `safety: 0.8`. Both numbers live under `app.external.budget.*` in `src/main/resources/application.yml`.

| Counter (`monthly-free.<name>`) | One unit is | Value in `application.yml` | App daily limit now | Google free per month | Our Google daily cap |
|:--:|:--:|:--:|:--:|:--:|:--:|
| `map-loads` | one page with an interactive map (live mode only) | 10000 | 266 | 10,000 | 322 |
| `routes` | one `computeRoutes` request | 5000 | 133 | 10,000 | 322 |
| `route-matrix-elements` | one matrix element (start × school) | 5000 | 133 | 10,000 | 322 |
| `places-search` | one `searchNearby` or `searchText` request (both share this counter) | 5000 | 133 | 5,000 each | 161 each |
| `place-details` | one Place Details request | 1000 | 26 | 1,000 | 32 |

- A day is a **Singapore** calendar day; the counters restart at midnight Singapore time.
- With a browser key in stub mode, map pages still load from Google, but the app does not count them; only Google's cap (322) limits them.
- The counters are stored in the `external_usage` table (columns `usage_day`, `sku`, `used`) of the local H2 database, so restarting the app does not reset them. Each database counts on its own: `.local/h2/dev` for the dev profile, `.local/h2/demo` for the demo profile, and every laptop has its own. Deleting `.local/h2` resets them. Two key holders running live mode at the same time can therefore use twice the app's allowance; only Google's caps cover the whole project.
- Matrix requests are charged per batch before sending: at most 100 elements per request (`app.google.matrix-batch-size`; Google allows 100 for TRANSIT and 625 otherwise).
- Answers are cached in memory: routes and matrix elements for 30 minutes, place searches and details for 24 hours. A repeat inside that time costs nothing. A restart clears the caches but not the counters.

**What users see when a limit is reached** (the app's or Google's):

| Feature | What the user sees |
|:--:|:--:|
| Any map | "Map unavailable" box; the list beside it still works |
| Directions | "Directions are temporarily unavailable. Please try again in a few minutes." |
| Nearby facilities | "Nearby facility information is temporarily unavailable. Please try again in a few minutes." |
| Facility details | "Facility details are temporarily unavailable. Please try again in a few minutes." |
| Travel-time filter | "Travel-time filter is temporarily unavailable." The other filters still apply. |
| Recommendations | Commute shows "Not available"; its weight is shared by the other factors |

**Known follow-ups** (this guide does not change the code):

1. `routes` and `route-matrix-elements` are 5000 in `application.yml`, but Google's free amount for these Essentials SKUs is 10,000. The app's limits are 133 per day; with 10000 they would be 266.
2. Because of 1, a live travel-time filter that must route more than 133 schools always shows "temporarily unavailable". TRANSIT with 45 or 60 minutes reaches nearly all 147 schools. The first batch of 100 elements is counted even though the filter then fails. With 10000 (266 per day), only one such island-wide filter fits per day; repeating it from the same start point and mode within 30 minutes is free.
3. `places-search` is one counter for two Google SKUs that each have 5,000 free. The app is stricter than needed: one school's facility list uses 2 units, so about 66 schools per day.
4. The app's day ends at midnight Singapore time; Google's daily caps reset at midnight Pacific Time. The two do not reset together.
5. The comment in `application.yml` still calls the numbers "UNVERIFIED placeholders". `map-loads`, `places-search` and `place-details` now match Google; `routes` and `route-matrix-elements` do not.

## Step 8: Put the keys into the app

Create `.env` in the repo root (the folder with `pom.xml`). The app reads it from the folder it is started in.

Mac (Terminal):
```zsh
cp .env.example .env
```

Windows (PowerShell):
```powershell
Copy-Item .env.example .env
```

Edit `.env` in IntelliJ or another plain-text editor. Avoid word processors, which turn quotes into curly quotes.

| Variable | Values | Who sets it |
|:--:|:--:|:--:|
| `GOOGLE_MODE` | `stub` (made-up routes and facilities, no Google calls) or `live` (real calls, needs the server key) | everyone (`stub`); key holders may use `live` |
| `GOOGLE_MAPS_SERVER_KEY` | the server key | E, A, demo laptop |
| `GOOGLE_MAPS_BROWSER_KEY` | the browser key; works in stub mode too | E, D, demo laptop |
| `GOOGLE_MAPS_MAP_ID` | the Map ID, or empty | browser key holders (optional) |
| `ONEMAP_MODE` | `stub` (answers only `579767`, `catholic high school`, `bishan`) or `live` (real address search, free, no key) | anyone |

Example `.env` on a key holder's laptop (placeholders, not real keys):
```properties
GOOGLE_MODE=live
ONEMAP_MODE=live
GOOGLE_MAPS_SERVER_KEY=AIza...your-server-key
GOOGLE_MAPS_BROWSER_KEY=AIza...your-browser-key
GOOGLE_MAPS_MAP_ID=your-map-id
DATAGOVSG_API_KEY=
```

Format rules (the file is read as a Java `.properties` file):

- **No quotes.** Quote marks become part of the value, and Google rejects the key.
- **No spaces** around `=` or at the end of a line.
- One setting per line. A comment needs its own line starting with `#`; text after a value on the same line becomes part of the value.
- `GOOGLE_MODE` must be `stub` or `live`; anything else stops the app at start-up. An empty value means `stub`.
- A variable with the same name set in your shell or in an IntelliJ run configuration overrides `.env`.
- Restart the app after every change to `.env`.

`.env` is listed in `.gitignore`. **Never commit it.** `git status` must never show `.env`; `git check-ignore .env` prints `.env` when it is ignored.

## Step 9: Test in two stages

Start the app from the repo root (from the README):

| | Mac | Windows (PowerShell) |
|:--:|:--:|:--:|
| Dev profile | `./mvnw spring-boot:run` | `.\mvnw.cmd spring-boot:run` |
| Demo profile | `./mvnw spring-boot:run -Dspring-boot.run.profiles=demo` | `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"` |

Open http://localhost:8080. Stop the app with `Ctrl+C`.

### Stage A: browser key only, stub mode

Set `GOOGLE_MODE=stub` and `GOOGLE_MAPS_BROWSER_KEY` (and `GOOGLE_MAPS_MAP_ID` if you have one). Restart.

- [ ] http://localhost:8080/schools/map shows a real Google map with school markers.
- [ ] The **Show planning areas** switch draws the planning-area boundaries; the markers stay.
- [ ] A school → **View nearby facilities** → map: "(stub)" facilities on the real map.
- [ ] A school → **Get directions**, start `579767` (works with the OneMap stub): a straight route line on the map.
- [ ] The footer still shows the **Demo data (stub)** badge.
- [ ] The browser console (F12 → Console) shows no red Google error.

### Stage B: add the server key, live mode

Add `GOOGLE_MAPS_SERVER_KEY`, set `GOOGLE_MODE=live` (and `ONEMAP_MODE=live` if you want real address search). Restart.

- [ ] The **Demo data (stub)** badge is gone.
- [ ] `/schools/map`: map and planning-area switch work.
- [ ] A school → **View nearby facilities**: real libraries and tuition centres within 3 km, no "(stub)" names.
- [ ] Open one facility: phone, website and opening hours where Google has them.
- [ ] **Get directions** in WALK, DRIVE and TRANSIT: real steps; TRANSIT steps name the lines; the route line follows the roads.
- [ ] Travel-time filter (`/schools/filter`, set a starting point on the right first): try WALK, 15 min first (it routes only the few schools within reach), then a longer one.
- [ ] Log in → **Recommendations** with a starting point: the commute column shows minutes, not "Not available".

### Check the usage

Expected cost of each action (repeats inside the cache time cost nothing):

| Action | App counter | Google |
|:--:|:--:|:--:|
| Open a map page (live mode) | `map-loads` +1 | 1 map load |
| One school's nearby facilities | `places-search` +2 | 1 SearchNearby + 1 SearchText |
| Open one facility | `place-details` +1 | 1 GetPlace |
| Directions in one mode | `routes` +1 | 1 ComputeRoutes |
| Travel-time filter | `route-matrix-elements` + schools within reach | same number of elements |
| Recommendations | `route-matrix-elements` + up to 20 | same number of elements |

- **In Google Cloud:** **Google Maps Platform → Metrics** (requests per API and response code) and **Google Maps Platform → Quotas** (today's usage against each daily cap). New numbers can take a few minutes to appear.
- **In the app:** add `H2_CONSOLE=true` to `.env`, restart, open http://localhost:8080/h2-console, JDBC URL `jdbc:h2:file:./.local/h2/dev` (demo profile: `./.local/h2/demo`), user `sa`, empty password, and run `SELECT * FROM external_usage ORDER BY usage_day DESC, sku;`. Remove `H2_CONSOLE=true` afterwards (the README explains why).
- The two should roughly agree. If Google shows much more than the app, someone else is using the keys: rotate them (see [Security](#security)).

## Troubleshooting

| Problem | Likely cause and fix |
|:--:|:--:|
| "Map unavailable" on every map page | `GOOGLE_MAPS_BROWSER_KEY` is empty or misspelled, or the app was not restarted. Otherwise Google refused the key: read the browser console (rows below). Also check the daily map-load limit: the app's own (terminal WARN "Today's Google map-load limit is used up") or Google's (`OverQuotaMapError` in the console). |
| `RefererNotAllowedMapError` in the browser console | The page address is not in the browser key's website list. Add `http://localhost:8080/*` and `http://127.0.0.1:8080/*` (use the same host you typed in the address bar), wait a few minutes, reload. |
| `ApiNotActivatedMapError` | Maps JavaScript API is not enabled in the project (step 3). |
| `ApiTargetBlockedMapError` | The browser key's API restrictions do not include Maps JavaScript API (step 4). |
| `InvalidKeyMapError` or `BillingNotEnabledMapError` | Wrong or deleted key, or the project has no billing account (step 2). |
| Warning "`google.maps.Marker` is deprecated" | Expected without a Map ID; markers still work. |
| App stops at start-up: "GOOGLE_MODE=live needs GOOGLE_MAPS_SERVER_KEY in .env" | Add the server key, or set `GOOGLE_MODE=stub`. The demo profile with no `.env` at all defaults to live and stops the same way. |
| App stops at start-up: "app.external.*.mode must be 'stub' or 'live'" | `GOOGLE_MODE` or `ONEMAP_MODE` has quotes, a space or a typo. |
| "temporarily unavailable" for facilities, directions or the travel-time filter; "Not available" commute in recommendations | (1) The app's daily limit is reached. (2) Google refused: the API is not enabled, the server key's API restrictions lack Routes API or Places API (New), or billing is off. (3) Google's daily cap is reached (it resets at midnight Pacific Time). (4) A short Google error or timeout: retry after a minute. See the next row for how to tell them apart. |
| Which cause? | Directions and recommendations write a WARN line in the terminal. A lower-case name after "Google" (`routes`, `route-matrix-elements`) means the app's own limit. "Google Routes" or "Google Places" means Google refused or failed. The facility pages and the travel-time filter write no log line. For Google's reason, open **Google Maps Platform → Metrics** and look at the response codes for that API. |
| Facilities still named "(stub)", footer badge still there | The app is still in stub mode: `GOOGLE_MODE` is not `live`, or the app was not restarted. |
| Edits to `.env` have no effect | Restart the app. Check that `.env` is next to `pom.xml`, not in `src/` or your home folder. On Windows, check it is not named `.env.txt` (`Get-ChildItem -Force`); on Mac, `ls -a`. Remove quotes and trailing spaces. Check for an environment variable with the same name in your shell or IntelliJ run configuration. |
| Changes in Cloud Console have no effect | Restriction and key changes can take a few minutes. Reload the page after waiting. |

## Security

- **If a key leaks** (pushed to GitHub, posted in a chat, visible in a screenshot), tell E at once. Our GitHub repo is public, and a key stays in git history even after a later commit removes it.
- **Rotate the key:** **APIs & Services → Credentials** → the key → **Rotate key** (older screens: Regenerate key). Google creates a new key with the same restrictions, and both keys work until you click **Delete the previous key**. Put the new key into `.env` on every holder's laptop and the demo laptop, then delete the previous key. If the key was public, delete it at once instead, then create a new one as in step 4.
- **Who may hold keys:** only the people in [Who does this](#who-does-this). The browser key is visible in every map page, which is normal; its restrictions protect it. The server key must never appear in a page, a URL, a log or a screenshot.
- **Keep the repo free of keys.** Keys live only in `.env`. Never put a key in `application*.yml`, Java or JavaScript code, tests, docs or `.env.example`. CI does not need keys: the `test` profile forces every external service to stub mode, and the live Google code is tested against a mock server.
- **Do not widen restrictions** to fix an error. Find the cause in [Troubleshooting](#troubleshooting) instead.

## Sources

Google documentation used (checked 3 Oct 2026):

- Pricing and free amounts per SKU: https://developers.google.com/maps/billing-and-pricing/pricing
- Billing overview (free usage per billing account): https://developers.google.com/maps/billing-and-pricing/overview
- SKU triggers: https://developers.google.com/maps/billing-and-pricing/sku-details
- Cost control, quotas, budgets do not cap usage: https://developers.google.com/maps/billing-and-pricing/manage-costs
- Routes API usage and billing (elements, matrix limits): https://developers.google.com/maps/documentation/routes/usage-and-billing
- Routes API routing preference (default `TRAFFIC_UNAWARE`): https://developers.google.com/maps/documentation/routes/reference/rest/v2/RoutingPreference
- Places API (New) usage and billing: https://developers.google.com/maps/documentation/places/web-service/usage-and-billing
- Field lists per SKU: https://developers.google.com/maps/documentation/places/web-service/nearby-search, https://developers.google.com/maps/documentation/places/web-service/text-search, https://developers.google.com/maps/documentation/places/web-service/place-details
- Maps JavaScript API usage and billing: https://developers.google.com/maps/documentation/javascript/usage-and-billing
- Maps JavaScript API error messages: https://developers.google.com/maps/documentation/javascript/error-messages
- Advanced Markers and `DEMO_MAP_ID`: https://developers.google.com/maps/documentation/javascript/advanced-markers/start
- Map IDs: https://developers.google.com/maps/documentation/get-map-id
- API key security: https://developers.google.com/maps/api-security-best-practices
- Creating, restricting and rotating API keys: https://docs.cloud.google.com/docs/authentication/api-keys
- Daily quotas reset at midnight Pacific Time: https://docs.cloud.google.com/docs/quotas/overview
- Budgets and alerts: https://docs.cloud.google.com/billing/docs/how-to/budgets

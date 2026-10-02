# Routes and dialog map

This file maps the Lab 2 dialog map (`docs/design/SC2006-Lab2-5-InitialDialogMap.drawio`, page **Initial Dialog Map (revised)**)
to URLs, UI classes and templates. It is the contract between pages and UI classes: when you add or change a route, update this file in the same PR.

- **DM** = dialog-map state id (DM-01 to DM-24, in the order the brief lists the states).
- **Login** = the route is protected by `AuthInterceptor`. A guest is sent to `/login?next=<path>`.
- The login-required paths are listed in `WebConfig.LOGIN_REQUIRED_PATHS`: `/profile/**`, `/shortlist/**`, `/compare/**`, `/plan/**`, `/recommendations/**`, `/logout`, and `POST /schools/*/shortlist`.
- Every state below is built (round 2, October 2026). Google runs in stub mode by default, so routes and facilities are made-up demo data until a key holder sets `GOOGLE_MODE=live`.
- Each template keeps its DM id in a comment at the top. `PagesSmokeTest` opens every GET route: public pages answer 200 for a guest, login-only pages send a guest to `/login?next=<page>` and answer 200 for a member.

## Rules

- Search, filter, sort and paging use **GET** with query parameters, so a URL can be bookmarked, Back works, and the same URL always gives the same order (by name, then school code).
- Every change is a **POST followed by a redirect** to a GET page, so Refresh never repeats it. This includes `POST /recommendations`, which stores the results in the servlet session and redirects to `/recommendations/results/{id}`.
- `next` (login) and `returnTo` (location forms) are accepted only when they start with a single `/` (never `//` or `http`), so they cannot send the user to another site.
- The only JSON endpoint is `GET /api/districts` (`application/geo+json`, Feature properties `code` and `name`). It is a `@GetMapping` + `@ResponseBody` method inside `SchoolMapUI` (a `@Controller`); `@RestController` is not allowed (ArchitectureTest rule 2c). Other map data is embedded in the page as JSON.

## States → routes

| DM | State | Route | UI class | Template | Login |
|:--:|:--:|:--:|:--:|:--:|:--:|
| DM-01 | HomePage (Guest) | `GET /` | `HomeUI` | `home.html` (guest block) | – |
| DM-02 | LoginForm | `GET /login?next=&add=`, `POST /login` (a failed login redirects back to `GET /login` keeping `next`, `add` and the identifier, DC-46) | `LoginUI` | `login.html` | – |
| DM-03 | RegistrationForm | `GET /register`, `POST /register` | `RegisterUI` | `register.html` | – |
| DM-04 | HomePage (Authenticated) | `GET /` (member block) | `HomeUI` | `home.html` | – |
| DM-05 | UserProfile | `GET /profile`, `POST /profile` (one form for every field; several address matches → choose with `homeChoice`, DC-48) | `UserProfileUI` | `profile.html` | yes |
| DM-06 | LogoutConfirmation | `GET /logout` (confirm page), `POST /logout` | `UserProfileUI` | `logout-confirm.html` | yes |
| DM-07 | SchoolSearch | `GET /schools` | `SchoolSearchUI` | `school-search.html` | – |
| DM-08 | SearchResults | `GET /schools?q=&<filter params>&sort=NAME_ASC\|DISTANCE_ASC\|COMMUTE_ASC&page=` (filter params in `FilterParams`, DC-42; the travel-time filter runs only with `travel=1`) | `SchoolSearchUI` | `school-search.html` | – |
| DM-09 | SchoolDetails | `GET /schools/{code}` | `SchoolDetailsUI` | `school-details.html` | – |
| DM-10 | SchoolFilter | `GET /schools/filter?<current params>` (the form submits `GET /schools`) | `SchoolFilterUI` | `school-filter.html` | – |
| DM-11 | SchoolMap | `GET /schools/map?<same params as /schools>`, `GET /api/districts` | `SchoolMapUI` | `school-map.html` | – |
| DM-12 | NearbyFacilities | `GET /schools/{code}/facilities?type=&radiusKm=` | `NearbyFacilitiesUI` | `nearby-facilities.html` | – |
| DM-13 | FacilityFilter | same route as DM-12, filter panel | `NearbyFacilitiesUI` | `nearby-facilities.html` | – |
| DM-14 | FacilityDetails | `GET /facilities/{placeId}?from={code}` (unknown or malformed id → 404, DC-62) | `FacilityDetailsUI` | `facility-details.html` | – |
| DM-15 | FacilityMap | `GET /schools/{code}/facilities/map?type=&radiusKm=` (same params as DM-12) | `FacilityMapUI` | `facility-map.html` | – |
| DM-16 | Shortlist | `GET /shortlist` | `ShortlistUI` | `shortlist.html` | yes |
| DM-17 | ChoicePlanner | `GET /plan` | `ChoicePlanUI` | `choice-plan.html` | yes |
| DM-18 | Compare Schools | `GET /compare?codes=a,b,c` (also `codes=a&codes=b`; 2–4 shortlisted schools, else back to `/shortlist` with the message, DC-66) | `ComparisonUI` | `compare.html` | yes |
| DM-19 | DirectionsInput | `GET /directions?to=school:{code}` or `to=facility:{placeId}`, optional `from=<local page for Cancel>` (DC-60); `POST /location`, `/location/device`, `/location/choose`, `/location/clear` (DC-23) | `DirectionsUI` | `directions-input.html` | – |
| DM-20 | DirectionsLoading | loading overlay while the directions request runs | `DirectionsUI` | `fragments/loading.html` | – |
| DM-21 | Directions | `GET /directions?to=&mode=WALK\|DRIVE\|TRANSIT&from=` (`mode` ignores case; no start, no route or service down → `directions-input.html` with the message, DC-60) | `DirectionsUI` | `directions.html` | – |
| DM-22 | RecommendationCriteria | `GET /recommendations` (optional draft criteria in the query string: `psleScore`, `postingGroup`, `travelMode`, `maxCommuteMin`, `preferredCCAs`, `preferredProgrammes`) | `RecommendationUI` | `recommendation-criteria.html` | yes |
| DM-23 | RecommendationLoading | loading overlay while `POST /recommendations` runs | `RecommendationUI` | `fragments/loading.html` | yes |
| DM-24 | Recommendation | `POST /recommendations` → redirect `GET /recommendations/results/{id}` | `RecommendationUI` | `recommendation-results.html` | yes |

## Actions (POST) and data endpoints

Every route below is built.

| Route | UI class | Design operation | Control call | Owner |
|:--:|:--:|:--:|:--:|:--:|
| `POST /login` (fields `identifier`, `password`, hidden `next`, `add`) | `LoginUI` | `submitLogin` | `AuthController.login`; with `add`, also `ShortlistController.addSchool` (DC-08) | A |
| `POST /register` | `RegisterUI` | `submitRegistration` | `AccountController.register` | A |
| `POST /profile` (every profile field; `homeChoice=<index>` picks one of several address matches) | `UserProfileUI` | `submitProfile` | `ProfileController.saveProfile` (DC-47, DC-48) | A |
| `POST /logout` | `UserProfileUI` | `promptLogoutConfirmation` (confirmed) | `AuthController.logout` | A |
| `POST /schools/{code}/shortlist` (login) | `ShortlistUI` (DC-38) | `selectAddToShortlist` | `ShortlistController.addSchool` | F |
| `POST /shortlist/{code}/remove` | `ShortlistUI` | `selectRemoveSchool` | `ShortlistController.removeSchool` | F |
| `POST /plan/choices` (fields `code`, `rank`) | `ChoicePlanUI` | `addChoice` (DC-06) | `ChoicePlanController.addChoice` | F |
| `POST /plan/choices/{code}/move?dir=up\|down` | `ChoicePlanUI` | `reorderChoice` | `ChoicePlanController.reorderChoices` | F |
| `POST /plan/choices/{code}/remove` | `ChoicePlanUI` | `removeChoice` (DC-06) | `ChoicePlanController.removeChoice` | F |
| `POST /plan/score` ("Use my current score") | `ChoicePlanUI` | `useProfileScore` (DC-65) | `ChoicePlanController.useProfileScore` | F |
| `POST /location` (fields `address`, `returnTo`) | `DirectionsUI` | `enterManualLocation` | `LocationController.findCandidates` (one hit → set; up to 5 → pick list) | D |
| `POST /location/device` (fields `latitude`, `longitude`, `returnTo`) | `DirectionsUI` | `requestStartingLocation` | `LocationController.getDeviceLocation` | D |
| `POST /location/choose` (fields `index`, `returnTo`) | `DirectionsUI` | `selectLocationCandidate` (DC-23) | – (picks from the stored candidates) | D |
| `POST /location/clear` (fields `returnTo`, optional `candidatesOnly=true` = "None of these", DC-60) | `DirectionsUI` | `clearLocation` (DC-23) | – | D |
| `POST /recommendations` | `RecommendationUI` | `submitCriteria` | `RecommendationController.recommend` | B |
| `GET /recommendations/results/{id}` | `RecommendationUI` | `displayRecommendations` | – (reads the stored results) | B |
| `GET /api/districts` (`application/geo+json` via `@ResponseBody`) | `SchoolMapUI` | `toggleDistrictLayer` | `MapController.toggleDistricts` (DC-16, DC-59) | D |
| `GET /about/data` (data sources page, not a dialog-map state; footer link) | `HomeUI` | `displayDataSources` (DC-68) | `SchoolDataController.getActiveManifest`, `getActiveDataset` | B |
| `GET /actuator/health`, `GET /actuator/info` (Spring Boot Actuator; every other `/actuator/*` is 404) | – | – | – | A |

The chosen reference location (from `/location*`) is kept in the servlet session (the `JSESSIONID` cookie). That session holds page state only; login uses the `SM_SESSION` cookie.

## UI class operations

Handler methods in a UI class are named after the design's input operations. The Transitions table quotes the Lab 2 labels; where a design change altered a signature, the current one follows in brackets. The other design operations are links, form fields, browser-side actions, or parts of the page template (display operations become `th:fragment` names or page sections).

| UI class | Handlers in code (route) | Other design operations |
|:--:|:--:|:--:|
| `HomeUI` | `displayHomePage` (`GET /`), `displayDataSources` (`GET /about/data`, DC-68) | links: `selectLogin`, `selectRegister`, `selectSearchSchools`, `selectGetRecommendations`, `selectProfile` |
| `LoginUI` | `displayLoginForm` (`GET /login`), `submitLogin` (`POST /login`) | `showLoginError`, `highlightInvalidFields`, `displayServiceUnavailable` |
| `RegisterUI` | `displayRegistrationForm` (`GET /register`), `submitRegistration` (`POST /register`) | `highlightInvalidFields`, `displayMessage` |
| `UserProfileUI` | `displayUserProfile` (`GET /profile`), `submitProfile` (`POST /profile`; `submitPreferences` merged into it, DC-48), `selectLogOut` (`GET /logout`), `promptLogoutConfirmation` (`POST /logout`) | – |
| `SchoolSearchUI` | `submitSearch` (`GET /schools`) | fields: `selectSortOrder` (`sort`, DC-56), `loadNextPage` (`page`); links: `selectViewOnMap`, `selectSchool`; template: `displaySearchField`, `displayResults`, `displayNoMatches`, `highlightInvalidFields`, `displayServiceUnavailable` |
| `SchoolFilterUI` | `displayFilterCategories` (`GET /schools/filter`) | fields: `selectAttributes`, `enterPsleScore`, `selectProximity`, `selectTransportation`; form submit: `confirmFilters`; link: `clearAllFilters`; template: `highlightInvalidFields`, `displayNoMatches` |
| `SchoolDetailsUI` | `selectSchool` (`GET /schools/{code}`) | buttons: `selectAddToShortlist` (handled by `ShortlistUI`, DC-38), `selectViewNearbyFacilities`, `selectGetDirections`; template: `displaySchoolDetails`, `displayUnavailable`, `displayAlreadyShortlisted` |
| `SchoolMapUI` | `displaySchoolMarkers` (`GET /schools/map`) | browser: `toggleDistrictLayer`, `panAndZoom`, `selectMarker`, `showSchoolInfo`; template: `displayMapUnavailable` |
| `NearbyFacilitiesUI` | `applyFacilityFilter` (`GET /schools/{code}/facilities`) | links: `selectFacility`, `selectViewOnMap`; template: `displayFacilities`, `displayFacilityFilters`, `displayNoNearbyFacilities`, `displayServiceUnavailable` |
| `FacilityDetailsUI` | `displayFacilityDetails` (`GET /facilities/{placeId}`) | link: `selectGetDirections`; template: `displayUnavailable` |
| `FacilityMapUI` | `displayFacilityMarkers` (`GET /schools/{code}/facilities/map`) | browser: `panAndZoom`, `selectMarker`; template: `displayMapUnavailable` |
| `ShortlistUI` | `displayShortlist` (`GET /shortlist`), `selectAddToShortlist` (`POST /schools/{code}/shortlist`), `selectRemoveSchool` (`POST /shortlist/{code}/remove`) | links: `selectCompare`, `selectPlanChoices`; template: `displayEmptyShortlist`, `displayServiceUnavailable` |
| `ChoicePlanUI` | `displayPlan` (`GET /plan`), `addChoice` (DC-06), `reorderChoice`, `removeChoice` (DC-06) (`POST /plan/choices…`), `useProfileScore` (`POST /plan/score`, DC-65) | template: `displayAdmissionChance`, `displayRiskWarnings` |
| `ComparisonUI` | `displayComparison` (`GET /compare`) | link: `selectSchool`; template: `highlightDifferences` |
| `DirectionsUI` | `selectGetDirections` (`GET /directions?to=`), `selectTravelMode` (`GET /directions?to=&mode=`), `enterManualLocation` (`POST /location`), `requestStartingLocation` (`POST /location/device`), `selectLocationCandidate` (`POST /location/choose`, DC-23), `clearLocation` (`POST /location/clear`) | template: `displayDirections`, `displayLoadingIndicator`, `animateSpinner`, `showNoRouteFoundMessage`, `displayDirectionsUnavailable`, `displayInvalidLocation` |
| `RecommendationUI` | `displayCriteriaForm` (`GET /recommendations`), `submitCriteria` (`POST /recommendations`), `displayRecommendations` (`GET /recommendations/results/{id}`) | link: `selectSchool`; template: `displayLoadingIndicator`, `animateSpinner`, `showRecommendationUnavailable`, `displayReasons` |

## Transitions

T-01 to T-64 are the 64 arrows of the revised dialog map, grouped by the state they start from. One arrow can carry several events (for example a success and an error case); they stay in one row. T-65 onwards are added by design changes. "Same page" means the route re-renders the state with a message.

| T | From | Event [guard] / action | To | Route | Source |
|:--:|:--:|:--:|:--:|:--:|:--:|
| T-01 | (start) | open the app | HomePage (Guest) | `GET /` | Lab 2 |
| T-02 | HomePage (Guest) | select Login | LoginForm | `GET /login` | Lab 2 |
| T-03 | HomePage (Guest) | select Register | RegistrationForm | `GET /register` | Lab 2 |
| T-04 | HomePage (Guest) | select search schools | SearchResults | `GET /schools?q=<term>` (home search box) | Lab 2 |
| T-05 | LoginForm | `submitLogin()` [valid credentials] / `login(identifier, password)` | HomePage (Authenticated) | `POST /login` → redirect `/` (or to `next`, T-77) | Lab 2 |
| T-06 | LoginForm | `submitLogin()` [invalid credentials] / `showLoginError()`; [missing input] / `highlightInvalidFields(errors)`; [service unavailable] / `displayServiceUnavailable()` | LoginForm | `POST /login` → redirect `GET /login?next=&add=` with the messages (DC-46) | Lab 2 |
| T-07 | LoginForm | click cancel | HomePage (Guest) | `GET /` | Lab 2 |
| T-08 | RegistrationForm | `submitRegistration()` [valid input] | LoginForm | `POST /register` → redirect `/login` | Lab 2 |
| T-09 | RegistrationForm | `submitRegistration()` [invalid input] / `highlightInvalidFields(errors)` | RegistrationForm | `POST /register` → same page | Lab 2 |
| T-10 | RegistrationForm | click cancel | HomePage (Guest) | `GET /` | Lab 2 |
| T-11 | HomePage (Authenticated) | select search schools | SearchResults | `GET /schools?q=<term>` | Lab 2 |
| T-12 | HomePage (Authenticated) | click profile / `getProfile(sessionId)` | UserProfile | `GET /profile` | Lab 2 |
| T-13 | HomePage (Authenticated) | click get recommendations | RecommendationCriteria | `GET /recommendations` | Lab 2 |
| T-14 | UserProfile | submit profile changes / `saveProfile(profile)` [now `saveProfile(sessionId, profile)`, DC-27] | UserProfile | `POST /profile` → redirect `/profile` | Lab 2 |
| T-15 | UserProfile | click logout | LogoutConfirmation | `GET /logout` | Lab 2 |
| T-16 | UserProfile | click view shortlisted schools / `getShortlistedSchools(sessionId)` | Shortlist | `GET /shortlist` | Lab 2 |
| T-17 | LogoutConfirmation | confirm logout / `logout(sessionId)` | HomePage (Guest) | `POST /logout` → redirect `/` with "You have logged out" (DC-03) | Lab 2 |
| T-18 | LogoutConfirmation | cancel logout | UserProfile | `GET /profile` | Lab 2 |
| T-19 | SchoolSearch | `submitSearch(string)` / `searchSchools(string)` | SearchResults | `GET /schools?q=<term>` | Lab 2 |
| T-20 | SchoolSearch | `submitSearch(string)` [invalid input] / `highlightInvalidFields(errors)`; [no match] / `displayNoMatches()` | SchoolSearch | `GET /schools?q=<term>` → same page | Lab 2 |
| T-21 | SearchResults | select search | SchoolSearch | `GET /schools` | Lab 2 |
| T-22 | SearchResults | select open filters | SchoolFilter | `GET /schools/filter?<current params>` | Lab 2 |
| T-23 | SearchResults | click view on map / `showSchoolsOnMap(results)` | SchoolMap | `GET /schools/map?<current params>` | Lab 2 |
| T-24 | SearchResults | select view details on a school / `getSchoolDetails(schoolCode)` | SchoolDetails | `GET /schools/{code}` | Lab 2 |
| T-25 | SearchResults | [service unavailable] / `displayServiceUnavailable()` | SearchResults | same page | Lab 2 |
| T-26 | SearchResults | request next page / `loadNextPage()`; change sort order / `sortResults(order)` [now `sortResults(results, order)`, DC-15, DC-56] | SearchResults | `GET /schools?q=&sort=&page=` | Lab 2 |
| T-27 | SchoolDetails | back to results | SearchResults | browser Back, or `GET /schools?<previous params>` | Lab 2 |
| T-28 | SchoolDetails | click get directions | DirectionsInput | `GET /directions?to=school:{code}` | Lab 2 |
| T-29 | SchoolDetails | click view nearby facilities / `getNearbyFacilities(school)` | NearbyFacilities | `GET /schools/{code}/facilities` | Lab 2 |
| T-30 | SchoolDetails | click add to shortlist [logged in] / `addSchool()`; [already in shortlist] / `displayAlreadyShortlisted()` | SchoolDetails | `POST /schools/{code}/shortlist` → redirect `/schools/{code}` | Lab 2 |
| T-31 | SchoolDetails | click add to shortlist [not logged in] | LoginForm | `POST /schools/{code}/shortlist` → redirect `/login?next=/schools/{code}&add={code}` (DC-08) | Lab 2 |
| T-32 | SchoolFilter | `confirmFilters()` / `applyFilters(filters)`; `clearAllFilters()` / `clearFilters()` [now `applyFilters(results, filters)`, `clearFilters(results)`, DC-15] | SearchResults | `GET /schools?<filter params>`; clear all: `GET /schools?q=<term>` (DC-15) | Lab 2 |
| T-33 | SchoolFilter | `confirmFilters()` [invalid filter value] / `highlightInvalidFields(errors)`; [no school matches] / `displayNoMatches()` | SchoolFilter | same page | Lab 2 |
| T-34 | SchoolMap | back to results | SearchResults | `GET /schools?<same params>` | Lab 2 |
| T-35 | SchoolMap | toggle district layer / `toggleDistricts(visible)` | SchoolMap | in the browser; data from `GET /api/districts` (DC-16) | Lab 2 |
| T-36 | SchoolMap | select a school marker / `showSchoolInfo()` | SchoolMap | in the browser (info window) | Lab 2 |
| T-37 | NearbyFacilities | click view on map / `showFacilitiesOnMap(facilities)` | FacilityMap | `GET /schools/{code}/facilities/map?<params>` | Lab 2 |
| T-38 | NearbyFacilities | click a facility | FacilityDetails | `GET /facilities/{placeId}?from={code}` | Lab 2 |
| T-39 | NearbyFacilities | click open filters | FacilityFilter | same page, filter panel | Lab 2 |
| T-40 | NearbyFacilities | [no facility found] / `displayNoNearbyFacilities()`; [service unavailable] / `displayServiceUnavailable()` | NearbyFacilities | same page | Lab 2 |
| T-41 | FacilityFilter | click apply filter / `filterFacilities(criteria)` [now `filterFacilities(school, criteria)`, DC-30] | NearbyFacilities | `GET /schools/{code}/facilities?type=&radiusKm=` | Lab 2 |
| T-42 | FacilityFilter | click apply filter [no facility matches] / `displayNoNearbyFacilities()` | FacilityFilter | same page | Lab 2 |
| T-43 | FacilityDetails | click get directions | DirectionsInput | `GET /directions?to=facility:{placeId}` | Lab 2 |
| T-44 | FacilityDetails | back to facilities | NearbyFacilities | `GET /schools/{code}/facilities` | Lab 2 |
| T-45 | FacilityMap | back to facilities | NearbyFacilities | `GET /schools/{code}/facilities` | Lab 2 |
| T-46 | Shortlist | remove school / `removeSchool(sessionId, schoolCode)`; [shortlist empty] / `displayEmptyShortlist()`; [service unavailable] / `displayServiceUnavailable()` | Shortlist | `POST /shortlist/{code}/remove` → redirect `/shortlist` | Lab 2 |
| T-47 | Shortlist | select schools and click compare selected schools | Compare Schools | `GET /compare?codes=a,b,c` | Lab 2 |
| T-48 | Shortlist | click plan choices / `getPlan(sessionId)` | ChoicePlanner | `GET /plan` | Lab 2 |
| T-49 | Shortlist | back to profile | UserProfile | `GET /profile` | Lab 2 |
| T-50 | ChoicePlanner | click add, remove or reorder choice / `addChoice()`, `removeChoice()` or `reorderChoices()` | ChoicePlanner | `POST /plan/choices`, `POST /plan/choices/{code}/remove`, `POST /plan/choices/{code}/move?dir=` → redirect `/plan` | Lab 2 |
| T-51 | ChoicePlanner | click back to shortlist | Shortlist | `GET /shortlist` | Lab 2 |
| T-52 | Compare Schools | click back to shortlist | Shortlist | `GET /shortlist` | Lab 2 |
| T-53 | DirectionsInput | click get directions / `getDirections(destination, mode)` [now `getDirections(origin, destination, mode)`, DC-05] | DirectionsLoading | `GET /directions?to=&mode=` (the overlay shows while it loads) | Lab 2 |
| T-54 | DirectionsInput | click cancel [from school details] | SchoolDetails | `GET /schools/{code}` | Lab 2 |
| T-55 | DirectionsInput | click cancel [from facility details] | FacilityDetails | `GET /facilities/{placeId}` | Lab 2 |
| T-56 | DirectionsInput | [invalid starting location] / `displayInvalidLocation()`; [no starting point when Show route is pressed] | DirectionsInput | `POST /location` → redirect back with the error; `GET /directions?to=&mode=` without a start renders `directions-input.html` with "Set a starting point first" (DC-60) | Lab 2 |
| T-57 | DirectionsLoading | [route returned] | Directions | `GET /directions?to=&mode=` renders `directions.html` | Lab 2 |
| T-58 | DirectionsLoading | [no route found] / `showNoRouteFoundMessage()` | DirectionsInput | same request renders `directions-input.html` with the message | Lab 2 |
| T-59 | DirectionsLoading | [routing request failed] / `displayDirectionsUnavailable()` | DirectionsInput | same request renders `directions-input.html` with "Directions are temporarily unavailable" (DC-60) | Lab 2 |
| T-60 | Directions | click back | DirectionsInput | `GET /directions?to=` | Lab 2 |
| T-61 | RecommendationCriteria | click submit criteria / `recommend(criteria)` | RecommendationLoading | `POST /recommendations` (overlay) | Lab 2 |
| T-62 | RecommendationLoading | [recommendations returned] | Recommendation | redirect `GET /recommendations/results/{id}` | Lab 2 |
| T-63 | RecommendationLoading | [service unavailable] / `showRecommendationUnavailable()` | RecommendationCriteria | redirect `GET /recommendations` with the message | Lab 2 |
| T-64 | Recommendation | click refine criteria | RecommendationCriteria | `GET /recommendations` | Lab 2 |
| T-65 | Recommendation | select a school | SchoolDetails | `GET /schools/{code}` | DC-08 |
| T-66 | Compare Schools | select a school | SchoolDetails | `GET /schools/{code}` | DC-08 |
| T-67 | SchoolMap | select a school marker, then its link | SchoolDetails | `GET /schools/{code}` | DC-08 |
| T-68 | FacilityMap | select a facility marker, then its link | FacilityDetails | `GET /facilities/{placeId}?from={code}` | DC-08 |
| T-69 | any state | navbar Home | HomePage (Guest or Authenticated) | `GET /` | DC-08 |
| T-70 | any state | navbar Search | SchoolSearch | `GET /schools` | DC-08 |
| T-71 | any state | navbar Map | SchoolMap | `GET /schools/map` | DC-08 |
| T-72 | any state | navbar Shortlist | Shortlist (guest → LoginForm) | `GET /shortlist` | DC-08 |
| T-73 | any state (logged in) | navbar Profile | UserProfile (a session that ended meanwhile → LoginForm) | `GET /profile` | DC-08 |
| T-74 | any state (logged in) | navbar Logout | LogoutConfirmation | `GET /logout` | DC-08 |
| T-75 | any state (guest) | navbar Login | LoginForm | `GET /login` | DC-08 |
| T-76 | LoginForm | `submitLogin()` [valid credentials, `add=<code>`] / `login()`, then `addSchool(sessionId, code)` | SchoolDetails | `POST /login` → redirect `/schools/{code}` with "Added to shortlist" | DC-08 |
| T-77 | LoginForm | `submitLogin()` [valid credentials, `next=<path>`] / `login()` | the page in `next` | `POST /login` → redirect `<path>` | DC-03, DC-08 |
| T-78 | any login-required page | [guest, or session expired] | LoginForm | redirect `/login?next=<path>` | DC-03, DC-07 |
| T-79 | DirectionsInput, SchoolFilter, RecommendationCriteria | enter an address / `enterManualLocation(address)`; one match sets the location, several show up to 5 choices | same page (`returnTo`) | `POST /location` → redirect `returnTo` | DC-11, DC-23 |
| T-80 | DirectionsInput, SchoolFilter, RecommendationCriteria | pick one of the choices / `selectLocationCandidate(index)` | same page (`returnTo`) | `POST /location/choose` → redirect `returnTo` | DC-23 |
| T-81 | DirectionsInput, SchoolFilter, RecommendationCriteria | click "Use my location" / `requestStartingLocation()`; the browser asks permission first (NFR-SEC-06) | same page (`returnTo`) | `POST /location/device` → redirect `returnTo` | DC-11, DC-23 |
| T-82 | DirectionsInput, SchoolFilter, RecommendationCriteria | clear the location; "None of these" drops only the match list (`candidatesOnly=true`) | same page (`returnTo`) | `POST /location/clear` → redirect `returnTo` | DC-23, DC-60 |
| T-83 | UserProfile | enter a home address with several matches, then pick one | UserProfile | `POST /profile` → same page with the choice list; `POST /profile` with `homeChoice=<index>` → redirect `/profile` | DC-48 |
| T-84 | ChoicePlanner | "Use my current score" / `useProfileScore(sessionId)` [profile score changed since the plan was made] | ChoicePlanner | `POST /plan/score` → redirect `/plan` | DC-65 |
| T-85 | SchoolMap | click a filter chip's remove link, "Back to results" or "Filters" | SchoolMap, SearchResults or SchoolFilter | `GET /schools/map?<params without that filter>`, `GET /schools?<same params>`, `GET /schools/filter?<same params>` | DC-15 |
| T-86 | NearbyFacilities, FacilityDetails | click Directions on a facility | DirectionsInput | `GET /directions?to=facility:{placeId}&from=<this page>` | DC-60 |

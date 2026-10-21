# UI style guide

SchoolMatch SG uses Bootstrap 5.3 (from the CDN in `layout.html`) plus one stylesheet,
`src/main/resources/static/css/app.css`. That file holds the design tokens, the Bootstrap overrides and a small
set of app components. This guide lists what to use in page templates.

Aim: clean, short and clear pages with vivid colour. Each page has one obvious main action.

## Tokens

All tokens are CSS custom properties on `:root`. Use them in `app.css`, not in templates.

| Group | Token | Value | Use |
|---|---|---|---|
| Brand | `--sm-primary` | `#4f46e5` | Links, the one solid button, active states (6.3:1 on white) |
| | `--sm-primary-hover` / `-active` | `#4338ca` / `#3730a3` | Hover and pressed |
| | `--sm-primary-soft` | `#eef0ff` | Tinted backgrounds (soft buttons, chips, range chips) |
| | `--sm-primary-ink` | `#3730a3` | Text on `--sm-primary-soft` |
| | `--sm-primary-border` | `#c7cbfe` | Borders of tinted items |
| | `--sm-accent` / `--sm-accent-ink` | `#22d3ee` / `#083344` | Hero button and decoration only, never text on white |
| | `--sm-gradient` | indigo → violet | Hero, brand mark, featured tile, score meter |
| Neutral | `--sm-ink` | `#0f172a` | Headings |
| | `--sm-text` | `#1e293b` | Body text |
| | `--sm-muted` | `#475569` | Secondary text (7:1 on white and on the page) |
| | `--sm-line` / `--sm-line-strong` | `#e2e8f0` / `#cbd5e1` | Card, table and divider lines |
| | `--sm-control-border` | `#7a8599` | Inputs, selects, checkboxes, radios (3.7:1, WCAG 1.4.11) |
| | `--sm-bg` | `#f4f6fb` | Page background |
| | `--sm-surface` / `--sm-surface-2` | `#fff` / `#f8fafc` | Cards / table heads and quiet panels |
| Status | `--sm-safe` | `#15803d` | SAFE (white text, 5.0:1) |
| | `--sm-match` | `#f59e0b` | MATCH (dark text `--sm-match-on`, 8.1:1) |
| | `--sm-reach` | `#dc2626` | REACH, destructive buttons (white text, 4.8:1) |
| | `--sm-danger-text` | `#b91c1c` | Error text |
| | `--sm-warning-bg` / `-border` / `-ink` | amber tints | Warning boxes, TEST VALUES notice, "Not applied" chips |
| Map pins | `--sm-pin-school`, `-library`, `-tuition`, `-start` | `#0d6efd`, `#198754`, `#fd7e14`, `#dc3545` | Legend dots; same as the pins in `GoogleMapsPlatformInterface.js` |
| Type | `--sm-font` | system font stack | No web fonts |
| | `--sm-text-xs` … `--sm-text-3xl` | 0.75rem … clamp(1.875rem, …, 2.875rem) | Type scale |
| Space | `--sm-space-1` … `--sm-space-7` | 0.25, 0.5, 0.75, 1, 1.5, 2, 3 rem | Same steps as Bootstrap `m-*`/`p-*`/`gap-*` 1–5 |
| Radius | `--sm-radius-sm`, `--sm-radius`, `--sm-radius-lg`, `--sm-radius-xl` | 0.375, 0.5, 0.875, 1.25 rem | Chips, controls, cards, hero |
| Shadow | `--sm-shadow-sm`, `--sm-shadow`, `--sm-shadow-lg` | | Cards use `--sm-shadow`; overlays use `-lg` |

Bootstrap's own variables (`--bs-primary`, `--bs-success`, `--bs-warning`, `--bs-danger`, `--bs-info`,
`--bs-link-color`, `--bs-border-color`, `--bs-secondary-color`, radii, shadows, fonts) are set from these tokens.
So `text-primary`, `text-bg-success`, `alert-info`, `spinner-border text-primary` and so on already use the new
palette.

In templates, use Bootstrap utilities for spacing and layout (`mb-3`, `gap-2`, `row g-3`, `d-flex`). Do not write
inline colours.

## Components

### Page frame

- **Navbar and footer** come from `layout :: navbar` and `layout :: footer`. Do not copy them. The footer holds the
  dataset label, the stub badge, the credits and the licence line in small print.
- **Page header**:

  ```html
  <header class="page-header">
      <a class="back-link" href="/schools">&larr; Search schools</a>   <!-- optional, once per page -->
      <h1 class="page-title">Search schools</h1>
      <p class="page-subtitle">147 schools · A–Z</p>                     <!-- optional -->
      <div class="page-actions"> … buttons … </div>                    <!-- optional -->
  </header>
  ```

  Put the "Back to …" link here only, not again at the bottom of the page.
- **Section heading** inside a page: `<h2 class="section-title">School details</h2>`.

### Buttons

| Class | Look | Use |
|---|---|---|
| `btn btn-primary` | Solid indigo | The single main action of the page or form |
| `btn btn-outline-primary` (alias `btn-soft-primary`) | Indigo tint | Other actions in the main task (Open filters, Compare selected, Use address) |
| `btn btn-outline-secondary` (alias `btn-neutral`) | White, grey border | Cancel, Back, Clear, Log out in the navbar |
| `btn btn-outline-danger` | White, red text | Remove in lists (quiet) |
| `btn btn-danger` | Solid red | Only when the destructive step is the page's main action (Log out?) |
| `btn btn-link` | Text link | Minor actions (None of these, Clear all) |
| `btn btn-accent` | Cyan, dark text | Only on the coloured hero |

- Use the default size for main actions. `btn-sm` (32 px) is for actions inside cards, tables and chips.
- A selected option in a button group (the sort order) gets `aria-current="true"` (or class `is-selected`).
  It then looks chosen, solid indigo, not disabled. Do not use `btn-secondary disabled` for it.

### Cards

- `card` + `card-body`: white, light border, soft shadow, 0.875rem radius. Do not add `shadow-sm`; it is already
  there.
- Use one card per section. Do not leave a section bare on the grey page next to carded sections.
- `card-header` (grey band, semibold) for a titled card.

### School summary card

`fragments/school-summary-card :: card(school)`. The card shows the name as an indigo link, the school type as a
`tag`, address and district in a `data-list`, the PSLE ranges as `range-chip`s, and a one-line note. The per-card
note must stay: tests count one "TEST VALUES – not MOE data" (seed) or one "PSLE score ranges: not available yet"
(no PSLE data) per card.

### Data lists

```html
<dl class="data-list">            <!-- 5.75rem label column, small text: cards -->
    <dt>Address</dt><dd class="caps">9 BISHAN STREET 22</dd>
</dl>
<dl class="data-list data-list-lg"> … </dl>   <!-- wider labels, body text: details pages; stacks on phones -->
```

`caps` on a value that is in ALL CAPS in the data adds a little letter-spacing. CSS cannot change the case of the
text, so do not try `text-transform`.

### Chips, tags and badges

| Class | Use |
|---|---|
| `chip` | An active filter. Remove link: `<a class="chip-remove" aria-label="Remove …">&times;</a>` (24 × 24 px target) |
| `chip chip-warning` | A "Not applied" filter |
| `chip chip-neutral` | A plain value chip (programme, CCA) |
| `tag` | A small grey label (school type, "IP") |
| `chance chance-safe` / `chance-match` / `chance-reach` | SAFE / MATCH / REACH. Keep the word inside. Existing `badge` elements with `data-chance="…"` get the same colours automatically |
| `badge-stub` | "Demo data (stub)". Quiet amber pill; use it instead of `badge text-bg-warning` |
| `notice-test` | The "TEST VALUES – not MOE data" box |
| `note` | A one-line muted note, e.g. "historical, not guaranteed"; can start with an `info` icon |
| `range-list` > `range-chip` | PSLE ranges; inner spans `range-pg`, `range-al`, `range-year` |
| `facility-tag is-library` / `is-tuition` | Facility type with a dot in the map pin colour |
| `differs-tag` | The "differs" marker on a compare row label. Put it in its own element after the label text |
| `rank` | The rank number on a recommendation |

`badge text-bg-primary` is reserved: the recommendation results page must use it exactly once per result.

### Forms

- Every input keeps a visible `form-label` (or `visually-hidden` label when a heading already names it).
- Inputs, selects, checkboxes and radios have 3:1 borders and a 3 px indigo focus ring. Do not remove the outline.
- Help text: `form-text`, one short sentence. Error text: `invalid-feedback` with an `id`, and the input gets
  `is-invalid`, `aria-invalid="true"` and `aria-describedby="<help-id> <error-id>"`. Show each error once.
- Multi-selects (`select multiple`) show chosen options in solid indigo, also after the box loses focus. Prefer a
  checkbox list in `filter-options` with the `option-search` box for long lists.
- Switch: `form-check form-switch` (bigger track than Bootstrap's).
- `filter-options`: a scroll box for long checkbox lists (max 16rem). It shows a shadow at the edge when more
  options are hidden. Use at most one per screen on mobile.

### Tables

- `table` (add `table-hover` for clickable rows). Head cells are small, grey and on a light band.
- Wrap wide tables in `table-responsive`. Put a table that sits on the page inside a `card`.
- Compare page: `compare-table`. Its `th scope="row"` label column stays fixed while the school columns scroll.
  Mark differing rows with a `differs-tag` element, never with colour alone.

### Alerts and messages

- `fragments/messages :: messages` shows flash messages (`alert-info`, indigo tint) and errors (`alert-danger`).
- `alert-warning` is the yellow box for checks such as "Plan check".
- Put an error next to the control it is about when the page allows it.

### Empty states

```html
<div class="empty-state">
    <span class="empty-state-icon"><svg th:replace="~{fragments/icon :: icon('bookmark')}"></svg></span>
    <p class="empty-state-title">Your shortlist is empty</p>
    <p>Add schools from search or a school's page.</p>
    <a class="btn btn-primary" href="/schools">Search schools</a>
</div>
```

### Key figures, steps, score meter

- `stat-grid` > `stat` > `stat-value` + `stat-label`: large numbers such as "4 min" and "1.2 km".
- `step` > `step-number` + content with `step-title`: numbered form steps (directions: To, From, Travel by).
- `score-meter`: `<div class="score-meter" role="img" aria-label="Score 91 of 100"><span style="width: 91%"></span></div>`
  next to the written score.

### Maps

- `fragments/map-canvas :: mapCanvas(...)` draws `#map` (`map-canvas`) or the short "Map unavailable" box.
- Legend: `<ul class="map-legend"><li class="legend-item"><span class="legend-dot is-library"></span>Library</li></ul>`.
  Do not use inline colours; the dots use the pin tokens.
- `map-list`: the list next to the map. It scrolls only on wide screens.

### Location picker

`fragments/location-picker :: locationPicker(returnTo, location)`. It shows the current starting point in an
indigo box with a Clear button, the address form and "Use my current location". Do not repeat the starting
address elsewhere on the same page.

### Loading overlay

`fragments/loading :: loading('Finding the route…')` and `data-loading="…"` on the form. No change needed in pages.

### Icons

`<svg th:replace="~{fragments/icon :: icon('search')}"></svg>` inserts a 1.15em line icon in the text colour,
hidden from screen readers. Names: `search`, `filter`, `map`, `pin`, `locate`, `bookmark`, `compare`, `plan`,
`sparkle`, `user`, `arrow-left`, `info`, `alert`, `route`, `clock`, `book`, `school`, `check`, `external`.
Always keep a visible word next to an icon.

## Do and don't

Do:

- Keep one solid `btn-primary` per page; make the rest soft, neutral or links.
- Write short copy: one sentence of help, no repeated disclaimers within a section.
- Show SAFE, MATCH and REACH as words; colour is extra.
- Show "Not available" through `fragments/value` for every missing value.
- Check phone width (390 px): main actions come before side notes; no sideways scrolling except in tables.

Don't:

- Don't change ids, `name`/`value` attributes, `data-*` attributes or the classes the scripts use
  (`loading-overlay`, `d-none`, `filter-options`, `form-check`, `location-picker`, `#map`, `#map-unavailable`,
  `#district-layer`, `#district-status`).
- Don't change wording that tests assert. Run `grep -rn "<text>" src/test` first.
- Don't add inline `style="color: …"`, new CSS files or web fonts.
- Don't use `btn-sm` for the page's main action, or `btn-secondary disabled` for a selected option.
- Don't use orange `#fd7e14` or cyan `--sm-accent` behind white text: both fail contrast.

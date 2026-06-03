# ui-plan.md

> **STATUS (current): largely delivered.** The two-UI shell, nav, season-control
> bar and Dashboard are built, and the game UI now spans Race Weekend, Schedule
> (with per-track favoured areas), Standings, Drivers, Staff, Market (driver +
> sponsor), R&D (per-area budgets + upgrade projects), Ladder (F2/F3), Teams and
> History. See `frontend/README.md` for the live screen list and `remaining.md`
> for the per-patch log. This file is kept as the original plan/visual direction.

Working doc for the **new game UI** for the F1 team-management sim. Pair this
with `remaining.md` (backend/game status) and `information.md` (design doc).
Goal: a real management-game front end alongside the existing test UI, same
stack, no new dependencies.

## Where we are

- Backend + a test-focused Vue UI already exist (12 raw-table panels). That
  test UI stays as the always-working fallback.
- A **static dashboard mockup** has been drafted: `dashboard-mockup.html`
  (self-contained HTML/CSS, dummy data, not wired to the API). It locks the
  visual direction. Open it in a browser to see the target look.
- Visual direction is **approved** in principle: dark F1-broadcast theme +
  f1dynasty-style top nav (red active underline). Still open to tweaks.

## Stack constraint

Keep the current stack: **Vue 3 + Vite + plain JS + CSS**. No router, no UI
libraries, no new deps. All data via the existing `frontend/src/api.js`.

## Architecture: two UIs in one app

`App.vue` gets a top-level `mode` ref: `'game'` (new UI) | `'test'` (today's
tab panel). Switch with `v-if` — no router. The current tab shell becomes the
`'test'` branch almost untouched. The new game UI is a parallel shell. The
nav's "Test UI ↗" link sets `mode='test'`; a "← Back to game" link returns.
Both modes share `api.js`, so no backend duplication. This lets us ship the
game UI screen-by-screen while the test UI keeps everything reachable.

## Header (two tiers) — matches the mockup

1. **Top nav** (f1dynasty style, sticky): brand `F1SIM`, then section links —
   active link is red with a red underline; inactive light grey, hover white.
   The link list drives an `activePanel` ref. Right-aligned: Settings, Test
   UI ↗, Feedback (faint placeholder).
   Current item set (mix of real + placeholder): Dashboard · Race Weekend ·
   Schedule · Drivers · Finance · Market · Staff · Academy · Standings ·
   Teams · History. Trim/rename freely.
2. **Season-control bar** (sticky, below nav): team crest + accent, name +
   role, then Season / Round / Phase pill / Cash, and one big context-aware
   **Advance** button. The advance button is the spine of the game loop — its
   label comes from the phase. Reads `/api/game/state` + `/api/game/actions`,
   posts `/api/game/advance`.

## Screens → endpoints (all already exist)

- **Dashboard** (done as mockup): player summary, next-session card, drivers'
  + constructors' standings (own cars highlighted), finances snapshot, and a
  phase-driven "attention" banner. Uses `/api/game/state`,
  `/api/game/current-race`, `/api/standings`, `/api/teams`.
- **Race Weekend**: phase-driven flow, not tabs — PRACTICE shows focus,
  QUALIFYING shows grid + strategy, RACE/POST_RACE shows results. Uses
  `/api/race-weekend/*`, `/api/race-results`, `/api/sprint-results`. The phase
  gates which step is editable (mirrors backend rules).
- **Market** (high gameplay payoff, data fully ready): free agents with the
  realistic suggested salary, the player's cash + driver-salary-bill headroom
  (added in backend patches 13–14), open seats, pending offers,
  submit/withdraw. Uses `/api/market/driver/*`.
- **Standings**: driver + constructor tables, own team highlighted.
- **Team / Finance**: roster, finances (cash, income, expenses, academy
  investment), sponsorships. `/api/teams`, `/api/drivers?team=`,
  `/api/team-sponsorships`.
- **Schedule (Calendar)** + **Reference**: lighter read-only; low priority
  (test UI already covers them).
- **Staff / Academy / Car / History**: placeholders for now — backend systems
  (personnel market, R&D, history tables) are still to build. Stub screens are
  fine.

## Visual tokens (from the mockup CSS)

- Background `#0d0f12`, surfaces `#15181d` / `#1b1f26`, lines `#262b33`.
- Text `#e8eaed`, muted `#8b929c`, faint `#5b626c`.
- `--accent` = team color (placeholder `#e10600`; per-team accent later —
  consider an `accent_color` field or a teamId→color map).
- Podium: gold `#d9a441`, silver `#9aa3ad`, bronze `#b4724a`.
- `font-variant-numeric: tabular-nums` on all money / points / timing.
- Card-based layout, position pills, rounded 12px corners.

When building real components, lean on the `frontend-design` skill for styling
within these tokens.

## Build order (small chunks, same cadence as backend patches)

1. **Shell + nav + season bar** — `App.vue` mode toggle, `NavBar.vue`
   (drives `activePanel`), `SeasonBar.vue` wired to game state/actions/advance.
   Test UI reachable via the nav.
2. **Dashboard** — port `dashboard-mockup.html` to `DashboardPanel.vue`, wire
   to state / current-race / standings.
3. **Market** — the richest interactive screen; backend fields all ready.
4. **Race Weekend** flow.
5. **Team / Finance** + **Standings**.
6. **Polish** — theme tokens file, per-team accent colors, empty/loading
   states.

Each chunk is a `frontend/` zip touching only new files plus a tiny `App.vue`
edit, leaving the test UI intact throughout.

## Open questions / decisions to revisit

- Per-team accent colors: hardcode a map now, or add a backend field?
- Final nav item set (drop placeholders, or keep as "coming soon" stubs?).
- Whether the season bar shows more (next deadline, board target) later.
- Mobile/narrow layout — out of scope for v1, design desktop-first.

## Reference files

- `dashboard-mockup.html` — the approved visual draft (open in browser).
- `remaining.md` — backend status (patches through #14; market now exposes
  suggested salary, player cash, and salary bill).
- `information.md` — full design doc.
- Existing front end: `frontend/src/App.vue`, `frontend/src/api.js`,
  `frontend/src/panels/*`, `frontend/src/style.css`.

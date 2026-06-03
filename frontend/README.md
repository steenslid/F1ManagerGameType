# frontend — F1 Sim UI

Vue 3 + Vite, plain JS + CSS, no router, no UI libraries. Two UIs in one app:

- **Game UI** (default): a dark "F1-broadcast" theme — the real management
  front end.
- **Test UI** (fallback): the original raw-table panels that exercise every
  endpoint directly. Toggle between them with the small button bottom-right.

## Setup

Requires Node 20+.

```
cd frontend
npm install
npm run dev      # http://localhost:5173
```

Talks to the backend at `http://localhost:7777` (hard-coded in `src/api.js`);
both must be running.

## Layout

```
frontend/src/
├── main.js
├── style.css            # theme tokens (.new-ui-wrapper) + scoped legacy (.test-ui-wrapper)
├── api.js               # one fetch wrapper per endpoint, returns { data, elapsedMs }
├── useGame.js           # shared reactive game state (overview, teams, currentRace, advance, feed)
├── format.js            # money / phase / crest helpers
├── App.vue              # toggles NewUI <-> TestUI
├── NewUI.vue            # game shell: top nav + season-control bar + panel routing
├── panels/              # game screens
│   ├── SavesPanel.vue          load/create/delete saves (landing)
│   ├── TeamSelectPanel.vue     pick the constructor to manage
│   ├── DashboardPanel.vue      attention banner, next session (+favoured area),
│   │                           standings, finances, car, board objective
│   ├── RaceWeekendPanel.vue    phase-driven: practice focus, strategy, results,
│   │                           and the between-rounds lineup (reserve/junior call-up)
│   ├── SchedulePanel.vue       calendar with each track's favoured area + your fit
│   ├── StandingsPanel.vue      driver + constructor tables, season selector
│   ├── DriversPanel.vue        scouting database (search)
│   ├── StaffPanel.vue          personnel directory
│   ├── MarketPanel.vue         driver market + interactive sponsor market
│   ├── RdPanel.vue             per-area R&D budgets + upgrade projects
│   ├── LadderPanel.vue         F2 / F3 feeder tables (projected promotion)
│   ├── TeamsPanel.vue          constructors (prestige, car, points, cash)
│   ├── HistoryPanel.vue        per-season race results + off-season report
│   └── EventFeed.vue           toast/feed of advance events
└── testui/              # the original test UI (App -> TestUI.vue, panels/*)
```

## Conventions

- All data via `api.js`; errors surface verbatim as `[CODE] message`.
- `useGame.js` is the single reactive store — one `advance()` refreshes the
  whole UI rather than each panel fetching in isolation.
- The season-control bar's **Advance** button is the spine of the loop; its
  label is phase-driven. The backend gates some advances (e.g. you must set a
  practice focus / strategy first), surfaced as an error on the button.

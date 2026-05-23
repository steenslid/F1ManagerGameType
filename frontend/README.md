# frontend — F1 Sim test UI

Vue 3 + Vite, plain JS, six tabs, no styling beyond a table grid. Exists to
exercise the backend API and visually verify behavior. Not a product UI.

## Setup

Requires Node 20+ (any recent LTS is fine).

```
cd frontend
npm install
npm run dev
```

Opens at http://localhost:5173 and talks to the backend at
http://localhost:7777. Both must be running.

## Layout

```
frontend/
├── index.html
├── package.json
├── vite.config.js
└── src/
    ├── main.js
    ├── style.css
    ├── api.js           # one fetch wrapper per endpoint
    ├── App.vue          # tabs + shell
    └── panels/
        ├── SavesPanel.vue
        ├── GamePanel.vue
        ├── CalendarPanel.vue
        ├── TeamsPanel.vue
        ├── DriversPanel.vue
        └── ReferencePanel.vue
```

## Conventions

- Each panel: fetch on mount, refresh button, error block, raw JSON viewer
  in a collapsed `<details>`.
- Errors are surfaced verbatim from the backend envelope:
  `[CODE] message`.
- No client-side "loaded save" tracking — if an endpoint needs a save, you'll
  see `[BAD_STATE] No save is currently loaded` and navigate to Saves to
  load one.
- API base URL hard-coded to `http://localhost:7777` in `api.js`. Change
  there if your backend runs elsewhere.

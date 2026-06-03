# f1-sim — F1 team-management simulation

A single-player Formula 1 **team-management** sim: take control of a constructor,
run race weekends, develop the car, sign drivers, manage sponsors and finances,
and chase the board's championship target season after season.

Kotlin/JVM backend (Javalin HTTP + PostgreSQL, schema-per-save) with a Vue 3
front end. See `information.md` for the full design doc and `remaining.md` for
the running implementation log (every feature shipped, patch by patch).

> Status: well past the original scaffold. The domain, seeds, phase loop, race
> sim, markets, R&D, the driver ladder and a full game UI are all in. The
> backend isn't compiled in the cloud dev environment (it needs JDK 25 + a
> reachable Postgres), so build and play locally.

## What's implemented

**Core loop**
- Phase machine + `POST /api/game/advance`: OFF_SEASON → DRIVER_MARKET →
  PRE_SEASON → (PRACTICE → QUALIFYING/SPRINT → RACE → POST_RACE → BETWEEN_ROUNDS)×N
  → END_OF_SEASON, with deterministic (seeded) simulation.
- Race / qualifying / sprint simulation (`RaceSim`), driver + constructor
  standings, race & sprint results, full 2026 seed grid.

**Squad & people**
- Driver market each off-season (player offers + AI matching, salary/cash gated).
- Mid-season **reserve / junior call-up** between rounds (free agents, reserves,
  or F2/F3 juniors 18+).
- **Driver ladder**: F3 → F2 → F1. Champions are promoted up each season; young
  drivers **develop** (pace/qualifying grow from their potential pool).
- Personnel (staff) directory.

**Car & R&D**
- Per-area car performance — **aerodynamics / chassis / powertrain** — each with
  its own rating and R&D budget; overall `car_performance` is their average.
- Cars develop toward what their spend + technical capability sustains; the sim
  weights the three areas by **each track's demands**, so the car suits some
  venues more than others.
- **In-season upgrade projects**: commission a timed boost to one area that
  costs cash up front and delivers a few rounds later.

**Money, sponsors, board**
- Finances: sponsor revenue, operating costs (base + academy + R&D + salaries),
  end-of-season settle.
- **Sponsor market**: sign / renew / drop deals (acceptance gated by team
  prestige vs the sponsor's standard + budget); player deals no longer
  auto-renew. AI auto-renews with performance scaling + defection.
- **Board objective**: a constructors'-finish target derived from prestige +
  board ambition, with an **end-of-season verdict** that swings prestige and
  budget — which ripples back into sponsors, the market and next year's target.

**Planning & continuity**
- Multi-season **calendar generation** (clones the prior season's calendar).
- The Schedule and Dashboard surface each track's **favoured area** and your fit,
  so you can steer per-area R&D and time upgrades against the calendar.

## Architecture

```
src/main/kotlin/f1sim/
├── Main.kt                wires services + HTTP server
├── config/                AppConfig
├── db/                    Database (HikariCP, per-borrow search_path), Migrations
├── seed/                  SeedLoader + Seeds (2026 reference data from resources/seeds/*.json)
├── sim/                   RaceSim (pure, deterministic)
├── game/                  GameService (phase loop), OffSeasonService, DriverMarketService,
│                          RaceWeekendService, StandingsService, LineupService, TeamRdService,
│                          UpgradeService, SponsorMarketService, BoardService
├── save/                  SaveService, SaveSession
└── http/                  Server, Envelope, routes/*

src/main/resources/
├── sql/{public_schema,save_schema}.sql    public registry + per-save schema
└── seeds/*.json                           teams, drivers, personnel, sponsors, tracks, races, ...

frontend/                  Vue 3 + Vite game UI (+ a fallback raw-table test UI)
```

Data model is **schema-per-save**: `public.saves` is the registry; each save
gets its own Postgres schema (`save_xxx`) created from `save_schema.sql`, with
`search_path` switched per connection borrow.

## Running

**Backend** — requires **JDK 25** (the Kotlin 2.3.20 toolchain targets it; see
`gradle.properties`) and a reachable PostgreSQL with an empty database.

```bash
createdb f1sim
./gradlew run \
  -Df1sim.db.url=jdbc:postgresql://localhost:5432/f1sim \
  -Df1sim.db.user=f1sim \
  -Df1sim.db.password=f1sim
```

`gradle.properties` pins a local JDK 25 path (`org.gradle.java.home`) — adjust it
to your machine, or override on the command line. Serves on `:7777`.

**Frontend** — Node 20+:

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173, talks to the backend on :7777
```

The game UI loads by default; a small toggle (bottom-right) switches to the
raw-table **test UI** that exercises every endpoint directly.

Smoke test:

```bash
curl localhost:7777/api/health
curl -X POST localhost:7777/api/saves -H 'content-type: application/json' \
  -d '{"saveName":"my-career","managerName":"M. Schumacher","difficulty":"NORMAL"}'
curl localhost:7777/api/saves
curl -X POST localhost:7777/api/saves/<id>/load
curl localhost:7777/api/game/state
```

## Working on it

- **Schema changes mean recreating saves.** Several features added columns/tables
  to `save_schema.sql`; an old save's schema won't have them. Delete and recreate
  saves after pulling schema changes.
- **Determinism:** simulation and off-season steps seed RNG from the save's
  `master_rng_seed` (XOR'd with per-step salts), so a given save replays
  identically.
- **Docs:** `information.md` is the design doc; `remaining.md` is the
  patch-by-patch implementation log and the list of what's still to build;
  `ui-plan.md` / `gemini-ui.md` cover the front-end direction.

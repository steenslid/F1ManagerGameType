# f1-sim — Starting Framework

This is the scaffolding for the F1 team simulation game described in
`information.md`. **It is intentionally incomplete** — it gets the project
building, the database connecting, and the HTTP server serving the save
management endpoints. Domain logic (drivers, teams, race sim, phases,
markets, R&D) is not yet here.

## What's in v0.1

- Gradle/Kotlin project, JVM 21, single module
- HikariCP pool with per-borrow `search_path` switching (the schema-per-save story)
- Public schema migration — `public.saves` registry
- Save schema migration — `game` row only, as the seed of the per-save table set
- HTTP server (Javalin) with the canonical response envelope
- Save CRUD endpoints:
  - `GET    /api/saves`
  - `POST   /api/saves`
  - `POST   /api/saves/{id}/load`
  - `DELETE /api/saves/{id}`
- Stub game state endpoint: `GET /api/game/state`
- Health check: `GET /api/health`

## What's deliberately not in v0.1

- Any domain table beyond `game` (drivers, teams, tracks, races, parts, ...)
- Seed JSON loading (real 2026 grid)
- Phase machine and `/api/game/advance`
- Decisions, markets, R&D, sponsors, events
- Race simulator
- Vue frontend (no `web/` directory yet — design says Vue 3 + Vite)
- Tests (add JUnit/kotlin-test once there's logic worth testing)

## Layout

```
src/main/kotlin/f1sim/
├── Main.kt
├── config/        AppConfig
├── db/            Database (HikariCP), Migrations
├── http/          Server, Envelope, routes/
├── save/          SaveService, SaveSession
└── domain/        (empty — placeholder for game-domain types)

src/main/resources/
├── sql/
│   ├── public_schema.sql
│   └── save_schema.sql
└── logback.xml

seeds/             (empty — for the 2026 reference JSONs)
```

## Running

Requirements: JDK 21, PostgreSQL reachable somewhere, an empty database.

```bash
createdb f1sim
./gradlew run \
  -Df1sim.db.url=jdbc:postgresql://localhost:5432/f1sim \
  -Df1sim.db.user=f1sim \
  -Df1sim.db.password=f1sim
```

Or set the env equivalents (`F1SIM_DB_URL`, etc.).

Smoke test:

```bash
curl localhost:7777/api/health

curl -X POST localhost:7777/api/saves \
  -H 'content-type: application/json' \
  -d '{"saveName":"my-career","managerName":"M. Schumacher","difficulty":"NORMAL"}'

curl localhost:7777/api/saves

# Use the save_id from above
curl -X POST localhost:7777/api/saves/<id>/load
curl localhost:7777/api/game/state
```

## Notes on key decisions

- **HTTP server: Javalin.** Per the design doc this was undecided between
  Javalin, Ktor, and `sun.net.httpserver`. Javalin is the lightest sensible
  option that still gives JSON parsing, path params, CORS, and routing
  without ceremony. Swap is mechanical if you change your mind.

- **Schema switching per borrow, not via `connectionInitSql`.** Hikari's
  init SQL is configured once at pool creation, but the active save changes
  at runtime. A single `SET search_path` statement per `withConnection` is
  cheap and always correct.

- **No Gradle wrapper committed.** Generate one with `gradle wrapper` after
  cloning, or run via your installed Gradle. (The wrapper jar can't be
  generated in this environment.)

- **Singleton-per-schema `game` row** is enforced with
  `CREATE UNIQUE INDEX game_singleton ON game ((true))`. Cheap trick.

## Next steps (suggested order)

1. Add domain tables to `save_schema.sql`: `tracks`, `teams`, `drivers`,
   `engine_suppliers`, `pu_versions`, `regulation_eras`, `tyre_compounds`.
2. Add the matching Kotlin domain types under `f1sim/domain/`.
3. Write the seed loader: JSON files in `seeds/` → inserted into the new
   save's schema right after `Migrations.createSaveSchema()`.
4. Build the phase state machine (start with `OFF_SEASON` ↔ `PRE_SEASON`).
5. Then either: race weekend mechanics, or the off-season pipeline.

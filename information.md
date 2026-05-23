# F1 Team Simulation Game — Design Document

## Overview

A single-player F1 team management simulation focused on the long career arc (10–20 in-game years), starting in the 2026 season. The player manages one F1 team — either a real 2026 team or a custom team — through driver markets, R&D, sponsorships, regulation changes, and seasons of racing. Races are auto-simulated (no live race watching); the game's texture lives in the years between, not the laps.

The player is the team, not a sackable principal — board pressure manifests as budgets, sponsors, and restrictions, never termination.

## Tech Stack

- **Backend:** Kotlin, Gradle, minimal external dependencies
- **Database:** PostgreSQL (one DB, schema-per-save)
- **Frontend:** Vue 3 + Vite (no visual assets — pure data UI)
- **JSON:** kotlinx.serialization
- **DB access:** plain JDBC + HikariCP (no ORM)
- **HTTP server:** undecided; candidates: Javalin (light), Ktor (Kotlin-idiomatic), sun.net.httpserver (zero-dep)
- **Architecture:** local single-player, no auth, no sessions, REST + JSON, synchronous

No 3D, no liveries, no animations. Cars are numbers on tables and charts.

## Data Architecture

### Three buckets

1. **Current world state** — snapshot of "now" needed to load a save. Mutates constantly.
2. **Historical achievements** — append-only race results, championships, stints. Source of truth for history.
3. **Transient** — in-race lap state, weather mid-session. Memory only, never persisted.

### Save model: schema-per-save

- Single Postgres database hosts all saves
- `public.saves` registry table — one row per save (save_id, schema_name, save_name, created_at, last_played_at, schema_version)
- Each save = its own schema (e.g., `save_a1b2c3`) with the full set of game tables
- Connection sets `search_path TO save_xxx, public` on load (via HikariCP connection init)
- Reference data (real teams, drivers, tracks) **copied** into each save schema at creation — not referenced across schemas
- `pg_dump -n save_xxx` becomes export-save for free; `CREATE SCHEMA` + load dump = import

### `game` table (one row per save schema)

`save_id, save_name, created_at, last_played_at, schema_version, player_team_id, player_manager_name, difficulty, master_rng_seed, current_season_year, current_round, current_phase`

## Schema Reference

### Historical tables

- `tracks` — id, name, country, length, type (street/high-speed/technical), demand profile, climate, pit_lane_loss_seconds, overtake_difficulty (0–1), rain_probability_baseline, temperature_range
- `races` — (season_year, round) natural key, track_id, conditions, session_format (STANDARD / SPRINT)
- `race_results` — race_id, driver_id, team_id (denormalized for mid-season swaps), finishing_position (nullable for DNF), grid_position, points, status, pole (bool), fastest_lap (bool), strategy_used, dnf_cause
- `sprint_results` — race_id, driver_id, team_id, finishing_position, grid_position, sprint_points, status
- `season_championships` — season_year, wdc_driver_id, wcc_team_id
- `driver_team_stints` — driver_id, team_id, role (RACING / RESERVE), start_year, start_round, end_year, end_round (nullable while active)
- `personnel_team_stints` — personnel_id, team_id, role, start_year, start_round, end_year, end_round
- `team_engine_supplier_stints` — team_id, supplier_id, start_year, end_year, annual_cost
- `junior_season_results` — driver_id, year, series (F2/F3), team_id, wins, podiums, poles, points, final_position, was_champion

### Current state tables

- `drivers` — name, nationality, current_age, current_racing_team_id (nullable), reserve_for_team_id (nullable F1 team), academy_team_id (nullable F1 team), retired (bool), current_salary, contract_expires_year, contract_expires_round, development_pool, morale (0–100), full composite stats, hidden traits
  - **Constraint:** if current_racing_team_id refers to an F1 team, reserve_for_team_id must be null
- `teams` — name, country, base_country, series (F1 / F2 / F3), prestige, is_custom_team, cash_reserves, current_year_income, current_year_expenses, cap_compliance_status, heritage_payment, base_operating_cost, academy_investment, regulation_understanding (0–1), pit_crew_rating (derived from crew chief), AI personality vector, board_ambition, board_realism, season_points (cached)
- `personnel` — name, nationality, age, current_team_id (nullable), current_role (PRINCIPAL / TECHNICAL_DIRECTOR / CHIEF_STRATEGIST / CREW_CHIEF / RACE_ENGINEER), current_salary, contract_expires_year, contract_expires_round, retired, skill ratings, hidden traits, development_pool
- `engine_suppliers` — name, country, entered_year, exited_year (nullable), works_team_id (nullable), is_custom_supplier
- `pu_versions` — id, supplier_id, mk_version, introduced_year, ice_performance, ers_performance, reliability, weight, cooling_requirement
- `sponsors` — name, country, tier (TITLE / PRIMARY / SECONDARY / MINOR), industry, prestige, preferences (performance_sensitivity, risk_tolerance, prestige_preference), budget_range
- `team_sponsorships` — team_id, sponsor_id, start_year, end_year, annual_value, is_title_sponsor, performance_bonus_schedule
- `regulation_eras` — id, name, start_year, end_year (nullable), performance_reset_severity (0–1), ers_share (0–1)
- `part_versions` — id, team_id, part_type (FRONT_WING / REAR_WING / FLOOR / SIDEPODS / SUSPENSION / BRAKES / GEARBOX), mk_version, developed_in_year, developed_in_round, attributes (per type), reliability (0–100)
- `team_parts_current` — team_id, part_type, current_version_id
- `development_projects` — team_id, target_part_type, started_year, started_round, expected_completion_round, allocated_budget, allocated_engineer_ids, status (IN_PROGRESS / COMPLETED / CANCELLED)
- `tyre_compounds` — id, name (C0–C5, INTER, WET), base_pace_factor, degradation_rate, longevity_laps, optimal_temperature_range
- `race_tyre_allocation` — race_id, hard_compound_id, medium_compound_id, soft_compound_id
- `event_templates` — id, name, category, prerequisites (predicates), probability_weight, effects_schema, requires_player_decision
- `event_log` — id, year, round, phase, template_id, affected_driver_id, affected_team_id, resolution

## Driver Model

### Composite stats

- `pace` — race pace
- `qualifying` — one-lap peak
- `overtaking` — attacking moves
- `defending` — holding position
- `consistency` — lap-to-lap variance, error rate
- `tyre_management`
- `ers_deployment` — extracting ERS smartly
- `wet_skill`
- `feedback_quality` — contributes to dev rate

### Hidden traits (generated at creation, never shown directly)

- `peak_age`, `decline_rate` — drives aging curve
- `retirement_threshold` — bias toward early/late retirement
- `loyalty` — stay-with-current-team pull in market
- `temperament` — morale swings, marketability
- `market_value_modifier` — premium beyond raw skill

### Development (talent ceiling via point pool)

Initial `development_pool` represents talent ceiling:

- Generational: 250–300
- Top tier: 180–250
- Solid F1: 100–180
- Journeyman: 50–100
- Backmarker: 20–50

Each season-end, a chunk converts to stat gains:

```
season_conversion = base_rate
  × age_factor              // peaks 18–23, falls past peak_age
  × experience_factor       // races started this season
  × performance_factor      // good season = more, bad = less
  × backing_factor          // academy support, feedback quality
  × random_jitter
```

Capped per season (~5–25 points/season for an active driver). Where points go is weighted by performance type (good quali → qualifying grows; good wet → wet_skill grows). "What if?" outcomes emerge when potential isn't converted (stuck at backmarker, low race exposure).

### Morale (0–100)

Updates after each race:

```
morale_delta = qualy_vs_expected + race_vs_expected
             + qualy_vs_teammate + race_vs_teammate
             + dnf_penalty
             + win_bonus / podium_bonus

morale = clamp(morale + morale_delta, 0, 100)
```

"Expected" = rolling average of last 5–8 races for this driver. Morale affects: consistency/racecraft modifier, error probability, contract negotiations, retirement bias, news event triggers.

### Reserve/academy relationships (three independent fields)

- `current_racing_team_id` — team they race for
- `reserve_for_team_id` — F1 team they sub in for (nullable; null required if racing in F1)
- `academy_team_id` — F1 team backing their development (nullable)

Academy boosts the backing_factor in development conversion (full ~1.5×).

### Injuries and suspensions

- Injury: low-prob roll per race weekend, modulated by consistency and in-race crashes. Length: 1 / 2–5 / season-ending.
- Suspension: from incidents and off-track events. 1–3 race bans.
- Flag driver `unavailable` for N rounds → triggers reserve callup via mid-season swap (a new short driver_team_stint).

## Car Performance Model

### Three layers

`Parts (what you develop) → Performance Characteristics (track behaviour) → Lap Time at a track`

The lap sim only reads performance characteristics — it has no knowledge of parts.

### Parts (one current version per team per type, each with multiple attributes)

- `front_wing` — downforce, drag, dirty_air_sensitivity
- `rear_wing` — downforce, drag, DRS_efficiency
- `floor` — downforce, ride_height_window
- `sidepods` — drag, cooling
- `suspension` — mechanical_grip, tyre_load
- `brakes` — braking_power, cooling
- `gearbox` — shift_speed, reliability

### Power unit (split)

- `engine_ice` — top_speed, fuel_efficiency, weight
- `ers` — deployment_power, recovery_rate, deployment_efficiency
- Shared: reliability, cooling_requirement
- `ers_share` set per regulation_era; 0 = ERS effectively off (backward-compat hook)

### Performance characteristics (derived from parts + PU)

`top_speed, acceleration, low_speed_cornering, medium_speed_cornering, high_speed_cornering, dirty_air_cornering, braking, tyre_wear_rate, cooling_headroom, reliability_overall, dirty_air_sensitivity`

### Development (discrete versions Mk1 → MkN)

- `development_projects` allocate engineers and budget to a target part type
- On completion, a new `part_versions` row is spawned with attributes = previous Mk × dev factors (TD/designer skill, budget, regulation maturity, RNG)
- Old versions stay in `part_versions` as history
- Reliability vs new-performance is the implicit R&D trade-off — all-in on new versions = lower starting reliability

### Track demands

Tracks have weights on the same axes as car performance characteristics. Lap time uses a weighted match between car_performance and track_demands.

- Monza: heavy top_speed/acceleration
- Monaco: heavy low-speed cornering, near-zero top_speed
- Silverstone: heavy high-speed cornering

## Phase Machine

### Top-level phases

- `OFF_SEASON`
- `PRE_SEASON`
- `RACE_WEEKEND` (sub-machine below)
- `BETWEEN_ROUNDS`
- `END_OF_SEASON`

### Race weekend sub-states

- Standard: `PRACTICE → QUALI → RACE → POST_RACE`
- Sprint: `PRACTICE → SPRINT_QUALI → SPRINT → QUALI → RACE → POST_RACE`

Phase transitions are where logic runs — phases themselves are mostly idle. `enterPhase(BETWEEN_ROUNDS)` ticks development; `enterPhase(END_OF_SEASON)` runs year-end logic.

## Race Simulation

Auto-simulated black box: `simulate(initial_state, strategies, rng_seed) → race_results + events`. Player never observes mid-race; pre-race inputs and post-race report are the only touch points.

### Pre-race inputs (only player decisions during a weekend)

- Practice focus per driver per session: `SETUP` / `TYRE_PROGRAM` / `RELIABILITY_CHECK` / `DEVELOPMENT_FEEDBACK`
- Strategy per driver: archetype menu shaped by conditions
  - Dry: M-H, S-H, S-M-M, M-M-H
  - Wet: W-I-Slick, Inter throughout, Full Wet

### Lap time (internal sim)

```
lap_time = base_lap_time(track)
  × perf_match(car_performance, track_demands)
  × driver_factor(driver_stats, conditions)
  × tire_factor(compound, age)
  × fuel_factor(fuel_kg)
  × weather_factor(weather, driver.wet_skill, car.cooling)
  × rng_jitter
```

### Pit stops

```
pit_loss = track.pit_lane_loss + crew_stop_time
if safety_car_active: pit_loss *= sc_multiplier (~0.5)
if red_flag_active:   pit_loss = 0
```

`crew_stop_time` driven by crew_chief skill → `teams.pit_crew_rating`. SC pitting becomes strategic gold (correct emergent behaviour).

### Overtake probability

```
overtake_prob = base_prob
  × (1 - track.overtake_difficulty)
  × pace_delta_factor
  × (attacker.overtaking / defender.defending)
  × (1 - leader_car.dirty_air_sensitivity)
  × overtake_button_factor
  × rng_jitter
```

ERS Overtake replaces DRS in 2026: trailing car can dump extra ERS energy for a boost. Each car has `ers_energy_kj` as per-lap session state. Active aero is abstracted into the era's baseline car performance (no per-corner modelling).

### Outputs

- Finishing positions → race_results
- DNF causes per failed car (engine failure, collision, hydraulics, etc.)
- Damage events with severity (minor / moderate / severe) → cash hit + spare parts consumed
- Penalty events (license points, grid drops, reprimands) → applied to subsequent state
- Weather as it actually unfolded
- Narrative event timeline for the race report

### Sprint specifics

- ~20–25 laps (`total_distance ≈ 100km / track_length`)
- Sprint points: 8-7-6-5-4-3-2-1 for top 8
- No mandatory pit stop, simpler tyre rules
- Sprint wins/poles tracked separately from race wins/poles in career stats

## Off-Season Pipeline (11 steps, ordered)

1. **Year-end finance settle** — WCC prize money, sponsor performance bonuses, operating costs deducted, next-year budget calculated against cap
2. **Board review** — score actual vs target; verdict (exceeded / met / missed / disaster)
3. **Aging tick** — every driver/personnel: `age += 1`, stats drift based on peak_age and decline_rate
4. **Retirements** — probabilistic check on age, recent trajectory, achievements
5. **Contract expirations** — flag everyone whose contract_expires_year == current_year
6. **Driver market** — multi-round, top of market signs first
7. **Personnel market** — same mechanics, tier order: principal → TD → strategist → crew chief → race engineers
8. **Junior promotions** — F2 champion → F1 seat if available, F3 champion → F2
9. **Sponsor market** — expiring deals re-evaluated, new entrants added, some retire
10. **Regulation reset** (only if entering a new era) — part_versions reset to era Mk1 via the reset event
11. **Season setup** — next year calendar finalized, season standings reset, transition to PRE_SEASON

### Driver market mechanics

Resolves in rounds. Each round: teams rank available drivers (skill, age, salary fit, team-fit); drivers rank interested teams (competitiveness, salary, prestige, loyalty). Matches form when mutual ranking is high enough. Player interactively makes offers and competes with AI in real time. Market closes after a few rounds; leftover seats fill from reserves or junior promotions.

## Money and Pressure

### Income

- WCC prize money (per `prize_distribution` reference, top ~25–30%, bottom ~3–5%)
- Heritage payments (Ferrari-style historic bonuses on `teams.heritage_payment`)
- Sponsorship revenue (sum of active team_sponsorships annual_value)
- Performance bonuses from certain sponsors

### Expenses

- Driver salaries (excluded from cap)
- Personnel salaries (top 3 excluded from cap)
- R&D budget (player allocates by part type)
- Operating costs (factory, travel, logistics)
- Academy investment
- PU supply cost (if not works team)

### Budget cap

Per regulation_era: `cap_amount`, `excluded_categories` set. Each expense has `is_cap_inclusive` flag. Going over triggers FIA penalties (next-season prize money deduction + reprimand events).

### R&D allocation (the main player lever during a season)

At season start, player allocates percentages across part types + reliability + PU components (if works team). Feeds `development_projects` funding levels. Mid-season re-allocation allowed with efficiency penalty (lost momentum).

### Board (Option 2: player IS the team — no sacking)

Targets set at season start:

- `target_wcc_position`, `target_wins`, `target_finance_status`, `time_horizon_years`

Calibrated by hidden `board_ambition` and `board_realism` plus current car competitiveness.

End-of-season verdict scales consequences:

- Exceeded → budget boost, patience grows
- Met → status quo
- Missed → budget tightened, patience shrinks
- Disaster → forced personnel changes, sponsor warnings, performance clauses can trigger
- Two consecutive disasters → forced major action (asset sale, restructure)

## Race Weekend Mechanics

### Practice focus outputs

- `SETUP` → temporary +1–3% performance for this weekend, scaled by Chief Designer
- `TYRE_PROGRAM` → tighter strategy recommendations from Chief Strategist
- `RELIABILITY_CHECK` → probability of discovering and fixing latent reliability issues
- `DEVELOPMENT_FEEDBACK` → dev_gain bonus to active projects, scaled by feedback_quality

### Weather model

Per-track climate baseline → forecast generated at weekend start (probability distribution over DRY / DAMP / LIGHT_RAIN / HEAVY_RAIN per session) → actual unfolds with ~80% forecast accuracy. Forecast visible before strategy lock-in. Weather affects lap times, compound choice, overtake difficulty, strategy menu, DNF probability.

### Tyre compounds

`tyre_compounds` reference table (C0–C5 + Inter + Wet) with pace, degradation, longevity, temperature optima. Per race: 3 dry compounds designated H/M/S by the supplier (in `race_tyre_allocation`). Strategies pick from that allocation.

### Reliability (internal to sim)

`part_versions.reliability` (0–100). Affects DNF probability in the simulator. Improves with race miles, faster with R&D allocated specifically to reliability. PU components have per-season allowances (e.g., 3 engines per driver) — exceeding = automatic grid drop.

### Damage

Severity tiers, summarised in race report:

- *Minor* — front wing/floor scrape. Pit fix, small cost.
- *Moderate* — significant aero damage. DNF or distant finish, spares consumed.
- *Severe* — chassis damage. DNF + parts unavailable next race + big repair + driver injury check.

### Penalties

In-race time penalties (5/10/20s for collisions). License points accumulate (12 in 12 months = 1-race ban). Grid drops for severe offenses or PU component overuse. Reprimands (3 = grid drop).

### Setup — abstracted

No explicit setup decisions; the SETUP practice focus covers it via the +1–3% performance boost.

## People Dynamics (beyond drivers)

### Personnel roles

- Team Principal
- Technical Director / Chief Designer
- Sporting Director / Chief Strategist
- Crew Chief (drives `pit_crew_rating`)
- Race Engineers (one per driver)

### Personnel development

Same pattern as drivers, smaller pools (~50–150), slower conversion, later peak (mid-40s). Gain skill from race weekend experience, mentoring effect (junior under high-skill senior), successful dev cycles.

### AI personality vector (per team)

`aggression, ambition, loyalty, frugality, development_focus`. Biases all algorithmic decisions: market behaviour, R&D allocation, sponsor priorities, strategy aggression, board patience. Seeded into team reference data — Ferrari ≠ Williams even at the same competitive level.

## Long-Arc Narrative

### News and events system

- `event_templates` — reference data with prerequisites (state predicates), probability_weight, effects_schema, requires_player_decision flag
- `event_log` — per-save history
- Fired during phase transitions (~2–5/race weekend, ~5–10/off-season)
- Effects: morale, cash, reputation, temporary performance modifier, cascade trigger, required player decision
- A subset are decision-driving (response screens with branching outcomes); the majority are flavour

### Engine supplier development

Mirror `part_versions`: `pu_versions` per supplier with discrete Mk versions, roughly one per off-season (PU regs lock specs in-season). Customer teams may get a slight disadvantage modifier compared to the works team. PU regulation reset at era boundaries → all suppliers' PUs reset to Mk1.

Supplier R&D for non-player suppliers is driven by AI personality (`ambition`, `frugality`).

### Player team identity (numerical only)

- `game.player_team_id`, `player_manager_name`, `difficulty`
- No livery, no rename, no logo
- Manager profile derivable from championships, signings, team trajectory — no extra table needed

### Initial save generation (2026 seed)

Reference data shipped as JSON files in `seeds/`, loaded into the new save's schema at creation:

- 11 F1 teams (+ optional custom 12th)
- ~22 F1 drivers + ~22 F2 + ~30 F3
- ~55 personnel + small free-agent pool
- 5–6 PU suppliers + Mk1 PU stats
- ~24 tracks
- 2026 calendar (round order, sprint flags)
- ~80–100 sponsors + matching initial team_sponsorships
- Initial part_versions Mk1 per team per part type
- 2026 regulation_era (ers_share=0.5, reset_severity=0)
- Tyre compounds (C0–C5 + Inter + Wet)

### Procedural generation (ongoing)

Only these churn:

- New F3 rookies yearly (~12 17-year-olds, name pools per nationality, Gaussian stat distributions with occasional generational-talent spikes)
- New personnel juniors entering yearly
- New sponsors (1–3/year, some retire)

**Teams and engine suppliers are fixed forever** (no new entrants, no folding).

### Custom team and engine option (at save creation only)

Save creation flow:

1. Pick mode: "manage real team" or "create custom team"
2. Custom team: name, base country, prestige tier, budget tier, engine supplier choice (existing or custom), initial drivers (pick or auto-generate), initial personnel (pick or auto-generate), board ambition
3. Custom engine: name, country, Mk1 PU stats (constrained range), works link to player team

Joins as the 12th team. `teams.is_custom_team`, `engine_suppliers.is_custom_supplier` flags (UI only — no mechanical effect).

## API Surface

### Architecture

- Kotlin backend on localhost
- Vue frontend in browser
- REST + JSON, no auth, no sessions, synchronous
- No WebSockets — races simulate in ~50–200ms server-side

### Five layers

**1. Save management**

- `GET /api/saves`
- `POST /api/saves` — create with setup options including custom team/engine
- `POST /api/saves/{id}/load` — sets search_path on connection pool
- `DELETE /api/saves/{id}` — drops the save schema

**2. Game state and phase orchestration**

- `GET /api/game/state` — year, round, phase, pending-decisions count, recent-events count
- `GET /api/game/actions` — valid actions in current phase
- `POST /api/game/advance` — advance to next phase, returns new state + any pending decisions/events from the transition

**3. Resource queries** (read-only)

- Standard REST: `/api/drivers`, `/api/teams`, `/api/personnel`, `/api/suppliers`, `/api/sponsors`, `/api/tracks`, `/api/seasons`, `/api/races`, `/api/standings`, `/api/history/...`
- Filters via query params (`?series=F2`, `?free_agent=true`, etc.)

**4. Decisions** (the player-input choke points)

- `GET /api/decisions/pending`
- `POST /api/decisions/{id}/resolve` — may cascade more pending decisions back in the response

**5. Domain actions**

- R&D: `GET/POST /api/rd/allocation`
- Markets: `POST /api/market/driver/offer`, `GET /api/market/driver/available`, similar for personnel and sponsors
- Race weekend: `POST /api/race-weekend/practice`, `POST /api/race-weekend/strategy`, `POST /api/race-weekend/simulate`
- News feed: `GET /api/events?since=...`

### Response envelope

```json
{
  "data": { ... },
  "errors": [ ... ],
  "meta": { "elapsed_ms": 12 }
}
```

### Client pattern

No continuous polling. Client hits endpoints on:

- App load → list saves + get game state
- User action → POST → refresh affected views
- Phase advance → POST advance → refresh state and pending items

## Open / Deferred Items

- HTTP server library choice (Javalin / Ktor / sun.net.httpserver)
- Decision data schema specifics
- Race report payload shape
- Tyre set allocation per weekend (deliberately abstracted; can be added later if depth desired)
- OpenAPI/Swagger spec (nice-to-have, not v1)
- "Watch race live" lap-by-lap UI mode (engine supports it; deferred as polish)
- Project layout (modules, package structure)
- Migration strategy specifics across save schemas
- Seed data sourcing and licensing for real-world names

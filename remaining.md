# remaining.md

Status snapshot for a single-player F1 team management game. Kotlin backend
(JDK 25, Kotlin 2.3.20, Javalin 6, HikariCP, PostgreSQL, kotlinx.serialization)
+ Vue 3 / Vite frontend, schema-per-save Postgres.

Design doc: `information.md`.

## Session handoff (read this first)

This file is the running status doc. The sections below ("Built so far",
"Left to build", "Known issues", "File map", "Gotchas") are the durable
reference. This block is the live handoff so a fresh instance can resume
without re-reading the whole chat.

**Working style.** Small, low-token chunks per turn. Each chunk: one focused
feature or fix, delivered as a zip with correct `src/main/...` structure,
plus the updated `remaining.md`. Known-issue fixes get folded into feature
work where natural. Each patch carries forward only the files it touches and
states "builds on patch X" explicitly.

**Most recent patch chain (newest last).** All applied on top of each other;
the user's repo should now contain all of them:

1. `loyalty` — `drivers.previous_team_id` column (SCHEMA CHANGE) + driver-side
   loyalty bonus in market scoring.
2. `sponsor-renewal` — `OffSeasonService.renewSponsors` stub (step 4b,
   PRE_SEASON); `academy_investment` now flows through the operating-cost
   tick; `trait_market_value_modifier` now read in `computeAiSalary`.
3. `salary-aging` — age decay on AI salary (`trait_peak_age`).
4. `loyalty-asymmetry` — team-side loyalty bonus; `trait_loyalty` now read;
   both loyalty bonuses weighted by it.
5. `loyalty-reset` — one-cycle reset of `previous_team_id` for drivers who
   entered the off-season already unsigned.
6. `ai-salary-noise` — ±5% per-(agent, team, round) noise on AI salary.
7. `sponsor-perf` — sponsor renewal scaled by last-season WCC rank
   (`readWccPerformanceModifiers`, ±15%); `MIN_OFFER_SALARY` 100k→500k.
8. `max-offer` — `MAX_OFFER_SALARY` 200M→75M.
9. `sponsor-defection` — sponsors of below-mid-grid teams may walk away (no
   renewal) instead of renewing at a discount. Per-deal defection chance =
   (shortfall / `SPONSOR_PERF_MAX_PCT`) × `SPONSOR_DEFECTION_MAX_PROB` (0.40)
   × the sponsor's `performance_sensitivity`. `OffSeasonService.renewSponsors`
   only; no schema change; reuses `SPONSOR_RENEW_SALT` (noise drawn first so
   non-defecting renewals are byte-for-byte unchanged from patch 7).

**Schema state.** The only schema change in this chain was `previous_team_id`
(patch 1). If the user already recreated saves after that, no further
recreate is needed — patches 2–9 are all code-only. Schema-per-save means no
migrations: a schema edit requires drop + recreate of test saves.

**Files touched this chain** (latest version of each lives in the repo after
applying all zips):
- `src/main/resources/sql/save_schema.sql` — `previous_team_id` + index.
- `src/main/kotlin/f1sim/game/OffSeasonService.kt` — loyalty set/clear on
  expiry+retirement, one-cycle reset, sponsor renewal + WCC perf scaling,
  academy in cost tick.
- `src/main/kotlin/f1sim/game/DriverMarketService.kt` — loyalty (both sides),
  trait reads, age decay, salary noise, offer bounds.

**Good next chunks** (small, backend-only unless noted; see "Known issues"
and "Left to build" for full context):
- **AI affordability check** — gate AI signings on `teams.cash_reserves` in
  `DriverMarketService`; AI currently signs regardless of money.
- **AI personality in market** — `ai_aggression` / `ai_ambition` /
  `ai_frugality` exist on teams but market scoring ignores them.
- **Frontend (needs the Vue files, not in recent zips)** — surface
  `academy_investment` in TeamsPanel; distinguish sponsor renewal vs revenue
  events in the off-season events panel.

**To resume:** pick a chunk, copy the relevant current file(s) from the repo
into a fresh work dir, make the edit, update this handoff block + the
matching "Known issues" entry, zip with `src/main/...` structure, deliver.

## Built so far

**Framework.** Gradle Kotlin project. `Database.withConnection { }` sets
`search_path` to the loaded save schema on every borrow. `withAdminConnection
{ }` for admin ops. No migration framework — `save_schema.sql` runs from
scratch per save creation.

**Save lifecycle.** `public.saves` registry table, `save_xxx` schemas per
save. `SaveService` does create / list / load / delete; save creation runs
the seed loader inside the same transaction as the registry + game-row
inserts. `SaveSession` (AtomicReference singleton) holds the currently-loaded
save id and schema name.

**Schema (per-save).** `game` (singleton), `regulation_eras`, `tracks`,
`races`, `tyre_compounds`, `sponsors`, `engine_suppliers`, `teams`,
`drivers`, `personnel`, `pu_versions`, `race_results`, `sprint_results`,
`practice_focus`, `race_strategy`, `off_season_events`, `team_sponsorships`.

**Seeds.** JSON files in `src/main/resources/seeds/`. SeedLoader reads,
batch-inserts in FK order, then runs `computeDerivedSeedValues` to fill
`teams.base_operating_cost` (30M + prestige × 1M) and `drivers.current_salary`
(banded by stat_pace: ≥90 → $20M, 80-89 → $8M, 70-79 → $2M, else $500k).
Loaded: 1 era (2026), 8 compounds, 10 tracks, 10-race 2026 calendar with 2
sprints, 5 F1 teams, 10 drivers, 15 personnel, 12 sponsors, 3 engine
suppliers, 3 Mk1 PUs. Team sponsorships are seeded inline in SeedLoader
(15 deals across 5 F1 teams for 2026-2027). Names are fictional placeholders.

**Phase machine.** Flat enum, 11 phases. `PhaseMachine.next(state,
roundsPerSeason, isSprintWeekend, isMarketComplete)` is pure.
`GameService` queries `races` for season length and current-round sprint
flag, consults `DriverMarketService` for market completion, calls
PhaseMachine, runs transition hooks, ticks `last_played_at`. Sprint
weekends (Spielberg, Interlagos) properly go through `PRACTICE →
SPRINT_QUALIFYING → SPRINT → QUALIFYING → RACE`. Off-season runs
`END_OF_SEASON → OFF_SEASON → DRIVER_MARKET (N rounds) → PRE_SEASON`.
DRIVER_MARKET self-loops until `isMarketComplete = true`.

**Race weekend gameplay (complete, sprint and standard).** Qualifying sim,
race sim, and sprint sim are pure in `RaceSim` (no DB, no logging). Stat
fields are `Double` in the sim path; DB stays `Int`, cast at the boundary
so multiplicative bonuses (SETUP +1%, strategy ±2 pace) don't truncate.
Deterministic RNG: `Random(masterSeed XOR raceId.hashCode() XOR phase_salt)`
with `QUALIFYING_SALT`, `RACE_SALT`, `SPRINT_QUALIFYING_SALT`, `SPRINT_SALT`,
`RETIRE_SALT` constants for replay parity.

- **Qualifying:** `stat_qualifying + gaussian(σ=8)`, sorted, grid assigned.
  Pole flag on P1. Reused for sprint qualifying with a different salt so
  the sprint grid is uncorrelated with the main quali grid.
- **Race:** finishers sort by `stat_pace + grid_bonus(P) + strategy_paceBonus
  + gaussian(σ × consistency × strategy_sigmaMultiplier)`. Grid bonus
  `6.0 * exp(-0.25*(P-1))` (P1 +6, P10 +0.5). DNF roll per driver, base 4%
  modulated by consistency. Fastest lap weighted-pick from top-5 finishers,
  +1 point only if `regulation_eras.fastest_lap_point = true` AND finisher
  is in top 10 (false for 2026 — current rules removed it).
- **Sprint:** same scoring shape as race (pace + grid_bonus + gaussian) but
  no strategy archetype effects (sprints have no mandatory stops), no
  fastest-lap mechanic, DNF probability halved (2% base; sprints are ~⅓
  the distance). Reuses the qualifying sim with `SPRINT_QUALIFYING_SALT`
  to generate an independent sprint grid, then `simulateSprint` produces
  finishing positions stored in `sprint_results`. Points 8-7-6-5-4-3-2-1
  for top 8.
- **Points:** Race uses FIA 25-18-15-12-10-8-6-4-2-1; sprint uses
  8-7-6-5-4-3-2-1 top 8.
- **Practice focus:** `practice_focus` table per (race, driver), set during
  PRACTICE phase. `SETUP` gives ×1.01 multiplier to `stat_qualifying` and
  `stat_pace` for that race. On sprint weekends the same row applies to
  both sprint qualifying and main qualifying — one practice session sets
  the car up for both. Other focuses are no-ops in v1 (stub for tyre /
  reliability / dev feedback later).
- **Strategy:** `race_strategy` table per (race, driver), set during
  QUALIFYING phase only. Archetypes `M_H`, `S_H`, `S_M_M`, `M_M_H`, `S_S_H`.
  Each modifies race sim's pace bonus (0 to +2) and sigma multiplier (0.9
  to 1.2). Sprint doesn't read strategy — no archetype slot in the sprint
  sim. Wet variants deferred until weather model exists.
- **Post-race:** `runPostRaceHook` re-aggregates `teams.season_points` from
  `race_results UNION ALL sprint_results` for the current year.

**Standings.** `StandingsService` aggregates from `race_results +
sprint_results`. Points = `SUM(race_results.points) +
SUM(sprint_results.sprint_points)` within season, joined per-driver and
per-team. Wins / podiums / poles remain race-only per design doc
("Sprint wins/poles tracked separately from race wins/poles in career
stats"). `GET /api/standings?type=driver|team|both&season=` returns the
leaderboard.

**Off-season / year-flip pipeline (7 of 11 steps).** `OffSeasonService`
owns year-level hooks; `DriverMarketService` owns the market. Per-save
deterministic via master seed + per-step salt constants. Skips
END_OF_SEASON / OFF_SEASON hooks if no races were simulated (prevents
first-advance aging on fresh saves).

- **END_OF_SEASON hook:**
  - **FOM prize money.** Hardcoded curve P1-P10: $180M/$160M/$140M/$125M/
    $110M/$95M/$80M/$70M/$60M/$50M. Distributed to F1 teams ordered by
    `season_points` DESC, tiebreak by team_id alphabetical. Adds to
    `current_year_income` so it flows through finance settle.
  - **Finance settle.** `cash_reserves += (income - expenses)`, then
    `income = 0, expenses = 0`. One event per F1 team.
- **OFF_SEASON hook (ordered):**
  - **Aging tick.** Drivers `current_age += 1`, personnel `age += 1`. For
    those past `trait_peak_age`, drop `stat_pace` (drivers) /
    `skill_design` (personnel) by `(age - peak_age) * decline_rate`,
    `roundToInt`, floored at 0. Two events per entity (AGE_TICK always;
    STAT_DRIFT only when stat moved).
  - **Retirement rolls.** Drivers > 32: roll `rng.nextDouble() < trait_
    retirement_threshold`. Personnel > 55: linear curve 5% at 60 → 100%
    at 75 (`personnelRetirementThresholdAt`). Retirees get
    `retired = true`, all team affiliations nulled.
  - **Contract expirations.** Drivers / personnel where
    `contract_expires_year <= endingSeasonYear` and not already retired
    have their team affiliations nulled. `contract_expires_year/round`
    kept as historical record. Purely date-driven — no RNG.
- **DRIVER_MARKET phase** (between OFF_SEASON and PRE_SEASON):
  - **Driver market.** New phase. Initialized on entry; each advance
    resolves one round of matching; auto-exits to PRE_SEASON when
    `total_rounds` (default 3) have been resolved. Player submits
    offers between rounds via `/api/market/driver/offers`; AI fills
    the remaining seats per the same scoring as before (pace + age +
    morale + noise on the team side, prestige + recent results + noise
    on the driver side). The player team's seats are reserved for
    player offers — AI cannot sign drivers to it. Offers cleared at
    each round resolution. State held in `driver_market_state`
    (singleton) and `driver_market_offers` tables.
- **PRE_SEASON hook:**
  - **Sponsor renewal stub.** Lapsed deals (`end_year < new_season_year`,
    and `>= new_season_year - 1` to keep it just-expired) auto-renew at
    `oldValue * (0.9..1.1)` for `SPONSOR_RENEWAL_TERM_YEARS = 2` more
    years. Per-deal RNG keyed on `deal.id XOR year XOR SPONSOR_RENEW_SALT`
    for replay parity. Logged as `SPONSOR_REVENUE` event_type with a
    "renewed" disambiguator (avoids a CHECK constraint update). Sponsors of
    below-mid-grid teams may defect (no renewal) instead — see Known issues.
  - **Sponsor revenue tick.** Sum each team's active sponsorships
    (`start_year <= year <= end_year`), set `current_year_income`. One
    event per team with revenue.
  - **Operating cost tick.** `base_operating_cost + academy_investment +
    sum(driver salaries where racing for team) + sum(personnel salaries
    where on team)`, set `current_year_expenses`. Reflects market
    signings (player offer salaries and AI-computed salaries both feed
    in here).

All off-season hooks log to `off_season_events`. Market signings reuse
the same table with `event_type = 'MARKET_SIGNING'`. Inside the same
transaction as the state changes — atomic per advance.

**Economy (v1 balanced).** Top teams clear ~$145M/year surplus; back-of-grid
teams hover near break-even. With 5-team grid: P1 nets ~$330M income vs
~$170M expenses; Albion GP (P5) nets ~$123M income vs ~$77M expenses. Multi-
year cash trajectory is now meaningful.

**HTTP API.** All under `/api/`. Envelope: `{ data, errors, meta:
{ elapsed_ms } }`. Routes by resource: save lifecycle, game state, reads
(teams, drivers, personnel, tracks, races, race-results, sprint-results,
engine-suppliers, pu-versions, tyre-compounds, regulation-eras, sponsors,
team-sponsorships), race-weekend (practice + strategy view/set), standings,
off-season report. `timed` extension wraps handlers with envelope + timing +
exception → status mapping (NotFoundException → 404,
IllegalArgumentException → 400, IllegalStateException → 400 BAD_STATE).

**Frontend.** Vue 3 + Vite, plain JS. 11 tabs: Saves, Game, Calendar, Teams,
Sponsorships, Drivers, Practice, Strategy, Results, Off-Season, Reference.
Each panel: fetch on mount, refresh button, error display, raw JSON viewer.
Test-focused — surfaces backend errors verbatim, shows response timing,
minimal styling. Results panel shows a "Sprint" table above the "Race"
table on sprint weekends.

## Left to build

### Big systems — multiple rounds each

- **Personnel market.** Same mechanics as driver market, smaller pool,
  tier order: principal → TD → strategist → crew chief → race engineers.
  Driver market structure is reusable (`DriverMarketService` pattern,
  phase split, offer table) — personnel version would be a parallel
  service with different scoring weights and a different offer table.
- **Richer counter-bidding.** Closest-prestige AI rival now bumps its
  scoring on player-contested drivers; that's the v1. Natural
  extensions: secondary rival also bumps (half-strength), bonus scales
  with how aggressive the player's offer is (high-salary offers signal
  importance and provoke harder counters), AI personality
  (`ai_aggression`) modulates the bump size.
- **R&D.** `part_versions`, `team_parts_current` (or just MAX(mk_version)
  per supplier-style), `development_projects`. Player allocates R&D budget
  by part type at season start; tick in `BETWEEN_ROUNDS` hook. Design doc
  § "Car Performance Model" + § "R&D allocation". Independent of markets
  — can slot in any time. Car quality will then modulate driver pace in
  RaceSim.
- **News / events.** `event_templates`, `event_log`. Prerequisite eval,
  decision branching, effects application. Transition hooks become where
  events get emitted.
- **F2 / F3.** Seeds for grids, per-series sim, junior promotion logic
  (F2 champion → F1 seat). The series CHECK already permits F2/F3 but
  nothing is seeded.

### Medium chunks — ~one round each

- **Off-season remaining steps:**
  - Regulation reset (apply `performance_reset_severity` to car stats at
    era boundaries).
  - Junior promotions (F2 champion → F1 seat).
  - Sponsor refresh / market (renegotiate at deal end, defection on poor
    performance).
  - Calendar generation for future years (currently 2026 only).
- **Board pressure.** Season-start targets (constructors' position,
  cash-trajectory floor), end-of-season verdict, consequences (firing,
  budget cuts). Uses `season_points`, `cash_reserves`, and the
  `teams.board_*` traits we already store.
- **Budget cap enforcement.** `cap_compliance_status` exists but isn't
  enforced. Need a tick that checks expenses against an era-defined cap,
  applies penalties (financial, future development restrictions).
- **Tyre system.** Per-stint compounds + degradation modelling. Would
  deepen strategy from "pick an archetype" to "pick compound choices and
  see degradation play out". `tyre_compounds` table already seeded.
- **Track-demand × strategy interaction.** Currently S_S_H is universally
  rewarded — Monza should favour low-deg strategies, Monaco should reward
  one-stop. Modulate strategy effects by `tracks.demand_tyre_wear`.
- **Weather model.** Per-track climate baselines exist; need forecast
  generation, unfolding during the weekend, `stat_wet_skill` matters.
- **Historical tables.** `driver_team_stints`, `personnel_team_stints`,
  `team_engine_supplier_stints`, `season_championships`,
  `junior_season_results`. Career history for retired drivers, all-time
  records.
- **Engine supply costs.** Customer teams pay their supplier; works teams
  pay nothing. Needs `engine_supply_contracts` table.

### Loose ends — less than a round

- Sponsor performance bonuses (patch 7) and defection (patch 9) both
  landed; remaining sponsor work is new entrants joining the pool and a
  player negotiation surface.
- New sponsor entrants joining the pool yearly (current renewal stub
  only extends existing deals; the sponsor table stays static).
- Aging stat drift for stats beyond `stat_pace` / `skill_design` (other
  driver stats currently don't drift).
- Injuries / suspensions (post-race hook).
- Custom team / custom engine option at save creation.
- Driver champion bonus on FOM prize money (WDC team gets extra).
- **Sprint sigma tuning.** Sprint currently reuses `RACE_PACE_SIGMA = 6.0`.
  Shorter races have less time for variance to play out — a slightly
  lower sigma (~4.5) would better reflect that. Tuning knob, not a bug.
- **Sprint career stats.** Sprint wins / sprint poles aren't tracked in
  standings (only championship points roll up). When historical career
  stats land (`season_championships` etc.) they should split sprint vs
  race wins per the design doc.

## Conventions to follow

**ID types.** TEXT primary keys for stable reference data (eras, compounds,
tracks, teams, engine_suppliers, sponsors) — provided in JSON, human-
readable. UUID for runtime-generated entities (drivers, personnel,
pu_versions, races) — generated at load time, never in JSON. BIGSERIAL for
log tables (`off_season_events`, `team_sponsorships`).

**JSON ↔ Kotlin.** snake_case in JSON, camelCase in Kotlin. `Json` instances
use `JsonNamingStrategy.SnakeCase` globally.

**Routes pattern.** One Routes class per resource type (except tiny lookup
tables grouped into ReferenceRoutes). Each has a `SELECT_COLUMNS` constant
and a private `mapRow` — keeps DTO and SQL aligned, avoids `SELECT *`
leaking hidden fields.

**DTOs.** Nested inside their service or route class. Hidden fields
(driver `trait_*`, `development_pool`; team `ai_*`, `board_*`) are
deliberately excluded — design doc says they're never shown directly.
Use nested DTOs for semantic grouping (`DriverStatsDto`, `TrackDemandsDto`,
`TeamFinanceDto`, `PuIceDto` / `PuErsDto`, `PlayerTeamDto`).

**Filter composition.** `mutableListOf<String>()` for WHERE conditions,
`mutableListOf<Any>()` for params, joined with AND. Missing param → no
filter. Unknown value → empty result, no validation error.

**Schema changes.** Add to `save_schema.sql`. Schema-per-save model means
no migration framework — users drop test saves and recreate after schema
edits. Document what triggered the recreate. (Most recent recreate
triggers: adding `sprint_results` table; adding `CONTRACT_EXPIRED` and
`MARKET_SIGNING` to the `off_season_events.event_type` CHECK; adding
`DRIVER_MARKET` to the `game_phase_valid` CHECK; adding
`driver_market_state` and `driver_market_offers` tables; adding
`previous_team_id` column to `drivers`.)

**Pipeline hook pattern.** `OffSeasonService.runXxxHooks(conn, year, ...)`
called from the matching transition branch in
`GameService.runTransitionHooks`. All work inside the existing advance
transaction; throw to rollback the whole advance. Each step is its own
private method, called from the orchestrator. Return event counts for the
transition log.

**Event logging.** Side effects in pipeline hooks log to
`off_season_events` via `logEvents(conn, year, events)`. Subject kind is
DRIVER/PERSONNEL/TEAM; subject_id stored as TEXT regardless of UUID vs
TEXT origin to keep the table simple. Message is human-readable, includes
the actual delta values.

**Stat math.** DB stays `Int` for stats. Sim path uses `Double` in
`RaceSim.QualifyingEntrant.statQualifying`, `RaceSim.RaceEntrant.statPace`,
`RaceSim.SprintEntrant.statPace`, cast at the boundary in GameService.
Multiplicative bonuses (practice SETUP, strategy paceBonus) operate in
Double. Aging stat drift is computed in Double and `roundToInt`'d when
written back. The Int↔Double bridge is all in GameService /
OffSeasonService — RaceSim itself never sees an Int.

**Determinism.** All RNG seeded as `Random(masterSeed XOR contextKey)`
where contextKey distinguishes purpose. Salts:

- `QUALIFYING_SALT = 0x5111EFA11L` (qualifying sim)
- `RACE_SALT = 0xACE0FA10L` (race sim)
- `SPRINT_QUALIFYING_SALT = 0x5111EFB22L` (sprint qualifying sim)
- `SPRINT_SALT = 0xACE0FB21L` (sprint race sim)
- `RETIRE_SALT = 0x4E71_4E72_4E73_4E74L` (retirement rolls)
- `MARKET_SALT = 0x6D61_726B_6574_5341L` (driver market scoring)
- `SPONSOR_RENEW_SALT = 0x53504F4E_524E5731L` (sponsor deal renewal noise)

For per-entity rolls (retirement), additionally XOR `entityId.hashCode()`
so each entity gets independent randomness deterministically tied to its
ID + the save seed.

**Frontend panels.** Fetch on mount, refresh button, error block, raw JSON
viewer in `<details>`. Tab switches use `v-if` (remount), no state
preservation. No client-side mirror of "loaded save" — query the backend.

## Known issues / shortcuts

- **Driver market doesn't use AI personality.** `ai_aggression`,
  `ai_ambition`, `ai_frugality` exist on teams but the v1 market scoring
  ignores them. Frugal teams should low-ball offers; ambitious teams
  should overpay for top talent. Tuning knob for later.
- **AI counter-bidding is minimal.** The closest-prestige AI team to the
  player gets a +15 score bump on any driver the player has offered for
  this round. That flips ~half the close head-to-heads. Beyond this one
  team, AI doesn't react to the player at all — and frugality / ambition
  still aren't consulted. Tuning levers: `COUNTER_BID_BONUS` size, and
  whether the second-closest rival should also get a (smaller) bump.
- **No affordability check in the market.** AI teams sign drivers
  without consulting `cash_reserves`. Player offers are bounded by
  `MIN_OFFER_SALARY = 500k` (aligned with `MIN_RENEWAL_VALUE`) and
  `MAX_OFFER_SALARY = 75M` but otherwise unchecked — player can sign
  Erik Hansson for $75M and the game accepts it. Cost shows up in
  next season's operating cost tick. AI offers in practice top out
  around $33M (max base × max prestige × max trait_market_value_modifier),
  so 75M gives the player ~2x headroom for impulse buys without
  permitting the old $200M troll signing.
- **Loyalty effect applies on both sides, weighted by `trait_loyalty`.**
  Driver side: `LOYALTY_BONUS = 8.0 × trait_loyalty` when scoring their
  previous team. Team side: `TEAM_LOYALTY_BONUS = 10.0 × trait_loyalty`
  when scoring a driver it just released. Previous-team relationship
  tracked via `drivers.previous_team_id`: set on contract expiration,
  cleared on retirement, cleared on a new signing, and cleared for any
  driver who entered the off-season already unsigned (one-cycle reset
  at the top of `expireDriverContracts`). A very loyal (0.9) veteran
  feels the full pull; a 0.2-loyalty hothead barely registers. Tuned
  so a more-prestigious rival can still poach loyal drivers, but
  mid-tier teams gain a real edge re-signing their own.
- **AI salary offers are scaled by talent and age, but player offers
  aren't validated against either.** `DriverMarketService.computeAiSalary`
  multiplies the base bracket by four factors: team prestige
  (`prestige / 75.0`), `trait_market_value_modifier`
  (generational ~1.30, journeyman ~0.90), age decay
  (`1 - 0.05 * years_past_trait_peak_age`, floored at 0.50), and a
  per-(agent, team, round) salary noise of ±5% so equal-prestige rivals
  don't quote identical numbers. Noise is seeded from
  `MARKET_SALT xor agent.id.leastSignificantBits xor team.id.hashCode() xor round`
  for replay parity, and uses its own RNG rather than drawing from the
  outer market RNG to avoid shifting state on subsequent matching
  draws. Player offers go through `MIN_OFFER_SALARY = 500k` only — the
  player can still try to lowball a star or massively overpay a
  veteran. Could surface the trait + age factors as a scout-report
  hint, since those signals are otherwise invisible to the player.
- **Personnel contracts expire but there's no personnel market.**
  Personnel released by expiration stay unsigned indefinitely. Race
  effects don't depend on personnel yet (just pit crew rating, which is
  on the team) so it's invisible but real.
- **Empty seats stay empty.** Player team's seats specifically are
  *only* fillable by player offers — AI never poaches into them. If
  the player skips a market entirely, they go into the season with
  empty seats and the race sim will run with however many drivers
  remain on their roster. Junior promotions (F2 → F1) are the proper
  fix for filling player-skipped seats.
- **Market state is global to the save.** Only one market can be active
  at a time (singleton tables). Fine for single-player, but means
  pausing mid-market and starting a new save can leave orphan offers
  if anything goes wrong; the initialize step wipes on entry, so
  recovery is automatic.
- **Sponsor renewal scales by performance but not by player choice.**
  Lapsed deals auto-renew at `oldValue * (1 + perfMod) * (0.9..1.1)`
  for `SPONSOR_RENEWAL_TERM_YEARS = 2` years via
  `OffSeasonService.renewSponsors` (step 4b, PRE_SEASON), where
  `perfMod` is `[-0.15, +0.15]` based on last-season WCC rank (top team
  +15%, bottom team -15%, linear interpolation; from
  `readWccPerformanceModifiers`). Stacks multiplicatively with the
  existing ±10% noise → best case ~+26%, worst case ~-23% per renewal.
  Sponsors of below-mid-grid teams (negative `perfMod`) can now defect
  instead of renewing: per-deal chance = (shortfall / `SPONSOR_PERF_MAX_PCT`)
  × `SPONSOR_DEFECTION_MAX_PROB` (0.40) × the sponsor's
  `performance_sensitivity`, rolled on the same per-deal RNG (noise drawn
  first, so non-defecting renewals match patch 7 exactly). A defected deal
  keeps its old `end_year` and drops out of the just-expired window next
  year, so it's gone for good and the revenue tick skips it. Still no new
  sponsor entrants joining the pool, no negotiation surface for the player.
- **`current_year_expenses` lacks R&D and engine costs.** Once R&D
  lands, expense math needs an additional term. Currently:
  `base_operating_cost + academy_investment + driver_salaries +
  personnel_salaries`.
- **FOM prize curve is generous for sparse grids.** With 5 teams, the
  top end pays out heavily ($715M total to 5 teams). When F2 promotions
  add more teams, the same curve spread across 10 teams will feel
  balanced — but until then, top teams print money.
- **Ties in standings broken alphabetically.** No race-wins countback.
  Practically never hits with our point spreads.
- **`GameService.countRoundsInSeason` falls back to 24** if no calendar
  exists. Off-season step 11 should generate the next year's calendar
  before this fires; currently any 2027+ season uses the fallback.
- **`GameService` imports `f1sim.http.NotFoundException`.** Slight
  layering leak; move to `f1sim.common` if more services need typed
  exceptions.
- **No tests.** PhaseMachine and RaceSim are pure and obvious test
  targets. Sprint sim is especially worth a smoke test — same shape as
  race sim but easy to drift if the helpers change.
- **No migration story.** Schema changes require dropping save schemas.
  Acceptable while solo / pre-production.
- **Seeded grid is fictional.** Real team/driver names are a licensing
  open question (design doc § "Open / Deferred Items").
- **Frontend hardcodes** `BASE = 'http://localhost:7777'` in `api.js`.
- **F2 / F3 entirely inert.** Schema permits the series; no seeds, no
  sim, no promotion logic.
- **Practice focus other than SETUP are no-ops.** TYRE_PROGRAM,
  RELIABILITY_CHECK, DEVELOPMENT_FEEDBACK affect nothing in v1.
- **No-strategy default ≠ M_H.** A driver with no strategy entry gets
  the neutral default in the sim (pace +0, sigma ×1.0). M_H specifically
  has sigma ×0.9. UI says "(default M-H)" — small lie.
- **Retired drivers/personnel stay in the table.** `retired = true` with
  team affiliations nulled. They're filtered out of entrant queries and
  salary sums. Useful for history; could be hidden from default UI lists.
- **`teams.season_points` cache is stale between SPRINT and RACE on a
  sprint weekend.** Cache is updated only in the post-race hook (after
  the main race), which aggregates race + sprint. Mid-weekend, after
  sprint has run but before the main race, the cached value lags.
  `/api/standings` computes live from `race_results + sprint_results`
  so it's always correct; the staleness only affects the cache directly.
- **Sprint sim doesn't apply strategy.** Sprints have no mandatory stops
  so there's no archetype slot. If a future strategy model adds wet-tyre
  decisions or starting-compound choices, sprint will need its own
  archetype set.

## File map

```
src/main/kotlin/f1sim/
├── Main.kt                # wiring (Database, services, Server)
├── config/AppConfig.kt    # env / sys-prop config
├── db/
│   ├── Database.kt        # HikariCP + per-borrow search_path
│   └── Migrations.kt      # SQL execution from resources
├── http/
│   ├── Server.kt          # Javalin + route registration
│   ├── Envelope.kt        # response envelope
│   ├── RouteHelpers.kt    # timed, NotFoundException, getIntOrNull
│   └── routes/
│       ├── SaveRoutes.kt
│       ├── GameRoutes.kt
│       ├── TeamRoutes.kt
│       ├── DriverRoutes.kt
│       ├── PersonnelRoutes.kt
│       ├── TrackRoutes.kt
│       ├── RaceRoutes.kt
│       ├── RaceResultsRoutes.kt
│       ├── SprintResultsRoutes.kt
│       ├── RaceWeekendRoutes.kt    # practice + strategy
│       ├── StandingsRoutes.kt
│       ├── OffSeasonRoutes.kt      # /api/off-season/report
│       ├── DriverMarketRoutes.kt   # /api/market/driver/*
│       ├── TeamSponsorshipRoutes.kt
│       ├── PowerUnitRoutes.kt
│       ├── SponsorRoutes.kt
│       └── ReferenceRoutes.kt
├── save/
│   ├── SaveService.kt     # save lifecycle
│   └── SaveSession.kt     # currently-loaded save (singleton)
├── seed/
│   ├── Seeds.kt           # seed data classes
│   └── SeedLoader.kt      # JSON → batch insert + computeDerivedSeedValues
├── game/
│   ├── Phase.kt           # enum + GameState
│   ├── PhaseMachine.kt    # pure next() (with isMarketComplete)
│   ├── GameService.kt     # state I/O, transition hooks
│   ├── RaceWeekendService.kt   # practice focus + strategy
│   ├── StandingsService.kt
│   ├── OffSeasonService.kt     # year-flip pipeline
│   └── DriverMarketService.kt  # driver market lifecycle + matching
└── sim/
    └── RaceSim.kt         # pure qualifying + race + sprint simulation

src/main/resources/
├── sql/
│   ├── public_schema.sql  # public.saves registry
│   └── save_schema.sql    # all per-save tables
├── seeds/                 # JSON seed files
└── logback.xml

frontend/
└── src/
    ├── App.vue            # tab shell, 12 panels
    ├── api.js             # backend client, BASE hardcoded
    └── panels/
        ├── SavesPanel.vue
        ├── GamePanel.vue
        ├── CalendarPanel.vue
        ├── TeamsPanel.vue
        ├── SponsorshipsPanel.vue
        ├── DriversPanel.vue
        ├── PracticePanel.vue
        ├── StrategyPanel.vue
        ├── ResultsPanel.vue
        ├── MarketPanel.vue
        ├── OffSeasonPanel.vue
        └── ReferencePanel.vue
```

## Gotchas worth remembering

- **Postgres reserves `CURRENT_ROLE`** (and `CURRENT_USER`, `SESSION_USER`,
  `CURRENT_DATE`, `CURRENT_TIME`, `CURRENT_TIMESTAMP`, `LOCALTIME`,
  `LOCALTIMESTAMP`, `USER`). The personnel role column is `role`.
- **Brace expansion doesn't work in `sh`** — `mkdir -p foo/{a,b}` will
  literally create `foo/{a,b}` under sh. Use explicit paths or `bash -c`.
- **Schema-per-save means cross-save references can't exist.** Anything
  cross-save (all-time stats, global achievements) needs a different
  storage model — probably in `public`.
- **Circular FKs need deferred `ALTER`.** `engine_suppliers.works_team_id`
  and `game.player_team_id` both reference `teams`, which is created later
  in `save_schema.sql`. They're added via `ALTER TABLE … ADD CONSTRAINT`
  after `teams` exists.
- **Drivers F1/reserve constraint is app-level.** If `current_racing_team_id`
  is an F1 team, `reserve_for_team_id` must be null. Not expressible as a
  CHECK (depends on a join into `teams.series`). Enforce in any new write
  paths.
- **Singleton tables.** `game` uses `CREATE UNIQUE INDEX ON game ((true))`
  to enforce exactly one row. Cheap trick.
- **PhaseMachine bumps year in END_OF_SEASON → OFF_SEASON.** Hooks fired
  during the OFF_SEASON transition that operate on the "ending year"
  should use `from.year`, not `to.year`. Aging and retirements use
  `from.year` for this reason. FOM and finance settle fire during
  END_OF_SEASON itself (no bump yet) and use `from.year` naturally.
  Sponsor revenue / operating cost fire during PRE_SEASON entry — they
  use the new year (`to.year`) which is the correct year for those.
- **Series filter on sim queries.** Always include `AND t.series = 'F1'`
  on driver lookups, since F2/F3 teams (when seeded) would otherwise
  silently enter F1 grids.
- **Off-season hooks skip first advance.** `hasSimulatedRaces` check
  prevents the END_OF_SEASON / OFF_SEASON pipeline running on a fresh
  save (which would age everyone by a year before they've raced).
  PRE_SEASON does *not* gate on this — first save's PRE_SEASON 2026 needs
  the revenue + cost tick to set up year 1.
- **`current_year_income` and `current_year_expenses` are set, not
  added.** The PRE_SEASON ticks replace, don't accumulate. Sponsor
  revenue sets income = revenue total; operating cost sets expenses =
  cost total. FOM prize at END_OF_SEASON does add (`income += prize`)
  because finance settle hasn't zeroed yet.
- **OFF_SEASON step order matters.** Aging → retirements → contract
  expirations. Retirements run before contract expirations so a retiring
  driver whose contract also expired ends up flagged retired (not
  released as a free agent). The expiration query requires
  `NOT retired AND has-a-team`, so the retiree is naturally skipped.
  Driver market now runs in its own phase after OFF_SEASON exits, with
  its own initialize / round / finalize hooks.
- **DRIVER_MARKET phase is self-looping.** Unlike every other phase,
  `PhaseMachine.next` from DRIVER_MARKET can return the same phase —
  the `isMarketComplete` flag from `DriverMarketService` drives the
  exit. `GameService.advance` runs the round hook BEFORE consulting
  the machine so the round counter is current. If you add new
  self-looping phases later, mirror that pattern.
- **Salt collisions.** All XOR salts must be distinct. Current set:
  `QUALIFYING_SALT`, `RACE_SALT`, `SPRINT_QUALIFYING_SALT`, `SPRINT_SALT`,
  `RETIRE_SALT`, `MARKET_SALT`, `SPONSOR_RENEW_SALT`. When adding new deterministic RNG
  contexts (e.g. personnel market, junior promotions), pick a new
  constant.
- **Sprint standings aggregation uses MAX, not SUM.** In `StandingsService`
  the sprint subquery returns one row per driver/team (pre-aggregated by
  `SUM(sprint_points)`). When LEFT JOINed to `race_results` which has
  many rows per entity, that single sprint total gets duplicated across
  rows. `MAX(sprint.pts)` over identical values picks it back out
  correctly; `SUM(sprint.pts)` would multiply by the race count. Easy
  trap if extending the query.
- **`hasSimulatedRaces` only checks `race_results`.** A weekend where
  the sprint ran but the main race was rolled back wouldn't count.
  Currently fine since the advance transaction is atomic per phase, but
  worth knowing if the granularity changes.

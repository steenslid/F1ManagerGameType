-- Per-save schema. Run against a freshly-created `save_xxx` schema with
-- `search_path TO save_xxx, public` set.
--
-- Conventions:
--   * TEXT primary keys for stable reference data seeded from JSON
--     (eras, tracks, compounds, teams, engine_suppliers, sponsors).
--   * UUID primary keys for runtime-generated entities
--     (drivers, personnel, pu_versions).
--   * Per-save uniqueness is implicit: this entire file runs inside ONE save schema.

-- ============================================================================
-- The save's identity row
-- ============================================================================

CREATE TABLE game (
    save_id                UUID         PRIMARY KEY,
    save_name              TEXT         NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_played_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    schema_version         INT          NOT NULL,

    player_team_id         TEXT,
    player_manager_name    TEXT         NOT NULL,
    difficulty             TEXT         NOT NULL,
    master_rng_seed        BIGINT       NOT NULL,

    current_season_year    INT          NOT NULL,
    current_round          INT          NOT NULL DEFAULT 0,
    current_phase          TEXT         NOT NULL DEFAULT 'OFF_SEASON',

    CONSTRAINT game_difficulty_valid CHECK (difficulty IN ('EASY','NORMAL','HARD','BRUTAL')),
    CONSTRAINT game_phase_valid CHECK (current_phase IN (
        'OFF_SEASON','PRE_SEASON','RACE_WEEKEND','BETWEEN_ROUNDS','END_OF_SEASON'
    ))
);

CREATE UNIQUE INDEX game_singleton ON game ((true));

-- ============================================================================
-- Reference: regulation eras
-- ============================================================================

CREATE TABLE regulation_eras (
    id                              TEXT         PRIMARY KEY,
    name                            TEXT         NOT NULL,
    start_year                      INT          NOT NULL,
    end_year                        INT,
    performance_reset_severity      NUMERIC(3,2) NOT NULL,
    ers_share                       NUMERIC(3,2) NOT NULL,

    CONSTRAINT reg_eras_reset_range CHECK (performance_reset_severity BETWEEN 0 AND 1),
    CONSTRAINT reg_eras_ers_range CHECK (ers_share BETWEEN 0 AND 1),
    CONSTRAINT reg_eras_years_valid CHECK (end_year IS NULL OR end_year >= start_year)
);

-- ============================================================================
-- Reference: tracks
-- Per-track demand profile mirrors the car performance characteristic axes.
-- ============================================================================

CREATE TABLE tracks (
    id                              TEXT         PRIMARY KEY,
    name                            TEXT         NOT NULL,
    country                         TEXT         NOT NULL,
    length_km                       NUMERIC(6,3) NOT NULL,
    type                            TEXT         NOT NULL,

    -- Demand weights on car performance axes (0..1)
    demand_top_speed                NUMERIC(3,2) NOT NULL,
    demand_acceleration             NUMERIC(3,2) NOT NULL,
    demand_low_speed_cornering      NUMERIC(3,2) NOT NULL,
    demand_medium_speed_cornering   NUMERIC(3,2) NOT NULL,
    demand_high_speed_cornering     NUMERIC(3,2) NOT NULL,
    demand_braking                  NUMERIC(3,2) NOT NULL,
    demand_tyre_wear                NUMERIC(3,2) NOT NULL,
    demand_cooling                  NUMERIC(3,2) NOT NULL,

    -- Race operations
    pit_lane_loss_seconds           NUMERIC(4,2) NOT NULL,
    overtake_difficulty             NUMERIC(3,2) NOT NULL,

    -- Climate baseline
    rain_probability_baseline       NUMERIC(3,2) NOT NULL,
    temperature_min_c               NUMERIC(4,1) NOT NULL,
    temperature_max_c               NUMERIC(4,1) NOT NULL,

    CONSTRAINT tracks_type_valid CHECK (type IN (
        'STREET','HIGH_SPEED','TECHNICAL','BALANCED','HIGH_DOWNFORCE'
    )),
    CONSTRAINT tracks_overtake_range CHECK (overtake_difficulty BETWEEN 0 AND 1),
    CONSTRAINT tracks_rain_range CHECK (rain_probability_baseline BETWEEN 0 AND 1),
    CONSTRAINT tracks_temp_range CHECK (temperature_max_c >= temperature_min_c)
);

-- ============================================================================
-- Reference: tyre compounds (C0–C5, INTER, WET)
-- ============================================================================

CREATE TABLE tyre_compounds (
    id                              TEXT         PRIMARY KEY,
    name                            TEXT         NOT NULL,
    base_pace_factor                NUMERIC(4,3) NOT NULL,
    degradation_rate                NUMERIC(5,4) NOT NULL,
    longevity_laps                  INT          NOT NULL,
    optimal_temp_min_c              NUMERIC(4,1) NOT NULL,
    optimal_temp_max_c              NUMERIC(4,1) NOT NULL,

    CONSTRAINT tyre_temp_range CHECK (optimal_temp_max_c >= optimal_temp_min_c)
);

-- ============================================================================
-- Reference: sponsors
-- ============================================================================

CREATE TABLE sponsors (
    id                              TEXT         PRIMARY KEY,
    name                            TEXT         NOT NULL,
    country                         TEXT         NOT NULL,
    tier                            TEXT         NOT NULL,
    industry                        TEXT         NOT NULL,
    prestige                        INT          NOT NULL,

    -- Preferences (0..1)
    performance_sensitivity         NUMERIC(3,2) NOT NULL,
    risk_tolerance                  NUMERIC(3,2) NOT NULL,
    prestige_preference             NUMERIC(3,2) NOT NULL,

    -- Annual deal value bounds
    budget_min                      BIGINT       NOT NULL,
    budget_max                      BIGINT       NOT NULL,

    CONSTRAINT sponsors_tier_valid CHECK (tier IN ('TITLE','PRIMARY','SECONDARY','MINOR')),
    CONSTRAINT sponsors_budget_valid CHECK (budget_max >= budget_min)
);

-- ============================================================================
-- Engine suppliers
-- Has a nullable FK to teams.works_team_id, added via ALTER below
-- to break the circular dependency with teams.
-- ============================================================================

CREATE TABLE engine_suppliers (
    id                              TEXT         PRIMARY KEY,
    name                            TEXT         NOT NULL,
    country                         TEXT         NOT NULL,
    entered_year                    INT          NOT NULL,
    exited_year                     INT,
    works_team_id                   TEXT,        -- FK added after teams exists
    is_custom_supplier              BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT suppliers_years_valid CHECK (exited_year IS NULL OR exited_year >= entered_year)
);

-- ============================================================================
-- Teams
-- The most state-heavy entity: AI personality, finances, board posture, cached
-- season state all live here.
-- ============================================================================

CREATE TABLE teams (
    id                              TEXT         PRIMARY KEY,
    name                            TEXT         NOT NULL,
    country                         TEXT         NOT NULL,
    base_country                    TEXT         NOT NULL,
    series                          TEXT         NOT NULL,
    prestige                        INT          NOT NULL,
    is_custom_team                  BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Finance (per-season state)
    cash_reserves                   BIGINT       NOT NULL DEFAULT 0,
    current_year_income             BIGINT       NOT NULL DEFAULT 0,
    current_year_expenses           BIGINT       NOT NULL DEFAULT 0,
    heritage_payment                BIGINT       NOT NULL DEFAULT 0,
    base_operating_cost             BIGINT       NOT NULL DEFAULT 0,
    academy_investment              BIGINT       NOT NULL DEFAULT 0,
    cap_compliance_status           TEXT         NOT NULL DEFAULT 'COMPLIANT',

    -- Capabilities
    regulation_understanding        NUMERIC(3,2) NOT NULL DEFAULT 0.50,
    pit_crew_rating                 INT          NOT NULL DEFAULT 50,

    -- AI personality vector (0..1)
    ai_aggression                   NUMERIC(3,2) NOT NULL DEFAULT 0.50,
    ai_ambition                     NUMERIC(3,2) NOT NULL DEFAULT 0.50,
    ai_loyalty                      NUMERIC(3,2) NOT NULL DEFAULT 0.50,
    ai_frugality                    NUMERIC(3,2) NOT NULL DEFAULT 0.50,
    ai_development_focus            NUMERIC(3,2) NOT NULL DEFAULT 0.50,

    -- Board calibration
    board_ambition                  NUMERIC(3,2) NOT NULL DEFAULT 0.50,
    board_realism                   NUMERIC(3,2) NOT NULL DEFAULT 0.50,

    -- Cached season state
    season_points                   INT          NOT NULL DEFAULT 0,

    CONSTRAINT teams_series_valid CHECK (series IN ('F1','F2','F3')),
    CONSTRAINT teams_cap_status_valid CHECK (cap_compliance_status IN ('COMPLIANT','WARNING','PENALISED')),
    CONSTRAINT teams_prestige_nonneg CHECK (prestige >= 0),
    CONSTRAINT teams_pit_crew_range CHECK (pit_crew_rating BETWEEN 0 AND 100)
);

-- Close the circular FK now that teams exists.
ALTER TABLE engine_suppliers
    ADD CONSTRAINT engine_suppliers_works_team_fk
    FOREIGN KEY (works_team_id) REFERENCES teams(id) ON DELETE SET NULL;

-- Helpful index for the common "find teams in series X" query.
CREATE INDEX teams_series_idx ON teams (series);

-- ============================================================================
-- Drivers
-- App-level constraint (not in DB): if current_racing_team_id refers to an F1
-- team, reserve_for_team_id MUST be null. Enforced by SaveService writes.
-- ============================================================================

CREATE TABLE drivers (
    id                              UUID         PRIMARY KEY,
    name                            TEXT         NOT NULL,
    nationality                     TEXT         NOT NULL,
    current_age                     INT          NOT NULL,

    -- Affiliation
    current_racing_team_id          TEXT         REFERENCES teams(id) ON DELETE SET NULL,
    reserve_for_team_id             TEXT         REFERENCES teams(id) ON DELETE SET NULL,
    academy_team_id                 TEXT         REFERENCES teams(id) ON DELETE SET NULL,
    retired                         BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Contract
    current_salary                  BIGINT       NOT NULL DEFAULT 0,
    contract_expires_year           INT,
    contract_expires_round          INT,

    -- Development & morale
    development_pool                INT          NOT NULL DEFAULT 0,
    morale                          INT          NOT NULL DEFAULT 50,

    -- Composite stats (0..100)
    stat_pace                       INT          NOT NULL,
    stat_qualifying                 INT          NOT NULL,
    stat_overtaking                 INT          NOT NULL,
    stat_defending                  INT          NOT NULL,
    stat_consistency                INT          NOT NULL,
    stat_tyre_management            INT          NOT NULL,
    stat_ers_deployment             INT          NOT NULL,
    stat_wet_skill                  INT          NOT NULL,
    stat_feedback_quality           INT          NOT NULL,

    -- Hidden traits — generated at creation, never shown directly
    trait_peak_age                  INT          NOT NULL,
    trait_decline_rate              NUMERIC(4,3) NOT NULL,
    trait_retirement_threshold      NUMERIC(3,2) NOT NULL,
    trait_loyalty                   NUMERIC(3,2) NOT NULL,
    trait_temperament               NUMERIC(3,2) NOT NULL,
    trait_market_value_modifier     NUMERIC(4,3) NOT NULL,

    CONSTRAINT drivers_age_range CHECK (current_age BETWEEN 14 AND 60),
    CONSTRAINT drivers_morale_range CHECK (morale BETWEEN 0 AND 100),
    CONSTRAINT drivers_stat_pace_range CHECK (stat_pace BETWEEN 0 AND 100),
    CONSTRAINT drivers_stat_qualifying_range CHECK (stat_qualifying BETWEEN 0 AND 100),
    CONSTRAINT drivers_stat_overtaking_range CHECK (stat_overtaking BETWEEN 0 AND 100),
    CONSTRAINT drivers_stat_defending_range CHECK (stat_defending BETWEEN 0 AND 100),
    CONSTRAINT drivers_stat_consistency_range CHECK (stat_consistency BETWEEN 0 AND 100),
    CONSTRAINT drivers_stat_tyre_range CHECK (stat_tyre_management BETWEEN 0 AND 100),
    CONSTRAINT drivers_stat_ers_range CHECK (stat_ers_deployment BETWEEN 0 AND 100),
    CONSTRAINT drivers_stat_wet_range CHECK (stat_wet_skill BETWEEN 0 AND 100),
    CONSTRAINT drivers_stat_feedback_range CHECK (stat_feedback_quality BETWEEN 0 AND 100)
);

CREATE INDEX drivers_racing_team_idx ON drivers (current_racing_team_id);
CREATE INDEX drivers_reserve_team_idx ON drivers (reserve_for_team_id);
CREATE INDEX drivers_academy_team_idx ON drivers (academy_team_id);

-- ============================================================================
-- Personnel
-- Skills are kept as flat columns; the active role picks which is "primary".
-- ============================================================================

CREATE TABLE personnel (
    id                              UUID         PRIMARY KEY,
    name                            TEXT         NOT NULL,
    nationality                     TEXT         NOT NULL,
    age                             INT          NOT NULL,

    current_team_id                 TEXT         REFERENCES teams(id) ON DELETE SET NULL,
    current_role                    TEXT,
    current_salary                  BIGINT       NOT NULL DEFAULT 0,
    contract_expires_year           INT,
    contract_expires_round          INT,
    retired                         BOOLEAN      NOT NULL DEFAULT FALSE,

    development_pool                INT          NOT NULL DEFAULT 0,

    -- Skill ratings (0..100). The active role determines which is primary.
    skill_leadership                INT          NOT NULL,  -- PRINCIPAL
    skill_design                    INT          NOT NULL,  -- TECHNICAL_DIRECTOR
    skill_strategy                  INT          NOT NULL,  -- CHIEF_STRATEGIST
    skill_crew_management           INT          NOT NULL,  -- CREW_CHIEF
    skill_driver_management         INT          NOT NULL,  -- RACE_ENGINEER

    -- Hidden traits
    trait_peak_age                  INT          NOT NULL,
    trait_decline_rate              NUMERIC(4,3) NOT NULL,
    trait_loyalty                   NUMERIC(3,2) NOT NULL,

    CONSTRAINT personnel_role_valid CHECK (current_role IS NULL OR current_role IN (
        'PRINCIPAL','TECHNICAL_DIRECTOR','CHIEF_STRATEGIST','CREW_CHIEF','RACE_ENGINEER'
    )),
    CONSTRAINT personnel_age_range CHECK (age BETWEEN 20 AND 75),
    CONSTRAINT personnel_skill_leadership_range CHECK (skill_leadership BETWEEN 0 AND 100),
    CONSTRAINT personnel_skill_design_range CHECK (skill_design BETWEEN 0 AND 100),
    CONSTRAINT personnel_skill_strategy_range CHECK (skill_strategy BETWEEN 0 AND 100),
    CONSTRAINT personnel_skill_crew_range CHECK (skill_crew_management BETWEEN 0 AND 100),
    CONSTRAINT personnel_skill_driver_range CHECK (skill_driver_management BETWEEN 0 AND 100)
);

CREATE INDEX personnel_team_idx ON personnel (current_team_id);

-- ============================================================================
-- Power-unit versions: one row per supplier per Mk
-- Old Mks stay around as history; the "current" PU for a supplier is the row
-- with the highest mk_version (no extra current-pointer table needed).
-- ============================================================================

CREATE TABLE pu_versions (
    id                              UUID         PRIMARY KEY,
    supplier_id                     TEXT         NOT NULL REFERENCES engine_suppliers(id) ON DELETE CASCADE,
    mk_version                      INT          NOT NULL,
    introduced_year                 INT          NOT NULL,

    -- ICE (internal combustion)
    ice_top_speed                   INT          NOT NULL,
    ice_fuel_efficiency             INT          NOT NULL,
    ice_weight                      INT          NOT NULL,

    -- ERS
    ers_deployment_power            INT          NOT NULL,
    ers_recovery_rate               INT          NOT NULL,
    ers_deployment_efficiency       INT          NOT NULL,

    -- Shared
    reliability                     INT          NOT NULL,
    cooling_requirement             INT          NOT NULL,

    UNIQUE (supplier_id, mk_version),
    CONSTRAINT pu_versions_mk_positive CHECK (mk_version >= 1),
    CONSTRAINT pu_versions_reliability_range CHECK (reliability BETWEEN 0 AND 100)
);

CREATE INDEX pu_versions_supplier_idx ON pu_versions (supplier_id);

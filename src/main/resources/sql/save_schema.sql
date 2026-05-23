-- Per-save schema. This file is run against a freshly-created `save_xxx` schema.
-- The session must have `search_path TO save_xxx, public` set before running.
--
-- v1 starter: only the `game` row exists. Add reference + historical + current-state
-- tables here as the domain grows (drivers, teams, races, etc.).

CREATE TABLE game (
    save_id                UUID         PRIMARY KEY,
    save_name              TEXT         NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_played_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    schema_version         INT          NOT NULL,

    player_team_id         UUID,           -- nullable until save setup picks a team
    player_manager_name    TEXT         NOT NULL,
    difficulty             TEXT         NOT NULL,
    master_rng_seed        BIGINT       NOT NULL,

    current_season_year    INT          NOT NULL,
    current_round          INT          NOT NULL DEFAULT 0,
    current_phase          TEXT         NOT NULL DEFAULT 'OFF_SEASON',

    CONSTRAINT game_difficulty_valid CHECK (difficulty IN ('EASY', 'NORMAL', 'HARD', 'BRUTAL')),
    CONSTRAINT game_phase_valid CHECK (current_phase IN (
        'OFF_SEASON', 'PRE_SEASON', 'RACE_WEEKEND', 'BETWEEN_ROUNDS', 'END_OF_SEASON'
    ))
);

-- Singleton guard: exactly one row in `game` per schema.
CREATE UNIQUE INDEX game_singleton ON game ((true));

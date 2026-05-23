-- Public schema. Lives in the `public` namespace.
-- One registry row per save; each save has its own dedicated schema.

CREATE TABLE IF NOT EXISTS public.saves (
    save_id          UUID         PRIMARY KEY,
    schema_name      TEXT         NOT NULL UNIQUE,
    save_name        TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_played_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    schema_version   INT          NOT NULL,
    CONSTRAINT saves_schema_name_format CHECK (schema_name ~ '^save_[a-z0-9_]+$')
);

CREATE INDEX IF NOT EXISTS saves_last_played_idx
    ON public.saves (last_played_at DESC);

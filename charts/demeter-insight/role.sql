-- The read-only role demeter-insight connects as.
--
-- Run once per database, as a superuser. Idempotent: safe to re-run, and safe
-- to run before the tables exist.
--
--   psql -U demeter -d demeter -v pw="'a-real-password'" -f role.sql
--
-- Why a role rather than a convention: the insight service is a second consumer
-- of a schema whose contents cannot be rebuilt -- flyers expire, so a deleted
-- observation is gone for good. "It only has GET routes" is a property of code
-- that someone can change; this is a property of the database that they cannot.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'demeter_read') THEN
    EXECUTE format('CREATE ROLE demeter_read LOGIN PASSWORD %L', current_setting('demeter.read_password'));
  END IF;
END
$$;

GRANT CONNECT ON DATABASE demeter TO demeter_read;
GRANT USAGE ON SCHEMA public TO demeter_read;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO demeter_read;

-- Load-bearing. Without it the role can read today's tables and not tomorrow's:
-- the next migration adds a table the reader cannot see, and the failure looks
-- like a bug in the reader rather than a missing grant.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO demeter_read;

-- Explicitly NOT granted: INSERT, UPDATE, DELETE, TRUNCATE, USAGE on sequences.
-- A reader that can advance a sequence can still cause a primary-key collision
-- in the writer.

-- ---------------------------------------------------------------------------
-- The watch-editing role.
--
-- Adding a UI for the watchlist means demeter-insight has to write SOMETHING,
-- which reverses this change's original "no writes" decision. The decision that
-- does NOT get reversed is why it existed: the price history cannot be rebuilt,
-- because flyers expire. So this role can write watch_item and nothing else.
--
-- watch_item is user configuration -- typed in, re-typable, and worth exactly
-- what it costs to enter again. price_observation, raw_response and
-- alert_ledger stay unwritable by anything but the daily job.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'demeter_watch') THEN
    EXECUTE format('CREATE ROLE demeter_watch LOGIN PASSWORD %L', current_setting('demeter.watch_password'));
  END IF;
END
$$;

GRANT CONNECT ON DATABASE demeter TO demeter_watch;
GRANT USAGE ON SCHEMA public TO demeter_watch;
-- Reads everything the reader reads, so one connection serves both paths.
GRANT SELECT ON ALL TABLES IN SCHEMA public TO demeter_watch;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO demeter_watch;
-- Writes exactly one table.
GRANT INSERT, UPDATE, DELETE ON watch_item TO demeter_watch;

-- Deliberately NOT granted, and each for a reason:
--   price_observation / raw_response  the irreplaceable half; flyers expire
--   alert_ledger                      dedup state; forging it re-alerts or silences
--   sequences                         a writable sequence can collide the writer's keys

-- Seed data for Rappelo Bridge VoIP Gateway
-- SIP domain is inserted by 03-seed-domain.sh using environment variables

-- Dispatcher table for SIP trunks (e.g. Twilio)
CREATE TABLE IF NOT EXISTS dispatcher (
    id SERIAL PRIMARY KEY,
    setid INTEGER NOT NULL DEFAULT 0,
    destination VARCHAR(192) NOT NULL DEFAULT '',
    flags INTEGER NOT NULL DEFAULT 0,
    priority INTEGER NOT NULL DEFAULT 0,
    attrs VARCHAR(128) NOT NULL DEFAULT '',
    description VARCHAR(64) NOT NULL DEFAULT ''
);

INSERT INTO version (table_name, table_version) VALUES ('dispatcher', 4) ON CONFLICT DO NOTHING;

-- Kamailio PostgreSQL schema for SIP subscriber management
-- This creates the essential tables for Kamailio auth, location, and dialog

-- ========================================
-- SUBSCRIBER TABLE (SIP accounts)
-- ========================================
CREATE TABLE IF NOT EXISTS subscriber (
    id SERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL DEFAULT '',
    domain VARCHAR(64) NOT NULL DEFAULT '',
    ha1 VARCHAR(128) NOT NULL DEFAULT '',
    ha1b VARCHAR(128) NOT NULL DEFAULT '',
    email_address VARCHAR(128) NOT NULL DEFAULT '',
    rpid VARCHAR(128) DEFAULT NULL,
    datetime_created TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT subscriber_account_idx UNIQUE (username, domain)
);
CREATE INDEX ON subscriber (username);

-- ========================================
-- LOCATION TABLE (SIP registrations)
-- ========================================
CREATE TABLE IF NOT EXISTS location (
    id SERIAL PRIMARY KEY,
    ruid VARCHAR(64) NOT NULL DEFAULT '',
    username VARCHAR(64) NOT NULL DEFAULT '',
    domain VARCHAR(64) DEFAULT NULL,
    contact VARCHAR(512) NOT NULL DEFAULT '',
    received VARCHAR(128) DEFAULT NULL,
    path VARCHAR(512) DEFAULT NULL,
    expires TIMESTAMP NOT NULL DEFAULT '2030-05-28 21:32:15',
    q REAL NOT NULL DEFAULT 1.0,
    callid VARCHAR(255) NOT NULL DEFAULT 'Default-Call-ID',
    cseq INTEGER NOT NULL DEFAULT 1,
    last_modified TIMESTAMP NOT NULL DEFAULT NOW(),
    flags INTEGER NOT NULL DEFAULT 0,
    cflags INTEGER NOT NULL DEFAULT 0,
    user_agent VARCHAR(255) NOT NULL DEFAULT '',
    socket VARCHAR(64) DEFAULT NULL,
    methods INTEGER DEFAULT NULL,
    instance VARCHAR(255) DEFAULT NULL,
    reg_id INTEGER NOT NULL DEFAULT 0,
    server_id INTEGER NOT NULL DEFAULT 0,
    connection_id INTEGER NOT NULL DEFAULT -1,
    keepalive INTEGER NOT NULL DEFAULT 0,
    partition INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT location_ruid UNIQUE (ruid)
);
CREATE INDEX ON location (username, domain, contact);
CREATE INDEX ON location (expires);

-- ========================================
-- VERSION TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS version (
    table_name VARCHAR(32) NOT NULL,
    table_version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT version_table_name_idx UNIQUE (table_name)
);

INSERT INTO version (table_name, table_version) VALUES ('subscriber', 7) ON CONFLICT DO NOTHING;
INSERT INTO version (table_name, table_version) VALUES ('location', 9) ON CONFLICT DO NOTHING;

-- ========================================
-- DIALOG TABLE (active calls tracking)
-- ========================================
CREATE TABLE IF NOT EXISTS dialog (
    id SERIAL PRIMARY KEY,
    hash_entry INTEGER NOT NULL,
    hash_id INTEGER NOT NULL,
    callid VARCHAR(255) NOT NULL,
    from_uri VARCHAR(255) NOT NULL,
    from_tag VARCHAR(128) NOT NULL,
    to_uri VARCHAR(255) NOT NULL,
    to_tag VARCHAR(128) NOT NULL,
    caller_cseq VARCHAR(20) NOT NULL,
    callee_cseq VARCHAR(20) NOT NULL,
    caller_route_set VARCHAR(512) DEFAULT NULL,
    callee_route_set VARCHAR(512) DEFAULT NULL,
    caller_contact VARCHAR(255) NOT NULL,
    callee_contact VARCHAR(255) NOT NULL,
    caller_sock VARCHAR(64) NOT NULL DEFAULT '',
    callee_sock VARCHAR(64) NOT NULL DEFAULT '',
    state INTEGER NOT NULL,
    start_time INTEGER NOT NULL,
    timeout INTEGER NOT NULL DEFAULT 0,
    sflags INTEGER NOT NULL DEFAULT 0,
    iflags INTEGER NOT NULL DEFAULT 0,
    toroute_name VARCHAR(32) DEFAULT NULL,
    req_uri VARCHAR(255) NOT NULL DEFAULT '',
    xdata VARCHAR(512) DEFAULT NULL
);
CREATE INDEX ON dialog (hash_entry, hash_id);

INSERT INTO version (table_name, table_version) VALUES ('dialog', 7) ON CONFLICT DO NOTHING;

-- ========================================
-- DIALOG_VARS TABLE (required by dialog module)
-- ========================================
CREATE TABLE IF NOT EXISTS dialog_vars (
    id SERIAL PRIMARY KEY,
    hash_entry INTEGER NOT NULL,
    hash_id INTEGER NOT NULL,
    dialog_key VARCHAR(128) NOT NULL,
    dialog_value VARCHAR(512) NOT NULL
);
CREATE INDEX ON dialog_vars (hash_entry, hash_id);

INSERT INTO version (table_name, table_version) VALUES ('dialog_vars', 1) ON CONFLICT DO NOTHING;

-- ========================================
-- ACC TABLE (call accounting / CDR)
-- ========================================
CREATE TABLE IF NOT EXISTS acc (
    id SERIAL PRIMARY KEY,
    method VARCHAR(16) NOT NULL DEFAULT '',
    from_tag VARCHAR(128) NOT NULL DEFAULT '',
    to_tag VARCHAR(128) NOT NULL DEFAULT '',
    callid VARCHAR(255) NOT NULL DEFAULT '',
    sip_code VARCHAR(3) NOT NULL DEFAULT '',
    sip_reason VARCHAR(128) NOT NULL DEFAULT '',
    time TIMESTAMP NOT NULL DEFAULT NOW(),
    src_user VARCHAR(64) NOT NULL DEFAULT '',
    src_domain VARCHAR(128) NOT NULL DEFAULT '',
    src_ip VARCHAR(64) NOT NULL DEFAULT '',
    dst_ouser VARCHAR(64) NOT NULL DEFAULT '',
    dst_user VARCHAR(64) NOT NULL DEFAULT '',
    dst_domain VARCHAR(128) NOT NULL DEFAULT ''
);
CREATE INDEX ON acc (callid);

CREATE TABLE IF NOT EXISTS missed_calls (
    id SERIAL PRIMARY KEY,
    method VARCHAR(16) NOT NULL DEFAULT '',
    from_tag VARCHAR(128) NOT NULL DEFAULT '',
    to_tag VARCHAR(128) NOT NULL DEFAULT '',
    callid VARCHAR(255) NOT NULL DEFAULT '',
    sip_code VARCHAR(3) NOT NULL DEFAULT '',
    sip_reason VARCHAR(128) NOT NULL DEFAULT '',
    time TIMESTAMP NOT NULL DEFAULT NOW(),
    src_user VARCHAR(64) NOT NULL DEFAULT '',
    src_domain VARCHAR(64) NOT NULL DEFAULT '',
    src_ip VARCHAR(64) NOT NULL DEFAULT '',
    dst_ouser VARCHAR(64) NOT NULL DEFAULT '',
    dst_user VARCHAR(64) NOT NULL DEFAULT '',
    dst_domain VARCHAR(64) NOT NULL DEFAULT ''
);

INSERT INTO version (table_name, table_version) VALUES ('acc', 5) ON CONFLICT DO NOTHING;
INSERT INTO version (table_name, table_version) VALUES ('missed_calls', 4) ON CONFLICT DO NOTHING;

-- ========================================
-- DOMAIN TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS domain (
    id SERIAL PRIMARY KEY,
    domain VARCHAR(64) NOT NULL,
    did VARCHAR(64) DEFAULT NULL,
    last_modified TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT domain_domain_idx UNIQUE (domain)
);

INSERT INTO version (table_name, table_version) VALUES ('domain', 2) ON CONFLICT DO NOTHING;

-- ========================================
-- DOMAIN_ATTRS TABLE (required by domain module)
-- ========================================
CREATE TABLE IF NOT EXISTS domain_attrs (
    id SERIAL PRIMARY KEY,
    did VARCHAR(64) NOT NULL DEFAULT '',
    name VARCHAR(32) NOT NULL DEFAULT '',
    type INTEGER NOT NULL DEFAULT 0,
    value VARCHAR(255) NOT NULL DEFAULT '',
    last_modified TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX ON domain_attrs (did);

INSERT INTO version (table_name, table_version) VALUES ('domain_attrs', 1) ON CONFLICT DO NOTHING;

-- ========================================
-- HTABLE for IP ban (pike)
-- ========================================
CREATE TABLE IF NOT EXISTS htable (
    id SERIAL PRIMARY KEY,
    key_name VARCHAR(64) NOT NULL DEFAULT '',
    key_type INTEGER NOT NULL DEFAULT 0,
    value_type INTEGER NOT NULL DEFAULT 0,
    key_value VARCHAR(128) NOT NULL DEFAULT '',
    expires INTEGER NOT NULL DEFAULT 0
);

INSERT INTO version (table_name, table_version) VALUES ('htable', 2) ON CONFLICT DO NOTHING;

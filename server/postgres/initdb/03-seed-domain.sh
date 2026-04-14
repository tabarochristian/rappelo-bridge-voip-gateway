#!/bin/bash
set -e

DOMAIN="${SIP_DOMAIN:-sip.rappelo.local}"

# Validate domain format (alphanumeric, dots, hyphens, colons for IP:port)
if [[ ! "$DOMAIN" =~ ^[a-zA-Z0-9._:-]+$ ]]; then
    echo "ERROR: Invalid SIP_DOMAIN format: $DOMAIN"
    exit 1
fi

echo "Seeding SIP domain: $DOMAIN"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
INSERT INTO domain (domain, last_modified)
VALUES ('$DOMAIN', NOW())
ON CONFLICT (domain) DO NOTHING;
EOSQL

#!/bin/bash
set -e

# Wait for PostgreSQL
echo "Waiting for PostgreSQL..."
until nc -z postgres 5432; do
    sleep 1
done
echo "PostgreSQL is ready."

# Substitute environment variables in config
sed -i "s/KAMAILIO_DB_USER/${KAMAILIO_DB_USER:-kamailio}/g" /etc/kamailio/kamailio.cfg
sed -i "s/KAMAILIO_DB_PASSWORD/${KAMAILIO_DB_PASSWORD:-kamailio}/g" /etc/kamailio/kamailio.cfg
sed -i "s/KAMAILIO_DB_NAME/${KAMAILIO_DB_NAME:-kamailio}/g" /etc/kamailio/kamailio.cfg
sed -i "s/KAMAILIO_SIP_DOMAIN/${SIP_DOMAIN:-sip.rappelo.local}/g" /etc/kamailio/kamailio.cfg

# Handle advertised address
if [ -n "$PUBLIC_IP" ]; then
    sed -i "s/#!ifdef WITH_ADVERTISED_ADDRESS/#!define WITH_ADVERTISED_ADDRESS\n#!ifdef WITH_ADVERTISED_ADDRESS/" /etc/kamailio/kamailio.cfg
    sed -i "s/ADVERTISED_ADDRESS/${PUBLIC_IP}/g" /etc/kamailio/kamailio.cfg
fi

# Handle TLS mode
if [ "${ENABLE_TLS}" = "true" ]; then
    sed -i '1i #!define WITH_TLS' /etc/kamailio/kamailio.cfg
fi

# Handle debug mode
if [ "${DEBUG}" = "true" ]; then
    sed -i '1i #!define WITH_DEBUG' /etc/kamailio/kamailio.cfg
fi

# Handle Twilio trunk
if [ -n "$TWILIO_TERMINATION_URI" ]; then
    sed -i '1i #!define WITH_TWILIO' /etc/kamailio/kamailio.cfg
    sed -i "s/TWILIO_TERMINATION_URI/${TWILIO_TERMINATION_URI}/g" /etc/kamailio/kamailio.cfg
    echo "Twilio trunk enabled: ${TWILIO_TERMINATION_URI}"
fi

echo "Starting Kamailio..."
exec kamailio -DD -E -m 128 -M 16 \
    -f /etc/kamailio/kamailio.cfg \
    -u kamailio -g kamailio

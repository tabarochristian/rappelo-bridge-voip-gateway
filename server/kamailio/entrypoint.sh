#!/bin/bash
set -e

# Wait for PostgreSQL
echo "Waiting for PostgreSQL..."
until nc -z postgres 5432; do
    sleep 1
done
echo "PostgreSQL is ready."

# Always work from a fresh copy of the config to avoid
# duplicate defines on container restart
cp /etc/kamailio/kamailio.cfg.orig /etc/kamailio/kamailio.cfg

# Fix module path for current architecture
ARCH=$(dpkg --print-architecture 2>/dev/null || echo "amd64")
case "$ARCH" in
    amd64)  MPATH="/usr/lib/x86_64-linux-gnu/kamailio/modules/" ;;
    arm64)  MPATH="/usr/lib/aarch64-linux-gnu/kamailio/modules/" ;;
    *)      MPATH="/usr/lib/$(uname -m)-linux-gnu/kamailio/modules/" ;;
esac
sed -i "s|mpath=.*|mpath=\"${MPATH}\"|" /etc/kamailio/kamailio.cfg

# Substitute environment variables in config
sed -i "s/KAMAILIO_DB_USER/${KAMAILIO_DB_USER:-kamailio}/g" /etc/kamailio/kamailio.cfg
sed -i "s/KAMAILIO_DB_PASSWORD/${KAMAILIO_DB_PASSWORD:-kamailio}/g" /etc/kamailio/kamailio.cfg
sed -i "s/KAMAILIO_DB_NAME/${KAMAILIO_DB_NAME:-kamailio}/g" /etc/kamailio/kamailio.cfg
sed -i "s/KAMAILIO_SIP_DOMAIN/${SIP_DOMAIN:-sip.rappelo.local}/g" /etc/kamailio/kamailio.cfg

# Handle advertised address (add advertise to listen directives)
if [ -n "$PUBLIC_IP" ]; then
    sed -i "s|listen=udp:0.0.0.0:5060$|listen=udp:0.0.0.0:5060 advertise ${PUBLIC_IP}:5060|" /etc/kamailio/kamailio.cfg
    sed -i "s|listen=tcp:0.0.0.0:5060$|listen=tcp:0.0.0.0:5060 advertise ${PUBLIC_IP}:5060|" /etc/kamailio/kamailio.cfg
    # Add alias so PUBLIC_IP is recognized as 'myself'
    # This ensures from_uri==myself for users @PUBLIC_IP, preventing
    # auth_check flag-1 identity mismatch (403 Forbidden)
    sed -i "s|# DYNAMIC_ALIAS|alias=\"${PUBLIC_IP}\"|" /etc/kamailio/kamailio.cfg
    echo "Advertised address: ${PUBLIC_IP} (alias added to myself)"
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

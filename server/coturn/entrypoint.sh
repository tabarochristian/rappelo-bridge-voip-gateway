#!/bin/bash
set -e

# Generate config from template
cp /etc/turnserver.conf.template /etc/turnserver.conf

# Substitute environment variables
sed -i "s/COTURN_REALM_PLACEHOLDER/${SIP_DOMAIN:-sip.rappelo.local}/g" /etc/turnserver.conf
sed -i "s/COTURN_AUTH_SECRET_PLACEHOLDER/${TURN_SECRET:-rappelo-turn-secret-change-me}/g" /etc/turnserver.conf

# Add external IP if set
if [ -n "$PUBLIC_IP" ]; then
    echo "external-ip=${PUBLIC_IP}" >> /etc/turnserver.conf
fi

echo "Starting coturn TURN/STUN server..."
exec turnserver -c /etc/turnserver.conf
